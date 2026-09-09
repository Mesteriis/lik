package io.github.mesteriis.lik.ai

import androidx.room.*
import io.github.mesteriis.lik.catalog.MediaRecord

enum class GenerationStatus { PREPARING, COMPLETE, ERROR }

@Entity(tableName = "ai_index_generation", indices = [Index("pipelineFingerprint")])
data class AiIndexGenerationRecord(
    @PrimaryKey val generationId: String,
    val profileId: String,
    val feature: String,
    val pipelineFingerprint: String,
    val status: GenerationStatus,
    val completed: Int,
    val total: Int,
    val checkpointMediaId: String?,
    val error: String?,
    val createdAt: Long,
)

@Entity(tableName = "ai_embedding", primaryKeys = ["generationId", "mediaId"],
    foreignKeys = [ForeignKey(entity = AiIndexGenerationRecord::class, parentColumns = ["generationId"], childColumns = ["generationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["generationId", "nativeKey"], unique = true), Index("mediaId")])
data class AiEmbeddingRecord(
    val generationId: String,
    val mediaId: String,
    val nativeKey: Long,
    val contentRevision: Long,
    val accessEpoch: Long,
    val vector: ByteArray,
)

enum class SensitiveRunStatus { RAW_RESULT, ERROR }
@Entity(tableName = "ai_sensitive_run", primaryKeys = ["mediaId", "contentRevision", "pipelineFingerprint"])
data class AiSensitiveRunRecord(
    val mediaId: String,
    val contentRevision: Long,
    val pipelineFingerprint: String,
    val status: SensitiveRunStatus,
    val rawOutput: ByteArray?,
    val error: String?,
    val evaluatedAt: Long,
)

@Dao
interface AiIndexDao {
    @Upsert fun saveGeneration(value: AiIndexGenerationRecord)
    @Query("SELECT * FROM ai_index_generation WHERE generationId = :id") fun generation(id: String): AiIndexGenerationRecord?
    @Query("SELECT * FROM ai_index_generation WHERE pipelineFingerprint = :pipeline AND status = 'COMPLETE' ORDER BY createdAt DESC LIMIT 1")
    fun compatible(pipeline: String): AiIndexGenerationRecord?
    @Query("SELECT * FROM media WHERE availability = 'AVAILABLE' AND (:after IS NULL OR mediaId > :after) ORDER BY mediaId LIMIT :limit")
    fun mediaBatch(after: String?, limit: Int): List<MediaRecord>
    @Query("SELECT m.* FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (x.exposure IS NULL OR x.exposure!='SENSITIVE') AND (:after IS NULL OR m.mediaId>:after) ORDER BY m.mediaId LIMIT :limit")
    fun aiIndexableMediaBatch(after:String?,limit:Int):List<MediaRecord>
    @Upsert fun saveEmbedding(value: AiEmbeddingRecord)
    @Query("SELECT * FROM ai_embedding WHERE generationId = :generation ORDER BY nativeKey") fun embeddings(generation: String): List<AiEmbeddingRecord>
    @Query("SELECT * FROM ai_embedding WHERE generationId = :generation AND (:after IS NULL OR nativeKey > :after) ORDER BY nativeKey LIMIT :limit")
    fun embeddingBatch(generation: String, after: Long?, limit: Int): List<AiEmbeddingRecord>
    @Query("SELECT * FROM ai_embedding WHERE generationId = :generation AND mediaId = :mediaId LIMIT 1") fun embedding(generation: String, mediaId: String): AiEmbeddingRecord?
    @Query("SELECT * FROM ai_embedding WHERE generationId = :generation AND nativeKey IN (:keys)") fun byKeys(generation: String, keys: LongArray): List<AiEmbeddingRecord>
    @Query("SELECT e.* FROM ai_embedding e JOIN media m ON m.mediaId = e.mediaId WHERE e.generationId = :generation AND e.nativeKey IN (:keys) AND m.availability = 'AVAILABLE' AND m.contentRevision = e.contentRevision AND m.accessGrantEpoch = e.accessEpoch")
    fun currentByKeys(generation: String, keys: LongArray): List<AiEmbeddingRecord>
    @Query("SELECT e.* FROM ai_embedding e JOIN media m ON m.mediaId = e.mediaId WHERE e.generationId = :generation AND m.availability = 'AVAILABLE' AND m.contentRevision = e.contentRevision AND m.accessGrantEpoch = e.accessEpoch ORDER BY e.nativeKey LIMIT :limit")
    fun boundedCurrent(generation: String, limit: Int): List<AiEmbeddingRecord>
    @Query("SELECT MAX(nativeKey) FROM ai_embedding WHERE generationId = :generation") fun maxKey(generation: String): Long?
    @Query("SELECT COUNT(*) FROM ai_embedding WHERE generationId = :generation") fun embeddingCount(generation: String): Int
    @Query("SELECT COUNT(*) FROM media WHERE availability = 'AVAILABLE'") fun availableCount(): Int
    @Query("SELECT COUNT(*) FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (x.exposure IS NULL OR x.exposure!='SENSITIVE')") fun aiIndexableCount():Int
    @Query("SELECT COUNT(*) FROM ai_embedding e JOIN media m ON m.mediaId = e.mediaId WHERE e.generationId = :generation AND m.availability = 'AVAILABLE' AND m.contentRevision = e.contentRevision AND m.accessGrantEpoch = e.accessEpoch")
    fun currentEmbeddingCount(generation: String): Int
    @Query("SELECT COUNT(*) FROM ai_embedding e JOIN media m ON m.mediaId=e.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE e.generationId=:generation AND m.availability='AVAILABLE' AND m.contentRevision=e.contentRevision AND m.accessGrantEpoch=e.accessEpoch AND x.exposure='SAFE'")
    fun currentSafeEmbeddingCount(generation: String): Int
    @Query("SELECT * FROM media WHERE mediaId = :mediaId") fun currentMedia(mediaId: String): MediaRecord?
    @Query("DELETE FROM ai_embedding WHERE generationId = :generation AND mediaId = :mediaId") fun deleteEmbedding(generation: String, mediaId: String): Int
    @Query("SELECT e.mediaId FROM ai_embedding e LEFT JOIN media m ON m.mediaId = e.mediaId WHERE e.generationId = :generation AND (m.mediaId IS NULL OR m.availability != 'AVAILABLE' OR m.contentRevision != e.contentRevision OR m.accessGrantEpoch != e.accessEpoch)")
    fun staleMediaIds(generation: String): List<String>
    @Query("SELECT mediaId FROM ai_embedding WHERE generationId = :generation AND nativeKey = :key LIMIT 1") fun mediaIdForKey(generation: String, key: Long): String?
    @Query("DELETE FROM ai_index_generation WHERE generationId = :id AND generationId NOT IN (:retained)") fun deleteGeneration(id: String, retained: Set<String>): Int
    @Query("DELETE FROM ai_index_generation WHERE generationId IN (:ids)") fun deleteGenerations(ids: Set<String>): Int
    @Query("SELECT * FROM ai_index_generation ORDER BY createdAt") fun generations(): List<AiIndexGenerationRecord>
    @Upsert fun saveSensitive(value: AiSensitiveRunRecord)
    @Query("SELECT * FROM ai_sensitive_run WHERE mediaId = :mediaId AND contentRevision = :revision AND pipelineFingerprint = :pipeline")
    fun sensitive(mediaId: String, revision: Long, pipeline: String): AiSensitiveRunRecord?

    @Transaction
    fun publishEmbeddingIfCurrent(value: AiEmbeddingRecord, generation: AiIndexGenerationRecord): Boolean {
        val media = currentMedia(value.mediaId)
        if (media == null || media.availability.name != "AVAILABLE" || media.contentRevision != value.contentRevision ||
            media.accessGrantEpoch != value.accessEpoch) return false
        saveEmbedding(value)
        saveGeneration(generation)
        return true
    }

    @Transaction
    fun completeIfCurrent(value: AiIndexGenerationRecord): AiIndexGenerationRecord? {
        val available = availableCount()
        val current = currentEmbeddingCount(value.generationId)
        val stored = embeddingCount(value.generationId)
        if (!IndexCompletion.canPublish(available, current, failures = 0, cancelled = false,
                storedEmbeddings = stored)) return null
        return value.copy(status = GenerationStatus.COMPLETE, completed = current, total = available,
            error = null).also(::saveGeneration)
    }
}

fun FloatArray.toBytes(): ByteArray = ByteArray(size * 4).also { bytes -> java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(this) }
fun ByteArray.toFloats(): FloatArray { require(size % 4 == 0); val buffer = java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); return FloatArray(buffer.remaining()).also(buffer::get) }
