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
            CalculatedFingerprint(sha,PerceptualFingerprintV1.fromLuma(width,height,luma))
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

class SimilarityProcessor(
    private val database:MediaDatabase,
    private val engine:FingerprintCalculator,
    private val stopped:()->Boolean,
    private val progress:(Int,Int)->Unit={_,_->},
) {
    fun run() {
        val dao=database.similarity();val total=dao.eligibleCount();var completed=dao.processedCount();var checkpoint:String?=dao.checkpoint()?.checkpointMediaId
        dao.saveCheckpoint(SimilarityCheckpoint(checkpointMediaId=checkpoint,completed=completed,total=total,status=SimilarityWorkStatus.RUNNING,updatedAt=System.currentTimeMillis()))
        try {
            while(true){
                if(stopped())throw InterruptedException("Similarity indexing cancelled")
                // Restart from the first unfinished row. This finds insertions before an old checkpoint.
                val batch=dao.pendingSafe(null,BATCH)
                if(batch.isEmpty())break
                for(row in batch){
                    if(stopped())throw InterruptedException("Similarity indexing cancelled")
                    val token=FingerprintToken(row.mediaId,row.contentRevision,row.accessGrantEpoch)
                    val existing=dao.fingerprint(row.mediaId)
                    if(existing?.let{it.contentRevision==token.contentRevision&&it.accessEpoch==token.accessEpoch&&it.perceptualVersion==PerceptualFingerprintV1.VERSION&&!it.relationsReady}==true){
                        SimilarityRelationBuilder.rebuildFor(database,existing,stopped)
                    }else try{
                        val result=engine.calculate(row,stopped)
                        val record=ContentFingerprintRecord(row.mediaId,row.contentRevision,row.accessGrantEpoch,result.sha256,PerceptualFingerprintV1.VERSION,result.perceptualBits,System.currentTimeMillis())
                        if(dao.publishIfCurrent(record))SimilarityRelationBuilder.rebuildFor(database,record,stopped)
                    }catch(error:InterruptedException){throw error}catch(error:Exception){
                        dao.failIfCurrent(FingerprintFailureRecord(row.mediaId,row.contentRevision,row.accessGrantEpoch,PerceptualFingerprintV1.VERSION,error.javaClass.simpleName.take(80),System.currentTimeMillis()))
                    }
                    checkpoint=row.mediaId;completed=dao.processedCount()
                    val currentTotal=dao.eligibleCount();dao.saveCheckpoint(SimilarityCheckpoint(checkpointMediaId=checkpoint,completed=completed,total=currentTotal,status=SimilarityWorkStatus.RUNNING,updatedAt=System.currentTimeMillis()));progress(completed,currentTotal)
                }
            }
            val finalTotal=dao.eligibleCount();dao.saveCheckpoint(SimilarityCheckpoint(checkpointMediaId=checkpoint,completed=finalTotal,total=finalTotal,status=SimilarityWorkStatus.COMPLETE,updatedAt=System.currentTimeMillis()))
        }catch(error:InterruptedException){
            dao.saveCheckpoint(SimilarityCheckpoint(checkpointMediaId=checkpoint,completed=completed,total=dao.eligibleCount(),status=SimilarityWorkStatus.PAUSED,updatedAt=System.currentTimeMillis()));throw error
        }catch(error:Exception){
            dao.saveCheckpoint(SimilarityCheckpoint(checkpointMediaId=checkpoint,completed=completed,total=dao.eligibleCount(),status=SimilarityWorkStatus.ERROR,updatedAt=System.currentTimeMillis(),error=error.javaClass.simpleName.take(80)));throw error
        }
    }
    companion object{private const val BATCH=8}
}

class SimilarityWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters){
    override fun doWork():Result{
        val manual=inputData.getBoolean(MANUAL,false)
        if(!manual&&applicationContext.getSystemService(PowerManager::class.java).currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE)return Result.retry()
        return runCatching{SimilarityProcessor(MediaDatabase.get(applicationContext),SimilarityFingerprintEngine(applicationContext),{isStopped}){done,total->setProgressAsync(workDataOf(COMPLETED to done,TOTAL to total))}.run()}.fold({Result.success()},{if(it is InterruptedException)Result.retry()else Result.failure(workDataOf(ERROR to it.javaClass.simpleName.take(80)))})
    }
    companion object{
        private const val MANUAL="manual";private const val COMPLETED="completed";private const val TOTAL="total";private const val ERROR="error";private const val WORK="photo-similarity"
        fun enqueue(context:Context,manual:Boolean=false){val constraints=Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).apply{if(!manual)setRequiresCharging(true)}.build();val request=OneTimeWorkRequestBuilder<SimilarityWorker>().setInputData(workDataOf(MANUAL to manual)).setConstraints(constraints).build();WorkManager.getInstance(context).enqueueUniqueWork(WORK,if(manual)ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request)}
        fun pause(context:Context)=WorkManager.getInstance(context).cancelUniqueWork(WORK)
        fun schedule(context:Context){enqueue(context);val constraints=Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build();WorkManager.getInstance(context).enqueueUniquePeriodicWork("$WORK-periodic",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SimilarityWorker>(24,TimeUnit.HOURS).setConstraints(constraints).build())}
    }
}
