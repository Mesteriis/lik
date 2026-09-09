package io.github.mesteriis.lik

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.privacy.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveMediaPersistenceTest {
    @Test fun automaticAndManualDecisionsRemainSeparateAndRevisionBound() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val row = MediaRecord(mediaId="private",source=MediaSource.GOOGLE_IMPORT,sourceKey="private",privateFileId="p",
                contentRevision=4,accessGrantEpoch=2,availability=MediaAvailability.AVAILABLE,lastSeenAt=1,sortAt=1)
            db.media().upsert(row)
            val dao = db.sensitiveMedia()
            assertEquals(SensitiveDecision.QUARANTINED, dao.resolved("private",4,2))
            dao.saveAutomatic(SensitiveAutomaticRecord("private",4,2,"classifier-v1",SensitiveDecision.SENSITIVE,byteArrayOf(1),1))
            assertEquals(SensitiveDecision.SENSITIVE, dao.resolved("private",4,2))
            dao.saveManual(SensitiveManualRecord("private",4,SensitiveDecision.SAFE,2))
            assertEquals(SensitiveDecision.SAFE, dao.resolved("private",4,2))
            db.media().upsert(row.copy(contentRevision=5))
            assertEquals(SensitiveDecision.QUARANTINED, dao.resolved("private",5,2))
        } finally { db.close() }
    }

    @Test fun failedOrUnknownClassifierRunCannotPublishSafe() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val row = MediaRecord(mediaId="failed",source=MediaSource.GOOGLE_IMPORT,sourceKey="failed",privateFileId="p",
                contentRevision=1,accessGrantEpoch=1,availability=MediaAvailability.AVAILABLE,lastSeenAt=1,sortAt=1)
            db.media().upsert(row)
            db.sensitiveMedia().saveRun(SensitiveClassifierRunRecord("failed",1,1,"classifier-v1",SensitiveRunOutcome.ERROR,null,"ORT_CRASH",1))
            assertEquals(SensitiveDecision.QUARANTINED, db.sensitiveMedia().resolved("failed",1,1))
        } finally { db.close() }
    }
}
