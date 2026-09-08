package io.github.mesteriis.lik

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import io.github.mesteriis.lik.imports.ImportViewModel
import io.github.mesteriis.lik.imports.ImportFailureKind
import io.github.mesteriis.lik.ui.MainActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val library get() = File(context.filesDir, "imported_photos")

    @Before fun clearTestLibrary() {
        library.deleteRecursively()
    }

    private fun launchShare(vararg names: String): ActivityScenario<Activity> {
        val uris = names.map { Uri.parse("content://io.github.mesteriis.lik.test.photos/$it") }
        instrumentation.context.startActivity(Intent().apply {
            setClassName(instrumentation.context, FixtureGrantActivity::class.java.name)
            putParcelableArrayListExtra("uris", ArrayList(uris))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        val grantDeadline = System.nanoTime() + 5_000_000_000
        while (uris.any { context.checkUriPermission(it, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED }) {
            check(System.nanoTime() < grantDeadline) { "Fixture grant did not arrive" }
            Thread.sleep(50)
        }
        val clip = ClipData.newRawUri("test", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            setClassName(context, "io.github.mesteriis.lik.imports.ShareImportActivity")
            type = "image/png"
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        return ActivityScenario.launch(intent)
    }

    private fun awaitPhotos(count: Int) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            if (library.listFiles().orEmpty().count { it.extension == "image" } == count) return
            Thread.sleep(50)
        }
        fail("Expected $count committed photos")
    }

    private fun awaitCompleted(scenario: ActivityScenario<Activity>, total: Int) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            var done = false
            scenario.onActivity {
                val state = ViewModelProvider(it as MainActivity)[ImportViewModel::class.java].state.value!!
                done = !state.busy && state.processed == total
            }
            if (done) return
            Thread.sleep(50)
        }
        fail("Import did not finish")
    }

    private fun assertSummary(scenario: ActivityScenario<Activity>, visible: Boolean, added: Int = 0) {
        scenario.onActivity { activity ->
            val summary = activity.findViewById<TextView>(R.id.import_summary)
            assertEquals(if (visible) View.VISIBLE else View.GONE, summary.visibility)
            if (visible) assertEquals(activity.getString(R.string.import_summary, added, 0, 0), summary.text)
        }
    }

    @Test fun shareSavesImageAndSurvivesActivityRecreationWithoutDuplicate() {
        launchShare("green").use { scenario ->
            awaitCompleted(scenario, 1)
            awaitPhotos(1)
            val saved = library.listFiles()!!.single().readBytes()
            context.contentResolver.openInputStream(Uri.parse("content://io.github.mesteriis.lik.test.photos/green"))!!.use {
                assertArrayEquals(it.readBytes(), saved)
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity as MainActivity)[ImportViewModel::class.java].state.value!!
                assertEquals(1, state.photos.count {
                    it.source == io.github.mesteriis.lik.gallery.PhotoSource.GOOGLE_IMPORT
                })
            }
        }
        launchShare("green").use { awaitCompleted(it, 1); awaitPhotos(1) }
        assertEquals(1, library.listFiles()!!.count { it.extension == "image" })
    }

    @Test fun multipleShareKeepsGoodImagesWhenOneUriFails() {
        launchShare("blue", "missing", "invalid", "green").use {
            awaitCompleted(it, 4)
            awaitPhotos(2)
            assertEquals(2, library.listFiles()!!.size)
            it.onActivity { activity ->
                val state = ViewModelProvider(activity as MainActivity)[ImportViewModel::class.java].state.value!!
                assertEquals(2, state.failed)
                assertEquals(2, state.added)
                assertEquals(setOf(ImportFailureKind.SOURCE_UNAVAILABLE, ImportFailureKind.INVALID_IMAGE), state.failureKinds)
            }
        }
    }

    @Test fun rotationDuringProviderReadKeepsSingleImportAndProgress() {
        launchShare("slow").use { scenario ->
            scenario.onActivity {
                assertTrue(ViewModelProvider(it as MainActivity)[ImportViewModel::class.java].state.value!!.busy)
            }
            scenario.recreate()
            awaitCompleted(scenario, 1)
            awaitPhotos(1)
            scenario.onActivity {
                val state = ViewModelProvider(it as MainActivity)[ImportViewModel::class.java].state.value!!
                assertEquals(1, state.added)
                assertEquals(0, state.failed)
                assertEquals(0, state.duplicates)
            }
        }
    }

    @Test fun completedShareRendersSummaryOnlyOnceAcrossRecreation() {
        launchShare("green").use { scenario ->
            awaitCompleted(scenario, 1)
            assertSummary(scenario, visible = true, added = 1)

            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertSummary(scenario, visible = false)
        }
    }

    @Test fun importRotatedWhileBusyRendersSummaryWhenItCompletes() {
        launchShare("slow").use { scenario ->
            scenario.onActivity {
                assertTrue(ViewModelProvider(it as MainActivity)[ImportViewModel::class.java].state.value!!.busy)
            }
            assertSummary(scenario, visible = false)

            scenario.recreate()
            awaitCompleted(scenario, 1)
            assertSummary(scenario, visible = true, added = 1)
        }
    }

}
