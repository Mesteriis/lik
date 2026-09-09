package io.github.mesteriis.lik.ai

import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaRecord

/** Last-moment, UI-thread privacy gate. Queries are indexed point/count reads and never expose hidden totals. */
object AiUiPublicationGuard {
    fun ocr(database:MediaDatabase,generation:String,row:AiOcrResultRecord):Boolean =
        row.generationId==generation && safe(database,row.mediaId,row.contentRevision,row.accessEpoch) && scalar(database,
            "SELECT COUNT(*) FROM ai_ocr_result WHERE generationId=? AND mediaId=? AND contentRevision=? AND accessEpoch=?",
            arrayOf(generation,row.mediaId,row.contentRevision.toString(),row.accessEpoch.toString()))==1

    fun mediaOcr(database:MediaDatabase,generation:String,row:MediaRecord):Boolean = safe(database,row.mediaId,row.contentRevision,row.accessGrantEpoch) && scalar(database,
        "SELECT COUNT(*) FROM ai_ocr_result WHERE generationId=? AND mediaId=? AND contentRevision=? AND accessEpoch=?",
        arrayOf(generation,row.mediaId,row.contentRevision.toString(),row.accessGrantEpoch.toString()))==1

    fun face(database:MediaDatabase,generation:String,row:VisibleFaceRow):Boolean = safe(database,row.mediaId,row.contentRevision,row.accessEpoch) && scalar(database,
        "SELECT COUNT(*) FROM ai_face_detection WHERE generationId=? AND detectionId=? AND mediaId=? AND contentRevision=? AND accessEpoch=?",
        arrayOf(generation,row.detectionId,row.mediaId,row.contentRevision.toString(),row.accessEpoch.toString()))==1

    fun coverage(database:MediaDatabase,generations:Map<AiFeature,String>):AiUiCoverage {
        val eligible=scalar(database,"SELECT COUNT(*) FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE'")
        fun count(feature:AiFeature)=generations[feature]?.let{generation->scalar(database,
            "SELECT COUNT(DISTINCT r.mediaId) FROM ai_feature_media_run r JOIN media m ON m.mediaId=r.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE r.generationId=? AND r.feature=? AND r.error IS NULL AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND m.contentRevision=r.contentRevision AND m.accessGrantEpoch=r.accessEpoch AND x.exposure='SAFE'",
            arrayOf(generation,feature.name))}?:0
        val search=generations[AiFeature.SEARCH]?.let{generation->scalar(database,
            "SELECT COUNT(*) FROM ai_embedding e JOIN media m ON m.mediaId=e.mediaId JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE e.generationId=? AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND m.contentRevision=e.contentRevision AND m.accessGrantEpoch=e.accessEpoch AND x.exposure='SAFE'",arrayOf(generation))}?:0
        return AiUiCoverage(eligible,search,count(AiFeature.OCR),count(AiFeature.PEOPLE))
    }

    private fun safe(database:MediaDatabase,id:String,revision:Long,epoch:Long)=scalar(database,
        "SELECT COUNT(*) FROM media m JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.mediaId=? AND m.contentRevision=? AND m.accessGrantEpoch=? AND m.availability='AVAILABLE' AND m.trashedAt IS NULL AND x.exposure='SAFE'",
        arrayOf(id,revision.toString(),epoch.toString()))==1
    private fun scalar(database:MediaDatabase,sql:String,args:Array<String> = emptyArray()):Int =
        database.openHelper.readableDatabase.query(sql,args).use{if(it.moveToFirst())it.getInt(0) else 0}
}

data class AiUiCoverage(val eligible:Int,val search:Int,val ocr:Int,val people:Int)
