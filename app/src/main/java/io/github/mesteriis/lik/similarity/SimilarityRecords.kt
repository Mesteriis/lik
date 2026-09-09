package io.github.mesteriis.lik.similarity

import androidx.room.*
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaRecord
import kotlin.math.min

@Entity(tableName="content_fingerprint",indices=[Index("sha256"),Index("perceptualVersion")])
data class ContentFingerprintRecord(
    @PrimaryKey val mediaId:String,
    val contentRevision:Long,
    val accessEpoch:Long,
    val sha256:String,
    val perceptualVersion:Int,
    val perceptualBits:ByteArray,
    val computedAt:Long,
    val relationsReady:Boolean=false,
    val relationsRevision:Long=0,
)

@Entity(tableName="fingerprint_failure",primaryKeys=["mediaId","contentRevision","accessEpoch","perceptualVersion"])
data class FingerprintFailureRecord(val mediaId:String,val contentRevision:Long,val accessEpoch:Long,val perceptualVersion:Int,val error:String,val failedAt:Long)

@Entity(tableName="fingerprint_band",primaryKeys=["mediaId","perceptualVersion","bandIndex"],indices=[Index(value=["perceptualVersion","bandIndex","bandValue"])])
data class FingerprintBandRecord(val mediaId:String,val perceptualVersion:Int,val bandIndex:Int,val bandValue:Int)

enum class SimilarityKind { EXACT, VISUAL }

/** Only visual edges are persisted. Exact duplicates are queried as digest groups. */
@Entity(tableName="similarity_relation",primaryKeys=["leftMediaId","rightMediaId"],indices=[Index("rightMediaId"),Index("kind")])
data class SimilarityRelationRecord(
    val leftMediaId:String,
    val rightMediaId:String,
    val leftRevision:Long,
    val rightRevision:Long,
    val leftAccessEpoch:Long,
    val rightAccessEpoch:Long,
    val kind:SimilarityKind,
    val fingerprintVersion:Int,
    val distance:Int,
    val updatedAt:Long,
    val libraryRevision:Long=0,
)

/** Durable keyset position for one bounded visual-neighbor scan. */
@Entity(tableName="similarity_scan")
data class SimilarityScanRecord(
    @PrimaryKey val mediaId:String,
    val contentRevision:Long,
    val accessEpoch:Long,
    val fingerprintVersion:Int,
    val afterMediaId:String,
    val examined:Int,
    val updatedAt:Long,
    val libraryRevision:Long=0,
)

enum class SimilarityWorkStatus { IDLE, RUNNING, PAUSED, COMPLETE, ERROR }

@Entity(tableName="similarity_checkpoint")
data class SimilarityCheckpoint(
    @PrimaryKey val checkpointId:String="default",
    val checkpointMediaId:String?,
    val completed:Int,
    val total:Int,
    val status:SimilarityWorkStatus,
    val updatedAt:Long,
    val error:String?=null,
    val libraryRevision:Long=0,
    val tranche:Int=0,
    val comparisons:Int=0,
    val continuations:Int=0,
)

@Entity(tableName="similarity_library_state")
data class SimilarityLibraryState(@PrimaryKey val stateId:String="default",val revision:Long=0)

data class ExactDuplicateGroup(val sha256:String,val memberCount:Int)
data class PerceptualBucket(val hashKey:String,val bits:ByteArray)
data class SimilarityProgress(val eligible:Int,val completed:Int,val failures:Int){val complete:Boolean get()=eligible==completed&&failures==0}
data class SimilarityScanStep(val examined:Int,val comparisons:Int,val complete:Boolean)

@Dao
interface SimilarityDao {
    @Query("SELECT * FROM content_fingerprint WHERE mediaId=:mediaId") fun fingerprint(mediaId:String):ContentFingerprintRecord?
    @Query("SELECT * FROM fingerprint_failure WHERE mediaId=:mediaId AND contentRevision=:revision AND accessEpoch=:epoch AND perceptualVersion=:version") fun failure(mediaId:String,revision:Long,epoch:Long,version:Int):FingerprintFailureRecord?
    @Query("SELECT * FROM similarity_scan WHERE mediaId=:mediaId") fun scan(mediaId:String):SimilarityScanRecord?
    @Upsert fun saveScan(value:SimilarityScanRecord)
    @Query("DELETE FROM similarity_scan WHERE mediaId=:mediaId") fun deleteScan(mediaId:String)
    @Query("SELECT * FROM similarity_checkpoint WHERE checkpointId='default'") fun checkpoint():SimilarityCheckpoint?
    @Upsert fun saveCheckpoint(value:SimilarityCheckpoint)
    @Insert(onConflict=OnConflictStrategy.IGNORE) fun ensureLibraryState(value:SimilarityLibraryState=SimilarityLibraryState())
    @Query("SELECT COALESCE((SELECT revision FROM similarity_library_state WHERE stateId='default'),0)") fun libraryRevision():Long
    @Query("UPDATE similarity_library_state SET revision=revision+1 WHERE stateId='default'") fun advanceLibraryRevision():Int
    @Query("SELECT status='PAUSED' FROM similarity_checkpoint WHERE checkpointId='default'") fun isPaused():Boolean
    @Query("UPDATE similarity_checkpoint SET comparisons=comparisons+:amount WHERE checkpointId='default' AND status!='PAUSED' AND libraryRevision=:revision AND :revision=(SELECT revision FROM similarity_library_state WHERE stateId='default') AND comparisons<=:limit-:amount") fun reserveComparisons(revision:Long,amount:Int,limit:Int=SimilarityBudgets.COMPARISONS_PER_TRANCHE):Int
    @Query("UPDATE similarity_checkpoint SET comparisons=MAX(0,comparisons-:amount) WHERE checkpointId='default' AND libraryRevision=:revision") fun releaseComparisons(revision:Long,amount:Int):Int
    @Query("UPDATE similarity_checkpoint SET continuations=continuations+1 WHERE checkpointId='default' AND continuations<:limit AND status!='PAUSED'") fun claimContinuation(limit:Int=SimilarityBudgets.MAX_AUTO_CONTINUATIONS):Int
    @Query("UPDATE similarity_checkpoint SET checkpointMediaId=:checkpointMediaId,completed=:completed,total=:total,status='ERROR',updatedAt=:updatedAt,error=:error,libraryRevision=:libraryRevision WHERE checkpointId='default' AND status!='PAUSED'")
    fun failCheckpointUnlessPaused(checkpointMediaId:String?,completed:Int,total:Int,updatedAt:Long,error:String,libraryRevision:Long):Int

    @Transaction fun prepareTranche(forceNew:Boolean):SimilarityCheckpoint {
        ensureLibraryState()
        val revision=libraryRevision();val old=checkpoint()
        if(forceNew){return (old?:SimilarityCheckpoint(checkpointMediaId=null,completed=0,total=0,status=SimilarityWorkStatus.IDLE,updatedAt=0)).copy(checkpointMediaId=null,status=SimilarityWorkStatus.RUNNING,updatedAt=System.currentTimeMillis(),error=null,libraryRevision=revision,tranche=(old?.tranche?:-1)+1,comparisons=0,continuations=0).also(::saveCheckpoint)}
        if(old?.status==SimilarityWorkStatus.PAUSED)return old
        if(old==null){return SimilarityCheckpoint(checkpointMediaId=null,completed=0,total=0,status=SimilarityWorkStatus.IDLE,updatedAt=System.currentTimeMillis(),libraryRevision=revision).also(::saveCheckpoint)}
        if(old.libraryRevision!=revision){return old.copy(checkpointMediaId=null,completed=0,total=0,status=SimilarityWorkStatus.IDLE,updatedAt=System.currentTimeMillis(),error=null,libraryRevision=revision).also(::saveCheckpoint)}
        return old
    }

    @Transaction fun pauseNow(){
        ensureLibraryState();val old=checkpoint()?:SimilarityCheckpoint(checkpointMediaId=null,completed=0,total=0,status=SimilarityWorkStatus.IDLE,updatedAt=0,libraryRevision=libraryRevision())
        saveCheckpoint(old.copy(status=SimilarityWorkStatus.PAUSED,updatedAt=System.currentTimeMillis()))
    }

    @Query("SELECT m.* FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision LEFT JOIN content_fingerprint f ON f.mediaId=m.mediaId AND f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND f.perceptualVersion=:version LEFT JOIN fingerprint_failure g ON g.mediaId=m.mediaId AND g.contentRevision=m.contentRevision AND g.accessEpoch=m.accessGrantEpoch AND g.perceptualVersion=:version WHERE m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' AND g.mediaId IS NULL AND (f.mediaId IS NULL OR f.relationsReady=0 OR f.relationsRevision!=(SELECT revision FROM similarity_library_state WHERE stateId='default')) AND (:after IS NULL OR m.mediaId>:after) ORDER BY m.mediaId LIMIT :limit")
    fun pendingSafe(after:String?,limit:Int,version:Int=PerceptualFingerprintV2.VERSION):List<MediaRecord>

    @Query("SELECT m.* FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision LEFT JOIN content_fingerprint f ON f.mediaId=m.mediaId AND f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND f.perceptualVersion=:version LEFT JOIN fingerprint_failure g ON g.mediaId=m.mediaId AND g.contentRevision=m.contentRevision AND g.accessEpoch=m.accessGrantEpoch AND g.perceptualVersion=:version WHERE m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' AND f.mediaId IS NULL AND g.mediaId IS NULL ORDER BY m.mediaId LIMIT :limit")
    fun pendingFingerprint(limit:Int,version:Int=PerceptualFingerprintV2.VERSION):List<MediaRecord>

    @Query("SELECT COUNT(*) FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE'")
    fun eligibleCount():Int

    @Query("SELECT COUNT(*) FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision JOIN content_fingerprint f ON f.mediaId=m.mediaId AND f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND f.perceptualVersion=:version AND f.relationsReady=1 AND f.relationsRevision=(SELECT revision FROM similarity_library_state WHERE stateId='default') WHERE m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE'")
    fun completedCount(version:Int=PerceptualFingerprintV2.VERSION):Int

    @Query("SELECT COUNT(*) FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision JOIN fingerprint_failure g ON g.mediaId=m.mediaId AND g.contentRevision=m.contentRevision AND g.accessEpoch=m.accessGrantEpoch AND g.perceptualVersion=:version WHERE m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE'")
    fun currentFailureCount(version:Int=PerceptualFingerprintV2.VERSION):Int

    @Transaction fun progress(version:Int=PerceptualFingerprintV2.VERSION)=SimilarityProgress(eligibleCount(),completedCount(version),currentFailureCount(version))

    @Transaction fun commitProgress(checkpointMediaId:String?=null,paused:Boolean=false):SimilarityProgress {
        val value=progress()
        val status=when{paused->SimilarityWorkStatus.PAUSED;value.complete->SimilarityWorkStatus.COMPLETE;value.failures>0&&pendingSafe(null,1).isEmpty()->SimilarityWorkStatus.ERROR;else->SimilarityWorkStatus.RUNNING}
        val old=checkpoint()
        if(!paused&&old?.status==SimilarityWorkStatus.PAUSED)return value
        saveCheckpoint((old?:SimilarityCheckpoint(checkpointMediaId=null,completed=0,total=0,status=SimilarityWorkStatus.IDLE,updatedAt=0,libraryRevision=libraryRevision())).copy(checkpointMediaId=checkpointMediaId,completed=value.completed,total=value.eligible,status=status,updatedAt=System.currentTimeMillis(),error=if(status==SimilarityWorkStatus.ERROR)"FINGERPRINT_FAILURE" else null))
        return value
    }

    @Query("SELECT m.* FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.mediaId=:mediaId AND m.contentRevision=:revision AND m.accessGrantEpoch=:epoch AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE'")
    fun currentSafe(mediaId:String,revision:Long,epoch:Long):MediaRecord?

    @Upsert fun saveFingerprint(value:ContentFingerprintRecord)
    @Upsert fun saveBands(value:List<FingerprintBandRecord>)
    @Query("DELETE FROM fingerprint_band WHERE mediaId=:mediaId") fun deleteBands(mediaId:String)
    @Upsert fun saveFailure(value:FingerprintFailureRecord)
    @Query("DELETE FROM fingerprint_failure WHERE mediaId=:mediaId") fun deleteFailures(mediaId:String)
    @Query("DELETE FROM content_fingerprint WHERE mediaId=:mediaId") fun deleteFingerprint(mediaId:String)
    @Query("DELETE FROM similarity_relation WHERE leftMediaId=:mediaId OR rightMediaId=:mediaId") fun deleteRelations(mediaId:String)
    @Query("DELETE FROM similarity_relation WHERE leftMediaId=:owner") fun deleteOwnerRelations(owner:String)
    @Upsert fun saveRelation(value:SimilarityRelationRecord)
    @Query("DELETE FROM similarity_relation WHERE leftMediaId=:owner AND rightMediaId NOT IN (SELECT rightMediaId FROM similarity_relation WHERE leftMediaId=:owner ORDER BY distance ASC,rightMediaId ASC LIMIT :keep)") fun pruneRelations(owner:String,keep:Int=SimilarityBudgets.TOP_K)
    @Query("SELECT COUNT(*) FROM similarity_relation") fun relationCount():Int
    @Query("UPDATE content_fingerprint SET relationsReady=1,relationsRevision=:libraryRevision WHERE mediaId=:mediaId AND contentRevision=:revision AND accessEpoch=:epoch AND perceptualVersion=:version") fun markRelationsReady(mediaId:String,revision:Long,epoch:Long,version:Int,libraryRevision:Long):Int
    @Query("SELECT rightMediaId FROM similarity_relation WHERE leftMediaId=:owner ORDER BY distance,rightMediaId") fun ownerRelationIds(owner:String):List<String>

    @Query("SELECT hex(f.perceptualBits) AS hashKey,f.perceptualBits AS bits FROM content_fingerprint f JOIN media m ON m.mediaId=f.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE hex(f.perceptualBits)>:after AND f.mediaId>:mediaId AND f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND f.perceptualVersion=:version AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' AND EXISTS(SELECT 1 FROM fingerprint_band candidate JOIN fingerprint_band mine ON mine.mediaId=:mediaId AND mine.perceptualVersion=:version AND mine.bandIndex=candidate.bandIndex AND mine.bandValue=candidate.bandValue WHERE candidate.mediaId=f.mediaId AND candidate.perceptualVersion=:version) GROUP BY f.perceptualBits ORDER BY hashKey LIMIT :limit")
    fun visualCandidateBuckets(mediaId:String,version:Int,after:String,limit:Int):List<PerceptualBucket>

    @Query("SELECT f.* FROM content_fingerprint f JOIN media m ON m.mediaId=f.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE f.perceptualBits=:bits AND f.mediaId>:mediaId AND f.sha256!=:sha AND f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND f.perceptualVersion=:version AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' ORDER BY f.mediaId LIMIT :limit")
    fun visualBucketMembers(bits:ByteArray,mediaId:String,sha:String,version:Int,limit:Int=SimilarityBudgets.TOP_K):List<ContentFingerprintRecord>

    @Query("SELECT f.sha256 AS sha256,COUNT(*) AS memberCount FROM content_fingerprint f JOIN media m ON m.mediaId=f.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' GROUP BY f.sha256 HAVING COUNT(*)>1 ORDER BY f.sha256 LIMIT :limit OFFSET :offset")
    fun exactGroups(limit:Int,offset:Int):List<ExactDuplicateGroup>

    @Query("SELECT m.* FROM content_fingerprint f JOIN media m ON m.mediaId=f.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE f.sha256=:sha AND f.mediaId>:after AND f.contentRevision=m.contentRevision AND f.accessEpoch=m.accessGrantEpoch AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' ORDER BY f.mediaId LIMIT :limit")
    fun exactMembers(sha:String,after:String,limit:Int):List<MediaRecord>

    @Query("SELECT lf.sha256 FROM content_fingerprint lf JOIN content_fingerprint rf ON rf.mediaId=:right AND rf.sha256=lf.sha256 JOIN media l ON l.mediaId=lf.mediaId JOIN media r ON r.mediaId=rf.mediaId JOIN ai_media_exposure lx ON lx.mediaId=l.mediaId AND lx.contentRevision=l.contentRevision JOIN ai_media_exposure rx ON rx.mediaId=r.mediaId AND rx.contentRevision=r.contentRevision WHERE lf.mediaId=:left AND lf.contentRevision=l.contentRevision AND lf.accessEpoch=l.accessGrantEpoch AND rf.contentRevision=r.contentRevision AND rf.accessEpoch=r.accessGrantEpoch AND l.availability='AVAILABLE' AND l.trashedAt IS NULL AND lx.exposure='SAFE' AND r.availability='AVAILABLE' AND r.trashedAt IS NULL AND rx.exposure='SAFE' LIMIT 1")
    fun exactPairSha(left:String,right:String):String?

    @Query("SELECT r.* FROM similarity_relation r JOIN media l ON l.mediaId=r.leftMediaId JOIN media q ON q.mediaId=r.rightMediaId JOIN ai_media_exposure lx ON lx.mediaId=l.mediaId AND lx.contentRevision=l.contentRevision JOIN ai_media_exposure qx ON qx.mediaId=q.mediaId AND qx.contentRevision=q.contentRevision JOIN content_fingerprint lf ON lf.mediaId=l.mediaId AND lf.contentRevision=l.contentRevision AND lf.accessEpoch=l.accessGrantEpoch AND lf.perceptualVersion=:version JOIN content_fingerprint rf ON rf.mediaId=q.mediaId AND rf.contentRevision=q.contentRevision AND rf.accessEpoch=q.accessGrantEpoch AND rf.perceptualVersion=:version WHERE r.kind='VISUAL' AND r.fingerprintVersion=:version AND r.libraryRevision=(SELECT revision FROM similarity_library_state WHERE stateId='default') AND lf.relationsReady=1 AND rf.relationsReady=1 AND lf.relationsRevision=r.libraryRevision AND rf.relationsRevision=r.libraryRevision AND l.availability='AVAILABLE' AND l.trashedAt IS NULL AND lx.exposure='SAFE' AND q.availability='AVAILABLE' AND q.trashedAt IS NULL AND qx.exposure='SAFE' AND l.contentRevision=r.leftRevision AND l.accessGrantEpoch=r.leftAccessEpoch AND q.contentRevision=r.rightRevision AND q.accessGrantEpoch=r.rightAccessEpoch ORDER BY r.distance,r.leftMediaId,r.rightMediaId LIMIT :limit OFFSET :offset")
    fun visibleRelations(limit:Int,offset:Int,version:Int=PerceptualFingerprintV2.VERSION):List<SimilarityRelationRecord>

    @Query("SELECT r.* FROM similarity_relation r JOIN media l ON l.mediaId=r.leftMediaId JOIN media q ON q.mediaId=r.rightMediaId JOIN ai_media_exposure lx ON lx.mediaId=l.mediaId AND lx.contentRevision=l.contentRevision JOIN ai_media_exposure qx ON qx.mediaId=q.mediaId AND qx.contentRevision=q.contentRevision JOIN content_fingerprint lf ON lf.mediaId=l.mediaId AND lf.contentRevision=l.contentRevision AND lf.accessEpoch=l.accessGrantEpoch AND lf.perceptualVersion=:version JOIN content_fingerprint rf ON rf.mediaId=q.mediaId AND rf.contentRevision=q.contentRevision AND rf.accessEpoch=q.accessGrantEpoch AND rf.perceptualVersion=:version WHERE r.leftMediaId=:left AND r.rightMediaId=:right AND r.kind='VISUAL' AND r.fingerprintVersion=:version AND r.libraryRevision=(SELECT revision FROM similarity_library_state WHERE stateId='default') AND lf.relationsReady=1 AND rf.relationsReady=1 AND lf.relationsRevision=r.libraryRevision AND rf.relationsRevision=r.libraryRevision AND l.availability='AVAILABLE' AND l.trashedAt IS NULL AND lx.exposure='SAFE' AND q.availability='AVAILABLE' AND q.trashedAt IS NULL AND qx.exposure='SAFE' AND l.contentRevision=r.leftRevision AND l.accessGrantEpoch=r.leftAccessEpoch AND q.contentRevision=r.rightRevision AND q.accessGrantEpoch=r.rightAccessEpoch LIMIT 1")
    fun visibleRelation(left:String,right:String,version:Int=PerceptualFingerprintV2.VERSION):SimilarityRelationRecord?

    @Transaction fun publishIfCurrent(value:ContentFingerprintRecord):Boolean {
        if(currentSafe(value.mediaId,value.contentRevision,value.accessEpoch)==null)return false
        deleteRelations(value.mediaId);deleteScan(value.mediaId);saveFingerprint(value.copy(relationsReady=false,relationsRevision=0));deleteFailures(value.mediaId);deleteBands(value.mediaId)
        if(value.perceptualVersion==PerceptualFingerprintV2.VERSION&&value.perceptualBits.size==8){saveBands(FingerprintBands.records(value.mediaId,value.perceptualVersion,value.perceptualBits));saveScan(SimilarityScanRecord(value.mediaId,value.contentRevision,value.accessEpoch,value.perceptualVersion,"",0,System.currentTimeMillis(),libraryRevision()))}
        return true
    }

    @Transaction fun failIfCurrent(value:FingerprintFailureRecord):Boolean {
        if(currentSafe(value.mediaId,value.contentRevision,value.accessEpoch)==null)return false
        deleteRelations(value.mediaId);deleteFingerprint(value.mediaId);deleteBands(value.mediaId);deleteScan(value.mediaId);saveFailure(value);return true
    }
}

object SimilarityRelationScanner {
    private data class Page(val examined:Int,val comparisons:Int,val complete:Boolean)

    fun step(database:MediaDatabase,mediaId:String,budget:Int=SimilarityBudgets.CANDIDATES_PER_ITEM_STEP,cancelled:()->Boolean={false}):SimilarityScanStep {
        require(budget in 1..SimilarityBudgets.CANDIDATES_PER_ITEM_STEP)
        var examined=0;var comparisons=0
        while(examined<budget){
            if(cancelled())throw InterruptedException("Similarity relation scan cancelled")
            val page=page(database,mediaId,min(SimilarityBudgets.CANDIDATE_PAGE,budget-examined))
            examined+=page.examined;comparisons+=page.comparisons
            if(page.complete)return SimilarityScanStep(examined,comparisons,true)
            if(page.examined==0)break
        }
        return SimilarityScanStep(examined,comparisons,false)
    }

    private fun page(database:MediaDatabase,mediaId:String,limit:Int):Page=database.runInTransaction<Page>{
        val dao=database.similarity();dao.ensureLibraryState();val libraryRevision=dao.libraryRevision();val current=dao.fingerprint(mediaId)?:run{dao.deleteScan(mediaId);return@runInTransaction Page(0,0,true)}
        if(current.perceptualVersion!=PerceptualFingerprintV2.VERSION||dao.currentSafe(mediaId,current.contentRevision,current.accessEpoch)==null){dao.deleteScan(mediaId);return@runInTransaction Page(0,0,true)}
        val stored=dao.scan(mediaId)
        val scan=stored?.takeIf{it.contentRevision==current.contentRevision&&it.accessEpoch==current.accessEpoch&&it.fingerprintVersion==current.perceptualVersion&&it.libraryRevision==libraryRevision}
            ?:SimilarityScanRecord(mediaId,current.contentRevision,current.accessEpoch,current.perceptualVersion,"",0,System.currentTimeMillis(),libraryRevision).also{dao.deleteOwnerRelations(mediaId);dao.saveScan(it)}
        val candidates=dao.visualCandidateBuckets(mediaId,current.perceptualVersion,scan.afterMediaId,limit)
        var comparisons=0
        candidates.forEach{bucket->
            val distance=if(current.perceptualBits.contentEquals(bucket.bits))0 else{comparisons++;runCatching{PerceptualFingerprintV2.distance(current.perceptualBits,bucket.bits)}.getOrNull()?:return@forEach}
            if(!SimilarityRules.isSimilar(distance))return@forEach
            dao.visualBucketMembers(bucket.bits,current.mediaId,current.sha256,current.perceptualVersion).forEach{candidate->
                dao.saveRelation(SimilarityRelationRecord(current.mediaId,candidate.mediaId,current.contentRevision,candidate.contentRevision,current.accessEpoch,candidate.accessEpoch,SimilarityKind.VISUAL,current.perceptualVersion,distance,System.currentTimeMillis(),libraryRevision))
            }
        }
        dao.pruneRelations(mediaId)
        if(candidates.size<limit){dao.markRelationsReady(mediaId,current.contentRevision,current.accessEpoch,current.perceptualVersion,libraryRevision);dao.deleteScan(mediaId);Page(candidates.size,comparisons,true)}
        else{dao.saveScan(scan.copy(afterMediaId=candidates.last().hashKey,examined=scan.examined+candidates.size,updatedAt=System.currentTimeMillis()));Page(candidates.size,comparisons,false)}
    }
}
