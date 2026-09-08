package io.github.mesteriis.lik

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.gallery.PhotoSource
import io.github.mesteriis.lik.imports.ImportViewModel
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.ui.MainActivity
import io.github.mesteriis.lik.ui.TimelineAdapter
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The connected-test install begins without READ_MEDIA_IMAGES. A reused app install must be revoked
 * from the emulator host before this test; it never revokes permission in the target process.
 */
@RunWith(AndroidJUnit4::class)
class AccessTransitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val library = File(context.filesDir, "imported_photos")
    private val mediaUris = mutableListOf<android.net.Uri>()

    @Before fun resetLibraryAndAccessPrompt() {
        library.deleteRecursively()
        context.getSharedPreferences("gallery_ui", 0).edit().clear().commit()
    }

    @After fun cleanLibrary() {
        library.deleteRecursively()
        mediaUris.forEach { context.contentResolver.delete(it, null, null) }
    }

    private fun addImportedPhoto(): String {
        val bytes = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).let { bitmap ->
            ByteArrayOutputStream().use { output ->
                bitmap.eraseColor(Color.CYAN)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
        }
        return PhotoLibrary.store(context).importPhoto(bytes.inputStream()).photo.id
    }

    private fun addDevicePhoto(): android.net.Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "lik-access-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LikTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })).also(mediaUris::add)
        val bytes = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).let { bitmap ->
            ByteArrayOutputStream().use { output ->
                bitmap.eraseColor(Color.MAGENTA)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
        }
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test fun deniedGalleryReloadsAfterGrantAndRetainsSelectionAcrossRecreate() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES))
        val importedId = addImportedPhoto()
        val deviceUri = addDevicePhoto()
        val deviceId = android.content.ContentUris.parseId(deviceUri)

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            // The initial denied launch opens the normal Android permission sheet. Dismiss it through
            // the emulator shell, then assert Lik's denied state before granting access.
            instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
            assertTrue(waitFor(5_000) {
                var denied = false
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                    denied = (list.adapter as TimelineAdapter).positionForPhoto(importedId) >= 0 &&
                        activity.findViewById<TextView>(R.id.gallery_access_status).isShown &&
                        activity.findViewById<Button>(R.id.allow_photo_access).isShown
                }
                denied
            })
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                val position = (list.adapter as TimelineAdapter).positionForPhoto(importedId)
                assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.performLongClick())
                assertEquals(context.getString(R.string.selected_count, 1), activity.findViewById<TextView>(R.id.selection_count).text)
            }

            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
            scenario.recreate()

            var finalState = ""
            val transitioned = waitFor(5_000) {
                var granted = false
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[ImportViewModel::class.java].state.value
                    finalState = "permission=${context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)}, " +
                        "photos=${state?.photos?.map { it.source to it.uri }}, " +
                        "status=${activity.findViewById<View>(R.id.gallery_access_status).isShown}, " +
                        "button=${activity.findViewById<View>(R.id.allow_photo_access).isShown}, " +
                        "selection=${activity.findViewById<TextView>(R.id.selection_count).text}"
                    granted = context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                        state?.photos.orEmpty().any {
                            it.source == PhotoSource.DEVICE &&
                                it.uri?.lastPathSegment?.toLongOrNull() == deviceId
                        } &&
                        !activity.findViewById<View>(R.id.gallery_access_status).isShown &&
                        !activity.findViewById<View>(R.id.allow_photo_access).isShown &&
                        activity.findViewById<TextView>(R.id.selection_count).text == context.getString(R.string.selected_count, 1)
                }
                granted
            }
            assertTrue(finalState, transitioned)
        }
    }
}
