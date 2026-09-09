package io.github.mesteriis.lik

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.catalog.*
import org.junit.Assert.*
import org.junit.Test

class OcrPeoplePersistenceTest {
    private fun fixture(block:(MediaDatabase)->Unit){val context=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(context,MediaDatabase::class.java).build();try{block(db)}finally{db.close()}}

    @Test fun ocrSearchAndViewerReadOnlyExposeCurrentSafeRows(){fixture{db->
        val row=MediaRecord("m",MediaSource.DEVICE,"1",contentUri="content://m",contentRevision=7,lastSeenAt=1)
        db.media().upsert(row);val gen=AiIndexGenerationRecord("ocr","balanced-v1","OCR","pipe",GenerationStatus.COMPLETE,1,1,"m",null,1);db.aiIndexes().saveGeneration(gen)
        val result=AiOcrResultRecord("ocr","m",7,1,"pipe","Ёлка Café","ёлка café","[]",.9f)
        assertTrue(db.ocrPeople().publishOcrRunIfCurrent(result,AiFeatureMediaRunRecord("ocr","m","OCR",7,1,null)))
        assertNull(db.ocrPeople().visibleOcr("ocr","m"));assertTrue(db.ocrPeople().searchOcr("ocr","ёлка",10,0).isEmpty())
        db.ocrPeople().saveExposure(AiMediaExposureRecord("m",7,AiExposure.SAFE,2))
        assertEquals("Ёлка Café",db.ocrPeople().visibleOcr("ocr","m")!!.displayText)
        assertEquals(listOf("m"),db.organization().search(CatalogSearch(ocrText="CAFÉ",ocrGenerationId="ocr").query()).map{it.mediaId})
        db.media().upsert(row.copy(contentRevision=8));assertNull(db.ocrPeople().visibleOcr("ocr","m"))
    }}

    @Test fun manualPeopleIntentSurvivesComputedGenerationDeletionAccessAndTrash(){fixture{db->
        val row=MediaRecord("m",MediaSource.GOOGLE_IMPORT,"1",privateFileId="a".repeat(64),lastSeenAt=1)
        db.media().upsert(row);db.ocrPeople().saveExposure(AiMediaExposureRecord("m",0,AiExposure.SAFE,1))
        listOf("old","new").forEach{generation->db.aiIndexes().saveGeneration(AiIndexGenerationRecord(generation,"balanced-v1","PEOPLE","face",GenerationStatus.COMPLETE,1,1,"m",null,1))}
        val face=AiFaceDetectionRecord("old:d","old","m",0,1,"face","anchor",.1f,.1f,.4f,.4f,FloatArray(10).toBytes(),floatArrayOf(1f,0f).toBytes(),.99f,"auto")
        assertTrue(db.ocrPeople().publishFacesIfCurrent(AiPublicationToken("m",0,1,"face","old"),listOf(face)))
        val repo=PeopleRepository(db);repo.exclude("anchor");assertEquals(listOf("anchor"),repo.excluded("old").map{it.anchorId});assertTrue(repo.groups("old").isEmpty());repo.clear("anchor")
        val person=repo.create("Анна");repo.move("anchor",person);repo.exclude("false")
        val duplicate=repo.create("Anna");repo.merge(duplicate,person);assertEquals(listOf(duplicate),repo.mergedSources(person).map{it.personId});repo.unmerge(duplicate);assertTrue(repo.mergedSources(person).isEmpty())
        db.aiIndexes().deleteGenerations(setOf("old"));db.media().markSource(MediaSource.GOOGLE_IMPORT,MediaAvailability.INACCESSIBLE);db.media().markSeen("m",3)
        val next=face.copy(detectionId="new:d",generationId="new",accessEpoch=2)
        assertTrue(db.ocrPeople().publishFacesIfCurrent(AiPublicationToken("m",0,2,"face","new"),listOf(next)))
        assertEquals("Анна",repo.groups("new").single().name)
        val split=repo.split("new",setOf("anchor"),person,"Аня");assertEquals("Аня",repo.groups("new").single{it.personId==split}.name)
        db.media().trash(setOf("m"),4);assertTrue(repo.groups("new").isEmpty())
        assertEquals(2,db.ocrPeople().decisions().size)
    }}

    @Test fun finalPeopleReadRejectsAccessRace(){fixture{db->
        val row=MediaRecord("race",MediaSource.DEVICE,"1",contentUri="content://race",lastSeenAt=1)
        db.media().upsert(row);db.ocrPeople().saveExposure(AiMediaExposureRecord("race",0,AiExposure.SAFE,1))
        db.aiIndexes().saveGeneration(AiIndexGenerationRecord("people","balanced-v1","PEOPLE","face",GenerationStatus.COMPLETE,1,1,"race",null,1))
        val face=AiFaceDetectionRecord("d","people","race",0,1,"face","a",.1f,.1f,.4f,.4f,FloatArray(10).toBytes(),floatArrayOf(1f,0f).toBytes(),.99f,"auto")
        assertTrue(db.ocrPeople().publishFacesIfCurrent(AiPublicationToken("race",0,1,"face","people"),listOf(face)))
        val groups=PeopleRepository(db).groups("people"){db.media().markSource(MediaSource.DEVICE,MediaAvailability.INACCESSIBLE)}
        assertTrue(groups.isEmpty())
    }}

    @Test fun finalOcrViewerAndSearchReadsRejectRevisionAndExposureRaces(){fixture{db->
        val row=MediaRecord("ocr-race",MediaSource.DEVICE,"1",contentUri="content://ocr-race",contentRevision=3,lastSeenAt=1)
        db.media().upsert(row);db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,3,AiExposure.SAFE,1));db.aiIndexes().saveGeneration(AiIndexGenerationRecord("ocr-race-g","balanced-v1","OCR","pipe",GenerationStatus.COMPLETE,1,1,row.mediaId,null,1))
        val result=AiOcrResultRecord("ocr-race-g",row.mediaId,3,1,"pipe","Ёлка","ёлка","[]",.9f);db.ocrPeople().publishOcrRunIfCurrent(result,AiFeatureMediaRunRecord("ocr-race-g",row.mediaId,"OCR",3,1,null))
        assertNull(OcrSafeRead.text(db,"ocr-race-g",row.mediaId){db.media().upsert(row.copy(contentRevision=4))})
        db.media().upsert(row);val found=db.ocrPeople().searchOcr("ocr-race-g","ёлка",10,0)
        assertTrue(OcrSafeRead.retainVisible(db,"ocr-race-g",found){db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,3,AiExposure.SENSITIVE,2))}.isEmpty())
    }}

    @Test fun uiCallbackGuardRechecksAfterRepositoryReturnAndClearsAllSafeCounts(){fixture{db->
        val row=MediaRecord("ui-race",MediaSource.DEVICE,"1",contentUri="content://ui-race",contentRevision=5,lastSeenAt=1)
        db.media().upsert(row);db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,5,AiExposure.SAFE,1));db.aiIndexes().saveGeneration(AiIndexGenerationRecord("g","balanced-v1","OCR","pipe",GenerationStatus.COMPLETE,1,1,row.mediaId,null,1))
        val result=AiOcrResultRecord("g",row.mediaId,5,1,"pipe","secret","secret","[]",.9f);db.ocrPeople().publishOcrRunIfCurrent(result,AiFeatureMediaRunRecord("g",row.mediaId,"OCR",5,1,null))
        val face=AiFaceDetectionRecord("face","g",row.mediaId,5,1,"face","anchor",.1f,.1f,.4f,.4f,FloatArray(10).toBytes(),floatArrayOf(1f,0f).toBytes(),.9f,"auto")
        db.ocrPeople().saveFace(face);val visibleFace=db.ocrPeople().visibleFaces("g").single()
        val returned=db.ocrPeople().visibleOcr("g",row.mediaId)!!;assertTrue(AiUiPublicationGuard.ocr(db,"g",returned));assertTrue(AiUiPublicationGuard.face(db,"g",visibleFace))
        db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,5,AiExposure.SENSITIVE,2))
        assertFalse(AiUiPublicationGuard.ocr(db,"g",returned));assertFalse(AiUiPublicationGuard.face(db,"g",visibleFace));val coverage=AiUiPublicationGuard.coverage(db,mapOf(AiFeature.OCR to "g"));assertEquals(0,coverage.eligible);assertEquals(0,coverage.ocr)
    }}

    @Test fun atomicRoomCompletionRefreshesMembershipAfterDeletion(){
        val context=ApplicationProvider.getApplicationContext<Context>();val name="atomic-room-${System.nanoTime()}.db";val root=java.io.File(context.cacheDir,"atomic-${System.nanoTime()}").apply{mkdirs()};val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).build()
        val trusted=TrustedModelCatalog.load(context);val catalog=ModelCatalog.openForTests(root,context.getDatabasePath(name),trusted);val pipeline=trusted.profiles.getValue(ProfileId.BALANCED).pipelines.getValue(AiFeature.OCR).fingerprint
        try{
            listOf("a","b").forEach{id->db.media().upsert(MediaRecord(id,MediaSource.DEVICE,id,contentUri="content://$id",lastSeenAt=1))}
            db.aiIndexes().saveGeneration(AiIndexGenerationRecord("atomic","balanced-v1","OCR",pipeline,GenerationStatus.COMPLETE,2,2,"b",null,1));listOf("a","b").forEach{id->db.ocrPeople().saveRun(AiFeatureMediaRunRecord("atomic",id,"OCR",0,1,null))}
            catalog.saveGeneration(IndexGeneration("atomic",AiFeature.OCR,pipeline,true,2,2));db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE mediaId='b'")
            val complete=catalog.completeRoomGeneration(db,ProfileId.BALANCED,AiFeature.OCR,"atomic")!!
            assertEquals(1,complete.record.total);assertEquals(1,db.ocrPeople().storedRunCount("atomic"));assertEquals(1,catalog.snapshot().generations.getValue("atomic").total)
        }finally{catalog.closeForTests();db.close();context.deleteDatabase(name);root.deleteRecursively()}
    }

    @Test fun activationJournalRecoversEveryRoomFileCrashWindowWithoutDeletingServingGeneration(){
        val context=ApplicationProvider.getApplicationContext<Context>();val trusted=TrustedModelCatalog.parse(org.json.JSONObject().apply{
            put("schemaVersion",1);put("delivery","settings-download-from-huggingface");put("catalogVersion","activation-test");put("oracleRevision","oracle");put("defaultProfile",ProfileId.BALANCED.wire);put("components",org.json.JSONArray());put("activationSmokeReferences",org.json.JSONArray())
            put("profiles",org.json.JSONArray(ProfileId.entries.map{id->org.json.JSONObject().put("id",id.wire).put("components",org.json.JSONArray()).put("pipelines",org.json.JSONObject().apply{AiFeature.entries.forEach{feature->put(feature.name.lowercase(),org.json.JSONObject().put("fingerprint","test-${feature.name.lowercase()}"))}})}))
        }.toString())
        val pipeline=trusted.profiles.getValue(ProfileId.COMPACT).pipelines.getValue(AiFeature.OCR).fingerprint
        ActivationCrashPoint.entries.forEach{point->
            val name="activation-${point.name}-${System.nanoTime()}.db";val root=java.io.File(context.cacheDir,"activation-${point.name}-${System.nanoTime()}").apply{mkdirs()}
            var db=Room.databaseBuilder(context,MediaDatabase::class.java,name).build();var catalog=ModelCatalog.openForTests(root,context.getDatabasePath(name),trusted)
            try{
                val media=MediaRecord("m",MediaSource.DEVICE,"1",contentUri="content://m",lastSeenAt=1);db.media().upsert(media)
                listOf("old","new").forEach{id->db.aiIndexes().saveGeneration(AiIndexGenerationRecord(id,if(id=="old")ProfileId.BALANCED.wire else ProfileId.COMPACT.wire,AiFeature.OCR.name,pipeline,if(id=="old")GenerationStatus.COMPLETE else GenerationStatus.PREPARING,1,1,"m",null,1));db.ocrPeople().saveRun(AiFeatureMediaRunRecord(id,"m",AiFeature.OCR.name,0,1,null))}
                val profiles=ProfileId.entries.associateWith{id->ProfileState(when(id){ProfileId.BALANCED->ProfilePhase.ACTIVE;ProfileId.COMPACT->ProfilePhase.PREPARING;else->ProfilePhase.NOT_INSTALLED})}
                val oldGeneration=IndexGeneration("old",AiFeature.OCR,pipeline,true,1,1)
                catalog.seedForTests(CatalogSnapshot(trusted.version,10,ProfileId.COMPACT,ProfileId.BALANCED,profiles,setOf(AiFeature.OCR),PendingProfile(ProfileId.COMPACT,setOf(AiFeature.OCR)),mapOf("old" to oldGeneration),mapOf(AiFeature.OCR to "old"),ProfileId.entries.associateWith{"oracle"}))
                ModelCatalog.activationCrashPointForTests=point
                assertThrows(SimulatedActivationCrash::class.java){catalog.completeRoomGeneration(db,ProfileId.COMPACT,AiFeature.OCR,"new")}
                catalog.closeForTests();db.close();db=Room.databaseBuilder(context,MediaDatabase::class.java,name).build();catalog=ModelCatalog.openForTests(root,context.getDatabasePath(name),trusted)
                val recovered=catalog.snapshot();val expectedNew=point==ActivationCrashPoint.AFTER_ROOM_COMMIT_BEFORE_INTENT_CLEAR
                assertEquals(if(expectedNew)ProfileId.COMPACT else ProfileId.BALANCED,recovered.active)
                assertEquals(if(expectedNew)"new" else "old",recovered.activeGenerations[AiFeature.OCR])
                assertNotNull(db.aiIndexes().generation("old"));assertEquals(GenerationStatus.COMPLETE,db.aiIndexes().generation(recovered.activeGenerations.getValue(AiFeature.OCR))!!.status)
                assertFalse(catalog.activationIntentPresentForTests())
            }finally{ModelCatalog.activationCrashPointForTests=null;catalog.closeForTests();db.close();context.deleteDatabase(name);root.deleteRecursively()}
        }
    }

    @Test fun compatibleGenerationCopiesOnlyCurrentRows(){fixture{db->
        val current=MediaRecord("current",MediaSource.DEVICE,"1",contentUri="content://current",lastSeenAt=1)
        val changed=MediaRecord("changed",MediaSource.DEVICE,"2",contentUri="content://changed",lastSeenAt=1)
        db.media().upsert(current);db.media().upsert(changed)
        listOf("old","new").forEach{db.aiIndexes().saveGeneration(AiIndexGenerationRecord(it,"balanced-v1","OCR","pipe",GenerationStatus.PREPARING,0,2,null,null,1))}
        val dao=db.ocrPeople();listOf(current,changed).forEach{row->dao.publishOcrRunIfCurrent(AiOcrResultRecord("old",row.mediaId,0,1,"pipe",row.mediaId,row.mediaId,"[]",.9f),AiFeatureMediaRunRecord("old",row.mediaId,"OCR",0,1,null))}
        db.media().upsert(changed.copy(contentRevision=2))
        assertEquals(1,dao.copyCurrentGeneration("old","new","OCR","pipe"));assertNotNull(dao.run("new","current"));assertNull(dao.run("new","changed"))
    }}

    @Test fun undoSplitRemovesOnlyItsCannotLinksAndSurvivesRecluster(){fixture{db->
        val row=MediaRecord("m",MediaSource.DEVICE,"1",contentUri="content://m",lastSeenAt=1);db.media().upsert(row);db.ocrPeople().saveExposure(AiMediaExposureRecord("m",0,AiExposure.SAFE,1));db.aiIndexes().saveGeneration(AiIndexGenerationRecord("g","balanced-v1","PEOPLE","face",GenerationStatus.COMPLETE,1,1,"m",null,1))
        val faces=listOf("a","b").mapIndexed{i,a->AiFaceDetectionRecord("d$i","g","m",0,1,"face",a,.1f+i*.2f,.1f,.2f+i*.2f,.3f,FloatArray(10).toBytes(),floatArrayOf(1f,0f).toBytes(),.99f,"original")};db.ocrPeople().publishFacesIfCurrent(AiPublicationToken("m",0,1,"face","g"),faces)
        val repo=PeopleRepository(db);val original=repo.create("Original");faces.forEach{repo.move(it.anchorId,original)};repo.manualCannotLink("a","b");val split=repo.split("g",setOf("a"),original,"Split");assertNotNull(repo.splitSource(split));assertEquals(2,db.ocrPeople().cannotLinkOwnerCount("a","b"))
        assertEquals(1,repo.undoSplit(split));assertNull(repo.splitSource(split));assertEquals(1,db.ocrPeople().cannotLinkOwnerCount("a","b"));assertEquals(1,db.ocrPeople().cannotLinks().size)
    }}

    @Test fun namedComputedGroupKeepsStableIdentityWhenItsFirstAnchorDisappears(){fixture{db->
        val row=MediaRecord("m",MediaSource.DEVICE,"1",contentUri="content://m",lastSeenAt=1);db.media().upsert(row);db.ocrPeople().saveExposure(AiMediaExposureRecord("m",0,AiExposure.SAFE,1))
        listOf("g1","g2").forEach{db.aiIndexes().saveGeneration(AiIndexGenerationRecord(it,"balanced-v1","PEOPLE","face",GenerationStatus.COMPLETE,1,1,"m",null,1))}
        fun face(id:String,g:String,anchor:String,cluster:String)=AiFaceDetectionRecord(id,g,"m",0,1,"face",anchor,.1f,.1f,.4f,.4f,FloatArray(10).toBytes(),floatArrayOf(1f,0f).toBytes(),.99f,cluster)
        db.ocrPeople().publishFacesIfCurrent(AiPublicationToken("m",0,1,"face","g1"),listOf(face("a","g1","a-old","auto:a-old"),face("b","g1","b-old","auto:a-old")))
        val repo=PeopleRepository(db);val stable=repo.name("g1","auto:a-old","Анна");assertNotEquals("auto:a-old",stable)
        db.aiIndexes().deleteGenerations(setOf("g1"));db.ocrPeople().publishFacesIfCurrent(AiPublicationToken("m",0,1,"face","g2"),listOf(face("n","g2","0-new","auto:0-new"),face("b2","g2","b-old","auto:0-new")))
        assertEquals(stable,repo.groups("g2").single().personId);assertEquals("Анна",repo.groups("g2").single().name)
    }}
}
