package io.github.mesteriis.lik

import androidx.test.core.app.ActivityScenario
import io.github.mesteriis.lik.ui.MainActivity
import io.github.mesteriis.lik.gallery.GalleryPhoto
import io.github.mesteriis.lik.gallery.PhotoSource
import android.widget.TextView
import android.view.View
import org.junit.Assert.*
import org.junit.Test

class OrganizationUiTest {
    @org.junit.Before fun prepareAccess() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, android.Manifest.permission.READ_MEDIA_IMAGES)
    }
    @Test fun realAlbumsSearchAndMoreRoutesPreserveCyrillicDraftOnRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.nav_albums).performClick()
                assertTrue(activity.findViewById<View>(R.id.organization_create_album).isShown)
                assertTrue(activity.findViewById<View>(R.id.organization_favorites).isShown)
                activity.findViewById<View>(R.id.open_search).performClick()
                activity.findViewById<android.widget.EditText>(R.id.search_name).setText("Ёлка Семья")
                activity.findViewById<android.widget.EditText>(R.id.search_from).setText("2026-09-01")
            }
            scenario.recreate()
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val aiMonitor = instrumentation.addMonitor(io.github.mesteriis.lik.settings.AiSettingsActivity::class.java.name, null, false)
            scenario.onActivity { activity ->
                assertEquals("Ёлка Семья", activity.findViewById<android.widget.EditText>(R.id.search_name).text.toString())
                assertEquals("2026-09-01", activity.findViewById<android.widget.EditText>(R.id.search_from).text.toString())
                activity.findViewById<View>(R.id.nav_more).performClick()
                activity.findViewById<View>(R.id.organization_trash).performClick()
                assertTrue(texts(activity.findViewById(R.id.section_placeholder)).contains(activity.getString(R.string.trash_help)))
                activity.findViewById<View>(R.id.nav_albums).performClick()
                activity.findViewById<View>(R.id.nav_more).performClick()
                activity.findViewById<View>(R.id.organization_ai).performClick()
            }
            val aiSettings = aiMonitor.waitForActivityWithTimeout(5_000)
            assertNotNull(aiSettings)
            aiSettings?.finish()
            instrumentation.removeMonitor(aiMonitor)
        }
    }

    @Test fun galleryRefreshDoesNotReplaceAnotherNavigationSectionWithAlbums() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.nav_albums).performClick()
                activity.findViewById<View>(R.id.nav_places).performClick()
                MainActivity::class.java.getDeclaredMethod("render", io.github.mesteriis.lik.imports.ImportState::class.java)
                    .apply { isAccessible = true }.invoke(activity, io.github.mesteriis.lik.imports.ImportState())
                assertTrue(texts(activity.findViewById(R.id.section_placeholder)).contains(activity.getString(R.string.section_future, activity.getString(R.string.nav_places))))
            }
        }
    }

    private fun texts(view: View): List<String> = if (view is android.view.ViewGroup)
        (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
    else if (view is TextView) listOf(view.text.toString()) else emptyList()

    @Test fun devicePhotoCanJoinSelectionWithoutEnablingDeletion() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val method = MainActivity::class.java.getDeclaredMethod("onPhotoLongClick", GalleryPhoto::class.java).apply { isAccessible = true }
                method.invoke(activity, GalleryPhoto("device:test", PhotoSource.DEVICE))
                assertEquals(activity.getString(R.string.selected_count, 1), activity.findViewById<TextView>(R.id.selection_count).text)
                assertTrue(activity.findViewById<View>(R.id.selection_bar).isShown)
                assertFalse(activity.findViewById<View>(R.id.delete_selected).isEnabled)
            }
        }
    }
}
