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
            val allocationUnit = android.system.Os.statvfs(root.absolutePath).let { maxOf(it.f_bsize, it.f_frsize) }
            val first = ledger.acquire("compact", listOf(spec to 5L), allocationUnit, 10)
            assertEquals(expected.size - 5L + 10L, first.requiredBytes)
            assertEquals(expected.size.toLong(), part.length())
            assertTrue(android.system.Os.stat(part.absolutePath).st_blocks * 512 >= expected.size)
            assertEquals(10L, ledger.reservedBytes("compact"))
            assertEquals("compact", ledger.owner(digest))
            ledger.update("compact", digest, 3)
            assertEquals(10L, ledger.reservedBytes("compact"))
            assertEquals(10L, DownloadReservationLedger(root).reservedBytes("compact"))
            assertThrows(IllegalArgumentException::class.java) {
                ledger.acquire("no-space", listOf(spec to 0), 0, 10)
            }
            ledger.acquire("balanced", listOf(spec to 5L), allocationUnit, 10)
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

    @Test fun reservationPreflightRoundsEveryTransferAndMarginAllocation() {
        val root = File(context.cacheDir, "allocation-rounding-${System.nanoTime()}").apply { mkdirs() }
        val unit = android.system.Os.statvfs(root.absolutePath).let { maxOf(it.f_bsize, it.f_frsize) }
        fun spec(seed: Char) = ArtifactSpec("$seed/model.onnx", 1, seed.toString().repeat(64),
            URI("https://huggingface.co/org/repo/resolve/${"a".repeat(40)}/$seed.onnx"))
        val files = listOf(spec('a') to 0L, spec('b') to 0L)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                DownloadReservationLedger(root).acquire("too-tight", files, 3, 1)
            }
            DownloadReservationLedger(root).acquire("exact-rounded", files, unit * 3, 1)
            files.forEach { (artifact, _) ->
                assertTrue(android.system.Os.stat(File(root, "staging/shared/${artifact.sha256}.part").absolutePath).st_blocks * 512 >= unit)
            }
            assertTrue(android.system.Os.stat(File(root, "staging/reservations/exact-rounded.reserve").absolutePath).st_blocks * 512 >= unit)
        } finally { root.deleteRecursively() }
    }

    @Test fun exactBoundaryUsesOnlyPreallocatedMetadataAfterAcceptance() {
        val root = File(context.cacheDir, "metadata-boundary-${System.nanoTime()}").apply { mkdirs() }
        val payload = "payload".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val spec = ArtifactSpec("test/model.onnx", payload.size.toLong(), digest,
            URI("https://huggingface.co/org/repo/resolve/${"a".repeat(40)}/model.onnx"))
        val store = ArtifactStore(root)
        val ledger = DownloadReservationLedger(root)
        val catalogState = File(root, "catalog-state-v1.json")
        try {
            store.prepareDownloadMetadata("test", listOf(spec), catalogState)
            store.prepareMissingTransferEntries(listOf(spec))
            val unit = android.system.Os.statvfs(root.absolutePath).let { maxOf(512L, it.f_bsize, it.f_frsize) }
            ledger.acquire("test", listOf(spec to 0L), unit * 2, 1)
            PreallocatedMetadata.rejectNewFilesForTests = true
            val part = store.sharedPart(digest)
            java.io.RandomAccessFile(part, "rw").use { it.seek(0); it.write(payload); it.fd.sync() }
            store.writeSharedJournal(spec, DownloadJournalStage.VERIFYING, payload.size.toLong())
            ledger.update("test", digest, 0)
            PreallocatedMetadata.write(catalogState, "{\"state\":1}".toByteArray())
            store.publish(store.verifyStaging(part, spec), spec)
            assertTrue(store.installed(digest, payload.size.toLong()))
            assertThrows(IllegalStateException::class.java) {
                PreallocatedMetadata.write(File(root, "late-metadata"), byteArrayOf(1))
            }
        } finally {
            PreallocatedMetadata.rejectNewFilesForTests = false
            root.deleteRecursively()
        }
    }

    @Test fun corruptArtifactRepairKeepsReceiptPreallocatedAtExactBoundary() {
        val root = File(context.cacheDir, "corrupt-receipt-boundary-${System.nanoTime()}").apply { mkdirs() }
        val payload = "expected".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val spec = ArtifactSpec("test/model.onnx", payload.size.toLong(), digest,
            URI("https://huggingface.co/org/repo/resolve/${"a".repeat(40)}/model.onnx"))
        val store = ArtifactStore(root)
        val ledger = DownloadReservationLedger(root)
        try {
            store.file(digest).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(payload.size) { 7 }) }
            store.prepareDownloadMetadata("repair", listOf(spec), File(root, "catalog-state-v1.json"))
            assertTrue(store.repair(spec))
            store.prepareMissingTransferEntries(listOf(spec))
            val unit = android.system.Os.statvfs(root.absolutePath).let { maxOf(512L, it.f_bsize, it.f_frsize) }
            ledger.acquire("repair", listOf(spec to 0L), unit * 2, 1)
            PreallocatedMetadata.rejectNewFilesForTests = true
            java.io.RandomAccessFile(store.sharedPart(digest), "rw").use {
                it.seek(0); it.write(payload); it.fd.sync()
            }
            store.publish(store.verifyStaging(store.sharedPart(digest), spec), spec)
            assertTrue(store.installed(digest, payload.size.toLong()))
        } finally {
            PreallocatedMetadata.rejectNewFilesForTests = false
            root.deleteRecursively()
        }
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
                assertTrue(ocr.isEnabled); assertTrue(people.isEnabled)
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

    @Test fun aiSettingsPreservesTask11FeaturesInPendingRequest() {
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
            verifiedOracles = state.verifiedOracles + (ProfileId.COMPACT to catalog.trusted.oracleRevision),
        ) }
        try {
            ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val state = catalog.snapshot()
                    assertNotNull(state.pending)
                    assertTrue(AiFeature.OCR in state.pending!!.enabled)
                    assertTrue(AiFeature.PEOPLE in state.pending!!.enabled)
                    assertTrue(it.findViewById<android.widget.Switch>(R.id.ai_feature_ocr).isChecked)
                    assertTrue(it.findViewById<android.widget.Switch>(R.id.ai_feature_people).isChecked)
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

    @Test fun aiGateOptOutCancelsProbeWithoutLateSettingsWrite() {
        val settings = AiGateSettings(context)
        val originalEnabled = settings.enabled
        val originalPort = settings.port
        settings.enabled = false
        settings.port = 54_321
        try {
            FakeAiGate("{\"service\":\"aigate\",\"running\":true,\"port\":1,\"models_count\":1}",
                responseDelayMillis = 1_000).use { server ->
                ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val enabled = activity.findViewById<android.widget.Switch>(R.id.aigate_enabled)
                        enabled.isChecked = true
                        activity.findViewById<android.widget.EditText>(R.id.aigate_port).setText(server.port.toString())
                        activity.findViewById<android.widget.Button>(R.id.aigate_check).performClick()
                        enabled.isChecked = false
                    }
                    Thread.sleep(1_200)
                    assertEquals(54_321, settings.port)
                    assertFalse(settings.enabled)
                }
            }
        } finally {
            settings.port = originalPort
            settings.enabled = originalEnabled
        }
    }

    @Test fun aiGateDiscoveryCancellationStopsBeforeNextPort() {
        val owner = AiGateRequestOwner()
        val token = owner.begin()
        val attempted = mutableListOf<Int>()
        val discovery = AiGateDiscovery(listOf(AiGateEndpoint(8_889), AiGateEndpoint(8_890))) { endpoint, _ ->
            attempted += endpoint.port
            owner.cancel()
            null
        }

        assertNull(discovery.discover(owner, token))
        assertEquals(listOf(8_889), attempted)
        assertFalse(owner.isCurrent(token))
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

    @Test fun modelCatalogBoundsThousandGenerationWriteWithinFixedSlot() {
        val catalog = ModelCatalog.get(context)
        val original = catalog.snapshot()
        val current = catalog.trusted.profiles.getValue(ProfileId.BALANCED)
            .pipelines.getValue(AiFeature.SEARCH).fingerprint
        val active = IndexGeneration("stress-active", AiFeature.SEARCH, current, true, 1, 1)
        val pending = IndexGeneration("stress-pending", AiFeature.SEARCH, current, true, 2, 2)
        try {
            catalog.update { state ->
                val generations = linkedMapOf(active.id to active, pending.id to pending)
                repeat(1_200) { at ->
                    val id = "stress-$at"
                    generations[id] = IndexGeneration(id, AiFeature.SEARCH, current,
                        complete = at % 3 != 0, completed = at, total = 1_200)
                }
                state.copy(
                    revision = state.revision + 1,
                    generations = generations,
                    activeGenerations = mapOf(AiFeature.SEARCH to active.id),
                    pending = PendingProfile(ProfileId.BALANCED, setOf(AiFeature.SEARCH),
                        mapOf(AiFeature.SEARCH to pending.id)),
                )
            }
            assertTrue(catalog.snapshot().generations.size <= 4)
            assertEquals(active, catalog.snapshot().generations[active.id])
            assertEquals(pending, catalog.snapshot().generations[pending.id])
            assertEquals(256L * 1024, catalog.stateFileForMetadata().length())
            assertTrue(PreallocatedMetadata.read(catalog.stateFileForMetadata()).size < 128 * 1024 - 48)
        } finally {
            catalog.update { original.copy(revision = it.revision + 1) }
        }
    }

    @Test fun semanticSearchLeaseDefersSupersededGenerationCleanup() {
        val catalog = ModelCatalog.get(context)
        val original = catalog.snapshot()
        val database = MediaDatabase.get(context)
        val dao = database.aiIndexes()
        val profile = ProfileId.COMPACT
        val pipeline = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SEARCH)
        val oldId = "lease-old-${System.nanoTime()}"
        val newId = "lease-new-${System.nanoTime()}"
        val mediaId = java.security.MessageDigest.getInstance("SHA-256")
            .digest(oldId.toByteArray()).joinToString("") { "%02x".format(it) }
        val old = AiIndexGenerationRecord(oldId, profile.wire, AiFeature.SEARCH.name, pipeline.fingerprint,
            GenerationStatus.COMPLETE, 1, 1, null, null, 1)
        val new = old.copy(generationId = newId, createdAt = 2)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val searchResult = java.util.concurrent.atomic.AtomicReference<SemanticSearchResult>()
        val searchError = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val oldNative = NativeIndexFiles.generation(context, oldId).apply {
            mkdirs(); File(this, "old-marker").writeText("old")
        }
        try {
            database.media().upsert(MediaRecord(mediaId, MediaSource.GOOGLE_IMPORT, mediaId,
                privateFileId = mediaId, mimeType = "image/jpeg", contentRevision = 1, lastSeenAt = 1))
            dao.saveGeneration(old); dao.saveGeneration(new)
            dao.saveEmbedding(AiEmbeddingRecord(oldId, mediaId, 1, 1, 1,
                FloatArray(pipeline.dimension!!) { if (it == 0) 1f else 0f }.toBytes()))
            catalog.update { state -> state.copy(revision = state.revision + 1, selected = profile, active = profile,
                profiles = state.profiles + (profile to ProfileState(ProfilePhase.ACTIVE)),
                enabledFeatures = setOf(AiFeature.SEARCH), pending = null,
                generations = linkedMapOf(oldId to old.toContractForTest(), newId to new.toContractForTest()),
                activeGenerations = mapOf(AiFeature.SEARCH to oldId)) }
            val repository = SemanticSearchRepository(context, catalog, database,
                queryEmbedding = { _, _ ->
                    entered.countDown(); release.await()
                    FloatArray(pipeline.dimension) { if (it == 0) 1f else 0f }
                })
            val search = thread {
                runCatching { repository.search("query", 1) }
                    .onSuccess(searchResult::set).onFailure(searchError::set)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            catalog.update { state -> state.copy(revision = state.revision + 1,
                generations = mapOf(newId to new.toContractForTest()),
                activeGenerations = mapOf(AiFeature.SEARCH to newId)) }
            val cleanup = thread { GenerationRetirement.retire(context, profile, setOf(oldId), catalog, database) }
            Thread.sleep(100)
            assertTrue(cleanup.isAlive)
            assertNotNull(dao.generation(oldId))
            assertTrue(oldNative.exists())
            release.countDown(); search.join(2_000); cleanup.join(2_000)
            assertNull(searchError.get())
            assertEquals(mediaId, searchResult.get().hits.single().mediaId)
            assertNull(dao.generation(oldId))
            assertFalse(oldNative.exists())
        } finally {
            release.countDown()
            GenerationRetirement.afterStepForTests = null
            GenerationRemovalJournal(File(context.filesDir, "ai")).complete(profile, setOf(oldId),
                GenerationRemovalReason.SUPERSEDED)
            catalog.update { original.copy(revision = it.revision + 1) }
            dao.deleteGenerations(setOf(oldId, newId))
            database.media().trash(setOf(mediaId), 1); database.media().claimPurge(setOf(mediaId))
            database.media().finishPurge(mediaId)
            NativeIndexFiles.remove(context, oldId); NativeIndexFiles.remove(context, newId)
        }
    }

    @Test fun supersededGenerationRemovalRecoversAfterRoomDeleteCrash() {
        val catalog = ModelCatalog.get(context)
        val original = catalog.snapshot()
        val database = MediaDatabase.get(context)
        val dao = database.aiIndexes()
        val profile = ProfileId.COMPACT
        val id = "retirement-crash-${System.nanoTime()}"
        val pipeline = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SEARCH)
        val native = NativeIndexFiles.generation(context, id).apply { mkdirs(); File(this, "marker").writeText("old") }
        try {
            dao.saveGeneration(AiIndexGenerationRecord(id, profile.wire, AiFeature.SEARCH.name,
                pipeline.fingerprint, GenerationStatus.COMPLETE, 0, 0, null, null, 1))
            catalog.update { state -> state.copy(revision = state.revision + 1,
                generations = state.generations - id,
                activeGenerations = state.activeGenerations.filterValues { it != id },
                pending = state.pending?.copy(readyGenerations = state.pending.readyGenerations.filterValues { it != id })) }
            GenerationRetirement.afterStepForTests = { step, generation ->
                if (step == GenerationRetirementStep.ROOM_REMOVED && generation == id) error("SIMULATED_CRASH")
            }
            assertThrows(IllegalStateException::class.java) {
                GenerationRetirement.retire(context, profile, setOf(id), catalog, database)
            }
            assertNull(dao.generation(id))
            assertTrue(native.exists())
            assertTrue(id in GenerationRemovalJournal(File(context.filesDir, "ai")).ids())
            GenerationRetirement.afterStepForTests = null
            ModelMaintenance.recover(context)
            assertFalse(native.exists())
            assertFalse(id in GenerationRemovalJournal(File(context.filesDir, "ai")).ids())
        } finally {
            GenerationRetirement.afterStepForTests = null
            GenerationRemovalJournal(File(context.filesDir, "ai")).complete(profile, setOf(id),
                GenerationRemovalReason.SUPERSEDED)
            catalog.update { original.copy(revision = it.revision + 1) }
            dao.deleteGenerations(setOf(id)); NativeIndexFiles.remove(context, id)
        }
    }

    @Test fun inactiveCleanupRecoveryPreservesGenerationActivatedByAnotherProfile() {
        val catalog = ModelCatalog.get(context)
        val original = catalog.snapshot()
        val database = MediaDatabase.get(context)
        val dao = database.aiIndexes()
        val owner = ProfileId.COMPACT
        val serving = ProfileId.EXTENDED
        val id = "shared-recovery-${System.nanoTime()}"
        val pipeline = catalog.trusted.profiles.getValue(owner).pipelines.getValue(AiFeature.PEOPLE)
        val record = AiIndexGenerationRecord(id, owner.wire, AiFeature.PEOPLE.name, pipeline.fingerprint,
            GenerationStatus.COMPLETE, 0, 0, null, null, 1)
        val native = NativeIndexFiles.generation(context, id).apply { mkdirs(); File(this, "marker").writeText("shared") }
        val journal = GenerationRemovalJournal(File(context.filesDir, "ai"))
        try {
            dao.saveGeneration(record)
            catalog.update { state -> state.copy(revision = state.revision + 1, selected = serving, active = serving,
                profiles = state.profiles + (owner to ProfileState(ProfilePhase.INSTALLED)) +
                    (serving to ProfileState(ProfilePhase.ACTIVE)),
                enabledFeatures = setOf(AiFeature.PEOPLE), pending = null,
                generations = state.generations + (id to record.toContractForTest()),
                activeGenerations = state.activeGenerations + (AiFeature.PEOPLE to id)) }
            journal.begin(owner, setOf(id), GenerationRemovalReason.INACTIVE_PROFILE)

            ModelMaintenance.recover(context)

            assertEquals(id, catalog.snapshot().activeGenerations[AiFeature.PEOPLE])
            assertEquals(record, dao.generation(id))
            assertTrue(native.exists())
            assertFalse(id in journal.ids())
            assertEquals(ProfilePhase.NOT_INSTALLED, catalog.snapshot().profile(owner).phase)
        } finally {
            journal.complete(owner, setOf(id), GenerationRemovalReason.INACTIVE_PROFILE)
            catalog.update { original.copy(revision = it.revision + 1) }
            dao.deleteGenerations(setOf(id)); NativeIndexFiles.remove(context, id)
        }
    }

    private fun AiIndexGenerationRecord.toContractForTest() = IndexGeneration(
        generationId, AiFeature.valueOf(feature), pipelineFingerprint, status == GenerationStatus.COMPLETE,
        completed, total,
    )

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
