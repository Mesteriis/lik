package io.github.mesteriis.lik.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadProgress(val profile: ProfileId, val completed: Long, val total: Long, val file: String)

class ModelDownloader(
    private val context: Context,
    private val catalog: ModelCatalog = ModelCatalog.get(context),
    private val store: ArtifactStore = ArtifactStore(File(context.filesDir, "ai")),
) {
    private val cancelled = AtomicBoolean()
    private val paused = AtomicBoolean()

    fun cancel() { cancelled.set(true); activeConnection?.disconnect() }
    fun pause() { paused.set(true) }

    /** Blocking by design for WorkManager/IO dispatchers. No network action occurs without this explicit call. */
    fun install(profile: ProfileId, progress: (DownloadProgress) -> Unit = {}): Result<Unit> = runCatching {
        DownloadCoordinator.run(store.root) { installLocked(profile, progress) }
    }

    private fun installLocked(profile: ProfileId, progress: (DownloadProgress) -> Unit) {
        cancelled.set(false); paused.set(false)
        val specs = catalog.trusted.artifacts(profile)
        val total = specs.sumOf { it.size }
        val operationId = profile.wire
        val operation = store.operation(operationId)
        val ledger = DownloadReservationLedger(store.root)
        activeOperation = operation
        var missing = emptyList<ArtifactSpec>()
        var already = 0L
        var completed = 0L
        catalog.operationPhase(profile, ProfilePhase.DOWNLOADING, 0, total)
        try {
            specs.forEach { store.repair(it, ::checkControl) }
            val installed = specs.associateWith { store.installed(it.sha256, it.size, ::checkControl) }
            missing = specs.filterNot { installed.getValue(it) }
            already = specs.filter { installed.getValue(it) }.sumOf { it.size }
            completed = already
            catalog.operationPhase(profile, ProfilePhase.DOWNLOADING, completed, total)
            ledger.acquire(operationId, missing.map { it to store.sharedPart(it.sha256).length().coerceAtMost(it.size) },
                store.availableBytes(), SAFETY_MARGIN)
            for (spec in missing) {
                checkControl()
                val part = store.sharedPart(spec.sha256)
                val journal = store.sharedJournal(spec.sha256)
                if (journal.isFile && !journalMatches(journal, spec)) {
                    part.delete()
                    journal.delete()
                }
                if (part.length() != spec.size) {
                    download(spec, part, journal) { fileBytes ->
                        val current = completed + fileBytes
                        catalog.operationPhase(profile, ProfilePhase.DOWNLOADING, current, total)
                        progress(DownloadProgress(profile, current, total, spec.path))
                    }
                }
                if (cancelled.get()) throw DownloadCancelled()
                writeJournal(journal, spec, DownloadJournalStage.VERIFYING, part.length())
                if (part.length() != spec.size || ArtifactStore.sha256(part, ::checkControl) != spec.sha256) {
                    part.delete()
                    journal.delete()
                    error("HASH_MISMATCH")
                }
                writeJournal(journal, spec, DownloadJournalStage.VERIFIED, part.length())
                store.publish(part, spec, ::checkControl)
                check(journal.delete())
                completed += spec.size
            }
            check(specs.all { store.installed(it.sha256, it.size, ::checkControl) })
            catalog.operationPhase(profile, ProfilePhase.SELF_TESTING, total, total)
            ModelSelfTest(store, IsolatedRuntimeClient(context), SemanticEmbeddingEngine(context), catalog.trusted)
                .validate(profile, specs, ::checkControl)
            checkControl()
            catalog.selfTested(profile)
            val requested = catalog.snapshot().let { state ->
                state.pending?.takeIf { it.profile == profile }?.enabled
                    ?: state.enabledFeatures.takeIf { state.active == profile }
                    ?: emptySet()
            }
            if (AiFeature.SEARCH in requested) AiIndexWorker.enqueue(context, profile, manual = false)
            operation.deleteRecursively()
            operation.parentFile?.let(DurableAiFiles::syncDirectory)
        } catch (pause: DownloadPaused) {
            catalog.operationPhase(profile, ProfilePhase.PAUSED,
                already + missing.sumOf { store.sharedPart(it.sha256).length() }, total)
            return
        } catch (cancel: DownloadCancelled) {
            store.abandonShared(specs.map { it.sha256 }.toSet(), operationId)
            val current = catalog.snapshot()
            catalog.update { state ->
                state.copy(revision = state.revision + 1, pending = state.pending?.takeUnless { it.profile == profile },
                    profiles = state.profiles + (profile to if (current.active == profile) ProfileState(ProfilePhase.ACTIVE, total, total) else ProfileState()))
            }
        } catch (error: Throwable) {
            if (paused.get()) throw error
            File(operation, "control").let { if (it.delete()) operation.let(DurableAiFiles::syncDirectory) }
            val code = (error.message ?: error.javaClass.simpleName).take(120)
            catalog.operationPhase(profile, ProfilePhase.ERROR, completed, total, code)
            throw error
        } finally { ledger.release(operationId); activeOperation = null; activeConnection = null }
    }

    fun abandon(profile: ProfileId) = DownloadCoordinator.run(store.root) {
        val specs = catalog.trusted.artifacts(profile)
        store.abandonShared(specs.map { it.sha256 }.toSet(), profile.wire)
        cleanupUnreferencedArtifacts()
    }

    private fun cleanupUnreferencedArtifacts() {
        val state = catalog.snapshot()
        val installed = state.profiles.filterValues { it.phase !in setOf(ProfilePhase.NOT_INSTALLED, ProfilePhase.ERROR) }
            .keys.map { id -> catalog.trusted.artifacts(id).map { it.sha256 }.toSet() }
        store.cleanup(ArtifactRetention.retained(installed, emptyList(), emptySet(), IsolatedRuntimeClient.liveArtifactDigests()))
    }

    private fun download(spec: ArtifactSpec, part: File, journal: File, report: (Long) -> Unit) {
        part.parentFile?.mkdirs()
        var offset = part.length().coerceAtMost(spec.size)
        if (part.length() > spec.size) { part.delete(); offset = 0 }
        writeJournal(journal, spec, DownloadJournalStage.DOWNLOADING, offset)
        var redirects = 0
        var current = spec.url
        while (true) {
            checkControl()
            val connection = (URL(current.toString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "Lik/${catalog.trusted.version}")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            activeConnection = connection
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    require(++redirects <= MAX_REDIRECTS) { "REDIRECT_LIMIT" }
                    val location = connection.getHeaderField("Location") ?: error("REDIRECT_WITHOUT_LOCATION")
                    val destination = current.resolve(location)
                    require(DownloadProtocol.allowedRedirect(spec.url, destination)) { "UNTRUSTED_REDIRECT" }
                    current = destination
                    continue
                }
                val decision = DownloadProtocol.response(spec, offset, code,
                    connection.getHeaderField("Content-Range"), connection.contentLengthLong, current)
                require(decision != DownloadDecision.REJECT) { "INVALID_RANGE_RESPONSE" }
                if (decision == DownloadDecision.RESTART) { offset = 0; part.delete() }
                FileOutputStream(part, offset > 0).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var sinceSync = 0L
                        while (true) {
                            checkControl()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count); offset += count; sinceSync += count
                            require(offset <= spec.size) { "SIZE_MISMATCH" }
                            if (sinceSync >= CHECKPOINT_BYTES) {
                                output.fd.sync(); writeJournal(journal, spec, DownloadJournalStage.DOWNLOADING, offset)
                                report(offset); sinceSync = 0
                            }
                        }
                        output.fd.sync()
                    }
                }
                writeJournal(journal, spec, DownloadJournalStage.DOWNLOADING, offset); report(offset)
                require(offset == spec.size) { "TRUNCATED_DOWNLOAD" }
                return
            } finally { activeConnection = null; connection.disconnect() }
        }
    }

    private fun checkControl() {
        if (Thread.currentThread().isInterrupted) cancelled.set(true)
        val command = activeOperation?.let { File(it, "control").takeIf(File::isFile)?.readText()?.trim() }
        if (command == "cancel") cancelled.set(true)
        if (command == "pause") paused.set(true)
        if (cancelled.get()) throw DownloadCancelled()
        if (paused.get()) throw DownloadPaused()
    }

    private fun writeJournal(file: File, spec: ArtifactSpec, stage: DownloadJournalStage, bytes: Long) {
        val value = JSONObject().put("schema", 1).put("path", spec.path).put("size", spec.size)
            .put("sha256", spec.sha256).put("url", spec.url.toString()).put("stage", stage.name).put("bytes", bytes)
        DurableAiFiles.atomicWrite(file, value.toString().toByteArray())
    }

    private fun journalMatches(file: File, spec: ArtifactSpec): Boolean = runCatching {
        val value = JSONObject(file.readText())
        value.getInt("schema") == 1 && value.getString("path") == spec.path &&
            value.getLong("size") == spec.size && value.getString("sha256") == spec.sha256 &&
            value.getString("url") == spec.url.toString() &&
            DownloadJournalStage.valueOf(value.getString("stage")).let { true }
    }.getOrDefault(false)

    private class DownloadCancelled : Exception("CANCELLED")
    private class DownloadPaused : Exception("PAUSED")
    @Volatile private var activeOperation: File? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 6
        private const val BUFFER_SIZE = 128 * 1024
        private const val CHECKPOINT_BYTES = 4L * 1024 * 1024
        private const val SAFETY_MARGIN = 64L * 1024 * 1024
    }
}

object ProfileDownloadControls {
    private fun control(context: Context, profile: ProfileId) = File(context.filesDir, "ai/staging/operations/${profile.wire}/control")
    fun pause(context: Context, profile: ProfileId) { DurableAiFiles.atomicWrite(control(context, profile), "pause".toByteArray()) }
    fun cancel(context: Context, profile: ProfileId) {
        DurableAiFiles.atomicWrite(control(context, profile), "cancel".toByteArray())
        ProfileDownloadWorker.cancel(context, profile)
        ProfileAbandonWorker.enqueue(context, profile)
    }
    fun resume(context: Context, profile: ProfileId) { control(context, profile).delete(); ProfileDownloadWorker.enqueue(context, profile) }
}

/** Exact file verification plus sequential ORT graph opening; sessions never overlap. */
@android.annotation.SuppressLint("UseKtx")
class ModelSelfTest(private val store: ArtifactStore, private val runtime: IsolatedRuntimeClient,
                    private val semantic: SemanticEmbeddingEngine, private val trusted: TrustedModelCatalog) {
    fun validate(profile: ProfileId, specs: List<ArtifactSpec>, checkControl: () -> Unit = {}) {
        specs.forEach { require(store.installed(it.sha256, it.size, checkControl)) { "ARTIFACT_INVALID" } }
        checkControl()
        runtime.validate(specs.filter { it.path.endsWith(".onnx") }.map { store.file(it.sha256) }).getOrThrow()
        checkControl()
        val expected = when (profile) { ProfileId.COMPACT -> 512; ProfileId.BALANCED -> 768; ProfileId.EXTENDED -> 1024 }
        require(semantic.query(profile, "красная машина на снегу").size == expected) { "RU_TEXT_SELF_TEST_FAILED" }
        val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(131, 72, 43))
        }
        try { require(semantic.imageBitmap(profile, bitmap).size == expected) { "IMAGE_SELF_TEST_FAILED" } }
        finally { bitmap.recycle() }
        val searchPaths = when (profile) {
            ProfileId.COMPACT -> setOf("clip-image-v1/image.onnx", "multilingual-text-v1/model.onnx")
            ProfileId.BALANCED -> setOf("siglip2-base-v1/image.onnx", "siglip2-base-v1/text.onnx")
            ProfileId.EXTENDED -> setOf("siglip2-large-v1/image.onnx", "siglip2-large-v1/text.onnx")
        }
        trusted.smokeGraphs(profile).filter { it.artifactPath !in searchPaths }.forEach { graph ->
            checkControl()
            val count = graph.shape.fold(1) { product, value -> Math.multiplyExact(product, value) }
            val result = runtime.runFloat(file(graph.artifactPath), graph.inputName, graph.shape, FloatArray(count), graph.outputName).getOrThrow()
            require(result.isNotEmpty() && result.all(Float::isFinite)) { "COMPONENT_SELF_TEST_FAILED:${graph.artifactPath}" }
        }
    }

    private fun file(path: String): File {
        val spec = trusted.allArtifacts.values.single { it.path == path }
        return store.file(spec.sha256)
    }
}
