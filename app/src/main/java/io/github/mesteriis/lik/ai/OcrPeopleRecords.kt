package io.github.mesteriis.lik.ai

import androidx.room.*
import io.github.mesteriis.lik.catalog.MediaAvailability
import io.github.mesteriis.lik.catalog.MediaRecord

@Entity(tableName = "ai_media_exposure", primaryKeys = ["mediaId", "contentRevision"], indices = [Index("exposure")])
data class AiMediaExposureRecord(
    val mediaId: String,
    val contentRevision: Long,
    val exposure: AiExposure,
    val decidedAt: Long,
)

@Entity(
    tableName = "ai_ocr_result", primaryKeys = ["generationId", "mediaId"],
    foreignKeys = [ForeignKey(entity = AiIndexGenerationRecord::class, parentColumns = ["generationId"], childColumns = ["generationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mediaId"), Index("searchText")],
)
data class AiOcrResultRecord(
    val generationId: String,
    val mediaId: String,
    val contentRevision: Long,
    val accessEpoch: Long,
    val pipelineFingerprint: String,
    val displayText: String,
    val searchText: String,
    val regionsJson: String,
    val confidence: Float,
)

@Entity(
    tableName = "ai_feature_media_run", primaryKeys = ["generationId", "mediaId"],
    foreignKeys = [ForeignKey(entity = AiIndexGenerationRecord::class, parentColumns = ["generationId"], childColumns = ["generationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mediaId")],
)
data class AiFeatureMediaRunRecord(
    val generationId: String,
    val mediaId: String,
    val feature: String,
    val contentRevision: Long,
    val accessEpoch: Long,
    val error: String?,
)

@Entity(
    tableName = "ai_face_detection",
    foreignKeys = [ForeignKey(entity = AiIndexGenerationRecord::class, parentColumns = ["generationId"], childColumns = ["generationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("generationId"), Index("mediaId"), Index("anchorId"),
        Index(value=["generationId","detectionId"]),Index(value=["generationId","anchorId"])],
)
data class AiFaceDetectionRecord(
    @PrimaryKey val detectionId: String,
    val generationId: String,
    val mediaId: String,
    val contentRevision: Long,
    val accessEpoch: Long,
    val pipelineFingerprint: String,
    val anchorId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val landmarks: ByteArray,
    val embedding: ByteArray,
    val confidence: Float,
    val computedClusterId: String,
)

@Entity(tableName = "person_identity")
data class PersonIdentityRecord(@PrimaryKey val personId: String, val name: String?, val createdAt: Long)

enum class ManualFaceDecision { ASSIGN, EXCLUDE }
@Entity(tableName = "person_face_decision", indices = [Index("personId")])
data class PersonFaceDecisionRecord(
    @PrimaryKey val anchorId: String,
    val decision: ManualFaceDecision,
    val personId: String?,
    val updatedAt: Long,
)

@Entity(tableName = "person_merge")
data class PersonMergeRecord(@PrimaryKey val fromPersonId: String, val intoPersonId: String, val updatedAt: Long)

@Entity(tableName = "person_cannot_link", primaryKeys = ["leftAnchorId", "rightAnchorId"])
data class PersonCannotLinkRecord(val leftAnchorId: String, val rightAnchorId: String, val updatedAt: Long, val splitPersonId: String? = null)

@Entity(
    tableName = "person_cannot_link_owner",
    primaryKeys = ["leftAnchorId", "rightAnchorId", "ownerId"],
    indices = [Index("ownerId")],
)
data class PersonCannotLinkOwnerRecord(
    val leftAnchorId: String, val rightAnchorId: String, val ownerId: String, val updatedAt: Long,
)

@Entity(tableName="person_split")
data class PersonSplitRecord(@PrimaryKey val splitPersonId:String,val fromPersonId:String,val createdAt:Long)

data class OcrCoverage(val indexed: Int, val eligible: Int, val revealAvailable: Boolean)
data class PersonGroupRow(val personId: String, val name: String?, val faceCount: Int, val coverMediaId: String?)
data class VisibleFaceRow(
    val detectionId: String, val anchorId: String, val mediaId: String, val computedClusterId: String,
    val contentRevision:Long, val accessEpoch:Long,
    val manualDecision: ManualFaceDecision?, val manualPersonId: String?,
)

@Dao
interface OcrPeopleDao {
    @Upsert fun saveExposure(value: AiMediaExposureRecord)
    @Query("SELECT exposure FROM ai_media_exposure WHERE mediaId = :mediaId AND contentRevision = :revision")
    fun exposure(mediaId: String, revision: Long): AiExposure?

    @Upsert fun saveOcr(value: AiOcrResultRecord)
    @Upsert fun saveRun(value: AiFeatureMediaRunRecord)
    @Query("SELECT * FROM ai_feature_media_run WHERE generationId=:generationId AND mediaId=:mediaId") fun run(generationId: String, mediaId: String): AiFeatureMediaRunRecord?
    @Query("DELETE FROM ai_feature_media_run WHERE generationId=:generationId AND mediaId=:mediaId") fun deleteRun(generationId: String, mediaId: String): Int
    @Query("SELECT COUNT(*) FROM ai_feature_media_run r JOIN media m ON m.mediaId=r.mediaId WHERE r.generationId=:generationId AND r.error IS NULL AND m.availability='AVAILABLE' AND m.contentRevision=r.contentRevision AND m.accessGrantEpoch=r.accessEpoch") fun currentRunCount(generationId: String): Int
    @Query("SELECT COUNT(*) FROM ai_feature_media_run WHERE generationId=:generationId AND error IS NULL") fun storedRunCount(generationId:String):Int
    @Query("SELECT COUNT(*) FROM ai_feature_media_run r JOIN media m ON m.mediaId=r.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE r.generationId=:generationId AND r.error IS NULL AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND m.contentRevision=r.contentRevision AND m.accessGrantEpoch=r.accessEpoch AND x.exposure='SAFE'") fun currentIndexableRunCount(generationId:String):Int
    @Query("SELECT COUNT(*) FROM ai_feature_media_run r JOIN media m ON m.mediaId=r.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE r.generationId=:generationId AND r.error IS NULL AND m.availability='AVAILABLE' AND m.contentRevision=r.contentRevision AND m.accessGrantEpoch=r.accessEpoch AND x.exposure='SAFE'") fun currentSafeRunCount(generationId: String): Int
    @Query("SELECT r.mediaId FROM ai_feature_media_run r LEFT JOIN media m ON m.mediaId=r.mediaId WHERE r.generationId=:generationId AND (m.mediaId IS NULL OR m.availability!='AVAILABLE' OR m.contentRevision!=r.contentRevision OR m.accessGrantEpoch!=r.accessEpoch)") fun staleRunIds(generationId: String): List<String>
    @Query("SELECT r.mediaId FROM ai_feature_media_run r LEFT JOIN media m ON m.mediaId=r.mediaId LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE r.generationId=:generationId AND (m.mediaId IS NULL OR m.availability!='AVAILABLE' OR m.trashedAt IS NOT NULL OR m.contentRevision!=r.contentRevision OR m.accessGrantEpoch!=r.accessEpoch OR x.exposure IS NULL OR x.exposure!='SAFE')") fun invalidIndexableRunIds(generationId:String):List<String>
    @Query("DELETE FROM ai_ocr_result WHERE generationId = :generationId AND mediaId = :mediaId") fun deleteOcr(generationId: String, mediaId: String): Int
    @Query("SELECT o.* FROM ai_ocr_result o JOIN media m ON m.mediaId=o.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE o.generationId=:generationId AND o.mediaId=:mediaId AND m.availability='AVAILABLE' AND m.contentRevision=o.contentRevision AND m.accessGrantEpoch=o.accessEpoch AND x.exposure='SAFE' LIMIT 1")
    fun visibleOcr(generationId: String, mediaId: String): AiOcrResultRecord?
    @Query("SELECT m.* FROM media m JOIN ai_ocr_result o ON o.mediaId=m.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE o.generationId=:generationId AND m.availability='AVAILABLE' AND m.contentRevision=o.contentRevision AND m.accessGrantEpoch=o.accessEpoch AND x.exposure='SAFE' AND instr(o.searchText,:query)>0 ORDER BY m.sortAt DESC,m.mediaId DESC LIMIT :limit OFFSET :offset")
    fun searchOcr(generationId: String, query: String, limit: Int, offset: Int): List<MediaRecord>
    @Query("SELECT COUNT(*) FROM ai_ocr_result o JOIN media m ON m.mediaId=o.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE o.generationId=:generationId AND m.availability='AVAILABLE' AND m.contentRevision=o.contentRevision AND m.accessGrantEpoch=o.accessEpoch AND x.exposure='SAFE'")
    fun currentOcrCount(generationId: String): Int
    @Query("SELECT COUNT(*) FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND x.exposure='SAFE'") fun eligibleCount(): Int
    @Query("SELECT COUNT(*) FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (x.exposure IS NULL OR x.exposure!='SAFE')") fun quarantinedCount(): Int
    @Query("SELECT o.mediaId FROM ai_ocr_result o LEFT JOIN media m ON m.mediaId=o.mediaId WHERE o.generationId=:generationId AND (m.mediaId IS NULL OR m.availability!='AVAILABLE' OR m.contentRevision!=o.contentRevision OR m.accessGrantEpoch!=o.accessEpoch)") fun staleOcrIds(generationId: String): List<String>
    @Query("INSERT OR IGNORE INTO ai_feature_media_run(generationId,mediaId,feature,contentRevision,accessEpoch,error) SELECT :target,r.mediaId,:feature,r.contentRevision,r.accessEpoch,NULL FROM ai_feature_media_run r JOIN media m ON m.mediaId=r.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE r.generationId=:source AND r.feature=:feature AND r.error IS NULL AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND m.contentRevision=r.contentRevision AND m.accessGrantEpoch=r.accessEpoch AND x.exposure='SAFE'")
    fun copyCurrentRuns(source:String,target:String,feature:String)
    @Query("INSERT OR REPLACE INTO ai_ocr_result(generationId,mediaId,contentRevision,accessEpoch,pipelineFingerprint,displayText,searchText,regionsJson,confidence) SELECT :target,o.mediaId,o.contentRevision,o.accessEpoch,:pipeline,o.displayText,o.searchText,o.regionsJson,o.confidence FROM ai_ocr_result o JOIN media m ON m.mediaId=o.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE o.generationId=:source AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND m.contentRevision=o.contentRevision AND m.accessGrantEpoch=o.accessEpoch AND x.exposure='SAFE'")
    fun copyCurrentOcr(source:String,target:String,pipeline:String)

    @Upsert fun saveFace(value: AiFaceDetectionRecord)
    @Query("DELETE FROM ai_face_detection WHERE generationId=:generationId AND mediaId=:mediaId") fun deleteFaces(generationId: String, mediaId: String): Int
    @Query("SELECT f.detectionId,f.anchorId,f.mediaId,f.computedClusterId,f.contentRevision,f.accessEpoch,d.decision AS manualDecision,d.personId AS manualPersonId FROM ai_face_detection f JOIN media m ON m.mediaId=f.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision LEFT JOIN person_face_decision d ON d.anchorId=f.anchorId WHERE f.generationId=:generationId AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND m.contentRevision=f.contentRevision AND m.accessGrantEpoch=f.accessEpoch AND x.exposure='SAFE'")
    fun visibleFaces(generationId: String): List<VisibleFaceRow>
    @Query("SELECT * FROM ai_face_detection WHERE generationId=:generationId ORDER BY anchorId") fun faces(generationId: String): List<AiFaceDetectionRecord>
    @Query("SELECT * FROM ai_face_detection WHERE generationId=:generationId AND detectionId>:after ORDER BY detectionId LIMIT :limit")
    fun faceBatch(generationId:String,after:String,limit:Int):List<AiFaceDetectionRecord>
    @Query("UPDATE ai_face_detection SET computedClusterId=:clusterId WHERE generationId=:generationId AND anchorId IN (:anchors)") fun setCluster(generationId: String, anchors: List<String>, clusterId: String): Int
    @Query("SELECT COUNT(DISTINCT f.mediaId) FROM ai_face_detection f JOIN media m ON m.mediaId=f.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE f.generationId=:generationId AND m.availability='AVAILABLE' AND m.contentRevision=f.contentRevision AND m.accessGrantEpoch=f.accessEpoch AND x.exposure='SAFE'") fun currentPeopleMediaCount(generationId: String): Int
    @Query("SELECT COUNT(DISTINCT mediaId) FROM ai_face_detection WHERE generationId=:generationId") fun peopleMediaCount(generationId: String): Int
    @Query("SELECT f.mediaId FROM ai_face_detection f LEFT JOIN media m ON m.mediaId=f.mediaId WHERE f.generationId=:generationId AND (m.mediaId IS NULL OR m.availability!='AVAILABLE' OR m.contentRevision!=f.contentRevision OR m.accessGrantEpoch!=f.accessEpoch) GROUP BY f.mediaId") fun staleFaceMediaIds(generationId: String): List<String>
    @Query("INSERT OR REPLACE INTO ai_face_detection(detectionId,generationId,mediaId,contentRevision,accessEpoch,pipelineFingerprint,anchorId,`left`,`top`,`right`,`bottom`,landmarks,embedding,confidence,computedClusterId) SELECT :target || ':' || f.detectionId,:target,f.mediaId,f.contentRevision,f.accessEpoch,:pipeline,f.anchorId,f.`left`,f.`top`,f.`right`,f.`bottom`,f.landmarks,f.embedding,f.confidence,f.computedClusterId FROM ai_face_detection f JOIN media m ON m.mediaId=f.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE f.generationId=:source AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND m.contentRevision=f.contentRevision AND m.accessGrantEpoch=f.accessEpoch AND x.exposure='SAFE'")
    fun copyCurrentFaces(source:String,target:String,pipeline:String)

    @Transaction fun copyCurrentGeneration(source:String,target:String,feature:String,pipeline:String):Int {
        copyCurrentRuns(source,target,feature)
        when(feature){AiFeature.OCR.name->copyCurrentOcr(source,target,pipeline);AiFeature.PEOPLE.name->copyCurrentFaces(source,target,pipeline)}
        return currentRunCount(target)
    }

    @Upsert fun savePerson(value: PersonIdentityRecord)
    @Query("SELECT * FROM person_identity WHERE personId=:id") fun person(id: String): PersonIdentityRecord?
    @Query("SELECT * FROM person_identity ORDER BY COALESCE(name,''),personId") fun people(): List<PersonIdentityRecord>
    @Query("UPDATE person_identity SET name=:name WHERE personId=:id") fun namePerson(id: String, name: String): Int
    @Upsert fun saveDecision(value: PersonFaceDecisionRecord)
    @Query("DELETE FROM person_face_decision WHERE anchorId=:anchorId") fun clearDecision(anchorId: String): Int
    @Query("SELECT * FROM person_face_decision") fun decisions(): List<PersonFaceDecisionRecord>
    @Upsert fun saveMerge(value: PersonMergeRecord)
    @Query("DELETE FROM person_merge WHERE fromPersonId=:from") fun removeMerge(from: String): Int
    @Query("SELECT * FROM person_merge") fun merges(): List<PersonMergeRecord>
    @Upsert fun saveCannotLink(value: PersonCannotLinkRecord)
    @Query("DELETE FROM person_cannot_link WHERE leftAnchorId=:left AND rightAnchorId=:right") fun removeCannotLink(left: String, right: String): Int
    @Query("SELECT * FROM person_cannot_link") fun cannotLinks(): List<PersonCannotLinkRecord>
    @Upsert fun saveCannotLinkOwner(value:PersonCannotLinkOwnerRecord)
    @Query("SELECT * FROM person_cannot_link_owner WHERE ownerId=:ownerId") fun cannotLinkOwners(ownerId:String):List<PersonCannotLinkOwnerRecord>
    @Query("SELECT COUNT(*) FROM person_cannot_link_owner WHERE leftAnchorId=:left AND rightAnchorId=:right") fun cannotLinkOwnerCount(left:String,right:String):Int
    @Query("DELETE FROM person_cannot_link_owner WHERE ownerId=:ownerId") fun removeCannotLinkOwner(ownerId:String):Int
    @Upsert fun saveSplit(value:PersonSplitRecord)
    @Query("SELECT * FROM person_split WHERE splitPersonId=:personId") fun split(personId:String):PersonSplitRecord?
    @Query("DELETE FROM person_split WHERE splitPersonId=:personId") fun removeSplit(personId:String):Int
    @Query("SELECT * FROM person_face_decision WHERE personId=:personId AND decision='ASSIGN'") fun decisionsForPerson(personId:String):List<PersonFaceDecisionRecord>

    @Transaction
    fun publishOcrIfCurrent(value: AiOcrResultRecord): Boolean {
        if (eligibleMedia(value.mediaId,value.contentRevision,value.accessEpoch) == null) return false
        saveOcr(value); return true
    }

    @Transaction
    fun publishOcrRunIfCurrent(value: AiOcrResultRecord, run: AiFeatureMediaRunRecord): Boolean {
        if (eligibleMedia(value.mediaId,value.contentRevision,value.accessEpoch) == null) return false
        require(run.generationId == value.generationId && run.mediaId == value.mediaId &&
            run.contentRevision == value.contentRevision && run.accessEpoch == value.accessEpoch && run.feature == AiFeature.OCR.name)
        saveOcr(value); saveRun(run); return true
    }

    @Transaction
    fun publishFacesIfCurrent(token: AiPublicationToken, faces: List<AiFaceDetectionRecord>): Boolean {
        if (eligibleMedia(token.mediaId,token.contentRevision,token.accessEpoch) == null) return false
        require(faces.all { it.generationId == token.generationId && it.mediaId == token.mediaId &&
            it.contentRevision == token.contentRevision && it.accessEpoch == token.accessEpoch && it.pipelineFingerprint == token.pipelineFingerprint })
        deleteFaces(token.generationId, token.mediaId)
        faces.forEach(::saveFace)
        saveRun(AiFeatureMediaRunRecord(token.generationId, token.mediaId, AiFeature.PEOPLE.name,
            token.contentRevision, token.accessEpoch, null))
        return true
    }

    @Query("SELECT * FROM media WHERE mediaId=:mediaId") fun currentMedia(mediaId: String): MediaRecord?

    @Query("SELECT m.* FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.mediaId=:mediaId AND m.contentRevision=:revision AND (:epoch IS NULL OR m.accessGrantEpoch=:epoch) AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE' LIMIT 1")
    fun eligibleMedia(mediaId:String,revision:Long,epoch:Long?=null):MediaRecord?
}
