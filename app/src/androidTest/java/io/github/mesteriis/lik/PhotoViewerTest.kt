package io.github.mesteriis.lik

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.widget.ImageView
import android.widget.Button
import android.widget.TextView
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Observer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.gallery.PhotoViewerActivity
import io.github.mesteriis.lik.gallery.PhotoViewerViewModel
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoViewerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val library = File(context.filesDir, "imported_photos")

    @Before fun clearLibrary() { library.deleteRecursively() }
    @After fun cleanLibrary() { library.deleteRecursively() }

    private fun addPhoto(width: Int = 32, height: Int = 20): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        return PhotoLibrary.store(context).importPhoto(bytes.inputStream()).photo.id
    }

    private fun addOrientedJpeg(orientation: Int): String {
        val fixture = File(context.cacheDir, "orientation-$orientation.jpg")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(fixture.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return PhotoLibrary.store(context).importPhoto(fixture.inputStream()).photo.id
    }

    @Test fun viewerRestoresPhotoAndShowsDimensions() {
        val id = addPhoto()
        val intent = Intent(context, PhotoViewerActivity::class.java)
            .putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, id)
        ActivityScenario.launch<PhotoViewerActivity>(intent).use { scenario ->
            val deadline = System.nanoTime() + 5_000_000_000
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                scenario.onActivity { activity -> ready = activity.findViewById<ImageView>(R.id.viewer_image).drawable != null }
                if (!ready) Thread.sleep(50)
            }
            assertTrue(ready)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.photo_details).text.contains("32 × 20"))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[PhotoViewerViewModel::class.java].state.value!!
                assertEquals(id, state.cursor?.current?.id)
            }
        }
    }

    @Test fun photoDetailsOpenFromTheInfoControl() {
        val id = addPhoto()
        val intent = Intent(context, PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, id)
        ActivityScenario.launch<PhotoViewerActivity>(intent).use { scenario ->
            val deadline = System.nanoTime() + 5_000_000_000
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                scenario.onActivity { ready = it.findViewById<ImageView>(R.id.viewer_image).drawable != null }
                if (!ready) Thread.sleep(50)
            }
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<TextView>(R.id.photo_details).visibility)
                assertTrue(activity.findViewById<View>(R.id.viewer_info).performClick())
                assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.photo_details).visibility)
            }
        }
    }

    @Test fun viewerReportsOriginalDimensionsWhenPreviewIsDownsampled() {
        val id = addPhoto(width = 5000, height = 2)
        val intent = Intent(context, PhotoViewerActivity::class.java)
            .putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, id)
        ActivityScenario.launch<PhotoViewerActivity>(intent).use { scenario ->
            val deadline = System.nanoTime() + 5_000_000_000
            var details: io.github.mesteriis.lik.gallery.PhotoDetails? = null
            while (details == null && System.nanoTime() < deadline) {
                scenario.onActivity { activity ->
                    details = ViewModelProvider(activity)[PhotoViewerViewModel::class.java].state.value?.details
                }
                if (details == null) Thread.sleep(50)
            }
            assertEquals(5000, details?.width)
            assertEquals(2, details?.height)
        }
    }

    @Test fun imageDecoderAndDetailsHonorAllExifOrientations() {
        val orientations = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        orientations.forEach { orientation ->
            val id = addOrientedJpeg(orientation)
            val photo = PhotoLibrary.store(context).photos().first { it.id == id }
            val decoded = PhotoLibrary.decode(photo.file, 200)
            val swapsAxes = orientation in setOf(
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSVERSE,
                ExifInterface.ORIENTATION_ROTATE_270,
            )
            assertEquals(if (swapsAxes) 20 else 40, decoded.width)
            assertEquals(if (swapsAxes) 40 else 20, decoded.height)
            decoded.recycle()
        }
    }

    @Test fun localLibraryDecodesJpegPngAndWebp() {
        val formats = listOf(
            Bitmap.CompressFormat.JPEG,
            Bitmap.CompressFormat.PNG,
            Bitmap.CompressFormat.WEBP_LOSSLESS,
        )
        formats.forEachIndexed { index, format ->
            val bitmap = Bitmap.createBitmap(37, 23, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(40 * index, 80, 160))
            }
            val bytes = ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(format, 95, output))
                output.toByteArray()
            }
            bitmap.recycle()
            val photo = PhotoLibrary.store(context).importPhoto(bytes.inputStream()).photo
            val decoded = PhotoLibrary.decode(photo.file, 100)
            assertEquals(37, decoded.width)
            assertEquals(23, decoded.height)
            decoded.recycle()
        }
    }

    @Test fun corruptPrivateCopyShowsErrorInsteadOfCrashing() {
        val id = "f".repeat(64)
        library.mkdirs()
        File(library, "$id.image").writeBytes(byteArrayOf(1, 2, 3))
        val intent = Intent(context, PhotoViewerActivity::class.java)
            .putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, id)
        ActivityScenario.launch<PhotoViewerActivity>(intent).use { scenario ->
            val deadline = System.nanoTime() + 5_000_000_000
            var visible = false
            while (!visible && System.nanoTime() < deadline) {
                scenario.onActivity { activity ->
                    visible = activity.findViewById<TextView>(R.id.viewer_error).visibility == View.VISIBLE
                }
                if (!visible) Thread.sleep(50)
            }
            assertTrue(visible)
        }
    }

    @Test fun deletingCurrentPhotoMovesToNextAvailablePhoto() {
        val first = addPhoto()
        val second = addPhoto(width = 33, height = 21)
        val intent = Intent(context, PhotoViewerActivity::class.java)
            .putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, first)
        ActivityScenario.launch<PhotoViewerActivity>(intent).use { scenario ->
            var model: PhotoViewerViewModel? = null
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[PhotoViewerViewModel::class.java] }
            val readyDeadline = System.nanoTime() + 5_000_000_000
            while (model?.state?.value?.cursor?.current == null && System.nanoTime() < readyDeadline) Thread.sleep(50)
            scenario.onActivity { model?.deleteCurrent() }
            val deleteDeadline = System.nanoTime() + 5_000_000_000
            while (model?.state?.value?.cursor?.current?.id == first && System.nanoTime() < deleteDeadline) Thread.sleep(50)
            assertTrue(model?.state?.value?.cursor?.photos?.any { it.id == second } == true)
            assertTrue(model?.state?.value?.cursor?.current?.id != first)
            assertTrue(!File(library, "$first.image").exists())
        }
    }

    @Test fun movingFromReadablePhotoToBrokenPhotoClearsImageAndDisablesDelete() {
        val readable = addPhoto()
        val broken = "e".repeat(64)
        library.mkdirs()
        File(library, "$broken.image").writeBytes(byteArrayOf(1, 2, 3))
        val intent = Intent(context, PhotoViewerActivity::class.java)
            .putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, readable)
        ActivityScenario.launch<PhotoViewerActivity>(intent).use { scenario ->
            var model: PhotoViewerViewModel? = null
            val loadedDeadline = System.nanoTime() + 5_000_000_000
            var loaded = false
            while (!loaded && System.nanoTime() < loadedDeadline) {
                scenario.onActivity { activity ->
                    loaded = activity.findViewById<ImageView>(R.id.viewer_image).drawable != null
                    model = ViewModelProvider(activity)[PhotoViewerViewModel::class.java]
                }
                if (!loaded) Thread.sleep(50)
            }
            assertTrue(loaded)
            scenario.onActivity { activity ->
                val cursor = requireNotNull(model?.state?.value?.cursor)
                model?.move(cursor.photos.indexOfFirst { it.id == broken } - cursor.photos.indexOfFirst { it.id == readable })
            }
            val errorDeadline = System.nanoTime() + 5_000_000_000
            var errored = false
            while (!errored && System.nanoTime() < errorDeadline) {
                scenario.onActivity { activity ->
                    errored = activity.findViewById<TextView>(R.id.viewer_error).visibility == View.VISIBLE
                }
                if (!errored) Thread.sleep(50)
            }
            assertTrue(errored)
            scenario.onActivity { activity ->
                assertEquals(broken, model?.state?.value?.cursor?.current?.id)
                assertEquals(null, activity.findViewById<ImageView>(R.id.viewer_image).drawable)
                assertEquals("", activity.findViewById<TextView>(R.id.photo_details).text)
                assertFalse(activity.findViewById<Button>(R.id.viewer_delete).isEnabled)
            }
        }
    }

    @Test fun twoRapidMovesAdvanceFromAThroughBToC() {
        val first = addPhoto(width = 31)
        val second = addPhoto(width = 32)
        val third = addPhoto(width = 33)
        val intent = Intent(context, PhotoViewerActivity::class.java)
            .putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, first)
        ActivityScenario.launch<PhotoViewerActivity>(intent).use { scenario ->
            var model: PhotoViewerViewModel? = null
            val readyDeadline = System.nanoTime() + 5_000_000_000
            while (model?.state?.value?.bitmap == null && System.nanoTime() < readyDeadline) {
                scenario.onActivity { activity -> model = ViewModelProvider(activity)[PhotoViewerViewModel::class.java] }
                if (model?.state?.value?.bitmap == null) Thread.sleep(50)
            }
            val cursor = requireNotNull(model?.state?.value?.cursor)
            val currentIndex = cursor.photos.indexOfFirst { it.id == first }
            val direction = if (currentIndex <= cursor.photos.lastIndex - 2) 1 else -1
            val expected = cursor.photos[currentIndex + (2 * direction)].id
            scenario.onActivity {
                model?.move(direction)
                model?.move(direction)
            }
            val finalDeadline = System.nanoTime() + 5_000_000_000
            while (model?.state?.value?.cursor?.current?.id != expected && System.nanoTime() < finalDeadline) Thread.sleep(50)
            assertEquals(expected, model?.state?.value?.cursor?.current?.id)
            assertFalse(first == model?.state?.value?.cursor?.current?.id)
        }
    }

    @Test fun movingPastTheFirstPhotoDoesNotStartAnotherLoad() {
        val id = addPhoto()
        val intent = Intent(context, PhotoViewerActivity::class.java)
            .putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, id)
        ActivityScenario.launch<PhotoViewerActivity>(intent).use { scenario ->
            var model: PhotoViewerViewModel? = null
            val readyDeadline = System.nanoTime() + 5_000_000_000
            while (model?.state?.value?.bitmap == null && System.nanoTime() < readyDeadline) {
                scenario.onActivity { activity -> model = ViewModelProvider(activity)[PhotoViewerViewModel::class.java] }
                if (model?.state?.value?.bitmap == null) Thread.sleep(50)
            }
            val cursor = requireNotNull(model?.state?.value?.cursor)
            val firstId = cursor.photos.first().id
            scenario.onActivity { model?.move(-cursor.photos.size) }
            val firstDeadline = System.nanoTime() + 5_000_000_000
            while (model?.state?.value?.cursor?.current?.id != firstId || model?.state?.value?.bitmap == null) {
                if (System.nanoTime() >= firstDeadline) break
                Thread.sleep(50)
            }
            val loading = AtomicBoolean(false)
            val observer = Observer<io.github.mesteriis.lik.gallery.ViewerState> { state ->
                if (state.cursor?.current?.id == firstId && state.loading) loading.set(true)
            }
            scenario.onActivity {
                model?.state?.observeForever(observer)
                model?.move(-1)
            }
            Thread.sleep(250)
            scenario.onActivity { model?.state?.removeObserver(observer) }

            assertFalse(loading.get())
            assertEquals(firstId, model?.state?.value?.cursor?.current?.id)
        }
    }

    @Test fun viewerReturnsTheLastViewedPhotoToTheGallery() {
        val first = addPhoto(width = 31, height = 20)
        addPhoto(width = 35, height = 20)
        val intent = Intent(context, PhotoViewerActivity::class.java)
            .putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, first)
        ActivityScenario.launchActivityForResult<PhotoViewerActivity>(intent).use { scenario ->
            var model: PhotoViewerViewModel? = null
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[PhotoViewerViewModel::class.java] }
            val readyDeadline = System.nanoTime() + 5_000_000_000
            while (model?.state?.value?.cursor?.current == null && System.nanoTime() < readyDeadline) Thread.sleep(50)
            val delta = if (model?.state?.value?.cursor?.hasNext == true) 1 else -1
            scenario.onActivity { model?.move(delta) }
            val moveDeadline = System.nanoTime() + 5_000_000_000
            while (model?.state?.value?.cursor?.current?.id == first && System.nanoTime() < moveDeadline) Thread.sleep(50)
            val expected = requireNotNull(model?.state?.value?.cursor?.current?.id)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertEquals(expected, scenario.result.resultData?.getStringExtra(PhotoViewerActivity.EXTRA_RESULT_PHOTO_ID))
        }
    }
}
