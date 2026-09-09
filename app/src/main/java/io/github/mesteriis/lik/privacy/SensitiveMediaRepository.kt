package io.github.mesteriis.lik.privacy

import android.content.Context
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.catalog.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SensitiveMediaRepository(private val context:Context,private val database:MediaDatabase=MediaDatabase.get(context)){
    fun decision(mediaId:String):SensitiveDecision{
        val row=database.media().get(mediaId)?:return SensitiveDecision.QUARANTINED
        return database.sensitiveMedia().resolved(mediaId,row.contentRevision,row.accessGrantEpoch)
    }

    fun mayAccess(mediaId:String,revision:Long,snapshot:RevealSnapshot=SensitiveMediaSession.current.snapshot()):Boolean{
        val row=database.media().get(mediaId)?:return false
        if(row.availability!=MediaAvailability.AVAILABLE||row.trashedAt!=null||row.contentRevision!=revision)return false
        val revealActive=snapshot.revealed&&SensitiveMediaSession.current.accepts(snapshot.epoch)
        return SensitiveMediaPolicy.mayReveal(database.sensitiveMedia().resolved(mediaId,revision,row.accessGrantEpoch),revealActive)
    }

    fun setManual(mediaId:String,decision:SensitiveDecision,reveal:RevealSnapshot=SensitiveMediaSession.current.snapshot(),expectedRevision:Long?=null):Boolean{
        require(decision==SensitiveDecision.SAFE||decision==SensitiveDecision.SENSITIVE)
        if(!reveal.revealed||!SensitiveMediaSession.current.accepts(reveal.epoch))return false
        return database.runInTransaction<Boolean>{
            val row=database.media().get(mediaId)?.takeIf{it.availability==MediaAvailability.AVAILABLE&&it.trashedAt==null}?:return@runInTransaction false
            if(expectedRevision!=null&&row.contentRevision!=expectedRevision)return@runInTransaction false
            if(!SensitiveMediaSession.current.accepts(reveal.epoch))return@runInTransaction false
            database.sensitiveMedia().saveManual(SensitiveManualRecord(mediaId,row.contentRevision,decision,System.currentTimeMillis()))
            database.ocrPeople().saveExposure(AiMediaExposureRecord(mediaId,row.contentRevision,decision.toLegacy(),System.currentTimeMillis()))
            true
        }
    }

    fun clearManual(mediaId:String,reveal:RevealSnapshot=SensitiveMediaSession.current.snapshot()):Boolean{
        if(!reveal.revealed||!SensitiveMediaSession.current.accepts(reveal.epoch))return false
        return database.runInTransaction<Boolean>{
            val row=database.media().get(mediaId)?:return@runInTransaction false
            database.sensitiveMedia().clearManual(mediaId)
            database.ocrPeople().saveExposure(AiMediaExposureRecord(mediaId,row.contentRevision,AiExposure.QUARANTINED,System.currentTimeMillis()))
            true
        }
    }

    private fun SensitiveDecision.toLegacy()=when(this){
        SensitiveDecision.SAFE->AiExposure.SAFE
        SensitiveDecision.SENSITIVE->AiExposure.SENSITIVE
        SensitiveDecision.QUARANTINED->AiExposure.QUARANTINED
    }

    companion object{
        fun raw(logits:FloatArray)=ByteBuffer.allocate(logits.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{asFloatBuffer().put(logits)}.array()
    }
}
