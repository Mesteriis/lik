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
    @Test fun manualDecisionCannotReleaseARevisionTheUserDidNotReview() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val row = MediaRecord("revised-manual",MediaSource.DEVICE,"revised-manual",contentRevision=5,lastSeenAt=1,sortAt=1)
            db.media().upsert(row)
            val request = SensitiveMediaSession.current.beginAuthentication()
            assertTrue(SensitiveMediaSession.current.authenticationSucceeded(request,AuthStrength.STRONG))
            val reveal = SensitiveMediaSession.current.snapshot()
            assertFalse(SensitiveMediaRepository(context,db).setManual(row.mediaId,SensitiveDecision.SAFE,reveal,expectedRevision=4))
            assertNull(db.sensitiveMedia().manual(row.mediaId))
        } finally {
            SensitiveMediaSession.current.relock(RevealRelockReason.EXPLICIT)
            db.close()
        }
    }

    @Test fun revisedRevokedTrashedAndMissingRowsRejectLateClassifierPublication() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val row = MediaRecord("late", MediaSource.DEVICE, "late", contentUri="content://test/late",
                contentRevision=4, accessGrantEpoch=2, lastSeenAt=1, sortAt=1)
            val run = SensitiveClassifierRunRecord("late",4,2,"pipeline",SensitiveRunOutcome.RAW_RESULT,byteArrayOf(1),null,1)
            val automatic = SensitiveAutomaticRecord("late",4,2,"pipeline",SensitiveDecision.SAFE,byteArrayOf(1),1)
            val dao = db.sensitiveMedia()
            assertFalse(dao.publishIfCurrent(row,run,automatic))
            listOf(row.copy(contentRevision=5),row.copy(accessGrantEpoch=3),
                row.copy(availability=MediaAvailability.INACCESSIBLE),row.copy(trashedAt=10)).forEach { changed ->
                db.media().upsert(changed)
                assertFalse(dao.publishIfCurrent(row,run,automatic))
                assertNull(dao.run("late",4,2,"pipeline"))
                assertNull(dao.automatic("late",4,2))
            }
        } finally { db.close() }
    }

    @Test fun cancelledUnknownAndFailedRunsRemainQuarantinedAndStaleManualLeaseCannotReleaseThem() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val row = MediaRecord("cancelled",MediaSource.DEVICE,"cancelled",lastSeenAt=1,sortAt=1)
            db.media().upsert(row)
            val request = SensitiveMediaSession.current.beginAuthentication()
            assertTrue(SensitiveMediaSession.current.authenticationSucceeded(request,AuthStrength.STRONG))
            val reveal = SensitiveMediaSession.current.snapshot()
            SensitiveMediaSession.current.relock(RevealRelockReason.SCREEN_LOCK)
            listOf(SensitiveRunOutcome.CANCELLED,SensitiveRunOutcome.UNKNOWN_OUTPUT,SensitiveRunOutcome.ERROR).forEach { outcome ->
                db.sensitiveMedia().saveRun(SensitiveClassifierRunRecord(row.mediaId,0,0,"pipeline",outcome,null,null,1))
                assertEquals(SensitiveDecision.QUARANTINED,db.sensitiveMedia().resolved(row.mediaId,0,0))
                assertFalse(SensitiveMediaRepository(context,db).setManual(row.mediaId,SensitiveDecision.SAFE,reveal))
                assertNull(db.sensitiveMedia().manual(row.mediaId))
            }
        } finally { db.close() }
    }

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
