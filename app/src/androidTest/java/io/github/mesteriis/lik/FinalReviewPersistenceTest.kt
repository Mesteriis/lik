package io.github.mesteriis.lik

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.catalog.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class FinalReviewPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun faceClusteringQueriesUseIndexedSeekAndBoundedAnchorUpdates() {
        val captured=java.util.concurrent.atomic.AtomicReference<Pair<String,List<Any?>>>()
        val db=Room.inMemoryDatabaseBuilder(context,MediaDatabase::class.java).setQueryCallback({sql,args->
            if(sql.startsWith("SELECT * FROM ai_face_detection")||sql.startsWith("UPDATE ai_face_detection SET computedClusterId"))
                captured.set(sql to args.toList())
        },java.util.concurrent.Executor{it.run()}).build()
        try {
            fun plan():String {
                val (sql,args)=captured.get()
                return db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql",args.toTypedArray()).use{cursor->
                    buildList{while(cursor.moveToNext())add(cursor.getString(3))}.joinToString(";")
                }
            }
            db.ocrPeople().faceBatch("generation","after",128);val page=plan()
            db.ocrPeople().setCluster("generation",listOf("a","b","c"),"cluster");val update=plan()
            assertTrue("Page must seek after its key, without sorting: $page; anchor update must be indexed: $update",
                page.contains("generationId=? AND detectionId>?")&&!page.contains("TEMP B-TREE")&&
                    update.contains("generationId=? AND anchorId=?"))
        }finally{db.close()}
    }

    @Test fun eligibilityChangesDuringRunningWorkAppendOneCoalescedSuccessor() {
        val manager=androidx.work.WorkManager.getInstance(context)
        val catalog=ModelCatalog.get(context);val before=catalog.snapshot();val db=MediaDatabase.get(context)
        val id="coalesced-${System.nanoTime()}"
        ReviewPipelineBlocker.entered=CountDownLatch(1);ReviewPipelineBlocker.release=CountDownLatch(1)
        try {
            val initial=manager.getWorkInfosForUniqueWork("ai-catalog-pipeline").get(3,TimeUnit.SECONDS).map{it.id}.toSet()
            db.media().upsert(MediaRecord(id,MediaSource.DEVICE,id,contentUri="content://invalid",lastSeenAt=1))
            catalog.seedForTests(CatalogSnapshot.readyForTest(ProfileId.BALANCED).copy(catalogVersion=catalog.trusted.version,
                enabledFeatures=setOf(AiFeature.SEARCH,AiFeature.OCR,AiFeature.PEOPLE)))
            // Establish the opt-in plan before measuring this test's subsequent eligibility burst.
            // A scheduler enqueue from preceding fixtures must not count as part of that burst.
            val readyDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            var prepared=false
            while(!prepared&&System.nanoTime()<readyDeadline){
                val tags=manager.getWorkInfosForUniqueWork("ai-catalog-pipeline").get(3,TimeUnit.SECONDS)
                    .filter{it.id !in initial}.flatMap{it.tags}
                prepared=tags.any{it.endsWith("AiIndexWorker")}&&tags.any{it.endsWith("OcrPeopleIndexWorker")}
                if(!prepared)Thread.sleep(30)
            }
            assertTrue("Opt-in setup must be durably scheduled before the measured burst",prepared)
            manager.beginUniqueWork("ai-catalog-pipeline",androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<ReviewPipelineBlocker>().build()).enqueue().result.get(3,TimeUnit.SECONDS)
            assertTrue("fixture worker must actually be running",ReviewPipelineBlocker.entered.await(3,TimeUnit.SECONDS))
            val existing=manager.getWorkInfosForUniqueWork("ai-catalog-pipeline").get(3,TimeUnit.SECONDS).map{it.id}.toSet()
            db.runInTransaction { repeat(20){at->db.ocrPeople().saveExposure(AiMediaExposureRecord(id,0,if(at%2==0)AiExposure.SENSITIVE else AiExposure.SAFE,at.toLong()))} }
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
            var added=emptyList<androidx.work.WorkInfo>()
            while(System.nanoTime()<deadline){
                added=manager.getWorkInfosForUniqueWork("ai-catalog-pipeline").get(3,TimeUnit.SECONDS).filter{it.id !in existing}
                if(added.size>=3)break
                Thread.sleep(30)
            }
            assertEquals("burst must yield one screening/search/OCR-People successor",3,added.size)
            assertTrue(added.all{it.state==androidx.work.WorkInfo.State.BLOCKED})
            assertEquals(1L,ReviewPipelineBlocker.release.count)
        }finally{
            catalog.seedForTests(before);manager.cancelUniqueWork("ai-catalog-pipeline").result.get(3,TimeUnit.SECONDS)
            ReviewPipelineBlocker.release.countDown();db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE mediaId=?",arrayOf(id))
        }
    }

    @Test fun failedPredecessorDoesNotLoseAlreadyAppendedCatalogRevision() {
        val manager=androidx.work.WorkManager.getInstance(context)
        val catalog=ModelCatalog.get(context);val before=catalog.snapshot();val db=MediaDatabase.get(context)
        val id="failed-predecessor-${System.nanoTime()}"
        ReviewFailingPipelineBlocker.entered=CountDownLatch(1);ReviewFailingPipelineBlocker.release=CountDownLatch(1)
        try {
            val initial=manager.getWorkInfosForUniqueWork("ai-catalog-pipeline").get(3,TimeUnit.SECONDS).map{it.id}.toSet()
            catalog.seedForTests(CatalogSnapshot.readyForTest(ProfileId.BALANCED).copy(catalogVersion=catalog.trusted.version,
                enabledFeatures=setOf(AiFeature.SEARCH,AiFeature.OCR,AiFeature.PEOPLE)))
            db.media().upsert(MediaRecord(id,MediaSource.DEVICE,id,contentUri="content://invalid",lastSeenAt=1))
            val setupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            var setupTags=emptyList<String>()
            while(System.nanoTime()<setupDeadline){
                setupTags=manager.getWorkInfosForUniqueWork("ai-catalog-pipeline").get(3,TimeUnit.SECONDS)
                    .filter{it.id !in initial}.flatMap{it.tags}
                if(setupTags.any{it.endsWith("AiIndexWorker")}&&setupTags.any{it.endsWith("OcrPeopleIndexWorker")})break
                Thread.sleep(30)
            }
            assertTrue("opt-in setup must be durably scheduled before failure fixture",setupTags.any{it.endsWith("AiIndexWorker")}&&setupTags.any{it.endsWith("OcrPeopleIndexWorker")})
            val blocker=androidx.work.OneTimeWorkRequestBuilder<ReviewFailingPipelineBlocker>().build()
            manager.beginUniqueWork("ai-catalog-pipeline",androidx.work.ExistingWorkPolicy.REPLACE,
                blocker).enqueue().result.get(3,TimeUnit.SECONDS)
            assertTrue("fixture worker must actually be running",ReviewFailingPipelineBlocker.entered.await(3,TimeUnit.SECONDS))
            assertEquals(androidx.work.WorkInfo.State.RUNNING,manager.getWorkInfoById(blocker.id).get(3,TimeUnit.SECONDS)?.state)
            val predecessorIds=manager.getWorkInfosForUniqueWork("ai-catalog-pipeline").get(3,TimeUnit.SECONDS).map{it.id}.toSet()
            db.ocrPeople().saveExposure(AiMediaExposureRecord(id,0,AiExposure.SAFE,1))
            val appendDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            var appended=emptyList<androidx.work.WorkInfo>()
            while(System.nanoTime()<appendDeadline){
                appended=manager.getWorkInfosForUniqueWork("ai-catalog-pipeline").get(3,TimeUnit.SECONDS).filter{it.id !in predecessorIds}
                if(appended.size>=3)break
                Thread.sleep(30)
            }
            assertEquals("catalog revision must be durably appended before predecessor fails",3,appended.size)
            assertTrue("successor must still depend on the running predecessor",appended.all{it.state==androidx.work.WorkInfo.State.BLOCKED})
            ReviewFailingPipelineBlocker.release.countDown()
            val failedIds=predecessorIds+appended.map{it.id}
            val repairDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6)
            var repaired=emptyList<androidx.work.WorkInfo>()
            while(System.nanoTime()<repairDeadline){
                repaired=manager.getWorkInfosForUniqueWork("ai-catalog-pipeline").get(3,TimeUnit.SECONDS).filter{it.id !in failedIds}
                if(repaired.flatMap{it.tags}.any{it.endsWith("SensitiveClassifierWorker")})break
                Thread.sleep(30)
            }
            val repairedTags=repaired.flatMap{it.tags}
            assertTrue("failed predecessor must trigger a replacement screening pass",repairedTags.any{it.endsWith("SensitiveClassifierWorker")})
            assertTrue("replacement must retain semantic indexing",repairedTags.any{it.endsWith("AiIndexWorker")})
            assertTrue("replacement must retain OCR/People indexing",repairedTags.any{it.endsWith("OcrPeopleIndexWorker")})
        }finally{
            catalog.seedForTests(before);manager.cancelUniqueWork("ai-catalog-pipeline").result.get(3,TimeUnit.SECONDS)
            ReviewFailingPipelineBlocker.release.countDown();db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE mediaId=?",arrayOf(id))
        }
    }

    @Test fun finalPeoplePublicationRejectsSameCountEligibilityAba() {
        val name="cluster-publication-${System.nanoTime()}.db"
        val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addCallback(MediaDatabase.SIMILARITY_CALLBACK).build()
        val root=java.io.File(context.cacheDir,"cluster-publication-${System.nanoTime()}").apply{mkdirs()}
        val catalog=ModelCatalog.openForTests(root,context.getDatabasePath(name),TrustedModelCatalog.load(context))
        try {
            val id="face-media"
            db.media().upsert(MediaRecord(id,MediaSource.DEVICE,id,contentUri="content://invalid",lastSeenAt=1))
            db.ocrPeople().saveExposure(AiMediaExposureRecord(id,0,AiExposure.SAFE,1))
            val pipeline=catalog.trusted.profiles.getValue(ProfileId.BALANCED).pipelines.getValue(AiFeature.PEOPLE).fingerprint
            db.aiIndexes().saveGeneration(AiIndexGenerationRecord("faces","balanced-v1","PEOPLE",pipeline,GenerationStatus.PREPARING,1,1,id,null,1))
            db.ocrPeople().saveRun(AiFeatureMediaRunRecord("faces",id,"PEOPLE",0,1,null))
            val before=CatalogChanges.revision(db)
            db.ocrPeople().saveExposure(AiMediaExposureRecord(id,0,AiExposure.SENSITIVE,2))
            db.ocrPeople().saveExposure(AiMediaExposureRecord(id,0,AiExposure.SAFE,3))
            assertNull(catalog.completeRoomGeneration(db,ProfileId.BALANCED,AiFeature.PEOPLE,"faces",before))
            assertEquals(GenerationStatus.PREPARING,db.aiIndexes().generation("faces")!!.status)
            assertNotNull(catalog.completeRoomGeneration(db,ProfileId.BALANCED,AiFeature.PEOPLE,"faces",CatalogChanges.revision(db)))
        }finally{catalog.closeForTests();db.close();context.deleteDatabase(name);root.deleteRecursively()}
    }

    @Test fun queuedOcrAndPeopleRecheckSafeAtActualAdmission() {
        val db=MediaDatabase.get(context);val id="queued-eligibility-${System.nanoTime()}"
        db.media().upsert(MediaRecord(id,MediaSource.DEVICE,id,contentUri="content://invalid",lastSeenAt=1))
        val pool=Executors.newFixedThreadPool(2)
        try {
            for(ocr in listOf(true,false)) {
                db.ocrPeople().saveExposure(AiMediaExposureRecord(id,0,AiExposure.SAFE,1))
                val entered=CountDownLatch(1);val release=CountDownLatch(1);val queued=CountDownLatch(1)
                pool.submit { InferenceGate.run(InferencePriority.BACKGROUND){entered.countDown();release.await(3,TimeUnit.SECONDS)} }
                assertTrue(entered.await(1,TimeUnit.SECONDS))
                val result=pool.submit<String?>{
                    queued.countDown()
                    runCatching { InferenceGate.run(InferencePriority.BACKGROUND){
                        val engine=OcrPeopleInferenceEngine(context)
                        if(ocr)engine.ocr(ProfileId.BALANCED,id,0,1) else engine.people(id,0,1)
                    }}.exceptionOrNull()?.message
                }
                assertTrue(queued.await(1,TimeUnit.SECONDS))
                db.ocrPeople().saveExposure(AiMediaExposureRecord(id,0,AiExposure.SENSITIVE,2))
                release.countDown()
                assertEquals("PHOTO_NOT_AI_ELIGIBLE",result.get(3,TimeUnit.SECONDS))
            }
        }finally{pool.shutdownNow();db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE mediaId=?",arrayOf(id))}
    }

    @Test fun catalogInsertSchedulesScreeningAndEveryEnabledIndexAfterOptIn() {
        val catalog=ModelCatalog.get(context);val before=catalog.snapshot()
        val manager=androidx.work.WorkManager.getInstance(context)
        manager.cancelAllWorkByTag("lik-catalog-ai").result.get(3,TimeUnit.SECONDS)
        val db=MediaDatabase.get(context);val id="catalog-enqueue-${System.nanoTime()}"
        try {
            catalog.seedForTests(CatalogSnapshot.readyForTest(ProfileId.BALANCED).copy(
                catalogVersion=catalog.trusted.version,enabledFeatures=setOf(AiFeature.SEARCH,AiFeature.OCR,AiFeature.PEOPLE)))
            db.media().upsert(MediaRecord(id,MediaSource.DEVICE,id,contentUri="content://invalid",lastSeenAt=1))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            var tags=emptySet<String>()
            while(System.nanoTime()<deadline){
                tags=manager.getWorkInfosByTag("lik-catalog-ai").get(3,TimeUnit.SECONDS).flatMap{it.tags}.toSet()
                if(tags.any{it.endsWith("AiIndexWorker")}&&tags.any{it.endsWith("OcrPeopleIndexWorker")}&&tags.any{it.endsWith("SensitiveClassifierWorker")})break
                Thread.sleep(30)
            }
            assertTrue("screening not enqueued on MediaStore/catalog change",tags.any{it.endsWith("SensitiveClassifierWorker")})
            assertTrue("semantic index not enqueued",tags.any{it.endsWith("AiIndexWorker")})
            assertTrue("OCR/People index not enqueued",tags.any{it.endsWith("OcrPeopleIndexWorker")})
        }finally{catalog.seedForTests(before);manager.cancelAllWorkByTag("lik-catalog-ai").result.get(3,TimeUnit.SECONDS);db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE mediaId=?",arrayOf(id))}
    }

    @Test fun revokedSafeExposureRejectsOcrAndFacesAtCommit() {
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        try {
            val row = MediaRecord("privacy-race", MediaSource.DEVICE, "1", contentUri="content://invalid", lastSeenAt=1)
            db.media().upsert(row)
            db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,0,AiExposure.SAFE,1))
            db.aiIndexes().saveGeneration(AiIndexGenerationRecord("g","balanced-v1","OCR","pipe",GenerationStatus.PREPARING,0,1,null,null,1))
            val fetched = db.aiIndexes().aiIndexableMediaBatch(null, 8).single()
            db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,0,AiExposure.SENSITIVE,2))
            val ocr = AiOcrResultRecord("g",fetched.mediaId,0,1,"pipe","private","private","[]",1f)
            assertFalse(db.ocrPeople().publishOcrRunIfCurrent(ocr,AiFeatureMediaRunRecord("g",row.mediaId,"OCR",0,1,null)))
            assertFalse(db.ocrPeople().publishOcrIfCurrent(ocr))
            assertFalse(db.ocrPeople().publishFacesIfCurrent(AiPublicationToken(row.mediaId,0,1,"pipe","g"),emptyList()))
            assertNull(db.ocrPeople().run("g",row.mediaId))
        } finally { db.close() }
    }

    @Test fun revokedSafeExposureRejectsInferenceBeforeOpeningPhoto() {
        val db = MediaDatabase.get(context)
        val row = MediaRecord("admission-${System.nanoTime()}",MediaSource.DEVICE,"1",contentUri="content://invalid",lastSeenAt=1)
        db.media().upsert(row)
        try {
            db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,0,AiExposure.SAFE,1))
            val fetched = db.aiIndexes().aiIndexableMediaBatch(null,1000).first { it.mediaId == row.mediaId }
            db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,0,AiExposure.SENSITIVE,2))
            val engine = OcrPeopleInferenceEngine(context)
            listOf<() -> Any>({ engine.ocr(ProfileId.BALANCED,fetched.mediaId,0) }, { engine.people(fetched.mediaId,0) }).forEach { action ->
                assertEquals("PHOTO_NOT_AI_ELIGIBLE", runCatching(action).exceptionOrNull()?.message)
            }
        } finally { db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE mediaId=?",arrayOf(row.mediaId)) }
    }

    @Test fun cancellationRecoveryAndRemovalWaitInTheSamePipelineOrder() {
        val catalog = ModelCatalog.get(context)
        val profile = ProfileId.BALANCED
        val keys = catalog.trusted.profiles.getValue(profile).pipelines.values.map { it.fingerprint }.distinct().sorted()
        // Holding the first ordered lock must prevent every maintenance operation from taking
        // a later one. This catches the previous SEARCH -> OCR -> PEOPLE cancellation order.
        val first = keys.first(); val last = keys.last()
        IndexRunCoordinator.run(last,{false}) {}
        val field = IndexRunCoordinator.javaClass.getDeclaredField("locks").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST") val locks = field.get(IndexRunCoordinator) as MutableMap<String,ReentrantLock>
        val pool = Executors.newSingleThreadExecutor()
        try {
            listOf<() -> Unit>(
                { AiIndexWorker.discardPreparation(context,profile) },
                { GenerationRemovalJournal(java.io.File(context.filesDir,"ai")).begin(profile,setOf("review-no-generation")); ModelMaintenance.recover(context) },
                { ModelMaintenance.removeInactive(context,profile) },
            ).forEach { operation ->
                val started=CountDownLatch(1)
                lateinit var worker: Thread
                IndexRunCoordinator.run(first,{false}) {
                    val future=pool.submit { worker=Thread.currentThread();started.countDown();operation() }
                    assertTrue(started.await(2,TimeUnit.SECONDS))
                    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                    while(worker.state!=Thread.State.WAITING && System.nanoTime()<deadline) Thread.sleep(5)
                    val lastLock=synchronized(locks){locks.getValue(last)}
                    val available=lastLock.tryLock()
                    if(available) lastLock.unlock()
                    assertTrue("operation acquired later pipeline before first",available)
                    // Do not wait for the operation while holding its prerequisite lock.
                    assertFalse(future.isDone)
                }
                pool.submit {}.get(10,TimeUnit.SECONDS)
            }
        } finally { pool.shutdownNow() }
    }
}

class ReviewPipelineBlocker(context:Context,parameters:androidx.work.WorkerParameters):androidx.work.Worker(context,parameters) {
    override fun doWork():Result { entered.countDown();release.await(10,TimeUnit.SECONDS);return Result.success() }
    companion object { var entered=CountDownLatch(1);var release=CountDownLatch(1) }
}

class ReviewFailingPipelineBlocker(context:Context,parameters:androidx.work.WorkerParameters):androidx.work.Worker(context,parameters) {
    override fun doWork():Result { entered.countDown();release.await(10,TimeUnit.SECONDS);return Result.failure() }
    companion object { var entered=CountDownLatch(1);var release=CountDownLatch(1) }
}
