package io.github.mesteriis.lik

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.gallery.*
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test

class TrashUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun buttons(view: View): List<Button> = if (view is ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else if (view is Button) listOf(view) else emptyList()
    private fun waitFor(check: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000
        var satisfied = check()
        while (!satisfied && System.nanoTime() < deadline) { Thread.sleep(30); satisfied = check() }
        assertTrue(satisfied)
    }
    private fun fixture(test: (String, TrashRepository) -> Unit) {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val bitmap = android.graphics.Bitmap.createBitmap(17, 13, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(System.nanoTime().toInt())
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val trash = TrashRepository(MediaDatabase.get(context), PhotoLibrary.store(context))
        val id = trash.importPhoto(bytes.inputStream()).photo.id
        try { test(id, trash) } finally { trash.trash(setOf(id)); trash.purgeNow(setOf(id)) }
    }

    @Test fun moreTrashRestoresAndRequiresConfirmationForPurge() = fixture { id, trash ->
        val db = MediaDatabase.get(context)
        trash.trash(setOf(id))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun openTrash() = scenario.onActivity {
                it.findViewById<View>(R.id.nav_albums).performClick()
                it.findViewById<View>(R.id.nav_more).performClick()
                it.findViewById<View>(R.id.organization_trash).performClick()
            }
            fun click(label: Int) { waitFor {
                var clicked = false
                scenario.onActivity { activity ->
                    buttons(activity.findViewById(R.id.section_placeholder)).firstOrNull { it.text == context.getString(label) }
                        ?.let { clicked = it.performClick() }
                }
                clicked
            } }
            fun dialog(buttonId: Int) = instrumentation.runOnMainSync {
                val button = android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull { it.findViewById<Button>(buttonId) }.first()
                button.performClick()
            }
            openTrash(); click(R.string.restore_photo)
            waitFor { db.media().get(id)?.availability == MediaAvailability.AVAILABLE }
            assertTrue(PhotoLibrary.store(context).fileFor(id).exists())
            trash.trash(setOf(id)); openTrash(); click(R.string.purge_photo)
            dialog(android.R.id.button2)
            assertEquals(MediaAvailability.TRASHED, db.media().get(id)!!.availability)
            click(R.string.purge_photo); dialog(android.R.id.button1)
            waitFor { db.media().get(id) == null }
            assertFalse(PhotoLibrary.store(context).fileFor(id).exists())
        }
    }

    @Test fun cancelledSystemDestinationLeavesSourceAndNoPreparedCopy() = fixture { id, _ ->
        val before = java.io.File(context.cacheDir, "prepared_exports").listFiles().orEmpty().map { it.name }.toSet()
        val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("image/*") }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("onPhotoLongClick", GalleryPhoto::class.java).apply { isAccessible = true }
                        .invoke(activity, GalleryPhoto(id, PhotoSource.GOOGLE_IMPORT))
                    activity.findViewById<View>(R.id.save_copy).performClick()
                }
                waitFor { monitor.hits == 1 }
                instrumentation.waitForIdleSync()
                waitFor {
                    var enabled = false
                    scenario.onActivity { enabled = it.findViewById<View>(R.id.save_copy).isEnabled }
                    enabled
                }
                assertTrue(PhotoLibrary.store(context).fileFor(id).exists())
                assertEquals(before, java.io.File(context.cacheDir, "prepared_exports").listFiles().orEmpty().map { it.name }.toSet())
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
