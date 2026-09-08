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
        val rows = database.aiIndexes().embeddings(generationId).filter { embedding ->
            database.media().get(embedding.mediaId)?.let { media ->
                media.availability == MediaAvailability.AVAILABLE && media.contentRevision == embedding.contentRevision
            } == true
        }
        val exact = ExactVectorIndex(dimension)
        rows.forEach { exact.upsert(it.mediaId, it.vector.toFloats()) }
        val query = InferenceGate.run(InferencePriority.INTERACTIVE) { engine.query(profile, text) }
        val reference = exact.search(query, limit)
        var nativeVerified = false
        val nativeFile = NativeIndexFiles.root(context).resolve("$generationId.usearch")
        if (USearchBridge.available && nativeFile.isFile && rows.isNotEmpty()) runCatching {
            val bridge = USearchBridge(); val handle = bridge.create(dimension)
            try {
                bridge.load(handle, nativeFile.absolutePath)
                val keys = bridge.search(handle, query, minOf(rows.size, maxOf(limit * 4, limit)))
                val byKey = rows.associateBy { it.nativeKey }
                val candidates = keys.asSequence().mapNotNull(byKey::get).map { row ->
                    VectorHit(row.mediaId, dot(query, row.vector.toFloats()))
                }.sortedWith(compareByDescending<VectorHit> { it.score }.thenBy { it.mediaId }).take(limit).toList()
                nativeVerified = SearchParity.accept(reference, candidates, limit)
            } finally { bridge.close(handle) }
        }
        return SemanticSearchResult(reference, rows.size, database.aiIndexes().availableCount(),
            generation.status == GenerationStatus.COMPLETE, nativeVerified)
    }

    private fun dot(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size)
        var value = 0f; for (i in left.indices) value += left[i] * right[i]
        return value
    }
}

object NativeIndexFiles {
    fun root(context: Context) = File(context.filesDir, "ai/indexes").also(File::mkdirs)
}

class AiIndexWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val profile = inputData.getString(PROFILE)?.let(ProfileId::fromWire) ?: return Result.failure()
        val catalog = ModelCatalog.get(applicationContext)
        if (AiFeature.SEARCH !in catalog.snapshot().enabledFeatures) return Result.success()
        val installed = catalog.trusted.artifacts(profile).all {
            ArtifactStore(File(applicationContext.filesDir, "ai")).installed(it.sha256, it.size)
        }
        if (!installed) return Result.failure(workDataOf(ERROR to "PROFILE_NOT_INSTALLED"))
        val thermal = applicationContext.getSystemService(PowerManager::class.java).currentThermalStatus
        if (!inputData.getBoolean(MANUAL, false) && thermal >= PowerManager.THERMAL_STATUS_SEVERE) return Result.retry()
        return runCatching { prepareSearch(profile, catalog, if (inputData.getBoolean(MANUAL, false)) InferencePriority.MANUAL else InferencePriority.BACKGROUND) }.fold(
            { Result.success() },
            { error -> Result.failure(workDataOf(ERROR to (error.message ?: error.javaClass.simpleName).take(160))) },
        )
    }

    private fun prepareSearch(profile: ProfileId, catalog: ModelCatalog, priority: InferencePriority) {
        val database = MediaDatabase.get(applicationContext)
        val dao = database.aiIndexes()
        val pipeline = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SEARCH)
        val compatible = dao.compatible(pipeline.fingerprint)
        if (compatible != null && generationCurrent(compatible, dao, database)) {
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
        var checkpoint = generation.checkpointMediaId
        var completed = dao.embeddingCount(generation.generationId)
        var nativeKey = (dao.maxKey(generation.generationId) ?: 0L) + 1L
        while (true) {
            if (isStopped) error("INDEX_CANCELLED")
            val batch = dao.mediaBatch(checkpoint, BATCH_SIZE)
            if (batch.isEmpty()) break
            for (row in batch) {
                if (isStopped) error("INDEX_CANCELLED")
                val item = IndexItem(row.mediaId, row.contentRevision, row.lastSeenAt, pipeline.fingerprint)
                if (dao.sensitive(row.mediaId, row.contentRevision, sensitivePipeline) == null) {
                    val raw = runCatching { InferenceGate.run(priority) { engine.sensitive(row.mediaId, row.contentRevision) } }
                    dao.saveSensitive(AiSensitiveRunRecord(row.mediaId, row.contentRevision, sensitivePipeline,
                        if (raw.isSuccess) SensitiveRunStatus.RAW_RESULT else SensitiveRunStatus.ERROR,
                        raw.getOrNull()?.toBytes(), raw.exceptionOrNull()?.message?.take(160), System.currentTimeMillis()))
                }
                val embedded = runCatching { InferenceGate.run(priority) { engine.image(profile, row.mediaId, row.contentRevision) } }
                val fresh = database.media().get(row.mediaId)
                if (embedded.isSuccess && fresh != null && item.canPublish(fresh.mediaId, fresh.contentRevision,
                        fresh.lastSeenAt, fresh.availability == MediaAvailability.AVAILABLE)) {
                    dao.saveEmbedding(AiEmbeddingRecord(generation.generationId, row.mediaId, nativeKey++,
                        row.contentRevision, row.lastSeenAt, embedded.getOrThrow().toBytes()))
                    completed++
                }
                checkpoint = row.mediaId
                val updated = generation.copy(completed = completed, total = dao.availableCount(), checkpointMediaId = checkpoint)
                dao.saveGeneration(updated)
                catalog.saveGeneration(updated.toContract(complete = false))
                setProgressAsync(workDataOf(COMPLETED to completed, TOTAL to updated.total))
            }
        }
        val rows = dao.embeddings(generation.generationId)
        persistIndexes(generation.generationId, pipeline.dimension!!, rows)
        val done = generation.copy(status = GenerationStatus.COMPLETE, completed = rows.size,
            total = dao.availableCount(), checkpointMediaId = checkpoint, error = null)
        dao.saveGeneration(done)
        publishCatalog(catalog, profile, done)
    }

    private fun generationCurrent(record: AiIndexGenerationRecord, dao: AiIndexDao, database: MediaDatabase): Boolean {
        val rows = dao.embeddings(record.generationId)
        if (rows.size != dao.availableCount()) return false
        return rows.all { embedding -> database.media().get(embedding.mediaId)?.let { media ->
            media.availability == MediaAvailability.AVAILABLE && media.contentRevision == embedding.contentRevision
        } == true }
    }

    private fun persistIndexes(id: String, dimension: Int, rows: List<AiEmbeddingRecord>) {
        val directory = NativeIndexFiles.root(applicationContext)
        val exact = ExactVectorIndex(dimension)
        rows.forEach { exact.upsert(it.mediaId, it.vector.toFloats()) }
        exact.save(File(directory, "$id.exact"))
        if (rows.isEmpty()) return
        check(USearchBridge.available) { "USEARCH_UNAVAILABLE" }
        val bridge = USearchBridge(); val handle = bridge.create(dimension)
        val target = File(directory, "$id.usearch"); val temporary = File(directory, ".$id-${System.nanoTime()}.tmp")
        try {
            bridge.reserve(handle, rows.size.toLong())
            rows.forEach { bridge.upsert(handle, it.nativeKey, it.vector.toFloats()) }
            bridge.save(handle, temporary.absolutePath)
            FileOutputStream(temporary, true).use { it.fd.sync() }
            check(temporary.renameTo(target)) { "INDEX_PUBLISH_FAILED" }
            bridge.search(handle, rows.first().vector.toFloats(), minOf(10, rows.size))
        } finally { bridge.close(handle); temporary.delete() }
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
    }
}
