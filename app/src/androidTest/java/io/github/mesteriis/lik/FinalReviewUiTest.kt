package io.github.mesteriis.lik

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.privacy.*
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FinalReviewUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun buttons(view:View):List<Button> = if(view is ViewGroup) (0 until view.childCount).flatMap{buttons(view.getChildAt(it))} else if(view is Button) listOf(view) else emptyList()

    @Test fun organizationRejectsRowsAndCountsChangedBeforeFinalRender() {
        val db=MediaDatabase.get(context)
        val id="organization-race-${System.nanoTime()}"
        db.media().upsert(MediaRecord(id,MediaSource.DEVICE,id,contentUri="content://invalid",lastSeenAt=1))
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // All organization loaders (ordinary, semantic, similarity and counts) pass through
            // this production boundary. Change policy in its real IO dispatch before UI delivery.
            for(payload in listOf("ordinary rows","semantic hits","similarity pairs","cover counts")) {
                db.ocrPeople().saveExposure(AiMediaExposureRecord(id,0,AiExposure.SAFE,1))
                val done=CountDownLatch(1);val rendered=AtomicBoolean()
                lateinit var panel:OrganizationPanel
                scenario.onActivity { activity ->
                    panel=OrganizationPanel(activity,LinearLayout(activity),{emptySet()},{})
                    val load=panel.javaClass.getDeclaredMethod("load",kotlin.jvm.functions.Function0::class.java,kotlin.jvm.functions.Function1::class.java).apply{isAccessible=true}
                    val work:()->String={db.ocrPeople().saveExposure(AiMediaExposureRecord(id,0,AiExposure.SENSITIVE,2));done.countDown();payload}
                    val render:(String)->Unit={rendered.set(true)}
                    load.invoke(panel,work,render)
                }
                assertTrue(done.await(3,TimeUnit.SECONDS));instrumentation.waitForIdleSync();Thread.sleep(100)
                scenario.onActivity { panel.close() }
                assertFalse("stale $payload reached final render",rendered.get())
            }
        } } finally { db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE mediaId=?",arrayOf(id)) }
    }

    @Test fun relockDismissesProtectedPurgeAndRejectsItsRetainedConfirmation() {
        val bitmap=android.graphics.Bitmap.createBitmap(9,7,android.graphics.Bitmap.Config.ARGB_8888).apply{eraseColor(System.nanoTime().toInt())}
        val bytes=java.io.ByteArrayOutputStream().also{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val db=MediaDatabase.get(context);val store=PhotoLibrary.store(context);val trash=TrashRepository(db,store)
        val id=trash.importPhoto(bytes.inputStream()).photo.id
        trash.trash(setOf(id))
        try { ActivityScenario.launch(MainActivity::class.java).use{scenario->
            val session=SensitiveMediaSession.current
            Thread.sleep(200)
            val request=session.beginAuthentication();assertTrue(session.authenticationSucceeded(request,AuthStrength.STRONG))
            scenario.onActivity{a->a.findViewById<View>(R.id.nav_more).performClick();a.findViewById<View>(R.id.organization_trash).performClick()}
            var clicked=false;val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!clicked&&System.nanoTime()<deadline){scenario.onActivity{a->buttons(a.findViewById(R.id.section_placeholder)).firstOrNull{it.text==context.getString(R.string.purge_photo)}?.let{clicked=it.performClick()}};if(!clicked)Thread.sleep(30)}
            assertTrue(clicked)
            lateinit var confirmation:Button
            instrumentation.runOnMainSync{confirmation=android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull{it.findViewById<Button>(android.R.id.button1)}.first();session.relock(RevealRelockReason.BACKGROUND)}
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync{assertFalse("protected confirmation remained visible",confirmation.isShown);confirmation.performClick()}
            Thread.sleep(150)
            assertEquals(MediaAvailability.TRASHED,db.media().get(id)?.availability)
            assertTrue(store.fileFor(id).isFile)
        }}finally{SensitiveMediaSession.current.relock(RevealRelockReason.EXPLICIT);trash.purgeNow(setOf(id))}
    }
}
