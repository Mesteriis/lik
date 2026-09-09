package io.github.mesteriis.lik.privacy

import android.content.Context
import androidx.work.*
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.catalog.*

class SensitiveClassifierWorker(context:Context,params:WorkerParameters):Worker(context,params){
    override fun doWork():Result{
        val catalog=ModelCatalog.get(applicationContext)
        val component=catalog.trusted.components.getValue(COMPONENT)
        val pipeline="${component.fingerprint}:${SensitiveMediaPolicy.POLICY_REVISION}"
        val store=ArtifactStore(java.io.File(applicationContext.filesDir,"ai"))
        if(component.artifacts.any{!store.installed(it.sha256,it.size)})return Result.success()
        val database=MediaDatabase.get(applicationContext)
        val dao=database.sensitiveMedia()
        val engine=SemanticEmbeddingEngine(applicationContext)
        val manual=inputData.getBoolean("manual",false)
        val priority=if(manual)InferencePriority.MANUAL else InferencePriority.BACKGROUND
        fun stopped()=isStopped || (!manual && applicationContext.getSystemService(android.os.PowerManager::class.java).currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE)
        return try{
            while(!isStopped){
                val rows=dao.pending(pipeline,16);if(rows.isEmpty())break
                var failed=false
                for(row in rows){
                    if(isStopped)return Result.retry()
                    val now=System.currentTimeMillis()
                    val logits=runCatching{InferenceGate.run(priority,::stopped){engine.sensitive(row.mediaId,row.contentRevision)}}
                    if(stopped() || logits.exceptionOrNull() is InterruptedException) return Result.retry()
                    logits.onSuccess{output->
                        val decision=SensitiveMediaPolicy.fromClassifier(output,releaseThreshold=null)
                        val raw=SensitiveMediaRepository.raw(output)
                        database.runInTransaction{
                            if(dao.publishIfCurrent(row,SensitiveClassifierRunRecord(row.mediaId,row.contentRevision,row.accessGrantEpoch,pipeline,SensitiveRunOutcome.RAW_RESULT,raw,null,now),SensitiveAutomaticRecord(row.mediaId,row.contentRevision,row.accessGrantEpoch,pipeline,decision,raw,now))){
                                val resolved=dao.resolved(row.mediaId,row.contentRevision,row.accessGrantEpoch)
                                val exposure=when(resolved){
                                    SensitiveDecision.SAFE->AiExposure.SAFE
                                    SensitiveDecision.SENSITIVE->AiExposure.SENSITIVE
                                    SensitiveDecision.QUARANTINED->AiExposure.QUARANTINED
                                }
                                database.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,row.contentRevision,exposure,now))
                            }
                        }
                    }.onFailure{failure->dao.publishIfCurrent(row,SensitiveClassifierRunRecord(row.mediaId,row.contentRevision,row.accessGrantEpoch,pipeline,SensitiveRunOutcome.ERROR,null,failure.javaClass.simpleName,now),null);failed=true}
                }
                if(failed)return failedPass()
            }
            Result.success()
        }catch(_:Throwable){failedPass()}
    }

    private fun failedPass():Result {
        if(isStopped || !inputData.getBoolean("catalog-pass",false))return Result.retry()
        // Retry uncertain photos independently. Their failure must not starve already-SAFE
        // rows in downstream indexes, whose own admission and publication remain fail closed.
        enqueue(applicationContext)
        return Result.success()
    }

    companion object{
        const val COMPONENT="sensitive-v1"
        fun enqueue(context:Context,manual:Boolean=false){
            val constraints=Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).apply{if(!manual)setRequiresCharging(true)}.build()
            val request=OneTimeWorkRequestBuilder<SensitiveClassifierWorker>().setInputData(workDataOf("manual" to manual)).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sensitive-classifier",if(manual)ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request)
        }
    }
}
