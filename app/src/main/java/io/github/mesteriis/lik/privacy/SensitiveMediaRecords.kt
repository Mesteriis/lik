package io.github.mesteriis.lik.privacy

import androidx.room.*
import io.github.mesteriis.lik.catalog.MediaAvailability
import io.github.mesteriis.lik.catalog.MediaRecord

@Entity(tableName = "sensitive_automatic", primaryKeys = ["mediaId", "contentRevision", "accessEpoch"])
data class SensitiveAutomaticRecord(
    val mediaId: String,
    val contentRevision: Long,
    val accessEpoch: Long,
    val pipelineFingerprint: String,
    val decision: SensitiveDecision,
    val rawOutput: ByteArray,
    val decidedAt: Long,
)

@Entity(tableName = "sensitive_manual")
data class SensitiveManualRecord(
    @PrimaryKey val mediaId: String,
    val contentRevision: Long,
    val decision: SensitiveDecision,
    val decidedAt: Long,
)

enum class SensitiveRunOutcome { RAW_RESULT, ERROR, CANCELLED, UNKNOWN_OUTPUT }

@Entity(tableName = "sensitive_classifier_run", primaryKeys = ["mediaId", "contentRevision", "accessEpoch", "pipelineFingerprint"])
data class SensitiveClassifierRunRecord(
    val mediaId: String,
    val contentRevision: Long,
    val accessEpoch: Long,
    val pipelineFingerprint: String,
    val outcome: SensitiveRunOutcome,
    val rawOutput: ByteArray?,
    val error: String?,
    val evaluatedAt: Long,
)

@Dao
interface SensitiveMediaDao {
    @Upsert fun saveAutomatic(value: SensitiveAutomaticRecord)
    @Upsert fun saveManual(value: SensitiveManualRecord)
    @Upsert fun saveRun(value: SensitiveClassifierRunRecord)
    @Query("DELETE FROM sensitive_manual WHERE mediaId=:mediaId") fun clearManual(mediaId: String): Int
    @Query("SELECT * FROM sensitive_automatic WHERE mediaId=:mediaId AND contentRevision=:revision AND accessEpoch=:epoch LIMIT 1")
    fun automatic(mediaId: String, revision: Long, epoch: Long): SensitiveAutomaticRecord?
    @Query("SELECT * FROM sensitive_manual WHERE mediaId=:mediaId LIMIT 1") fun manual(mediaId: String): SensitiveManualRecord?
    @Query("SELECT * FROM sensitive_classifier_run WHERE mediaId=:mediaId AND contentRevision=:revision AND accessEpoch=:epoch AND pipelineFingerprint=:pipeline LIMIT 1")
    fun run(mediaId: String, revision: Long, epoch: Long, pipeline: String): SensitiveClassifierRunRecord?
    @Query("SELECT m.* FROM media m LEFT JOIN sensitive_classifier_run r ON r.mediaId=m.mediaId AND r.contentRevision=m.contentRevision AND r.accessEpoch=m.accessGrantEpoch AND r.pipelineFingerprint=:pipeline WHERE m.availability='AVAILABLE' AND m.trashedAt IS NULL AND (r.mediaId IS NULL OR r.outcome!='RAW_RESULT') ORDER BY m.mediaId LIMIT :limit")
    fun pending(pipeline:String,limit:Int):List<MediaRecord>

    @Transaction
    fun resolved(mediaId: String, revision: Long, epoch: Long): SensitiveDecision = SensitiveMediaPolicy.resolve(
        automatic(mediaId, revision, epoch)?.let {
            AutomaticSensitiveDecision(it.pipelineFingerprint, it.contentRevision, it.accessEpoch, it.decision)
        },
        manual(mediaId)?.let { ManualSensitiveOverride(it.contentRevision, it.decision) },
        revision,
        epoch,
    )

    @Transaction
    fun publishIfCurrent(media:MediaRecord,run:SensitiveClassifierRunRecord,automatic:SensitiveAutomaticRecord?):Boolean{
        val current=currentMedia(media.mediaId)?:return false
        if(current.availability!=MediaAvailability.AVAILABLE||current.trashedAt!=null||current.contentRevision!=media.contentRevision||current.accessGrantEpoch!=media.accessGrantEpoch)return false
        saveRun(run)
        automatic?.let(::saveAutomatic)
        return true
    }

    @Query("SELECT * FROM media WHERE mediaId=:mediaId LIMIT 1") fun currentMedia(mediaId:String):MediaRecord?
}
