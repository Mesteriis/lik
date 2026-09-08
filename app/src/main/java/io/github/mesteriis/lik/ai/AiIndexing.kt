package io.github.mesteriis.lik.ai

import android.content.Context
import android.os.PowerManager
import androidx.work.*
import io.github.mesteriis.lik.catalog.MediaAvailability
import io.github.mesteriis.lik.catalog.MediaDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class SemanticSearchResult(
    val hits: List<VectorHit>,
    val indexed: Int,
    val available: Int,
    val complete: Boolean,
    val nativeVerified: Boolean,
)

class SemanticSearchRepository(
    private val context: Context,
    private val catalog: ModelCatalog = ModelCatalog.get(context),
    private val database: MediaDatabase = MediaDatabase.get(context),
    private val engine: SemanticEmbeddingEngine = SemanticEmbeddingEngine(context),
) {
    fun search(text: String, limit: Int = 60): SemanticSearchResult {
        require(text.isNotBlank() && limit in 1..200)
        val state = catalog.snapshot()
        val profile = state.active ?: error("AI_PROFILE_NOT_ACTIVE")
        require(AiFeature.SEARCH in state.enabledFeatures) { "SEMANTIC_SEARCH_DISABLED" }
        val generationId = state.activeGenerations[AiFeature.SEARCH] ?: error("SEARCH_INDEX_NOT_READY")
        val generation = database.aiIndexes().generation(generationId) ?: error("SEARCH_INDEX_MISSING")
        require(generation.status == GenerationStatus.COMPLETE) { "SEARCH_INDEX_NOT_READY" }
        val dimension = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SEARCH).dimension!!
        val query = InferenceGate.run(InferencePriority.INTERACTIVE) { engine.query(profile, text) }
        val dao = database.aiIndexes()
        val indexed = dao.currentEmbeddingCount(generationId)
        val available = dao.availableCount()
        var nativeUsed = false
        var rows: List<AiEmbeddingRecord> = emptyList()
        val nativeDirectory = NativeIndexFiles.generation(context, generationId)
        val nativeFile = nativeDirectory.resolve("index.usearch")
        if (USearchBridge.available && nativeFile.isFile && nativeDirectory.resolve("verified").isFile && indexed > 0) runCatching {
            val bridge = USearchBridge(); val handle = bridge.create(dimension)
            try {
                bridge.load(handle, nativeFile.absolutePath)
                val keys = bridge.search(handle, query, minOf(indexed, maxOf(64, limit * 8).coerceAtMost(MAX_CANDIDATES)))
                rows = if (keys.isEmpty()) emptyList() else dao.currentByKeys(generationId, keys)
                nativeUsed = true
            } finally { bridge.close(handle) }
        }
        if (!nativeUsed) rows = dao.boundedCurrent(generationId, FALLBACK_SCAN_LIMIT)
        val candidates = rows.map { NativeCandidate(it.nativeKey, it.mediaId, it.vector.toFloats()) }
        val hits = CandidateReranker.rank(query, candidates, limit)
        return SemanticSearchResult(hits, indexed, available,
            generation.status == GenerationStatus.COMPLETE && indexed == available &&
                dao.embeddingCount(generationId) == indexed && (nativeUsed || available == 0), nativeUsed)
    }

    companion object { private const val MAX_CANDIDATES = 512; private const val FALLBACK_SCAN_LIMIT = 512 }
}

object NativeIndexFiles {
    fun root(context: Context) = File(context.filesDir, "ai/indexes").also(File::mkdirs)
    fun generation(context: Context, id: String) = File(root(context), "$id.ready")
    fun remove(context: Context, id: String) {
        val directory = root(context)
        directory.listFiles().orEmpty().filter { file ->
            file.name == "$id.ready" || file.name == "$id.exact" || file.name == "$id.usearch" ||
                (file.name.startsWith(".$id-") && (file.name.endsWith(".building") || file.name.endsWith(".previous"))) ||
                (file.name.startsWith(".$id.ready-") && file.name.endsWith(".previous"))
        }.forEach(File::deleteRecursively)
        DurableAiFiles.syncDirectory(directory)
    }
}

private class RetryableIndexException(message: String, cause: Throwable? = null) : Exception(message, cause)

object IndexRunCoordinator {
    private val locks = mutableMapOf<String, java.util.concurrent.locks.ReentrantLock>()
    fun <T> run(key: String, stopped: () -> Boolean, block: () -> T): T {
        val lock = synchronized(locks) { locks.getOrPut(key) { java.util.concurrent.locks.ReentrantLock() } }
        lock.lockInterruptibly()
        return try { if (stopped()) throw InterruptedException("INDEX_CANCELLED"); block() }
        finally { lock.unlock(); synchronized(locks) { if (!lock.isLocked && !lock.hasQueuedThreads()) locks.remove(key, lock) } }
    }
}

class AiIndexWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val profile = inputData.getString(PROFILE)?.let(ProfileId::fromWire) ?: return Result.failure()
        val catalog = ModelCatalog.get(applicationContext)
        val requested = catalog.snapshot().let { state ->
            state.pending?.takeIf { it.profile == profile }?.enabled
                ?: state.enabledFeatures.takeIf { state.active == profile }
                ?: emptySet()
        }
        if (AiFeature.SEARCH !in requested) return Result.success()
        val installed = catalog.trusted.artifacts(profile).all {
            ArtifactStore(File(applicationContext.filesDir, "ai")).installed(it.sha256, it.size)
        }
        if (!installed) return Result.failure(workDataOf(ERROR to "PROFILE_NOT_INSTALLED"))
        val thermal = applicationContext.getSystemService(PowerManager::class.java).currentThermalStatus
        if (!inputData.getBoolean(MANUAL, false) && thermal >= PowerManager.THERMAL_STATUS_SEVERE) return Result.retry()
        val pipeline = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SEARCH)
        return runCatching { IndexRunCoordinator.run(pipeline.fingerprint, { isStopped }) {
            prepareSearch(profile, catalog, if (inputData.getBoolean(MANUAL, false)) InferencePriority.MANUAL else InferencePriority.BACKGROUND)
        } }.fold(
            { Result.success() },
            { error -> if (error is RetryableIndexException || error is InterruptedException) Result.retry()
                else Result.failure(workDataOf(ERROR to (error.message ?: error.javaClass.simpleName).take(160))) },
        )
    }

    private fun prepareSearch(profile: ProfileId, catalog: ModelCatalog, priority: InferencePriority) {
        val database = MediaDatabase.get(applicationContext)
        val dao = database.aiIndexes()
        val pipeline = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SEARCH)
        val retired = GenerationRemovalJournal(File(applicationContext.filesDir, "ai")).ids()
        val compatible = dao.compatible(pipeline.fingerprint)?.takeIf { it.generationId !in retired }
        if (compatible != null && generationCurrent(compatible, dao)) {
            publishCatalog(catalog, profile, compatible)
            return
        }
        val generation = dao.generations().lastOrNull {
            it.profileId == profile.wire && it.feature == AiFeature.SEARCH.name &&
                it.pipelineFingerprint == pipeline.fingerprint && it.status == GenerationStatus.PREPARING
        } ?: AiIndexGenerationRecord(
            UUID.randomUUID().toString(), profile.wire, AiFeature.SEARCH.name, pipeline.fingerprint,
            GenerationStatus.PREPARING, 0, dao.availableCount(), null, null, System.currentTimeMillis(),
        ).also(dao::saveGeneration)
        val engine = SemanticEmbeddingEngine(applicationContext)
        val sensitivePipeline = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SENSITIVE).fingerprint
        var checkpoint = generation.checkpointMediaId.takeUnless { generation.error != null }
        dao.staleMediaIds(generation.generationId).forEach { dao.deleteEmbedding(generation.generationId, it) }
        var completed = dao.currentEmbeddingCount(generation.generationId)
        var nativeKey = (dao.maxKey(generation.generationId) ?: 0L) + 1L
        while (true) {
            if (isStopped) throw InterruptedException("INDEX_CANCELLED")
            val batch = dao.mediaBatch(checkpoint, BATCH_SIZE)
            if (batch.isEmpty()) break
            for (row in batch) {
                if (isStopped) throw InterruptedException("INDEX_CANCELLED")
                val item = IndexItem(row.mediaId, row.contentRevision, row.lastSeenAt, pipeline.fingerprint)
                val priorEmbedding = dao.embedding(generation.generationId, row.mediaId)
                if (priorEmbedding?.let { it.contentRevision == row.contentRevision && it.accessEpoch == row.lastSeenAt } == true) {
                    checkpoint = row.mediaId
                    dao.saveGeneration(generation.copy(completed = completed, total = dao.availableCount(), checkpointMediaId = checkpoint, error = null))
                    continue
                }
                if (dao.sensitive(row.mediaId, row.contentRevision, sensitivePipeline)?.status != SensitiveRunStatus.RAW_RESULT) {
                    val raw = runCatching { InferenceGate.run(priority) { engine.sensitive(row.mediaId, row.contentRevision) } }
                    dao.saveSensitive(AiSensitiveRunRecord(row.mediaId, row.contentRevision, sensitivePipeline,
                        if (raw.isSuccess) SensitiveRunStatus.RAW_RESULT else SensitiveRunStatus.ERROR,
                        raw.getOrNull()?.toBytes(), raw.exceptionOrNull()?.message?.take(160), System.currentTimeMillis()))
                    if (raw.isFailure) {
                        dao.saveGeneration(generation.copy(completed = completed, checkpointMediaId = checkpoint,
                            error = "SENSITIVE_RETRY:${raw.exceptionOrNull()?.message.orEmpty().take(100)}"))
                        throw RetryableIndexException("SENSITIVE_RETRY", raw.exceptionOrNull())
                    }
                }
                val embedded = runCatching { InferenceGate.run(priority) { engine.image(profile, row.mediaId, row.contentRevision) } }
                if (isStopped) throw InterruptedException("INDEX_CANCELLED")
                val fresh = database.media().get(row.mediaId)
                if (embedded.isFailure) {
                    dao.saveGeneration(generation.copy(completed = completed, checkpointMediaId = checkpoint,
                        error = "EMBEDDING_RETRY:${embedded.exceptionOrNull()?.message.orEmpty().take(100)}"))
                    throw RetryableIndexException("EMBEDDING_RETRY", embedded.exceptionOrNull())
                }
                if (fresh == null || fresh.availability != MediaAvailability.AVAILABLE) {
                    if (dao.deleteEmbedding(generation.generationId, row.mediaId) > 0) completed--
                    checkpoint = row.mediaId
                    dao.saveGeneration(generation.copy(completed = completed, total = dao.availableCount(), checkpointMediaId = checkpoint))
                    continue
                }
                if (!item.canPublish(fresh.mediaId, fresh.contentRevision, fresh.lastSeenAt, true))
                    throw RetryableIndexException("MEDIA_CHANGED").also {
                        dao.saveGeneration(generation.copy(completed = completed, checkpointMediaId = checkpoint, error = "MEDIA_CHANGED"))
                    }
                val nextGeneration = generation.copy(completed = completed + 1, total = dao.availableCount(),
                    checkpointMediaId = row.mediaId, error = null)
                val published = dao.publishEmbeddingIfCurrent(AiEmbeddingRecord(generation.generationId, row.mediaId, nativeKey,
                    row.contentRevision, row.lastSeenAt, embedded.getOrThrow().toBytes()), nextGeneration)
                if (!published) throw RetryableIndexException("MEDIA_CHANGED")
                nativeKey++
                completed++
                checkpoint = row.mediaId
                val updated = nextGeneration
                catalog.saveGeneration(updated.toContract(complete = false))
                setProgressAsync(workDataOf(COMPLETED to completed, TOTAL to updated.total))
            }
        }
        val rows = dao.embeddings(generation.generationId)
        persistIndexes(generation.generationId, pipeline.dimension!!, rows)
        if (isStopped) throw InterruptedException("INDEX_CANCELLED")
        val done = dao.completeIfCurrent(generation.copy(completed = rows.size,
            total = dao.availableCount(), checkpointMediaId = checkpoint, error = null)) ?: run {
            dao.saveGeneration(generation.copy(completed = dao.currentEmbeddingCount(generation.generationId),
                total = dao.availableCount(), checkpointMediaId = checkpoint, error = "INDEX_COVERAGE_CHANGED"))
            throw RetryableIndexException("INDEX_COVERAGE_CHANGED")
        }
        if (isStopped) throw InterruptedException("INDEX_CANCELLED")
        publishCatalog(catalog, profile, done)
    }

    private fun generationCurrent(record: AiIndexGenerationRecord, dao: AiIndexDao): Boolean {
        val available = dao.availableCount()
        val directory = NativeIndexFiles.generation(applicationContext, record.generationId)
        return dao.currentEmbeddingCount(record.generationId) == available &&
            ((directory.resolve("index.usearch").isFile && directory.resolve("verified").isFile) ||
                (available == 0 && directory.resolve("empty").isFile)) && dao.embeddingCount(record.generationId) == available
    }

    private fun persistIndexes(id: String, dimension: Int, rows: List<AiEmbeddingRecord>) {
        val root = NativeIndexFiles.root(applicationContext)
        val directory = File(root, ".$id-${System.nanoTime()}.building").also { check(it.mkdirs()) }
        val exact = ExactVectorIndex(dimension)
        rows.forEach { exact.upsert(it.mediaId, it.vector.toFloats()) }
        exact.save(File(directory, "index.exact"))
        if (rows.isEmpty()) {
            DurableAiFiles.atomicWrite(File(directory, "empty"), byteArrayOf())
            val target = NativeIndexFiles.generation(applicationContext, id)
            DurableAiFiles.replaceDirectory(directory, target)
            return
        }
        check(USearchBridge.available) { "USEARCH_UNAVAILABLE" }
        val bridge = USearchBridge(); val handle = bridge.create(dimension)
        val native = File(directory, "index.usearch")
        try {
            bridge.reserve(handle, rows.size.toLong())
            rows.forEach { bridge.upsert(handle, it.nativeKey, it.vector.toFloats()) }
            bridge.save(handle, native.absolutePath)
            FileOutputStream(native, true).use { it.fd.sync() }
            val byKey = rows.associateBy { it.nativeKey }
            val probes = rows.filterIndexed { index, _ -> index % maxOf(1, rows.size / 8) == 0 }.take(8)
            probes.forEach { probe ->
                val query = probe.vector.toFloats()
                val probeLimit = minOf(10, rows.size)
                val candidateKeys = bridge.search(handle, query, minOf(rows.size, PARITY_CANDIDATES))
                val candidates = candidateKeys.map { byKey[it] }.filterNotNull().map {
                    NativeCandidate(it.nativeKey, it.mediaId, it.vector.toFloats())
                }
                require(SearchParity.accept(exact.search(query, probeLimit),
                    CandidateReranker.rank(query, candidates, probeLimit), probeLimit)) { "USEARCH_PARITY_FAILED" }
            }
            DurableAiFiles.atomicWrite(File(directory, "verified"), byteArrayOf())
            val target = NativeIndexFiles.generation(applicationContext, id)
            DurableAiFiles.replaceDirectory(directory, target)
        } finally { bridge.close(handle); directory.deleteRecursively() }
    }

    private fun publishCatalog(catalog: ModelCatalog, profile: ProfileId, record: AiIndexGenerationRecord) {
        catalog.generationReady(profile, AiFeature.SEARCH, record.toContract(complete = true))
    }

    private fun AiIndexGenerationRecord.toContract(complete: Boolean) = IndexGeneration(
        generationId, AiFeature.valueOf(feature), pipelineFingerprint, complete, completed, total,
    )

    companion object {
        private const val PROFILE = "profile"
        private const val MANUAL = "manual"
        private const val ERROR = "error"
        private const val COMPLETED = "completed"
        private const val TOTAL = "total"
        private const val BATCH_SIZE = 16
        private const val PARITY_CANDIDATES = 512

        fun enqueue(context: Context, profile: ProfileId, manual: Boolean) {
            val constraints = Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).apply {
                if (!manual) setRequiresCharging(true)
            }.build()
            val request = OneTimeWorkRequestBuilder<AiIndexWorker>()
                .setInputData(workDataOf(PROFILE to profile.wire, MANUAL to manual))
                .setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("ai-index-${profile.wire}",
                if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
            if (!manual) {
                val periodic = PeriodicWorkRequestBuilder<AiIndexWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
                    .setInputData(workDataOf(PROFILE to profile.wire, MANUAL to false)).setConstraints(constraints).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork("ai-index-periodic-${profile.wire}",
                    ExistingPeriodicWorkPolicy.KEEP, periodic)
            }
        }

        fun pause(context: Context, profile: ProfileId) =
            WorkManager.getInstance(context).run {
                cancelUniqueWork("ai-index-${profile.wire}"); cancelUniqueWork("ai-index-periodic-${profile.wire}")
            }

        fun discardPreparation(context: Context, profile: ProfileId) {
            pause(context, profile)
            val catalog = ModelCatalog.get(context)
            val pipeline = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SEARCH)
            IndexRunCoordinator.run(pipeline.fingerprint, { false }) {
                val database = MediaDatabase.get(context)
                val ids = database.aiIndexes().generations().filter {
                    it.profileId == profile.wire && it.status != GenerationStatus.COMPLETE
                }.map { it.generationId }.toSet()
                if (catalog.snapshot().pending?.profile == profile) catalog.cancelPreparation(profile)
                if (ids.isNotEmpty()) {
                    catalog.discardGenerations(ids)
                    database.runInTransaction { database.aiIndexes().deleteGenerations(ids) }
                    ids.forEach { NativeIndexFiles.remove(context, it) }
                }
            }
        }
    }
}
