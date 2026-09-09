package io.github.mesteriis.lik.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
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
        var releaseReservation = false
        catalog.operationPhase(profile, ProfilePhase.DOWNLOADING, 0, total)
        try {
            specs.forEach { store.repair(it, ::checkControl) }
            val installed = specs.associateWith { store.installed(it.sha256, it.size, ::checkControl) }
            missing = specs.filterNot { installed.getValue(it) }
            already = specs.filter { installed.getValue(it) }.sumOf { it.size }
            completed = already
            catalog.operationPhase(profile, ProfilePhase.DOWNLOADING, completed, total)
            missing.forEach { spec ->
                val journal = store.sharedJournal(spec.sha256)
                if (journal.isFile && !journalMatches(journal, spec)) {
                    store.sharedPart(spec.sha256).delete(); journal.delete()
                }
            }
            val resumeBytes = missing.associateWith { recoveredBytes(operationId, it, ledger) }
            ledger.acquire(operationId, missing.map { it to resumeBytes.getValue(it) },
                store.availableBytes(), SAFETY_MARGIN)
            for (spec in missing) {
                checkControl()
                val part = store.sharedPart(spec.sha256)
                val journal = store.sharedJournal(spec.sha256)
                val resumed = resumeBytes.getValue(spec)
                if (resumed != spec.size) {
                    download(spec, part, journal, resumed) { fileBytes ->
                        ledger.update(operationId, spec.sha256, (spec.size - fileBytes).coerceAtLeast(0))
                        val current = completed + fileBytes
                        catalog.operationPhase(profile, ProfilePhase.DOWNLOADING, current, total)
                        progress(DownloadProgress(profile, current, total, spec.path))
                    }
                }
                if (cancelled.get()) throw DownloadCancelled()
                writeJournal(journal, spec, DownloadJournalStage.VERIFYING, part.length())
                val verified = runCatching { store.verifyStaging(part, spec, ::checkControl) }.getOrElse {
                    part.delete()
                    journal.delete()
                    throw it
                }
                writeJournal(journal, spec, DownloadJournalStage.VERIFIED, part.length())
                store.publish(verified, spec)
                ledger.update(operationId, spec.sha256, 0)
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
            releaseReservation = true
        } catch (pause: DownloadPaused) {
            catalog.operationPhase(profile, ProfilePhase.PAUSED,
                already + missing.sumOf { spec ->
                    spec.size - (ledger.remaining(operationId, spec.sha256) ?: 0L)
                }, total)
            return
        } catch (cancel: DownloadCancelled) {
            releaseReservation = true
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
        } finally {
            if (releaseReservation) ledger.release(operationId)
            activeOperation = null; activeConnection = null
        }
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

    private fun recoveredBytes(operation: String, spec: ArtifactSpec, ledger: DownloadReservationLedger): Long {
        ledger.remaining(operation, spec.sha256)?.let { remaining ->
            if (remaining in 0..spec.size) return spec.size - remaining
        }
        val journal = store.sharedJournal(spec.sha256)
        if (journalMatches(journal, spec)) {
            val value = JSONObject(journal.readText()).getLong("bytes")
            if (value in 0..spec.size) return value
        }
        val part = store.sharedPart(spec.sha256)
        if (part.length() in 1 until spec.size) return part.length()
        if (part.length() == spec.size && runCatching { ArtifactStore.sha256(part, ::checkControl) == spec.sha256 }.getOrDefault(false))
            return spec.size
        return 0L
    }

    private fun download(spec: ArtifactSpec, part: File, journal: File, initialOffset: Long, report: (Long) -> Unit) {
        part.parentFile?.mkdirs()
        var offset = initialOffset
        require(offset in 0 until spec.size && part.length() == spec.size)
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
                if (decision == DownloadDecision.RESTART) {
                    offset = 0; writeJournal(journal, spec, DownloadJournalStage.DOWNLOADING, offset); report(offset)
                }
                RandomAccessFile(part, "rw").use { output ->
                    output.seek(offset)
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
        trusted.smokeGraphs(profile).forEach { graph ->
            checkControl()
            val smokeResult = runtime.runSmoke(file(graph.artifactPath), graph).getOrThrow()
            val outputCount = graph.outputShape.fold(1) { product, value -> Math.multiplyExact(product, value) }
            val pinnedMatch = SmokeReferenceVerifier.matchesExpectedSamples(graph.reference, smokeResult)
            val representative = if (graph.artifactPath in searchPaths) smokeResult else {
                val input = graph.inputs.single()
                require(input.type == "float32") { "COMPONENT_SELF_TEST_INPUT_TYPE:${graph.artifactPath}" }
                val count = input.shape.fold(1) { product, value -> Math.multiplyExact(product, value) }
                runtime.runFloat(file(graph.artifactPath), input.name, input.shape,
                    representativeInput(graph.artifactPath, input.shape, count), graph.outputName).getOrThrow()
            }
            checkControl()
            val outputShapeValid = smokeResult.size == outputCount && representative.size == outputCount
            val finite = smokeResult.all(Float::isFinite) && representative.all(Float::isFinite)
            val semantic = graph.artifactPath in searchPaths || semanticOutput(graph.artifactPath, representative)
            require(outputShapeValid && finite && pinnedMatch && semantic) {
                "COMPONENT_SELF_TEST_FAILED:${graph.artifactPath}:shape=$outputShapeValid:finite=$finite:pinned=$pinnedMatch:semantic=$semantic"
            }
        }
    }

    private fun representativeInput(path: String, shape: IntArray, count: Int): FloatArray {
        require(shape.size == 4 && shape[0] == 1 && shape[1] == 3)
        val plane = shape[2] * shape[3]
        return FloatArray(count) { at ->
            val channel = at / plane
            val pixel = ((at % plane * 37 + channel * 71) % 256).toFloat()
            when {
                path.startsWith("ocr-mobile-det") || path.startsWith("ocr-server-det") -> {
                    val mean = floatArrayOf(.485f, .456f, .406f); val std = floatArrayOf(.229f, .224f, .225f)
                    (pixel / 255f - mean[channel]) / std[channel]
                }
                path.startsWith("ocr-cyrillic") || path.startsWith("sensitive") -> pixel / 127.5f - 1f
                else -> pixel // YuNet/SFace contracts consume BGR/RGB byte-range tensors.
            }
        }
    }

    private fun semanticOutput(path: String, output: FloatArray): Boolean = when {
        path.startsWith("ocr-mobile-det") || path.startsWith("ocr-server-det") || path.startsWith("yunet") ->
            output.all { it in 0f..1f }
        path.startsWith("ocr-cyrillic") -> output.size % 852 == 0 && output.all { it in 0f..1f } &&
            output.asList().chunked(852).all { step -> kotlin.math.abs(step.sum() - 1f) < .02f }
        path.startsWith("sface") -> kotlin.math.sqrt(output.sumOf { (it * it).toDouble() }) > 1e-6
        path.startsWith("sensitive") -> output.size == 2 &&
            output.map { kotlin.math.exp((it - output.max()).toDouble()) }.let { probabilities ->
                val sum = probabilities.sum(); sum.isFinite() && sum > 0 && probabilities.all { it / sum in 0.0..1.0 }
            }
        else -> false
    }

    private fun file(path: String): File {
        val spec = trusted.allArtifacts.values.single { it.path == path }
        return store.file(spec.sha256)
    }
}
