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

    fun cancel() { cancelled.set(true) }
    fun pause() { paused.set(true) }

    /** Blocking by design for WorkManager/IO dispatchers. No network action occurs without this explicit call. */
    fun install(profile: ProfileId, progress: (DownloadProgress) -> Unit = {}): Result<Unit> = runCatching {
        cancelled.set(false); paused.set(false)
        val specs = catalog.trusted.artifacts(profile)
        val missing = specs.filterNot { store.installed(it.sha256, it.size) }
        val total = specs.sumOf { it.size }
        val already = specs.filter { store.installed(it.sha256, it.size) }.sumOf { it.size }
        val operationId = profile.wire
        val operation = store.operation(operationId)
        activeOperation = operation
        var completed = already
        catalog.operationPhase(profile, ProfilePhase.DOWNLOADING, completed, total)
        try {
            store.reserve(SpaceReservation.required(missing, emptySet(), SAFETY_MARGIN))
            for (spec in missing) {
                val part = File(operation, spec.sha256 + ".part")
                val journal = File(operation, spec.sha256 + ".json")
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
                if (part.length() != spec.size || ArtifactStore.sha256(part) != spec.sha256) {
                    part.delete()
                    journal.delete()
                    error("HASH_MISMATCH")
                }
                writeJournal(journal, spec, DownloadJournalStage.VERIFIED, part.length())
                store.publish(part, spec)
                check(journal.delete())
                completed += spec.size
            }
            check(specs.all { store.installed(it.sha256, it.size) })
            catalog.operationPhase(profile, ProfilePhase.SELF_TESTING, total, total)
            ModelSelfTest(store, IsolatedRuntimeClient(context), SemanticEmbeddingEngine(context)).validate(profile, specs)
            catalog.selfTested(profile)
            if (AiFeature.SEARCH in catalog.snapshot().enabledFeatures) AiIndexWorker.enqueue(context, profile, manual = false)
            operation.deleteRecursively()
        } catch (pause: DownloadPaused) {
            catalog.operationPhase(profile, ProfilePhase.PAUSED,
                already + missing.sumOf { File(operation, it.sha256 + ".part").length() }, total)
            return@runCatching
        } catch (cancel: DownloadCancelled) {
            operation.deleteRecursively()
            val current = catalog.snapshot()
            catalog.update { state ->
                state.copy(revision = state.revision + 1, pending = state.pending?.takeUnless { it.profile == profile },
                    profiles = state.profiles + (profile to if (current.active == profile) ProfileState(ProfilePhase.ACTIVE, total, total) else ProfileState()))
            }
        } catch (error: Throwable) {
            if (paused.get()) throw error
            val code = (error.message ?: error.javaClass.simpleName).take(120)
            catalog.operationPhase(profile, ProfilePhase.ERROR, completed, total, code)
            throw error
        } finally { activeOperation = null }
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
            } finally { connection.disconnect() }
        }
    }

    private fun checkControl() {
        val command = activeOperation?.let { File(it, "control").takeIf(File::isFile)?.readText()?.trim() }
        if (command == "cancel") cancelled.set(true)
        if (command == "pause") paused.set(true)
        if (cancelled.get()) throw DownloadCancelled()
        if (paused.get()) throw DownloadPaused()
    }

    private fun writeJournal(file: File, spec: ArtifactSpec, stage: DownloadJournalStage, bytes: Long) {
        val value = JSONObject().put("schema", 1).put("path", spec.path).put("size", spec.size)
            .put("sha256", spec.sha256).put("url", spec.url.toString()).put("stage", stage.name).put("bytes", bytes)
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { output -> output.write(value.toString().toByteArray()); output.fd.sync() }
        check(temporary.renameTo(file))
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
    private fun control(context: Context, profile: ProfileId) = File(context.filesDir, "ai/staging/${profile.wire}/control")
    fun pause(context: Context, profile: ProfileId) { control(context, profile).apply { parentFile?.mkdirs(); writeText("pause") } }
    fun cancel(context: Context, profile: ProfileId) {
        control(context, profile).apply { parentFile?.mkdirs(); writeText("cancel") }
        val catalog = ModelCatalog.get(context)
        if (catalog.snapshot().profile(profile).phase == ProfilePhase.PAUSED) {
            File(context.filesDir, "ai/staging/${profile.wire}").deleteRecursively()
            catalog.update { state -> state.copy(revision = state.revision + 1,
                pending = state.pending?.takeUnless { it.profile == profile },
                profiles = state.profiles + (profile to if (state.active == profile) state.profile(profile).copy(phase = ProfilePhase.ACTIVE)
                    else ProfileState())) }
        }
    }
    fun resume(context: Context, profile: ProfileId) { control(context, profile).delete(); ProfileDownloadWorker.enqueue(context, profile) }
}

/** Exact file verification plus sequential ORT graph opening; sessions never overlap. */
@android.annotation.SuppressLint("UseKtx")
class ModelSelfTest(private val store: ArtifactStore, private val runtime: IsolatedRuntimeClient,
                    private val semantic: SemanticEmbeddingEngine) {
    fun validate(profile: ProfileId, specs: List<ArtifactSpec>) {
        specs.forEach { require(store.installed(it.sha256, it.size)) { "ARTIFACT_INVALID" } }
        runtime.validate(specs.filter { it.path.endsWith(".onnx") }.map { store.file(it.sha256) }).getOrThrow()
        val expected = when (profile) { ProfileId.COMPACT -> 512; ProfileId.BALANCED -> 768; ProfileId.EXTENDED -> 1024 }
        require(semantic.query(profile, "красная машина на снегу").size == expected) { "RU_TEXT_SELF_TEST_FAILED" }
        val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(131, 72, 43))
        }
        try { require(semantic.imageBitmap(profile, bitmap).size == expected) { "IMAGE_SELF_TEST_FAILED" } }
        finally { bitmap.recycle() }
    }
}
