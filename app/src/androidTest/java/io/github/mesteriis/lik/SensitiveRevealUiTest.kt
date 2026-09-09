package io.github.mesteriis.lik

import android.view.WindowManager
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.privacy.AuthStrength
import io.github.mesteriis.lik.privacy.RevealRelockReason
import io.github.mesteriis.lik.privacy.SensitiveMediaSession
import io.github.mesteriis.lik.ui.MainActivity
import io.github.mesteriis.lik.gallery.PhotoViewerActivity
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.TrashRepository
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveRevealUiTest {
    @After fun lockAfterTest() = SensitiveMediaSession.current.relock(RevealRelockReason.EXPLICIT)

    @Test fun relockAfterSharePreparationPreventsChooserPublication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.READ_MEDIA_IMAGES)
        val bitmap = Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        bitmap.recycle()
        val photo = TrashRepository(MediaDatabase.get(context),PhotoLibrary.store(context)).importPhoto(bytes.inputStream()).photo
        val exports = java.io.File(context.cacheDir,"prepared_exports")
        val before = exports.listFiles().orEmpty().map { it.name }.toSet()
        val monitor = instrumentation.addMonitor(android.content.IntentFilter(android.content.Intent.ACTION_CHOOSER),
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val request = SensitiveMediaSession.current.beginAuthentication()
                assertTrue(SensitiveMediaSession.current.authenticationSucceeded(request,AuthStrength.STRONG))
                var ready = false
                val deadline = System.nanoTime()+5_000_000_000
                while(!ready&&System.nanoTime()<deadline) {
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.photo_timeline)
                        val adapter = list.adapter as io.github.mesteriis.lik.ui.TimelineAdapter
                        val position = adapter.positionForPhoto(photo.id)
                        val holder = list.findViewHolderForAdapterPosition(position)
                        if(holder!=null) { holder.itemView.performLongClick();ready=true }
                    }
                    if(!ready)Thread.sleep(20)
                }
                assertTrue(ready)
                scenario.onActivity { activity ->
                    activity.findViewById<android.view.View>(R.id.share_selected).performClick()
                    // Hold UI delivery until a background-prepared snapshot exists, then relock.
                    val preparedDeadline = System.nanoTime()+5_000_000_000
                    while(exports.listFiles().orEmpty().none { it.name !in before && it.extension!="part" }&&System.nanoTime()<preparedDeadline)Thread.sleep(10)
                    assertTrue(exports.listFiles().orEmpty().any { it.name !in before && it.extension!="part" })
                    Thread.sleep(100)
                    SensitiveMediaSession.current.relock(RevealRelockReason.SCREEN_LOCK)
                }
                instrumentation.waitForIdleSync()
                assertTrue("A stale prepared share must not open the chooser",monitor.hits==0)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            PhotoLibrary.store(context).fileFor(photo.id).delete()
            exports.listFiles().orEmpty().filter { it.name !in before }.forEach(java.io.File::delete)
        }
    }

    @Test fun viewerManualSafeActionPersistsWithoutMainThreadDatabaseAccess() = withProtectedViewer { scenario, id ->
        scenario.onActivity { it.findViewById<android.view.View>(R.id.viewer_mark_safe).performClick() }
        val db = MediaDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val deadline = System.nanoTime() + 5_000_000_000
        while (db.sensitiveMedia().manual(id) == null && System.nanoTime() < deadline) Thread.sleep(20)
        org.junit.Assert.assertEquals(io.github.mesteriis.lik.privacy.SensitiveDecision.SAFE, db.sensitiveMedia().manual(id)?.decision)
    }

    @Test fun viewerRouterConsentDialogOpensWithoutMainThreadDatabaseAccess() = withProtectedViewer { scenario, _ ->
        val settings = io.github.mesteriis.lik.aigate.AiGateSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        val old = settings.enabled
        try {
            settings.enabled = true
            scenario.onActivity { it.findViewById<android.view.View>(R.id.viewer_aigate).performClick() }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            val title = instrumentation.targetContext.getString(R.string.aigate_send_title)
            val deadline=System.nanoTime()+5_000_000_000
            var shown=false
            while(!shown&&System.nanoTime()<deadline){
                shown=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title)?.isNotEmpty()==true
                if(!shown)Thread.sleep(20)
            }
            assertTrue("Consent dialog must be visible before dismissal",shown)
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        } finally { settings.enabled = old }
    }

    private fun withProtectedViewer(action: (ActivityScenario<PhotoViewerActivity>, String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE);setPixel(0,0,0xff000000.toInt() or System.nanoTime().toInt())
        }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val db=MediaDatabase.get(context)
        val trash=TrashRepository(db,PhotoLibrary.store(context))
        val photo = trash.importPhoto(bytes.inputStream()).photo
        // Finish the prior scenario's queued background relock and this import's invalidations
        // before injecting a new authentication result. Never weaken production relock timing.
        db.invalidationTracker.refreshVersionsSync()
        instrumentation.waitForIdleSync()
        val request = SensitiveMediaSession.current.beginAuthentication()
        assertTrue(SensitiveMediaSession.current.authenticationSucceeded(request, AuthStrength.STRONG))
        try {
            ActivityScenario.launch<PhotoViewerActivity>(android.content.Intent(context, PhotoViewerActivity::class.java)
                .putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, photo.id)).use { scenario ->
                val deadline = System.nanoTime() + 5_000_000_000
                var ready = false
                while (!ready && System.nanoTime() < deadline) {
                    scenario.onActivity { ready = it.findViewById<ImageView>(R.id.viewer_image).drawable != null }
                    if (!ready) Thread.sleep(20)
                }
                assertTrue(ready)
                action(scenario, photo.id)
            }
        } finally { trash.trash(setOf(photo.id));trash.purgeNow(setOf(photo.id)) }
    }

    @Test fun strongRevealSurvivesRecreationButRelocksWhenAppLeavesForeground() {
        SensitiveMediaSession.current.relock(RevealRelockReason.EXPLICIT)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName,android.Manifest.permission.READ_MEDIA_IMAGES)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val request = SensitiveMediaSession.current.beginAuthentication()
            assertTrue(SensitiveMediaSession.current.authenticationSucceeded(request, AuthStrength.STRONG))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }

            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertTrue(SensitiveMediaSession.current.snapshot().revealed)

            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.waitForIdleSync()
            assertFalse(SensitiveMediaSession.current.snapshot().revealed)
        }
    }

    @Test fun weakOrCredentialResultCannotOpenTheRevealLease() {
        var request = SensitiveMediaSession.current.beginAuthentication()
        assertFalse(SensitiveMediaSession.current.authenticationSucceeded(request, AuthStrength.WEAK))
        assertFalse(SensitiveMediaSession.current.snapshot().revealed)
        request = SensitiveMediaSession.current.beginAuthentication()
        assertFalse(SensitiveMediaSession.current.authenticationSucceeded(request, AuthStrength.DEVICE_CREDENTIAL))
        assertFalse(SensitiveMediaSession.current.snapshot().revealed)
    }

    @Test fun relockClearsAProtectedViewerBeforeAStaleDecodeCanPublish() = withProtectedViewer { scenario, _ ->
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val activity=AtomicReference<PhotoViewerActivity>()
        scenario.onActivity(activity::set)
        SensitiveMediaSession.current.relock(RevealRelockReason.SCREEN_LOCK)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync{
            assertTrue(activity.get().isFinishing)
            assertTrue(activity.get().findViewById<ImageView>(R.id.viewer_image).drawable==null)
        }
    }
}
