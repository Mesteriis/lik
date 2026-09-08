package io.github.mesteriis.lik

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.testing.TestWorkerBuilder
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.aigate.*
import io.github.mesteriis.lik.settings.AiSettingsActivity
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AiRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeUSearchSupportsUpdateDeletePersistenceAndExactParity() {
        assertTrue("USearch JNI must load", USearchBridge.available)
        val bridge = USearchBridge()
        var handle = bridge.create(3)
        val file = File(context.cacheDir, "usearch-${System.nanoTime()}.index")
        try {
            bridge.reserve(handle, 4)
            bridge.upsert(handle, 1, floatArrayOf(1f, 0f, 0f))
            bridge.upsert(handle, 2, floatArrayOf(0f, 1f, 0f))
            bridge.upsert(handle, 3, floatArrayOf(0f, 0f, 1f))
            assertEquals(1L, bridge.search(handle, floatArrayOf(1f, 0f, 0f), 3).first())
            bridge.upsert(handle, 1, floatArrayOf(0f, 0f, 1f))
            bridge.remove(handle, 2)
            bridge.save(handle, file.absolutePath)
            bridge.close(handle); handle = 0
            handle = bridge.create(3); bridge.load(handle, file.absolutePath)
            assertEquals(2L, bridge.size(handle))
            val ids = bridge.search(handle, floatArrayOf(0f, 0f, 1f), 2).toList()
            assertEquals(setOf(1L, 3L), ids.toSet())
            val exact = ExactVectorIndex(3).apply {
                upsert("1", floatArrayOf(0f, 0f, 1f)); upsert("3", floatArrayOf(0f, 0f, 1f))
            }
            assertEquals(setOf("1", "3"), exact.search(floatArrayOf(0f, 0f, 1f), 2).map { it.mediaId }.toSet())
        } finally { if (handle != 0L) bridge.close(handle); file.delete() }
    }

    @Test fun corruptArtifactsAreQuarantinedAndSharedReservationsRecoverAcrossOperations() {
        val root = File(context.cacheDir, "ai-durable-${System.nanoTime()}")
        val store = ArtifactStore(root)
        val expected = "verified model bytes".toByteArray()
        val source = File(root, "expected").apply { parentFile!!.mkdirs(); writeBytes(expected) }
        val digest = ArtifactStore.sha256(source)
        val spec = ArtifactSpec("component/model.onnx", expected.size.toLong(), digest,
            URI("https://huggingface.co/org/repo/resolve/${"a".repeat(40)}/model.onnx"))
        store.file(digest).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(expected.size) { 7 }) }
        try {
            assertTrue(store.repair(spec))
            assertFalse(store.file(digest).exists())
            assertEquals(1, File(root, "quarantine").listFiles().orEmpty().size)
            val verifiedPart = File(root, "verified.part").apply { writeBytes(expected) }
            store.publish(store.verifyStaging(verifiedPart, spec), spec)
            assertTrue(store.installed(digest, expected.size.toLong()))
            assertTrue(File(root, "verification/$digest.json").isFile)
            store.file(digest).writeBytes(ByteArray(expected.size) { 9 })
            assertFalse(store.installed(digest, expected.size.toLong()))
            assertTrue(store.repair(spec))

            val part = store.sharedPart(digest).apply { writeBytes(expected.copyOf(5)) }
            val ledger = DownloadReservationLedger(root)
            val first = ledger.acquire("compact", listOf(spec to part.length()), 1_000, 10)
            assertEquals(expected.size - 5L + 10L, first.requiredBytes)
            assertEquals(first.requiredBytes, ledger.reservedBytes("compact"))
            assertEquals("compact", ledger.owner(digest))
            ledger.update("compact", digest, 3)
            assertEquals(13L, ledger.reservedBytes("compact"))
            assertEquals(13L, DownloadReservationLedger(root).reservedBytes("compact"))
            assertThrows(IllegalArgumentException::class.java) {
                ledger.acquire("no-space", listOf(spec to 0), 0, 10)
            }
            ledger.acquire("balanced", listOf(spec to part.length()), 1_000, 10)
            assertEquals("balanced", ledger.owner(digest))
            assertEquals(10L, ledger.reservedBytes("compact"))
            ledger.release("compact")
            assertEquals("balanced", ledger.owner(digest))
            ledger.release("balanced")
            assertEquals(0L, ledger.reservedBytes("balanced"))
            store.abandonShared(setOf(digest), "balanced")
            assertFalse(part.exists())
            val removals = GenerationRemovalJournal(root)
            removals.begin(ProfileId.COMPACT, setOf("old-generation"))
            assertEquals(setOf("old-generation"), removals.ids())
            removals.finish(ProfileId.COMPACT)
            assertTrue(removals.ids().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun downloadCoordinatorSerializesConcurrentProfileWriters() {
        val root = File(context.cacheDir, "download-lock-${System.nanoTime()}")
        val active = java.util.concurrent.atomic.AtomicInteger(); val maximum = java.util.concurrent.atomic.AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(2)
        fun launch(first: Boolean) = thread {
            DownloadCoordinator.run(root) {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                if (first) { entered.countDown(); release.await() }
                active.decrementAndGet()
            }
            done.countDown()
        }
        try {
            launch(true); assertTrue(entered.await(2, TimeUnit.SECONDS)); launch(false)
            Thread.sleep(100); assertEquals(1, maximum.get()); release.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(1, maximum.get())
        } finally { release.countDown(); root.deleteRecursively() }
    }

    @Test fun roomPublicationRejectsSameGenerationRevisionRaceWithoutAdvancingCheckpoint() {
        val mediaId = "d".repeat(63) + "1"
        val file = PhotoLibrary.store(context).fileFor(mediaId).apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3))
        }
        val media = ImportedCatalogMigration.record(io.github.mesteriis.lik.imports.ImportedPhoto(mediaId, file), 17)
        val database = MediaDatabase.get(context)
        val dao = database.aiIndexes()
        val generationId = "race-${System.nanoTime()}"
        val generation = AiIndexGenerationRecord(generationId, ProfileId.COMPACT.wire, AiFeature.SEARCH.name,
            "pipeline", GenerationStatus.PREPARING, 0, 1, null, null, System.currentTimeMillis())
        try {
            database.media().upsert(media)
            dao.saveGeneration(generation)
            val stale = AiEmbeddingRecord(generationId, mediaId, 1, media.contentRevision + 1, media.accessGrantEpoch,
                floatArrayOf(1f, 0f).toBytes())
            assertFalse(dao.publishEmbeddingIfCurrent(stale, generation.copy(completed = 1, checkpointMediaId = mediaId)))
            assertNull(dao.embedding(generationId, mediaId))
            assertEquals(0, dao.generation(generationId)!!.completed)
            assertNull(dao.generation(generationId)!!.checkpointMediaId)
        } finally {
            dao.deleteGenerations(setOf(generationId))
            database.media().trash(setOf(mediaId), 1); database.media().claimPurge(setOf(mediaId)); database.media().finishPurge(mediaId)
            file.delete()
        }
    }

    @Test fun aiSettingsShowsThreeProfilesAndControlsAfterRecreation() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val heading = activity.findViewById<android.view.View>(R.id.ai_profile_compact)
                assertNotNull(heading)
                val card = (heading.parent as android.view.ViewGroup)
                val visibleText = (0 until card.childCount).mapNotNull { (card.getChildAt(it) as? android.widget.TextView)?.text?.toString() }
                    .joinToString(" ")
                assertFalse(visibleText.contains("clip-image-v1"))
                assertNotNull(activity.findViewById<android.view.View>(R.id.ai_profile_balanced))
                assertNotNull(activity.findViewById<android.view.View>(R.id.ai_profile_extended))
                assertNotNull(activity.findViewById<android.view.View>(R.id.ai_feature_search))
                assertNotNull(activity.findViewById<android.view.View>(R.id.aigate_enabled))
                val ocr = activity.findViewById<android.widget.Switch>(R.id.ai_feature_ocr)
                val people = activity.findViewById<android.widget.Switch>(R.id.ai_feature_people)
                assertFalse(ocr.isEnabled); assertFalse(people.isEnabled)
                activity.findViewById<android.widget.EditText>(R.id.aigate_port).setText("4567")
                ModelCatalog.get(activity).update { it.copy(revision = it.revision + 1) }
                assertSame(heading, activity.findViewById<android.view.View>(R.id.ai_profile_compact))
            }
            scenario.recreate()
            scenario.onActivity {
                assertNotNull(it.findViewById<android.view.View>(R.id.ai_profile_balanced))
                assertEquals("4567", it.findViewById<android.widget.EditText>(R.id.aigate_port).text.toString())
            }
        }
    }

    @Test fun aiSettingsRemovesTask11FeaturesFromPendingRequest() {
        val catalog = ModelCatalog.get(context)
        val before = catalog.snapshot()
        catalog.update { state -> state.copy(
            revision = state.revision + 1,
            selected = ProfileId.COMPACT,
            active = ProfileId.COMPACT,
            profiles = state.profiles + (ProfileId.COMPACT to ProfileState(ProfilePhase.ACTIVE)),
            enabledFeatures = emptySet(),
            pending = PendingProfile(ProfileId.COMPACT, setOf(AiFeature.OCR, AiFeature.PEOPLE)),
            activeGenerations = emptyMap(),
        ) }
        try {
            ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val state = catalog.snapshot()
                    assertNull(state.pending)
                    assertFalse(AiFeature.OCR in state.enabledFeatures)
                    assertFalse(AiFeature.PEOPLE in state.enabledFeatures)
                    assertFalse(it.findViewById<android.widget.Switch>(R.id.ai_feature_ocr).isChecked)
                    assertFalse(it.findViewById<android.widget.Switch>(R.id.ai_feature_people).isChecked)
                }
            }
        } finally {
            catalog.update { before.copy(revision = it.revision + 1) }
        }
    }

    @Test fun aiGateUsesOnlyLoopbackAndRequiresSingleUsePhotoConsent() {
        val server = FakeAiGate("{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"model\":\"auto\"}")
        try {
            val consent = PhotoSendConsent(); val token = consent.grant("photo", 7)
            val result = AiGateClient(AiGateEndpoint(server.port)).chat(token, consent, "photo", 7, 11, "describe",
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
            assertEquals("ok", result.text)
            assertThrows(IllegalArgumentException::class.java) {
                AiGateClient(AiGateEndpoint(server.port)).chat(token, consent, "photo", 7, 12, "again", byteArrayOf(1))
            }
            assertTrue(server.request.contains("POST /v1/chat/completions"))
            assertTrue(server.request, server.request.contains("base64,"))
            assertFalse(server.request.contains("content://"))
        } finally { server.close() }
    }

    @Test fun aiGateReencodesPixelsWithoutExifAndBoundsImageSize() {
        val source = File(context.cacheDir, "aigate-source-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        android.media.ExifInterface(source.path).apply {
            setAttribute(android.media.ExifInterface.TAG_IMAGE_DESCRIPTION, "LIK_PRIVATE_METADATA_SENTINEL")
            saveAttributes()
        }
        try {
            val encoded = AiGateImage.encode(context, android.net.Uri.fromFile(source), null)
            assertFalse(String(encoded, Charsets.ISO_8859_1).contains("LIK_PRIVATE_METADATA_SENTINEL"))
            val decoded = android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            try {
                assertEquals(1600, maxOf(decoded.width, decoded.height))
                assertTrue(encoded.size <= AiGateClient.MAX_IMAGE_BYTES)
            } finally { decoded.recycle() }
        } finally { source.delete() }
    }

    @Test fun aiGateHealthAndModelsKeepCapabilityUnknown() {
        FakeAiGate("{\"service\":\"aigate\",\"running\":true,\"port\":8899,\"version\":\"1\",\"models_count\":1}").use { server ->
            val health = AiGateClient(AiGateEndpoint(server.port)).health()
            assertTrue(health.running)
            assertEquals(1, health.modelCount)
            assertTrue(server.request.startsWith("GET /health"))
        }
        FakeAiGate("{\"data\":[{\"id\":\"deepseek\",\"owned_by\":\"router\"}]}").use { server ->
            val model = AiGateClient(AiGateEndpoint(server.port)).models().single()
            assertEquals("deepseek", model.id)
            assertFalse(model.visionKnown)
            assertTrue(server.request.startsWith("GET /v1/models"))
        }
    }

    @Test fun aiGateRejectsRedirectsAndHttpErrorsAndHonorsTimeoutAndCancel() {
        FakeAiGate("{}", status = "302 Found", extraHeaders = "Location: http://127.0.0.1:1/health\r\n").use { server ->
            assertThrows(IllegalArgumentException::class.java) { AiGateClient(AiGateEndpoint(server.port)).health() }
        }
        FakeAiGate("{\"error\":\"no\"}", status = "503 Unavailable").use { server ->
            val error = assertThrows(IllegalArgumentException::class.java) { AiGateClient(AiGateEndpoint(server.port)).health() }
            assertTrue(error.message.orEmpty().contains("503"))
        }
        FakeAiGate("{}", responseDelayMillis = 750).use { server ->
            assertThrows(java.net.SocketTimeoutException::class.java) {
                AiGateClient(AiGateEndpoint(server.port), readTimeoutMs = 100).health()
            }
        }
        FakeAiGate("{}", responseDelayMillis = 750).use { server ->
            assertThrows(java.net.SocketTimeoutException::class.java) {
                AiGateClient(AiGateEndpoint(server.port), readTimeoutMs = 5_000, overallTimeoutMs = 100).health()
            }
        }
        FakeAiGate("{}", responseDelayMillis = 2_000).use { server ->
            val client = AiGateClient(AiGateEndpoint(server.port), readTimeoutMs = 5_000)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Throwable?> { runCatching { client.health() }.exceptionOrNull() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (server.request.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(server.request.isNotEmpty())
                client.cancel()
                assertNotNull(result.get(2, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
        FakeAiGate("{}", requestReadDelayMillis = 2_000).use { server ->
            val consent = PhotoSendConsent(); val token = consent.grant("large", 1)
            assertThrows(java.net.SocketTimeoutException::class.java) {
                AiGateClient(AiGateEndpoint(server.port), readTimeoutMs = 5_000, overallTimeoutMs = 100)
                    .chat(token, consent, "large", 1, 1, "describe", ByteArray(AiGateClient.MAX_IMAGE_BYTES) { 1 })
            }
        }
    }

    @Test fun modelCatalogSurvivesProcessStyleReopenWithoutDuplicatedPreferences() {
        val stateFile = File(context.filesDir, "ai/catalog-state-v1.json")
        val original = stateFile.takeIf(File::isFile)?.readBytes()
        var catalog = ModelCatalog.get(context)
        try {
            val target = if (catalog.snapshot().selected == ProfileId.EXTENDED) ProfileId.COMPACT else ProfileId.EXTENDED
            val expected = catalog.update { state -> state.copy(revision = state.revision + 1, selected = target) }
            catalog.closeForTests()
            catalog = ModelCatalog.get(context)
            assertEquals(expected, catalog.snapshot())
        } finally {
            catalog.closeForTests()
            if (original == null) stateFile.delete() else stateFile.writeBytes(original)
            ModelCatalog.get(context).closeForTests()
        }
    }

    @Test fun allThreeInstalledProfilesExecuteRussianTextAndImageInIsolatedRuntime() {
        val required = InstrumentationRegistry.getArguments().getString("likRuntimeModels") == "true"
        assumeTrue("Explicit external emulator provisioning is required", required)
        val catalog = ModelCatalog.get(context)
        val store = ArtifactStore(File(context.filesDir, "ai"))
        val engine = SemanticEmbeddingEngine(context)
        val runtime = IsolatedRuntimeClient(context)
        val requested = InstrumentationRegistry.getArguments().getString("runtimeProfile")
        ProfileId.entries.filter { requested == null || it.wire == requested }.forEach { profile ->
            val specs = catalog.trusted.artifacts(profile)
            assertTrue(profile.wire, specs.all { store.installed(it.sha256, it.size) })
            ModelSelfTest(store, runtime, engine, catalog.trusted).validate(profile, specs)
            val expected = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SEARCH).dimension
            assertEquals(expected, engine.query(profile, "красная машина и белый снег").size)
            val image = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff885533.toInt()) }
            try { assertEquals(expected, engine.imageBitmap(profile, image).size) } finally { image.recycle() }
        }
    }

    @Test fun isolatedRuntimeReusesSessionsAndRebindsAfterTransportDeath() {
        assumeTrue("Explicit external emulator provisioning is required",
            InstrumentationRegistry.getArguments().getString("likRuntimeModels") == "true")
        val profile = ProfileId.COMPACT
        val catalog = ModelCatalog.get(context)
        val store = ArtifactStore(File(context.filesDir, "ai"))
        assumeTrue(catalog.trusted.artifacts(profile).all { store.installed(it.sha256, it.size) })
        val runtime = IsolatedRuntimeClient(context)
        val engine = SemanticEmbeddingEngine(context)
        engine.query(profile, "первый запрос")
        val first = runtime.stats().getOrThrow()
        engine.query(profile, "второй запрос")
        val second = runtime.stats().getOrThrow()
        assertEquals(first.createdSessions, second.createdSessions)
        assertTrue(second.liveSessions > 0)
        runtime.disconnectForTests()
        engine.query(profile, "запрос после перезапуска")
        val rebound = runtime.stats().getOrThrow()
        assertTrue(rebound.connectionGeneration > second.connectionGeneration)
        assertTrue(rebound.liveSessions > 0)
    }

    @Test fun realIndexWorkerPublishesRevisionCheckedGenerationAndNativeSearch() {
        assumeTrue("Explicit external emulator provisioning is required",
            InstrumentationRegistry.getArguments().getString("likRuntimeModels") == "true")
        val profile = ProfileId.COMPACT
        val catalog = ModelCatalog.get(context)
        catalog.update { state -> state.copy(revision = state.revision + 1, selected = profile, active = profile,
            enabledFeatures = setOf(AiFeature.SEARCH), pending = null,
            profiles = state.profiles + (profile to ProfileState(ProfilePhase.ACTIVE))) }
        val id = "e".repeat(64)
        val store = PhotoLibrary.store(context); val file = store.fileFor(id)
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff9a5b32.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }; bitmap.recycle()
        val record = io.github.mesteriis.lik.catalog.ImportedCatalogMigration.record(io.github.mesteriis.lik.imports.ImportedPhoto(id, file), 41)
        val database = MediaDatabase.get(context); database.media().upsert(record)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = TestWorkerBuilder.from(context, AiIndexWorker::class.java, executor)
                .setInputData(workDataOf("profile" to profile.wire, "manual" to true)).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            val generation = requireNotNull(catalog.snapshot().activeGenerations[AiFeature.SEARCH])
            val row = database.aiIndexes().embeddings(generation).single { it.mediaId == id }
            assertEquals(record.contentRevision, row.contentRevision)
            val result = SemanticSearchRepository(context).search("коричневое изображение", 10)
            assertEquals(id, result.hits.first().mediaId)
            assertTrue(result.nativeVerified)
        } finally {
            executor.shutdownNow(); file.delete()
            database.media().trash(setOf(id), 1); database.media().claimPurge(setOf(id)); database.media().finishPurge(id)
        }
    }

    @Test fun realHuggingFaceDownloadResumesRangeAndAtomicallyPublishes() {
        assumeTrue("Explicit network acceptance is required",
            InstrumentationRegistry.getArguments().getString("likDownloadNetwork") == "true")
        val profile = ProfileId.COMPACT
        val catalog = ModelCatalog.get(context)
        val store = ArtifactStore(File(context.filesDir, "ai"))
        val spec = catalog.trusted.artifacts(profile).single { it.path == "clip-image-v1/preprocessor_config.json" }
        val target = store.file(spec.sha256); assertTrue(store.installed(spec.sha256, spec.size))
        val bytes = target.readBytes(); val backup = File(context.cacheDir, "download-backup-${System.nanoTime()}")
        assertTrue(target.renameTo(backup))
        val part = store.sharedPart(spec.sha256)
        part.writeBytes(bytes.copyOf(bytes.size / 2))
        catalog.update { state -> state.copy(revision = state.revision + 1, selected = profile, active = profile,
            enabledFeatures = emptySet(), pending = null, profiles = state.profiles + (profile to ProfileState(ProfilePhase.ACTIVE))) }
        try {
            ModelDownloader(context).install(profile).getOrThrow()
            assertTrue(store.installed(spec.sha256, spec.size))
            assertArrayEquals(bytes, target.readBytes())
            assertFalse(part.exists())
        } finally {
            if (!target.exists()) assertTrue(backup.renameTo(target)) else backup.delete()
        }
    }

    @Test fun completedVerifiedStagingRecoversWithoutAnotherNetworkRequest() {
        assumeTrue("Explicit external emulator provisioning is required",
            InstrumentationRegistry.getArguments().getString("likRuntimeModels") == "true")
        val profile = ProfileId.COMPACT
        val catalog = ModelCatalog.get(context)
        val store = ArtifactStore(File(context.filesDir, "ai"))
        val spec = catalog.trusted.artifacts(profile).single { it.path == "clip-image-v1/preprocessor_config.json" }
        val target = store.file(spec.sha256); assertTrue(store.installed(spec.sha256, spec.size))
        val bytes = target.readBytes(); val backup = File(context.cacheDir, "recovery-backup-${System.nanoTime()}")
        assertTrue(target.renameTo(backup))
        store.sharedPart(spec.sha256).writeBytes(bytes)
        store.sharedJournal(spec.sha256).writeText(org.json.JSONObject().put("schema", 1)
            .put("path", spec.path).put("size", spec.size).put("sha256", spec.sha256)
            .put("url", spec.url.toString()).put("stage", DownloadJournalStage.VERIFIED.name)
            .put("bytes", spec.size).toString())
        catalog.update { state -> state.copy(revision = state.revision + 1, selected = profile, active = profile,
            enabledFeatures = emptySet(), pending = null,
            profiles = state.profiles + (profile to ProfileState(ProfilePhase.ACTIVE))) }
        try {
            ModelDownloader(context).install(profile).getOrThrow()
            assertTrue(store.installed(spec.sha256, spec.size))
            assertArrayEquals(bytes, target.readBytes())
        } finally {
            if (!target.exists()) assertTrue(backup.renameTo(target)) else backup.delete()
        }
    }

    private class FakeAiGate(
        private val response: String,
        private val status: String = "200 OK",
        private val extraHeaders: String = "",
        private val responseDelayMillis: Long = 0,
        private val requestReadDelayMillis: Long = 0,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        @Volatile var request = ""
        private val worker = thread(start = true, isDaemon = true) { runCatching {
            socket.accept().use { client ->
                if (requestReadDelayMillis > 0) Thread.sleep(requestReadDelayMillis)
                val input = client.getInputStream().bufferedReader()
                val lines = mutableListOf<String>(); var length = 0
                while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break
                    lines += line; if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                val body = CharArray(length); var at = 0
                while (at < length) { val count = input.read(body, at, length - at); if (count < 0) break; at += count }
                request = lines.joinToString("\n") + "\n" + String(body, 0, at)
                if (responseDelayMillis > 0) Thread.sleep(responseDelayMillis)
                val bytes = response.toByteArray()
                client.getOutputStream().write("HTTP/1.1 $status\r\n${extraHeaders}Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                client.getOutputStream().write(bytes); client.getOutputStream().flush()
            }
        } }
        override fun close() { socket.close(); worker.join(1_000) }
    }
}
