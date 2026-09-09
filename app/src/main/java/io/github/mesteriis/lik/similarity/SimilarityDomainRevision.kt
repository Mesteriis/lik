package io.github.mesteriis.lik.similarity

import io.github.mesteriis.lik.ai.AiMediaExposureRecord
import io.github.mesteriis.lik.catalog.MediaRecord

/** Fields whose actual value changes can alter fingerprint bytes or visible similarity membership. */
object SimilarityDomainRevision {
    private val MEDIA_TOKEN_COLUMNS=listOf("mediaId","source","sourceKey","volumeName","volumeVersion","generationAdded","contentUri","privateFileId","contentRevision","accessGrantEpoch")
    val MEDIA_UPDATE_COLUMNS=MEDIA_TOKEN_COLUMNS+listOf("availability","trashedAt")
    val EXPOSURE_UPDATE_COLUMNS=listOf("mediaId","contentRevision","exposure")
    val mediaInsertWhen=mediaEligible("NEW")
    val mediaDeleteWhen=mediaEligible("OLD")
    val mediaUpdateWhen=transition(mediaEligible("OLD"),mediaEligible("NEW"),same("OLD","NEW",MEDIA_TOKEN_COLUMNS))
    val exposureInsertWhen=exposureEligible("NEW")
    val exposureDeleteWhen=exposureEligible("OLD")
    val exposureUpdateWhen=transition(exposureEligible("OLD"),exposureEligible("NEW"),same("OLD","NEW",listOf("mediaId","contentRevision")))

    fun mediaChanged(old:MediaRecord,new:MediaRecord,oldHasSafeExposure:Boolean,newHasSafeExposure:Boolean):Boolean{
        val oldEligible=old.availability==io.github.mesteriis.lik.catalog.MediaAvailability.AVAILABLE&&old.trashedAt==null&&oldHasSafeExposure
        val newEligible=new.availability==io.github.mesteriis.lik.catalog.MediaAvailability.AVAILABLE&&new.trashedAt==null&&newHasSafeExposure
        if(oldEligible!=newEligible)return true
        if(!oldEligible)return false
        return listOf(
        old.mediaId,old.source,old.sourceKey,old.volumeName,old.volumeVersion,old.generationAdded,old.contentUri,old.privateFileId,
        old.contentRevision,old.accessGrantEpoch,
        )!=listOf(
        new.mediaId,new.source,new.sourceKey,new.volumeName,new.volumeVersion,new.generationAdded,new.contentUri,new.privateFileId,
        new.contentRevision,new.accessGrantEpoch,
        )
    }

    fun exposureChanged(media:MediaRecord,old:AiMediaExposureRecord?,new:AiMediaExposureRecord?):Boolean{
        fun eligible(value:AiMediaExposureRecord?)=media.availability==io.github.mesteriis.lik.catalog.MediaAvailability.AVAILABLE&&media.trashedAt==null&&value?.mediaId==media.mediaId&&value.contentRevision==media.contentRevision&&value.exposure==io.github.mesteriis.lik.ai.AiExposure.SAFE
        val oldEligible=eligible(old);val newEligible=eligible(new)
        return oldEligible!=newEligible||(oldEligible&&newEligible&&(old!!.mediaId!=new!!.mediaId||old.contentRevision!=new.contentRevision))
    }

    private fun mediaEligible(alias:String)="$alias.availability='AVAILABLE' AND $alias.trashedAt IS NULL AND EXISTS(SELECT 1 FROM ai_media_exposure AS exposure WHERE exposure.mediaId=$alias.mediaId AND exposure.contentRevision=$alias.contentRevision AND exposure.exposure='SAFE')"
    private fun exposureEligible(alias:String)="$alias.exposure='SAFE' AND EXISTS(SELECT 1 FROM media AS current_media WHERE current_media.mediaId=$alias.mediaId AND current_media.contentRevision=$alias.contentRevision AND current_media.availability='AVAILABLE' AND current_media.trashedAt IS NULL)"
    private fun same(old:String,new:String,columns:List<String>)=columns.joinToString(" AND "){"$old.$it IS $new.$it"}
    private fun transition(oldEligible:String,newEligible:String,sameToken:String)="(($oldEligible) AND NOT (($newEligible) AND ($sameToken))) OR (($newEligible) AND NOT (($oldEligible) AND ($sameToken)))"
}
