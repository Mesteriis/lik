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
        val split=repo.split(setOf("anchor"),person,"Аня");assertEquals("Аня",repo.groups("new").single{it.personId==split}.name)
        db.media().trash(setOf("m"),4);assertTrue(repo.groups("new").isEmpty())
        assertEquals(2,db.ocrPeople().decisions().size)
    }}
}
