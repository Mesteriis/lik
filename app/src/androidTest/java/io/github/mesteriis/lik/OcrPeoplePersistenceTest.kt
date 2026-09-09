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
        val repo=PeopleRepository(db);val original=repo.create("Original");faces.forEach{repo.move(it.anchorId,original)};val split=repo.split("g",setOf("a"),original,"Split");assertNotNull(repo.splitSource(split));assertEquals(1,db.ocrPeople().cannotLinks().count{it.splitPersonId==split})
        assertEquals(1,repo.undoSplit(split));assertNull(repo.splitSource(split));assertTrue(db.ocrPeople().cannotLinks().none{it.splitPersonId==split});assertEquals(setOf(original),repo.groups("g").map{it.personId}.toSet())
    }}
}
