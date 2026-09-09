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
    private val queryEmbedding: ((ProfileId, String) -> FloatArray)? = null,
) {
    fun search(text: String, limit: Int = 60): SemanticSearchResult {
        require(text.isNotBlank() && limit in 1..200)
        repeat(3) {
            val state = catalog.snapshot()
            val profile = state.active ?: error("AI_PROFILE_NOT_ACTIVE")
            require(AiFeature.SEARCH in state.enabledFeatures) { "SEMANTIC_SEARCH_DISABLED" }
            val generationId = state.activeGenerations[AiFeature.SEARCH] ?: error("SEARCH_INDEX_NOT_READY")
            val result = GenerationUseCoordinator.read(generationId) {
                val pinned = catalog.snapshot()
                if (pinned.active != profile || pinned.activeGenerations[AiFeature.SEARCH] != generationId) null
                else searchPinned(profile, generationId, text, limit)
            }
            if (result != null) return result
        }
        error("SEARCH_INDEX_CHANGED")
    }

    private fun searchPinned(profile: ProfileId, generationId: String, text: String, limit: Int): SemanticSearchResult {
        val generation = database.aiIndexes().generation(generationId) ?: error("SEARCH_INDEX_MISSING")
        require(generation.status == GenerationStatus.COMPLETE) { "SEARCH_INDEX_NOT_READY" }
        val dimension = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SEARCH).dimension!!
        val query = queryEmbedding?.invoke(profile, text)
            ?: InferenceGate.run(InferencePriority.INTERACTIVE) { engine.query(profile, text) }
        val dao = database.aiIndexes()
        val indexed = dao.currentEmbeddingCount(generationId)
        val available = dao.aiIndexableCount()
        var nativeUsed = false
        var rows: List<AiEmbeddingRecord> = emptyList()
        val nativeDirectory = NativeIndexFiles.generation(context, generationId)
        val nativeFile = nativeDirectory.resolve("index.usearch")
        if (USearchBridge.available && nativeFile.isFile && nativeDirectory.resolve("verified").isFile && indexed > 0) runCatching {
            val bridge = USearchBridge(); val handle = bridge.create(dimension)
            try {
                bridge.load(handle, nativeFile.absolutePath)
                val membership = NativeMembership.read(nativeDirectory)
                require(membership.generationId == generationId && membership.count == indexed &&
                    bridge.size(handle) == indexed.toLong()) { "NATIVE_MEMBERSHIP_MISMATCH" }
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
        // Keys come from the finite trusted pipeline catalog. Retaining locks prevents a caller
        // between lookup and acquisition from holding a different lock from the next caller.
        finally { lock.unlock() }
    }

    fun <T> runAll(keys: Collection<String>, stopped: () -> Boolean, block: () -> T): T {
        val ordered = keys.distinct().sorted()
        fun acquire(at: Int): T = if (at == ordered.size) block()
            else run(ordered[at], stopped) { acquire(at + 1) }
        return acquire(0)
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
            publishCatalog(catalog, profile, compatible, dao)
            return
        }
        val generation = dao.generations().lastOrNull {
            it.profileId == profile.wire && it.feature == AiFeature.SEARCH.name &&
                it.pipelineFingerprint == pipeline.fingerprint && it.status == GenerationStatus.PREPARING
        } ?: AiIndexGenerationRecord(
            UUID.randomUUID().toString(), profile.wire, AiFeature.SEARCH.name, pipeline.fingerprint,
            GenerationStatus.PREPARING, 0, dao.aiIndexableCount(), null, null, System.currentTimeMillis(),
        ).also(dao::saveGeneration)
        val engine = SemanticEmbeddingEngine(applicationContext)
        val sensitivePipeline = catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SENSITIVE).fingerprint
        var checkpoint = generation.checkpointMediaId.takeUnless { generation.error != null }
        dao.staleMediaIds(generation.generationId).forEach { dao.deleteEmbedding(generation.generationId, it) }
        var completed = dao.currentEmbeddingCount(generation.generationId)
        var nativeKey = (dao.maxKey(generation.generationId) ?: 0L) + 1L
        while (true) {
            if (isStopped) throw InterruptedException("INDEX_CANCELLED")
            val batch = dao.aiIndexableMediaBatch(checkpoint, BATCH_SIZE)
            if (batch.isEmpty()) break
            for (row in batch) {
                if (isStopped) throw InterruptedException("INDEX_CANCELLED")
                val item = IndexItem(row.mediaId, row.contentRevision, row.accessGrantEpoch, pipeline.fingerprint)
                val priorEmbedding = dao.embedding(generation.generationId, row.mediaId)
                if (priorEmbedding?.let { it.contentRevision == row.contentRevision && it.accessEpoch == row.accessGrantEpoch } == true) {
                    checkpoint = row.mediaId
                    dao.saveGeneration(generation.copy(completed = completed, total = dao.aiIndexableCount(), checkpointMediaId = checkpoint, error = null))
                    continue
                }
                if (dao.sensitive(row.mediaId, row.contentRevision, sensitivePipeline)?.status != SensitiveRunStatus.RAW_RESULT) {
                    val raw = runCatching { InferenceGate.run(priority, { isStopped }) { engine.sensitive(row.mediaId, row.contentRevision) } }
                    dao.saveSensitive(AiSensitiveRunRecord(row.mediaId, row.contentRevision, sensitivePipeline,
                        if (raw.isSuccess) SensitiveRunStatus.RAW_RESULT else SensitiveRunStatus.ERROR,
                        raw.getOrNull()?.toBytes(), raw.exceptionOrNull()?.message?.take(160), System.currentTimeMillis()))
                    if (raw.isFailure) {
                        dao.saveGeneration(generation.copy(completed = completed, checkpointMediaId = checkpoint,
                            error = "SENSITIVE_RETRY:${raw.exceptionOrNull()?.message.orEmpty().take(100)}"))
                        throw RetryableIndexException("SENSITIVE_RETRY", raw.exceptionOrNull())
                    }
                }
                val embedded = runCatching { InferenceGate.run(priority, { isStopped }) { engine.image(profile, row.mediaId, row.contentRevision, row.accessGrantEpoch) } }
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
                    dao.saveGeneration(generation.copy(completed = completed, total = dao.aiIndexableCount(), checkpointMediaId = checkpoint))
                    continue
                }
                if (!item.canPublish(fresh.mediaId, fresh.contentRevision, fresh.accessGrantEpoch, true))
                    throw RetryableIndexException("MEDIA_CHANGED").also {
                        dao.saveGeneration(generation.copy(completed = completed, checkpointMediaId = checkpoint, error = "MEDIA_CHANGED"))
                    }
                val nextGeneration = generation.copy(completed = completed + 1, total = dao.aiIndexableCount(),
                    checkpointMediaId = row.mediaId, error = null)
                val published = dao.publishEmbeddingIfCurrent(AiEmbeddingRecord(generation.generationId, row.mediaId, nativeKey,
                    row.contentRevision, row.accessGrantEpoch, embedded.getOrThrow().toBytes()), nextGeneration)
                if (!published) throw RetryableIndexException("MEDIA_CHANGED")
                nativeKey++
                completed++
                checkpoint = row.mediaId
                val updated = nextGeneration
                catalog.saveGeneration(updated.toContract(complete = false))
                setProgressAsync(workDataOf(COMPLETED to completed, TOTAL to updated.total))
            }
        }
        val indexed = persistIndexes(generation.generationId, pipeline.dimension!!, dao)
        if (isStopped) throw InterruptedException("INDEX_CANCELLED")
        val done = dao.completeIfCurrent(generation.copy(completed = indexed,
            total = dao.aiIndexableCount(), checkpointMediaId = checkpoint, error = null)) ?: run {
            dao.saveGeneration(generation.copy(completed = dao.currentEmbeddingCount(generation.generationId),
                total = dao.aiIndexableCount(), checkpointMediaId = checkpoint, error = "INDEX_COVERAGE_CHANGED"))
            throw RetryableIndexException("INDEX_COVERAGE_CHANGED")
        }
        if (isStopped) throw InterruptedException("INDEX_CANCELLED")
        publishCatalog(catalog, profile, done, dao)
    }

    private fun generationCurrent(record: AiIndexGenerationRecord, dao: AiIndexDao): Boolean {
        val available = dao.aiIndexableCount()
        val directory = NativeIndexFiles.generation(applicationContext, record.generationId)
        if (dao.currentEmbeddingCount(record.generationId) != available || dao.embeddingCount(record.generationId) != available)
            return false
        val membership = runCatching { NativeMembership.read(directory) }.getOrNull() ?: return false
        if (membership.generationId != record.generationId || membership.count != available ||
            membership.digest != membershipDigest(dao, record.generationId)) return false
        if (available == 0) return directory.resolve("empty").isFile
        val native = directory.resolve("index.usearch")
        if (!native.isFile || !directory.resolve("verified").isFile || !USearchBridge.available) return false
        return runCatching {
            val bridge = USearchBridge(); val handle = bridge.create(
                catalogDimension(record.profileId, record.feature))
            try {
                bridge.load(handle, native.absolutePath)
                if (bridge.size(handle) != available.toLong()) return@runCatching false
                membership.probeKeys.indices.all { at ->
                    val probe = dao.embedding(record.generationId, requireNotNull(
                        dao.mediaIdForKey(record.generationId, membership.probeKeys[at]))) ?: return@all false
                    val expectedKeys = membership.expectedTopKeys[at]
                    val byExpectedKey = dao.byKeys(record.generationId, expectedKeys).associateBy { it.nativeKey }
                    val expectedIds = expectedKeys.map { key -> byExpectedKey[key]?.mediaId ?: return@all false }
                    val candidates = dao.currentByKeys(record.generationId, bridge.search(handle, probe.vector.toFloats(),
                        minOf(available, PARITY_CANDIDATES))).map {
                        NativeCandidate(it.nativeKey, it.mediaId, it.vector.toFloats())
                    }
                    CandidateReranker.rank(probe.vector.toFloats(), candidates, expectedIds.size)
                        .map { it.mediaId } == expectedIds
                }
            }
            finally { bridge.close(handle) }
        }.getOrDefault(false)
    }

    private fun catalogDimension(profile: String, feature: String): Int = ModelCatalog.get(applicationContext).trusted
        .profiles.getValue(ProfileId.fromWire(profile)).pipelines.getValue(AiFeature.valueOf(feature)).dimension!!

    private fun persistIndexes(id: String, dimension: Int, dao: AiIndexDao): Int {
        val root = NativeIndexFiles.root(applicationContext)
        val directory = File(root, ".$id-${System.nanoTime()}.building").also { check(it.mkdirs()) }
        val total = dao.embeddingCount(id)
        if (total == 0) {
            NativeMembership(id, 0, NativeMembership.digest(emptySequence()), longArrayOf(), emptyList()).write(directory)
            DurableAiFiles.atomicWrite(File(directory, "empty"), byteArrayOf())
            val target = NativeIndexFiles.generation(applicationContext, id)
            DurableAiFiles.replaceDirectory(directory, target)
            return 0
        }
        check(USearchBridge.available) { "USEARCH_UNAVAILABLE" }
        val bridge = USearchBridge(); val handle = bridge.create(dimension)
        val native = File(directory, "index.usearch")
        try {
            bridge.reserve(handle, total.toLong())
            val probes = mutableListOf<AiEmbeddingRecord>()
            forEachEmbedding(dao, id) { row ->
                val vector = row.vector.toFloats()
                require(vector.size == dimension)
                bridge.upsert(handle, row.nativeKey, vector)
                if (probes.size < PARITY_PROBES) probes += row
            }
            require(bridge.size(handle) == total.toLong()) { "USEARCH_MEMBERSHIP_COUNT" }
            bridge.save(handle, native.absolutePath)
            FileOutputStream(native, true).use { it.fd.sync() }
            val exact = probes.associate { it.nativeKey to StreamingExactTop(it.vector.toFloats(), minOf(10, total)) }
            forEachEmbedding(dao, id) { row -> exact.values.forEach { it.offer(row) } }
            val expected = probes.map { exact.getValue(it.nativeKey).keys() }
            probes.forEachIndexed { index, probe ->
                val query = probe.vector.toFloats(); val probeLimit = minOf(10, total)
                val candidateKeys = bridge.search(handle, query, minOf(total, PARITY_CANDIDATES))
                val candidates = dao.currentByKeys(id, candidateKeys).map {
                    NativeCandidate(it.nativeKey, it.mediaId, it.vector.toFloats())
                }
                val approximate = CandidateReranker.rank(query, candidates, probeLimit)
                val byKey = dao.byKeys(id, expected[index]).associateBy { it.nativeKey }
                val expectedIds = expected[index].map { key -> requireNotNull(byKey[key]).mediaId }
                require(expectedIds == approximate.map { it.mediaId }) { "USEARCH_PARITY_FAILED" }
            }
            NativeMembership(id, total, membershipDigest(dao, id), probes.map { it.nativeKey }.toLongArray(), expected).write(directory)
            DurableAiFiles.atomicWrite(File(directory, "verified"), byteArrayOf())
            val target = NativeIndexFiles.generation(applicationContext, id)
            DurableAiFiles.replaceDirectory(directory, target)
            return total
        } finally { bridge.close(handle); directory.deleteRecursively() }
    }

    private fun membershipDigest(dao: AiIndexDao, generation: String): String {
        val rows = sequence {
            var after: Long? = null
            while (true) {
                val batch = dao.embeddingBatch(generation, after, INDEX_BUILD_BATCH)
                if (batch.isEmpty()) break
                yieldAll(batch); after = batch.last().nativeKey
            }
        }
        return NativeMembership.digest(rows)
    }

    private inline fun forEachEmbedding(dao: AiIndexDao, generation: String, block: (AiEmbeddingRecord) -> Unit) {
        var after: Long? = null
        while (true) {
            val batch = dao.embeddingBatch(generation, after, INDEX_BUILD_BATCH)
            if (batch.isEmpty()) return
            batch.forEach(block); after = batch.last().nativeKey
        }
    }

    private class StreamingExactTop(private val query: FloatArray, private val limit: Int) {
        private data class Value(val key: Long, val mediaId: String, val score: Float)
        private val values = ArrayList<Value>(limit + 1)
        fun offer(row: AiEmbeddingRecord) {
            val vector = row.vector.toFloats(); require(vector.size == query.size)
            var dot = 0f; var qn = 0f; var vn = 0f
            for (i in query.indices) { dot += query[i] * vector[i]; qn += query[i] * query[i]; vn += vector[i] * vector[i] }
            require(qn > 0f && vn > 0f)
            values += Value(row.nativeKey, row.mediaId, dot / kotlin.math.sqrt(qn * vn))
            values.sortWith(compareByDescending<Value> { it.score }.thenBy { it.mediaId })
            if (values.size > limit) values.removeAt(values.lastIndex)
        }
        fun keys() = values.map { it.key }.toLongArray()
    }

    private fun publishCatalog(catalog: ModelCatalog, profile: ProfileId, record: AiIndexGenerationRecord,
                               dao: AiIndexDao) {
        var superseded = emptySet<String>()
        catalog.generationReady(profile, AiFeature.SEARCH, record.toContract(complete = true)) { next ->
            superseded = CatalogPrunedGenerations.between(catalog.snapshot().generations.keys,next.generations.keys)
                .intersect(dao.generations().map(AiIndexGenerationRecord::generationId).toSet())
            GenerationRetirement.journal(File(applicationContext.filesDir, "ai"), profile, superseded)
        }
        GenerationRetirement.drain(applicationContext, profile, superseded, catalog,
            MediaDatabase.get(applicationContext))
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
        private const val PARITY_PROBES = 8
        private const val INDEX_BUILD_BATCH = 128

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
                cancelAllWorkByTag("ai-index-${profile.wire}")
            }

        fun discardPreparation(context: Context, profile: ProfileId) {
            pause(context, profile)
            OcrPeopleIndexWorker.pause(context, profile)
            val catalog = ModelCatalog.get(context)
            val keys = catalog.trusted.profiles.getValue(profile).pipelines.values.map { it.fingerprint }
            IndexRunCoordinator.runAll(keys, { false }) {
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
