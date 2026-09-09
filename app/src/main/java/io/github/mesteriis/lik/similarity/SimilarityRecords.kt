package io.github.mesteriis.lik.similarity

import androidx.room.*
import io.github.mesteriis.lik.catalog.MediaRecord
import io.github.mesteriis.lik.catalog.MediaDatabase

@Entity(tableName = "content_fingerprint", indices = [Index("sha256"), Index("perceptualVersion")])
data class ContentFingerprintRecord(
    @PrimaryKey val mediaId: String,
    val contentRevision: Long,
    val accessEpoch: Long,
    val sha256: String,
    val perceptualVersion: Int,
    val perceptualBits: ByteArray,
    val computedAt: Long,
    val relationsReady: Boolean = false,
)

@Entity(tableName="fingerprint_failure",primaryKeys=["mediaId","contentRevision","accessEpoch","perceptualVersion"])
data class FingerprintFailureRecord(
    val mediaId:String,
    val contentRevision:Long,
    val accessEpoch:Long,
    val perceptualVersion:Int,
    val error:String,
    val failedAt:Long,
)

@Entity(tableName="fingerprint_band",primaryKeys=["mediaId","perceptualVersion","bandIndex"],indices=[Index(value=["perceptualVersion","bandIndex","bandValue"])])
data class FingerprintBandRecord(val mediaId:String,val perceptualVersion:Int,val bandIndex:Int,val bandValue:Int)

enum class SimilarityKind { EXACT, VISUAL }

@Entity(tableName = "similarity_relation", primaryKeys = ["leftMediaId", "rightMediaId"],
    indices = [Index("rightMediaId"), Index("kind")])
data class SimilarityRelationRecord(
    val leftMediaId: String,
    val rightMediaId: String,
    val leftRevision: Long,
    val rightRevision: Long,
    val leftAccessEpoch: Long,
    val rightAccessEpoch: Long,
    val kind: SimilarityKind,
    val fingerprintVersion: Int,
    val distance: Int,
    val updatedAt: Long,
)

enum class SimilarityWorkStatus { IDLE, RUNNING, PAUSED, COMPLETE, ERROR }

@Entity(tableName = "similarity_checkpoint")
data class SimilarityCheckpoint(
    @PrimaryKey val checkpointId: String = "default",
    val checkpointMediaId: String?,
    val completed: Int,
    val total: Int,
    val status: SimilarityWorkStatus,
    val updatedAt: Long,
    val error: String? = null,
)

@Dao
interface SimilarityDao {
    @Query("SELECT * FROM content_fingerprint WHERE mediaId=:mediaId") fun fingerprint(mediaId:String):ContentFingerprintRecord?
    @Query("SELECT * FROM fingerprint_failure WHERE mediaId=:mediaId AND contentRevision=:revision AND accessEpoch=:epoch AND perceptualVersion=:version") fun failure(mediaId:String,revision:Long,epoch:Long,version:Int):FingerprintFailureRecord?
    @Query("SELECT * FROM similarity_checkpoint WHERE checkpointId='default'") fun checkpoint():SimilarityCheckpoint?
    @Upsert fun saveCheckpoint(value:SimilarityCheckpoint)

    @Query("SELECT m.* FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision LEFT JOIN content_fingerprint f ON f.mediaId=m.mediaId AND f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND f.perceptualVersion=:version LEFT JOIN fingerprint_failure g ON g.mediaId=m.mediaId AND g.contentRevision=m.contentRevision AND g.accessEpoch=m.accessGrantEpoch AND g.perceptualVersion=:version WHERE m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' AND g.mediaId IS NULL AND (f.mediaId IS NULL OR f.relationsReady=0) AND (:after IS NULL OR m.mediaId>:after) ORDER BY m.mediaId LIMIT :limit")
    fun pendingSafe(after:String?,limit:Int,version:Int=PerceptualFingerprintV1.VERSION):List<MediaRecord>

    @Query("SELECT COUNT(*) FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE'")
    fun eligibleCount():Int

    @Query("SELECT COUNT(*) FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision LEFT JOIN content_fingerprint f ON f.mediaId=m.mediaId AND f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND f.perceptualVersion=:version AND f.relationsReady=1 LEFT JOIN fingerprint_failure g ON g.mediaId=m.mediaId AND g.contentRevision=m.contentRevision AND g.accessEpoch=m.accessGrantEpoch AND g.perceptualVersion=:version WHERE m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' AND (f.mediaId IS NOT NULL OR g.mediaId IS NOT NULL)")
    fun processedCount(version:Int=PerceptualFingerprintV1.VERSION):Int

    @Query("SELECT m.* FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.mediaId=:mediaId AND m.contentRevision=:revision AND m.accessGrantEpoch=:epoch AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE'")
    fun currentSafe(mediaId:String,revision:Long,epoch:Long):MediaRecord?

    @Upsert fun saveFingerprint(value:ContentFingerprintRecord)
    @Upsert fun saveBands(value:List<FingerprintBandRecord>)
    @Query("DELETE FROM fingerprint_band WHERE mediaId=:mediaId") fun deleteBands(mediaId:String)
    @Upsert fun saveFailure(value:FingerprintFailureRecord)
    @Query("DELETE FROM fingerprint_failure WHERE mediaId=:mediaId") fun deleteFailures(mediaId:String)
    @Query("DELETE FROM content_fingerprint WHERE mediaId=:mediaId") fun deleteFingerprint(mediaId:String)
    @Query("DELETE FROM similarity_relation WHERE leftMediaId=:mediaId OR rightMediaId=:mediaId") fun deleteRelations(mediaId:String)
    @Upsert fun saveRelation(value:SimilarityRelationRecord)
    @Query("UPDATE content_fingerprint SET relationsReady=1 WHERE mediaId=:mediaId AND contentRevision=:revision AND accessEpoch=:epoch") fun markRelationsReady(mediaId:String,revision:Long,epoch:Long):Int

    @Query("SELECT f.* FROM content_fingerprint f JOIN media m ON m.mediaId=f.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE f.sha256=:sha AND f.mediaId!=:mediaId AND f.mediaId>:after AND f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' ORDER BY f.mediaId LIMIT :limit")
    fun exactCandidates(sha:String,mediaId:String,after:String,limit:Int):List<ContentFingerprintRecord>

    @Query("SELECT DISTINCT f.* FROM fingerprint_band mine JOIN fingerprint_band candidate ON candidate.perceptualVersion=mine.perceptualVersion AND candidate.bandIndex=mine.bandIndex AND candidate.bandValue=mine.bandValue JOIN content_fingerprint f ON f.mediaId=candidate.mediaId JOIN media m ON m.mediaId=f.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE mine.mediaId=:mediaId AND f.mediaId>:after AND f.mediaId!=:mediaId AND f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND f.perceptualVersion=:version AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' ORDER BY f.mediaId LIMIT :limit")
    fun visualCandidates(mediaId:String,version:Int,after:String,limit:Int):List<ContentFingerprintRecord>

    @Query("SELECT r.* FROM similarity_relation r JOIN media l ON l.mediaId=r.leftMediaId JOIN media q ON q.mediaId=r.rightMediaId JOIN ai_media_exposure lx ON lx.mediaId=l.mediaId AND lx.contentRevision=l.contentRevision JOIN ai_media_exposure qx ON qx.mediaId=q.mediaId AND qx.contentRevision=q.contentRevision WHERE l.availability='AVAILABLE' AND l.trashedAt IS NULL AND lx.exposure='SAFE' AND q.availability='AVAILABLE' AND q.trashedAt IS NULL AND qx.exposure='SAFE' AND l.contentRevision=r.leftRevision AND l.accessGrantEpoch=r.leftAccessEpoch AND q.contentRevision=r.rightRevision AND q.accessGrantEpoch=r.rightAccessEpoch ORDER BY CASE r.kind WHEN 'EXACT' THEN 0 ELSE 1 END,r.updatedAt DESC,r.leftMediaId,r.rightMediaId LIMIT :limit OFFSET :offset")
    fun visibleRelations(limit:Int,offset:Int):List<SimilarityRelationRecord>

    @Query("SELECT r.* FROM similarity_relation r JOIN media l ON l.mediaId=r.leftMediaId JOIN media q ON q.mediaId=r.rightMediaId JOIN ai_media_exposure lx ON lx.mediaId=l.mediaId AND lx.contentRevision=l.contentRevision JOIN ai_media_exposure qx ON qx.mediaId=q.mediaId AND qx.contentRevision=q.contentRevision WHERE r.leftMediaId=:left AND r.rightMediaId=:right AND l.availability='AVAILABLE' AND l.trashedAt IS NULL AND lx.exposure='SAFE' AND q.availability='AVAILABLE' AND q.trashedAt IS NULL AND qx.exposure='SAFE' AND l.contentRevision=r.leftRevision AND l.accessGrantEpoch=r.leftAccessEpoch AND q.contentRevision=r.rightRevision AND q.accessGrantEpoch=r.rightAccessEpoch LIMIT 1")
    fun visibleRelation(left:String,right:String):SimilarityRelationRecord?

    @Transaction
    fun publishIfCurrent(value:ContentFingerprintRecord):Boolean {
        if (currentSafe(value.mediaId,value.contentRevision,value.accessEpoch)==null) return false
        deleteRelations(value.mediaId)
        saveFingerprint(value.copy(relationsReady=false))
        deleteFailures(value.mediaId)
        deleteBands(value.mediaId)
        if(value.perceptualVersion==PerceptualFingerprintV1.VERSION&&value.perceptualBits.size==8)saveBands(value.perceptualBits.indices.map{FingerprintBandRecord(value.mediaId,value.perceptualVersion,it,value.perceptualBits[it].toInt() and 0xff)})
        return true
    }

    @Transaction
    fun failIfCurrent(value:FingerprintFailureRecord):Boolean {
        if(currentSafe(value.mediaId,value.contentRevision,value.accessEpoch)==null)return false
        deleteRelations(value.mediaId);deleteFingerprint(value.mediaId);deleteBands(value.mediaId);saveFailure(value);return true
    }
}

object SimilarityRelationBuilder {
    private const val PAGE=256
    fun rebuildFor(database:MediaDatabase,value:ContentFingerprintRecord,cancelled:()->Boolean={false}):Boolean = database.runInTransaction<Boolean> {
        val dao=database.similarity()
        if(dao.currentSafe(value.mediaId,value.contentRevision,value.accessEpoch)==null) return@runInTransaction false
        val current=dao.fingerprint(value.mediaId)?.takeIf{it.contentRevision==value.contentRevision&&it.accessEpoch==value.accessEpoch} ?: return@runInTransaction false
        dao.deleteRelations(value.mediaId)
        var exactAfter=""
        while(true){val batch=dao.exactCandidates(current.sha256,current.mediaId,exactAfter,PAGE);if(batch.isEmpty())break;batch.forEach{candidate->
                val pair=MediaPair.ordered(current.mediaId,candidate.mediaId);val left=if(pair.left==current.mediaId)current else candidate;val right=if(pair.right==current.mediaId)current else candidate
                val distance=if(current.perceptualVersion==candidate.perceptualVersion&&current.perceptualBits.size==8&&candidate.perceptualBits.size==8)PerceptualFingerprintV1.distance(current.perceptualBits,candidate.perceptualBits)else 0
                dao.saveRelation(SimilarityRelationRecord(pair.left,pair.right,left.contentRevision,right.contentRevision,left.accessEpoch,right.accessEpoch,SimilarityKind.EXACT,current.perceptualVersion,distance,System.currentTimeMillis()))
            };exactAfter=batch.last().mediaId;if(cancelled())throw InterruptedException("Similarity relation build cancelled")}
        var after=""
        while(true){
            if(cancelled()) throw InterruptedException("Similarity relation build cancelled")
            val batch=dao.visualCandidates(current.mediaId,current.perceptualVersion,after,PAGE)
            if(batch.isEmpty()) break
            batch.forEach{candidate->
                if(candidate.sha256==current.sha256)return@forEach
                val distance=if(current.perceptualBits.size==8&&candidate.perceptualBits.size==8)PerceptualFingerprintV1.distance(current.perceptualBits,candidate.perceptualBits) else return@forEach
                val kind=SimilarityKind.VISUAL.takeIf{SimilarityRules.isSimilar(distance)}
                if(kind!=null){
                    val pair=MediaPair.ordered(current.mediaId,candidate.mediaId)
                    val left=if(pair.left==current.mediaId)current else candidate;val right=if(pair.right==current.mediaId)current else candidate
                    dao.saveRelation(SimilarityRelationRecord(pair.left,pair.right,left.contentRevision,right.contentRevision,left.accessEpoch,right.accessEpoch,kind,current.perceptualVersion,distance,System.currentTimeMillis()))
                }
            }
            after=batch.last().mediaId
        }
        dao.markRelationsReady(current.mediaId,current.contentRevision,current.accessEpoch)==1
    }
}
