package io.github.mesteriis.lik

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.privacy.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveBoundaryTest {
    @Test fun feedSearchPeriodsViewerAndAiExcludeQuarantineWhileLocked() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MediaDatabase::class.java).allowMainThreadQueries().build()
        try{
            val safe=row("safe",3);val quarantine=row("quarantine",2);val sensitive=row("sensitive",1)
            listOf(safe,quarantine,sensitive).forEach(db.media()::upsert)
            db.ocrPeople().saveExposure(AiMediaExposureRecord("safe",0,AiExposure.SAFE,1))
            db.ocrPeople().saveExposure(AiMediaExposureRecord("quarantine",0,AiExposure.QUARANTINED,1))
            db.ocrPeople().saveExposure(AiMediaExposureRecord("sensitive",0,AiExposure.SENSITIVE,1))

            assertEquals(listOf("safe"),db.media().visible(false).map(MediaRecord::mediaId))
            assertEquals(setOf("safe","quarantine","sensitive"),db.media().visible(true).map(MediaRecord::mediaId).toSet())
            assertEquals(listOf("safe"),db.organization().search(CatalogSearch().query(includeProtected=false)).map(MediaRecord::mediaId))
            assertEquals(1,db.aiIndexes().aiIndexableCount())
            assertEquals(listOf("safe"),MediaRepository(db).viewerWindow("safe").map(MediaRecord::mediaId))
            assertTrue(MediaRepository(db).viewerWindow("quarantine").isEmpty())
            assertEquals(1,db.media().visibleDayCount("2026-09-09",false))
            assertEquals(3,db.media().visibleDayCount("2026-09-09",true))
        }finally{db.close()}
    }

    @Test fun contentRevisionImmediatelyReturnsManualSafePhotoToQuarantine(){
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MediaDatabase::class.java).allowMainThreadQueries().build()
        try{
            val media=row("revised",1);db.media().upsert(media)
            db.sensitiveMedia().saveManual(SensitiveManualRecord(media.mediaId,0,SensitiveDecision.SAFE,1))
            db.ocrPeople().saveExposure(AiMediaExposureRecord(media.mediaId,0,AiExposure.SAFE,1))
            assertEquals(1,db.media().visible(false).size)
            db.media().upsert(media.copy(contentRevision=1))
            assertTrue(db.media().visible(false).isEmpty())
            assertEquals(SensitiveDecision.QUARANTINED,db.sensitiveMedia().resolved(media.mediaId,1,1))
        }finally{db.close()}
    }

    @Test fun periodAnchorRankUsesTheSameProtectedDomainAsTheFeed(){
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MediaDatabase::class.java).allowMainThreadQueries().build()
        try{
            val newest=row("newest",3,"2026-09")
            val hidden=row("hidden",2,"2026-08")
            val oldest=row("oldest",1,"2026-07")
            listOf(newest,hidden,oldest).forEach(db.media()::upsert)
            listOf(newest,oldest).forEach{db.ocrPeople().saveExposure(AiMediaExposureRecord(it.mediaId,0,AiExposure.SAFE,1))}
            db.ocrPeople().saveExposure(AiMediaExposureRecord(hidden.mediaId,0,AiExposure.QUARANTINED,1))

            assertEquals(1,db.media().visiblePeriodRank("monthKey",hidden.sortAt,false))
            assertEquals(1,db.media().visiblePeriodRank("monthKey",hidden.sortAt,true))
            assertEquals(1,db.media().visiblePeriodRank("monthKey",oldest.sortAt,false))
            assertEquals(2,db.media().visiblePeriodRank("monthKey",oldest.sortAt,true))
        }finally{db.close()}
    }

    private fun row(id:String,sort:Long,month:String="2026-09")=MediaRecord(id,MediaSource.DEVICE,id,contentUri="content://test/$id",lastSeenAt=1,sortAt=sort,
        dayKey="$month-09",weekKey="$month-07",monthKey=month,yearKey=month.substringBefore('-'))
}
