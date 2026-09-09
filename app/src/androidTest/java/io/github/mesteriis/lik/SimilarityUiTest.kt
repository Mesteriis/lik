package io.github.mesteriis.lik

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mesteriis.lik.similarity.ComparisonActivity
import io.github.mesteriis.lik.similarity.ContentFingerprintRecord
import io.github.mesteriis.lik.similarity.PerceptualFingerprintV2
import io.github.mesteriis.lik.ui.MainActivity
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.ai.AiExposure
import io.github.mesteriis.lik.ai.AiMediaExposureRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimilarityUiTest {
    @Test fun moreExposesManualSimilarityIndexAndComparisonEntry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.nav_more).performClick()
            val entry=activity.findViewById<android.view.View>(R.id.organization_similarity);assertNotNull(entry);entry.performClick()
            assertNotNull(activity.findViewById<android.view.View>(R.id.organization_similarity_run))
        }}
    }

    @Test fun comparisonActivityRejectsMissingOrStalePairWithoutShowingPhotos() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent=Intent(context,ComparisonActivity::class.java).putExtra(ComparisonActivity.EXTRA_LEFT,"missing").putExtra(ComparisonActivity.EXTRA_RIGHT,"other")
        ActivityScenario.launch<ComparisonActivity>(intent).use { scenario -> scenario.onActivity { activity ->
            assertEquals(android.view.View.VISIBLE,activity.findViewById<android.view.View>(R.id.comparison_unavailable).visibility)
            assertEquals(android.view.View.GONE,activity.findViewById<android.view.View>(R.id.comparison_content).visibility)
        }}
    }

    @Test fun comparisonIgnoresIndexChurnWithoutDecodeAndImmediatelyClearsOnPrivacyRevoke() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>();val db=MediaDatabase.get(context);val store=io.github.mesteriis.lik.imports.PhotoLibrary.store(context)
        val stored=context.resources.openRawResource(R.drawable.lik_emblem).use{io.github.mesteriis.lik.catalog.TrashRepository(db,store).importPhoto(it)}
        val original=requireNotNull(db.media().get(stored.photo.id));val peerId="ui-similarity-${System.nanoTime()}";val peer=original.copy(mediaId=peerId,sourceKey=peerId,displayName="peer.png")
        db.media().upsert(peer);listOf(original,peer).forEach{row->db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,row.contentRevision,AiExposure.SAFE,1))}
        android.os.SystemClock.sleep(300)
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("photo-similarity").result.get()
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("photo-similarity-periodic").result.get()
        listOf(original,peer).forEach{row->assertTrue("publish ${row.mediaId}",db.similarity().publishIfCurrent(ContentFingerprintRecord(row.mediaId,row.contentRevision,row.accessGrantEpoch,"ui-same",PerceptualFingerprintV2.VERSION,ByteArray(8),1)))}
        assertTrue(store.fileFor(requireNotNull(original.privateFileId)).isFile);assertNotNull(io.github.mesteriis.lik.similarity.SimilarityRepository(context,db).pair(original.mediaId,peer.mediaId))
        val decodes=java.util.concurrent.atomic.AtomicInteger();ComparisonActivity.decodeObserver={decodes.incrementAndGet()}
        try{
            val intent=Intent(context,ComparisonActivity::class.java).putExtra(ComparisonActivity.EXTRA_LEFT,original.mediaId).putExtra(ComparisonActivity.EXTRA_RIGHT,peer.mediaId)
            ActivityScenario.launch<ComparisonActivity>(intent).use{scenario->
                waitUntil(scenario){it.findViewById<android.view.View>(R.id.comparison_content).visibility==android.view.View.VISIBLE}
                assertEquals(2,decodes.get())
                db.similarity().saveCheckpoint(io.github.mesteriis.lik.similarity.SimilarityCheckpoint(checkpointMediaId=null,completed=0,total=2,status=io.github.mesteriis.lik.similarity.SimilarityWorkStatus.RUNNING,updatedAt=System.nanoTime()))
                android.os.SystemClock.sleep(300);assertEquals(2,decodes.get())
                db.ocrPeople().saveExposure(AiMediaExposureRecord(peer.mediaId,peer.contentRevision,AiExposure.SENSITIVE,2))
                waitUntil(scenario){activity->activity.findViewById<android.view.View>(R.id.comparison_content).visibility==android.view.View.GONE&&activity.findViewById<android.widget.TextView>(R.id.comparison_left_details).text.isEmpty()&&activity.findViewById<android.widget.ImageView>(R.id.comparison_left_image).drawable==null}
                assertEquals(2,decodes.get())
            }
        }finally{
            ComparisonActivity.decodeObserver=null
            db.openHelper.writableDatabase.execSQL("DELETE FROM content_fingerprint WHERE mediaId IN (?,?)",arrayOf<Any?>(original.mediaId,peer.mediaId));db.openHelper.writableDatabase.execSQL("DELETE FROM fingerprint_band WHERE mediaId IN (?,?)",arrayOf<Any?>(original.mediaId,peer.mediaId));db.openHelper.writableDatabase.execSQL("DELETE FROM ai_media_exposure WHERE mediaId IN (?,?)",arrayOf<Any?>(original.mediaId,peer.mediaId));db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE mediaId=?",arrayOf<Any?>(peer.mediaId))
        }
    }

    private fun waitUntil(scenario:ActivityScenario<ComparisonActivity>,predicate:(ComparisonActivity)->Boolean){
        repeat(120){var ready=false;scenario.onActivity{ready=predicate(it)};if(ready)return;android.os.SystemClock.sleep(50)}
        fail("Timed out waiting for comparison state")
    }
}
