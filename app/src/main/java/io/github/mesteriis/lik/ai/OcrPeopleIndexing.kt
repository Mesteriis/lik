package io.github.mesteriis.lik.ai

import android.content.Context
import android.os.PowerManager
import androidx.work.*
import io.github.mesteriis.lik.catalog.MediaAvailability
import io.github.mesteriis.lik.catalog.MediaDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class OcrPeopleIndexWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val profile = inputData.getString(PROFILE)?.let(ProfileId::fromWire) ?: return Result.failure()
        val catalog = ModelCatalog.get(applicationContext)
        val requested = catalog.snapshot().let { it.pending?.takeIf { pending -> pending.profile == profile }?.enabled
            ?: it.enabledFeatures.takeIf { _ -> it.active == profile }.orEmpty() }
        val features = requested.intersect(setOf(AiFeature.OCR, AiFeature.PEOPLE))
        if (features.isEmpty()) return Result.success()
        if (!catalog.trusted.artifacts(profile).all { ArtifactStore(java.io.File(applicationContext.filesDir,"ai")).installed(it.sha256,it.size) })
            return Result.failure(workDataOf(ERROR to "PROFILE_NOT_INSTALLED"))
        if (!inputData.getBoolean(MANUAL,false) && applicationContext.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
            return Result.retry()
        val priority = if (inputData.getBoolean(MANUAL,false)) InferencePriority.MANUAL else InferencePriority.BACKGROUND
        return runCatching { features.sortedBy(Enum<*>::ordinal).forEach { feature ->
            val pipeline = catalog.trusted.profiles.getValue(profile).pipelines.getValue(feature)
            IndexRunCoordinator.run(pipeline.fingerprint,{isStopped}) { prepare(profile,feature,pipeline,catalog,priority) }
        }}.fold({Result.success()},{ error -> if(error is InterruptedException) Result.retry() else Result.failure(workDataOf(ERROR to (error.message?:error.javaClass.simpleName).take(160))) })
    }

    private fun prepare(profile:ProfileId,feature:AiFeature,pipeline:PipelineSpec,catalog:ModelCatalog,priority:InferencePriority){
        val database=MediaDatabase.get(applicationContext);val index=database.aiIndexes();val dao=database.ocrPeople()
        val compatibleSource=(listOf(pipeline.fingerprint)+pipeline.compatibleFingerprints).asSequence().mapNotNull(index::compatible).firstOrNull{it.feature==feature.name}
        val compatible=compatibleSource?.takeIf { dao.currentRunCount(it.generationId)==index.availableCount() }
        if(compatible!=null){publish(catalog,profile,feature,compatible);return}
        val resumed=index.generations().lastOrNull{it.profileId==profile.wire&&it.feature==feature.name&&it.pipelineFingerprint==pipeline.fingerprint&&it.status==GenerationStatus.PREPARING}
        var generation=resumed ?:AiIndexGenerationRecord(UUID.randomUUID().toString(),profile.wire,feature.name,pipeline.fingerprint,GenerationStatus.PREPARING,0,index.availableCount(),null,null,System.currentTimeMillis()).also(index::saveGeneration)
        if(resumed==null&&compatibleSource!=null) database.runInTransaction { dao.copyCurrentGeneration(compatibleSource.generationId,generation.generationId,feature.name,pipeline.fingerprint) }
        val engine=OcrPeopleInferenceEngine(applicationContext);val sensitive=SemanticEmbeddingEngine(applicationContext)
        dao.staleRunIds(generation.generationId).forEach { mediaId ->
            dao.deleteOcr(generation.generationId,mediaId);dao.deleteFaces(generation.generationId,mediaId);dao.deleteRun(generation.generationId,mediaId)
        }
        // Restart the bounded keyset scan and skip valid runs. This also finds media inserted before
        // the old checkpoint after a crash without recomputing unchanged rows.
        var checkpoint:String?=null;var completed=dao.currentRunCount(generation.generationId);var failures=0
        while(true){if(isStopped)throw InterruptedException("INDEX_CANCELLED");val batch=index.mediaBatch(checkpoint,8);if(batch.isEmpty())break
            for(row in batch){if(isStopped)throw InterruptedException("INDEX_CANCELLED")
                val old=dao.run(generation.generationId,row.mediaId)
                if(old?.let{it.contentRevision==row.contentRevision&&it.accessEpoch==row.accessGrantEpoch&&it.error==null}==true){checkpoint=row.mediaId;continue}
                val sensitivePipeline=catalog.trusted.profiles.getValue(profile).pipelines.getValue(AiFeature.SENSITIVE).fingerprint
                if(index.sensitive(row.mediaId,row.contentRevision,sensitivePipeline)?.status!=SensitiveRunStatus.RAW_RESULT){
                    val raw=InferenceGate.run(priority){sensitive.sensitive(row.mediaId,row.contentRevision)}
                    index.saveSensitive(AiSensitiveRunRecord(row.mediaId,row.contentRevision,sensitivePipeline,SensitiveRunStatus.RAW_RESULT,raw.toBytes(),null,System.currentTimeMillis()))
                }
                val token=AiPublicationToken(row.mediaId,row.contentRevision,row.accessGrantEpoch,pipeline.fingerprint,generation.generationId)
                val published=runCatching { when(feature){
                    AiFeature.OCR->{val result=InferenceGate.run(priority){engine.ocr(profile,row.mediaId,row.contentRevision)};val regions=JSONArray(result.regions.map{r->JSONObject().put("points",JSONArray(r.quad.points.flatMap{listOf(it.x,it.y)})).put("left",r.box.left).put("top",r.box.top).put("right",r.box.right).put("bottom",r.box.bottom).put("text",r.text).put("confidence",r.confidence)}).toString()
                        dao.publishOcrRunIfCurrent(AiOcrResultRecord(generation.generationId,row.mediaId,row.contentRevision,row.accessGrantEpoch,pipeline.fingerprint,result.displayText,OcrText.searchKey(result.displayText),regions,result.confidence),AiFeatureMediaRunRecord(generation.generationId,row.mediaId,feature.name,row.contentRevision,row.accessGrantEpoch,null))}
                    AiFeature.PEOPLE->{val faces=InferenceGate.run(priority){engine.people(row.mediaId,row.contentRevision)}.mapIndexed{at,face->val anchor=FaceAnchor.from(row.mediaId,face.box);AiFaceDetectionRecord("${generation.generationId}:${row.mediaId}:$at",generation.generationId,row.mediaId,row.contentRevision,row.accessGrantEpoch,pipeline.fingerprint,anchor,face.box.left,face.box.top,face.box.right,face.box.bottom,face.landmarks.toBytes(),face.embedding.toBytes(),face.confidence,anchor)};dao.publishFacesIfCurrent(token,faces)}
                    else->error("UNSUPPORTED_FEATURE")
                }}
                if(published.isFailure){val fresh=database.media().get(row.mediaId);if(fresh?.availability==MediaAvailability.AVAILABLE&&fresh.contentRevision==row.contentRevision&&fresh.accessGrantEpoch==row.accessGrantEpoch){dao.saveRun(AiFeatureMediaRunRecord(generation.generationId,row.mediaId,feature.name,row.contentRevision,row.accessGrantEpoch,published.exceptionOrNull()?.message?.take(160)));failures++}}
                val fresh=database.media().get(row.mediaId);if(fresh?.availability==MediaAvailability.AVAILABLE&&fresh.contentRevision==row.contentRevision&&fresh.accessGrantEpoch==row.accessGrantEpoch)completed=dao.currentRunCount(generation.generationId)
                checkpoint=row.mediaId;generation=generation.copy(completed=completed,total=index.availableCount(),checkpointMediaId=checkpoint,error=null);index.saveGeneration(generation)
            }
        }
        completed=dao.currentRunCount(generation.generationId);if(failures>0||completed!=index.availableCount()){generation=generation.copy(status=GenerationStatus.ERROR,completed=completed,total=index.availableCount(),error="MEDIA_FAILURES:$failures;COVERAGE:$completed/${index.availableCount()}");index.saveGeneration(generation);error(generation.error!!)}
        if(feature==AiFeature.PEOPLE) recluster(generation.generationId,dao)
        generation=generation.copy(status=GenerationStatus.COMPLETE,completed=completed,total=completed,checkpointMediaId=checkpoint,error=null);index.saveGeneration(generation);publish(catalog,profile,feature,generation)
    }

    private fun recluster(generation:String,dao:OcrPeopleDao){
        val cannot=dao.cannotLinks().map{ManualFacePair.ordered(it.leftAnchorId,it.rightAnchorId)}.toSet()
        FaceClusterer.cluster(dao.faces(generation).map{FaceVector(it.anchorId,it.embedding.toFloats())},FACE_THRESHOLD,cannot).forEach{anchors->dao.setCluster(generation,anchors,"auto:${anchors.first()}")}
    }
    private fun publish(catalog:ModelCatalog,profile:ProfileId,feature:AiFeature,record:AiIndexGenerationRecord){val index=MediaDatabase.get(applicationContext).aiIndexes();var superseded=emptySet<String>();catalog.generationReady(profile,feature,IndexGeneration(record.generationId,feature,record.pipelineFingerprint,true,record.completed,record.total)){next->superseded=index.generations().filter{it.profileId==profile.wire&&it.feature==feature.name&&it.generationId !in next.generations}.map{it.generationId}.toSet();GenerationRetirement.journal(java.io.File(applicationContext.filesDir,"ai"),profile,superseded)};GenerationRetirement.drain(applicationContext,profile,superseded,catalog,MediaDatabase.get(applicationContext))}

    companion object{
        private const val PROFILE="profile";private const val MANUAL="manual";private const val ERROR="error";private const val FACE_THRESHOLD=.363f
        fun enqueue(context:Context,profile:ProfileId,manual:Boolean){val constraints=Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).apply{if(!manual)setRequiresCharging(true)}.build();val request=OneTimeWorkRequestBuilder<OcrPeopleIndexWorker>().setInputData(workDataOf(PROFILE to profile.wire,MANUAL to manual)).setConstraints(constraints).build();WorkManager.getInstance(context).enqueueUniqueWork("ai-ocr-people-${profile.wire}",if(manual)ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request)}
        fun pause(context:Context,profile:ProfileId)=WorkManager.getInstance(context).cancelUniqueWork("ai-ocr-people-${profile.wire}")
    }
}

internal object OcrSafeRead {
    fun text(database:MediaDatabase,generation:String,mediaId:String,beforeFinalCheck:()->Unit={}):AiOcrResultRecord?{val first=database.ocrPeople().visibleOcr(generation,mediaId)?:return null;beforeFinalCheck();return database.ocrPeople().visibleOcr(generation,mediaId)?.takeIf{it==first}}
    fun retainVisible(database:MediaDatabase,generation:String,rows:List<io.github.mesteriis.lik.catalog.MediaRecord>,beforeFinalCheck:()->Unit={}):List<io.github.mesteriis.lik.catalog.MediaRecord>{beforeFinalCheck();return rows.filter{row->database.ocrPeople().visibleOcr(generation,row.mediaId)?.let{it.contentRevision==row.contentRevision&&it.accessEpoch==row.accessGrantEpoch}==true}}
}

class OcrRepository(private val context:Context,private val catalog:ModelCatalog=ModelCatalog.get(context),private val database:MediaDatabase=MediaDatabase.get(context)){
    fun text(mediaId:String,beforeFinalCheck:()->Unit={}):AiOcrResultRecord?{val state=catalog.snapshot();if(AiFeature.OCR !in state.enabledFeatures)return null;val generation=state.activeGenerations[AiFeature.OCR]?:return null;return GenerationUseCoordinator.read(generation){OcrSafeRead.text(database,generation,mediaId,beforeFinalCheck)?.takeIf{catalog.snapshot().activeGenerations[AiFeature.OCR]==generation}}}
    fun search(query:String,limit:Int=60,offset:Int=0,beforeFinalCheck:()->Unit={}):List<io.github.mesteriis.lik.catalog.MediaRecord>{require(query.isNotBlank());val generation=catalog.snapshot().activeGenerations[AiFeature.OCR]?:return emptyList();return GenerationUseCoordinator.read(generation){val rows=database.ocrPeople().searchOcr(generation,OcrText.searchKey(query),limit,offset);val current=OcrSafeRead.retainVisible(database,generation,rows,beforeFinalCheck);current.takeIf{catalog.snapshot().activeGenerations[AiFeature.OCR]==generation}.orEmpty()}}
    fun coverage():OcrCoverage{val generation=catalog.snapshot().activeGenerations[AiFeature.OCR];val dao=database.ocrPeople();val eligible=dao.eligibleCount();return OcrCoverage(generation?.let(dao::currentSafeRunCount)?:0,eligible,eligible>0)}
}
