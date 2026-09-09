package io.github.mesteriis.lik

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.gallery.GallerySelection
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.privacy.*
import io.github.mesteriis.lik.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SensitiveSavePickerTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun await(message:String, condition:()->Boolean) {
        val deadline=System.nanoTime()+8_000_000_000
        while(!condition()) { assertTrue(message,System.nanoTime()<deadline);Thread.sleep(20) }
    }

    @Test fun protectedPickerRoundTripRelocksThenWritesOnlyAfterFreshStrongAuthentication() = roundTrip()
    @Test fun pendingDestinationSurvivesRecreationButRevealDoesNot() = roundTrip(recreate=true)
    @Test fun changedSourceAfterPickerCannotBeSavedAsOriginalRequest() = roundTrip(revise=true)

    private fun roundTrip(recreate:Boolean=false,revise:Boolean=false) {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.READ_MEDIA_IMAGES)
        val bitmap=Bitmap.createBitmap(24,18,Bitmap.Config.ARGB_8888)
        val bytes=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        bitmap.recycle()
        val db=MediaDatabase.get(context)
        val photo=TrashRepository(db,PhotoLibrary.store(context)).importPhoto(bytes.inputStream()).photo
        val destination=Uri.parse("content://io.github.mesteriis.lik.test.save-copy/${java.util.UUID.randomUUID()}")
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action==Intent.ACTION_CREATE_DOCUMENT) {
                    intent.component=ComponentName("${context.packageName}.test",SaveCopyPickerActivity::class.java.name)
                    intent.putExtra("destination",destination.toString())
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val initial=SensitiveMediaSession.current.beginAuthentication()
                assertTrue(SensitiveMediaSession.current.authenticationSucceeded(initial,AuthStrength.STRONG))
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("selection").apply { isAccessible=true }.set(activity,GallerySelection(setOf(photo.id)))
                    MainActivity::class.java.getDeclaredMethod("exportSelection",Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,true)
                }
                await("External document picker must appear") {
                    instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Finish save picker")?.isNotEmpty()==true
                }
                await("Backgrounding for the picker must relock") { !SensitiveMediaSession.current.snapshot().revealed }
                instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Finish save picker").first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                await("Result must return to MainActivity") {
                    instrumentation.uiAutomation.rootInActiveWindow?.packageName==context.packageName
                }
                instrumentation.waitForIdleSync()
                fun saved()=context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
                assertEquals("Picker return alone cannot disclose protected bytes",0,saved().size)
                val weak=SensitiveMediaSession.current.beginAuthentication()
                assertFalse(SensitiveMediaSession.current.authenticationSucceeded(weak,AuthStrength.WEAK))
                instrumentation.waitForIdleSync()
                assertEquals(0,saved().size)
                if(recreate) {
                    scenario.recreate()
                    assertFalse(SensitiveMediaSession.current.snapshot().revealed)
                    assertEquals(0,saved().size)
                }
                if(revise) {
                    val row=db.media().get(photo.id)!!
                    db.media().upsert(row.copy(contentRevision=row.contentRevision+1))
                    db.invalidationTracker.refreshVersionsSync()
                    instrumentation.waitForIdleSync()
                }
                val fresh=SensitiveMediaSession.current.beginAuthentication()
                assertTrue(SensitiveMediaSession.current.authenticationSucceeded(fresh,AuthStrength.STRONG))
                if(revise) {
                    await("Changed request must be discarded") {
                        var pending=true
                        scenario.onActivity { activity ->
                            pending=MainActivity::class.java.getDeclaredField("pendingExport").apply { isAccessible=true }.get(activity)!=null
                        }
                        !pending
                    }
                    assertEquals("Reauthentication cannot bless a changed source",0,saved().size)
                } else await("Retained picker destination must resume after fresh strong authentication") { saved().contentEquals(bytes) }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            SensitiveMediaSession.current.relock(RevealRelockReason.EXPLICIT)
            TrashRepository(db,PhotoLibrary.store(context)).apply { trash(setOf(photo.id));purgeNow(setOf(photo.id)) }
        }
    }
}
