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

    @Test fun relockClearsAProtectedViewerBeforeAStaleDecodeCanPublish(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(122,45,91))}
        val bytes=ByteArrayOutputStream().use{output->bitmap.compress(Bitmap.CompressFormat.PNG,100,output);output.toByteArray()}
        bitmap.recycle()
        val photo=TrashRepository(MediaDatabase.get(context),PhotoLibrary.store(context)).importPhoto(bytes.inputStream()).photo
        val request=SensitiveMediaSession.current.beginAuthentication()
        assertTrue(SensitiveMediaSession.current.authenticationSucceeded(request,AuthStrength.STRONG))
        val activity=AtomicReference<PhotoViewerActivity>()
        ActivityScenario.launch<PhotoViewerActivity>(android.content.Intent(context,PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID,photo.id)).use{scenario->
            val deadline=System.nanoTime()+5_000_000_000
            var ready=false
            while(!ready&&System.nanoTime()<deadline){
                scenario.onActivity{current->activity.set(current);ready=current.findViewById<ImageView>(R.id.viewer_image).drawable!=null}
                if(!ready)Thread.sleep(25)
            }
            assertTrue(ready)
            SensitiveMediaSession.current.relock(RevealRelockReason.SCREEN_LOCK)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync{
                assertTrue(activity.get().isFinishing)
                assertTrue(activity.get().findViewById<ImageView>(R.id.viewer_image).drawable==null)
            }
        }
        PhotoLibrary.store(context).fileFor(photo.id).delete()
    }
}
