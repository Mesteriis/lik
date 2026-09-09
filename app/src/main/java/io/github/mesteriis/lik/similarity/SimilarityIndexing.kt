package io.github.mesteriis.lik.similarity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import android.os.PowerManager
import androidx.work.*
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaRecord
import io.github.mesteriis.lik.catalog.MediaSource
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class CalculatedFingerprint(val sha256:String,val perceptualBits:ByteArray)

fun interface FingerprintCalculator{fun calculate(row:MediaRecord,cancelled:()->Boolean):CalculatedFingerprint}

class SimilarityFingerprintEngine(private val context:Context):FingerprintCalculator {
    override fun calculate(row:MediaRecord,cancelled:()->Boolean):CalculatedFingerprint {
        val sha=open(row).use{ContentDigest.sha256(it,cancelled)}
        if(cancelled())throw InterruptedException("Fingerprint calculation cancelled")
        val bitmap=decode(row)
        return bitmap.useBitmap {
            val pixels=IntArray(width*height);getPixels(pixels,0,width,0,0,width,height)
            val luma=IntArray(pixels.size){i->val color=pixels[i];((android.graphics.Color.red(color)*299+android.graphics.Color.green(color)*587+android.graphics.Color.blue(color)*114)/1000)}
            CalculatedFingerprint(sha,PerceptualFingerprintV2.fromLuma(width,height,luma))
        }
    }

    private fun open(row:MediaRecord):InputStream = when(row.source){
        MediaSource.GOOGLE_IMPORT->PhotoLibrary.store(context).fileFor(requireNotNull(row.privateFileId)).inputStream()
        MediaSource.DEVICE->requireNotNull(context.contentResolver.openInputStream(requireNotNull(row.contentUri).toUri()))
    }

    private fun decode(row:MediaRecord):Bitmap {
        val source=when(row.source){
            MediaSource.GOOGLE_IMPORT->ImageDecoder.createSource(PhotoLibrary.store(context).fileFor(requireNotNull(row.privateFileId)))
            MediaSource.DEVICE->ImageDecoder.createSource(context.contentResolver,requireNotNull(row.contentUri).toUri())
        }
        return ImageDecoder.decodeBitmap(source){decoder,info,_->
            val scale=minOf(1.0,64.0/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener{false}
        }
    }

    private inline fun <T> Bitmap.useBitmap(block:Bitmap.()->T):T=try{block()}finally{recycle()}
}

enum class SimilarityRunOutcome { COMPLETE, MORE_WORK, PAUSED_BUDGET, BLOCKED_BY_FAILURE }

class SimilarityProcessor(
    private val database:MediaDatabase,
    private val engine:FingerprintCalculator,
    private val stopped:()->Boolean,
    private val progress:(Int,Int)->Unit={_,_->},
    private val newTranche:Boolean=false,
) {
    fun run():SimilarityRunOutcome {
        val dao=database.similarity();val prepared=dao.prepareTranche(newTranche);var fingerprintsLeft=SimilarityBudgets.FINGERPRINTS_PER_RUN
        var candidatesLeft=minOf(SimilarityBudgets.CANDIDATES_PER_RUN,SimilarityBudgets.COMPARISONS_PER_TRANCHE-prepared.comparisons);var checkpoint=prepared.checkpointMediaId
        dao.commitProgress(checkpoint)
        try{
            while(fingerprintsLeft>0||candidatesLeft>0){
                if(stopped())throw InterruptedException("Similarity indexing cancelled")
                val row=dao.pendingSafe(null,1).firstOrNull()?:break
                val token=FingerprintToken(row.mediaId,row.contentRevision,row.accessGrantEpoch)
                var current=dao.fingerprint(row.mediaId)?.takeIf{it.contentRevision==token.contentRevision&&it.accessEpoch==token.accessEpoch&&it.perceptualVersion==PerceptualFingerprintV2.VERSION}
                if(current==null){
                    if(fingerprintsLeft==0)break
                    try{
                        val result=engine.calculate(row,stopped)
                        val record=ContentFingerprintRecord(row.mediaId,row.contentRevision,row.accessGrantEpoch,result.sha256,PerceptualFingerprintV2.VERSION,result.perceptualBits,System.currentTimeMillis())
                        if(dao.publishIfCurrent(record))current=record
                    }catch(error:InterruptedException){throw error}catch(error:Exception){
                        dao.failIfCurrent(FingerprintFailureRecord(row.mediaId,row.contentRevision,row.accessGrantEpoch,PerceptualFingerprintV2.VERSION,error.javaClass.simpleName.take(80),System.currentTimeMillis()))
                    }
                    fingerprintsLeft--
                }
                if(current!=null&&!current.relationsReady){
                    if(candidatesLeft==0)break
                    val allowance=minOf(SimilarityBudgets.CANDIDATES_PER_ITEM_STEP,candidatesLeft)
                    if(dao.reserveComparisons(prepared.libraryRevision,allowance)==0)break
                    val scanned=SimilarityRelationScanner.step(database,row.mediaId,allowance,stopped)
                    if(scanned.comparisons<allowance)dao.releaseComparisons(prepared.libraryRevision,allowance-scanned.comparisons)
                    candidatesLeft-=scanned.examined
                    if(!scanned.complete&&scanned.examined==0)break
                }
                checkpoint=row.mediaId
                val live=dao.commitProgress(checkpoint);progress(live.completed,live.eligible)
            }
            val final=dao.commitProgress(checkpoint)
            val stored=dao.checkpoint()!!
            return when{final.complete->SimilarityRunOutcome.COMPLETE;final.failures>0&&dao.pendingSafe(null,1).isEmpty()->SimilarityRunOutcome.BLOCKED_BY_FAILURE;stored.comparisons>=SimilarityBudgets.COMPARISONS_PER_TRANCHE->{dao.commitProgress(checkpoint,paused=true);SimilarityRunOutcome.PAUSED_BUDGET};else->SimilarityRunOutcome.MORE_WORK}
        }catch(error:InterruptedException){dao.commitProgress(checkpoint,paused=true);throw error}
        catch(error:Exception){
            val live=dao.progress();dao.saveCheckpoint(SimilarityCheckpoint(checkpointMediaId=checkpoint,completed=live.completed,total=live.eligible,status=SimilarityWorkStatus.ERROR,updatedAt=System.currentTimeMillis(),error=error.javaClass.simpleName.take(80)));throw error
        }
    }
}

class SimilarityWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters){
    override fun doWork():Result{
        val manual=inputData.getBoolean(MANUAL,false);val continuation=inputData.getBoolean(CONTINUATION,false)
        if(!manual&&applicationContext.getSystemService(PowerManager::class.java).currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE)return Result.retry()
        return synchronized(PROCESS_LOCK){runCatching{SimilarityProcessor(MediaDatabase.get(applicationContext),SimilarityFingerprintEngine(applicationContext),{isStopped},{done,total->setProgressAsync(workDataOf(COMPLETED to done,TOTAL to total))},newTranche=manual&&!continuation).run()}.fold({outcome->
            if(outcome==SimilarityRunOutcome.MORE_WORK){val dao=MediaDatabase.get(applicationContext).similarity();if(dao.claimContinuation()>0)enqueue(applicationContext,manual,continuation=true)else dao.commitProgress(dao.checkpoint()?.checkpointMediaId,paused=true)}
            Result.success()
        },{if(it is InterruptedException)Result.retry()else Result.failure(workDataOf(ERROR to it.javaClass.simpleName.take(80)))})}
    }
    companion object{
        private val PROCESS_LOCK=Any()
        private const val MANUAL="manual";private const val CONTINUATION="continuation";private const val COMPLETED="completed";private const val TOTAL="total";private const val ERROR="error";private const val WORK="photo-similarity"
        fun enqueue(context:Context,manual:Boolean=false,continuation:Boolean=false){val constraints=Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).apply{if(!manual)setRequiresCharging(true)}.build();val request=OneTimeWorkRequestBuilder<SimilarityWorker>().setInputData(workDataOf(MANUAL to manual,CONTINUATION to continuation)).setConstraints(constraints).build();val policy=when{continuation->ExistingWorkPolicy.APPEND_OR_REPLACE;manual->ExistingWorkPolicy.REPLACE;else->ExistingWorkPolicy.KEEP};WorkManager.getInstance(context).enqueueUniqueWork(WORK,policy,request)}
        fun pause(context:Context)=WorkManager.getInstance(context).cancelUniqueWork(WORK)
        fun schedule(context:Context){enqueue(context);val constraints=Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build();WorkManager.getInstance(context).enqueueUniquePeriodicWork("$WORK-periodic",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SimilarityWorker>(24,TimeUnit.HOURS).setConstraints(constraints).build())}
    }
}
