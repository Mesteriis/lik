package io.github.mesteriis.lik

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.MediaAvailability
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.gallery.GalleryCatalog
import io.github.mesteriis.lik.imports.ImportViewModel
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.ui.MainActivity
import io.github.mesteriis.lik.ui.TimelineAdapter
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CatalogInventoryFailureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val library = File(context.filesDir, "imported_photos")
    private val saved = File(context.cacheDir, "inventory-test-backup")
    private lateinit var importedId: String

    @Before fun prepareCatalog() {
        library.deleteRecursively()
        saved.deleteRecursively()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        importedId = PhotoLibrary.store(context).importPhoto(bytes.inputStream()).photo.id
        assertTrue(GalleryCatalog.load(context, false).any { it.id == importedId })
    }

    @After fun cleanup() {
        library.deleteRecursively()
        saved.deleteRecursively()
    }

    private fun failInventory() {
        assertTrue(library.renameTo(saved))
        library.writeText("Directory temporarily unavailable")
    }

    @Test fun failedInventoryReturnsPreviouslyCatalogedImportsWithoutChangingAvailability() {
        failInventory()
        val loaded = runCatching { GalleryCatalog.loadResult(context, false) }
        assertTrue("A failed inventory must still produce a load result: ${loaded.exceptionOrNull()}", loaded.isSuccess)
        assertTrue(loaded.getOrThrow().photos.any { it.id == importedId })
        assertFalse(loaded.getOrThrow().deviceSourceError)
        assertTrue(loaded.getOrThrow().importedSourceError)
        assertEquals(MediaAvailability.AVAILABLE, MediaDatabase.get(context).media().get(importedId)!!.availability)
    }

    @Test fun recreatedScreenFinishesScanAndShowsCachedImportsWithSourceErrorThenRecovers() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            assertTrue(waitFor {
                var ready = false
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[ImportViewModel::class.java].state.value
                    ready = state?.scanning == false && state.photos.any { it.id == importedId }
                }
                ready
            })
            failInventory()
            scenario.recreate()
            assertTrue("Recreation must end scanning and render cached imports with an error", waitFor {
                var recovered = false
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[ImportViewModel::class.java].state.value
                    val adapter = activity.findViewById<RecyclerView>(R.id.photo_timeline).adapter as TimelineAdapter
                    val status = activity.findViewById<TextView>(R.id.gallery_access_status)
                    recovered = state?.scanning == false && state.importedSourceError && state.photos.any { it.id == importedId } &&
                        adapter.positionForPhoto(importedId) >= 0 && status.isShown &&
                        status.text == context.getString(R.string.gallery_imported_source_error)
                }
                recovered
            })
            assertTrue(library.delete())
            assertTrue(saved.renameTo(library))
            scenario.recreate()
            assertTrue("A successful inventory must clear the source error", waitFor {
                var restored = false
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[ImportViewModel::class.java].state.value
                    restored = state?.scanning == false && !state.importedSourceError && state.photos.any { it.id == importedId } &&
                        !activity.findViewById<TextView>(R.id.gallery_access_status).isShown
                }
                restored
            })
        }
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
