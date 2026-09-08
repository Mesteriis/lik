package io.github.mesteriis.lik

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.view.View
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.imports.ImportViewModel
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.ui.MainActivity
import io.github.mesteriis.lik.ui.TimelineAdapter
import java.time.ZoneId
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mesteriis.lik.gallery.GalleryPhoto
import io.github.mesteriis.lik.gallery.PhotoSource
import io.github.mesteriis.lik.gallery.TimelineLevel

@RunWith(AndroidJUnit4::class)
class GalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val library = File(context.filesDir, "imported_photos")
    private val mediaUris = mutableListOf<android.net.Uri>()

    @Before fun clearLibrary() {
        library.deleteRecursively()
        context.getSharedPreferences("gallery_ui", 0).edit().clear().commit()
    }
    @After fun cleanLibrary() {
        library.deleteRecursively()
        mediaUris.forEach { context.contentResolver.delete(it, null, null) }
    }

    private fun addPhoto(color: Int): String {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        return PhotoLibrary.store(context).importPhoto(bytes.inputStream()).photo.id
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun dispatchPinch(
        list: RecyclerView,
        cancel: Boolean,
    ) {
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime
        fun event(action: Int, firstX: Float, secondX: Float): MotionEvent {
            val pointerCount = if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) 1 else 2
            return MotionEvent.obtain(
            downTime,
            eventTime.also { eventTime += 64 },
            action,
            pointerCount,
            arrayOf(
                MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
                MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
            ).copyOf(pointerCount),
            arrayOf(
                MotionEvent.PointerCoords().apply { x = firstX; y = 240f; pressure = 1f; size = 1f; touchMajor = 10f; touchMinor = 10f },
                MotionEvent.PointerCoords().apply { x = secondX; y = 240f; pressure = 1f; size = 1f; touchMajor = 10f; touchMinor = 10f },
            ).copyOf(pointerCount),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        }
        fun send(action: Int, firstX: Float, secondX: Float) {
            event(action, firstX, secondX).also { motion ->
                list.dispatchTouchEvent(motion)
                motion.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 120f, 160f)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 120f, 160f)
        send(MotionEvent.ACTION_MOVE, 100f, 180f)
        send(MotionEvent.ACTION_MOVE, 80f, 220f)
        send(MotionEvent.ACTION_MOVE, 40f, 260f)
        if (cancel) {
            send(MotionEvent.ACTION_CANCEL, 80f, 220f)
        } else {
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 80f, 220f)
            send(MotionEvent.ACTION_UP, 80f, 220f)
        }
    }

    @Test fun hashCollidingTimelineKeysDoNotEnableRecyclerViewStableIds() {
        val adapter = TimelineAdapter(context, {}, {}, {}, ZoneId.of("UTC"))
        val aa = GalleryPhoto(id = "Aa", source = PhotoSource.GOOGLE_IMPORT)
        val bb = GalleryPhoto(id = "BB", source = PhotoSource.GOOGLE_IMPORT)
        assertEquals("photo:Aa".hashCode(), "photo:BB".hashCode())
        try {
            adapter.submit(listOf(aa, bb), TimelineLevel.PHOTO)
            val deadline = System.nanoTime() + 5_000_000_000
            while (adapter.itemCount < 2 && System.nanoTime() < deadline) {
                instrumentation.waitForIdleSync()
                Thread.sleep(10)
            }

            assertFalse(adapter.hasStableIds())
            assertEquals(2, adapter.itemCount)
        } finally {
            adapter.close()
        }
    }

    @Test fun completedPinchChangesOnlyOneTimelineLevelAndCancelledPinchChangesNone() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        addPhoto(Color.CYAN)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            val loadedDeadline = System.nanoTime() + 5_000_000_000
            var loaded = false
            while (!loaded && System.nanoTime() < loadedDeadline) {
                scenario.onActivity { activity ->
                    loaded = (activity.findViewById<RecyclerView>(R.id.photo_timeline).adapter?.itemCount ?: 0) > 0
                }
                if (!loaded) Thread.sleep(50)
            }
            assertTrue(loaded)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.timeline_level_weeks).performClick())
                assertTrue(activity.findViewById<View>(R.id.timeline_level_weeks).isSelected)
            }
            scenario.onActivity { activity -> dispatchPinch(activity.findViewById(R.id.photo_timeline), cancel = false) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.timeline_level_days).isSelected)
                assertFalse(activity.findViewById<View>(R.id.timeline_level_photo).isSelected)
            }
            scenario.onActivity { activity -> dispatchPinch(activity.findViewById(R.id.photo_timeline), cancel = true) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.timeline_level_days).isSelected)
                assertFalse(activity.findViewById<View>(R.id.timeline_level_photo).isSelected)
            }
        }
    }

    @Test fun mediaStorePhotoAppearsWithoutCreatingPrivateCopy() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "lik-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LikTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })).also(mediaUris::add)
        context.contentResolver.openOutputStream(uri)!!.use { it.write(png(Color.MAGENTA)) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            val deadline = System.nanoTime() + 5_000_000_000
            var found = false
            while (!found && System.nanoTime() < deadline) {
                scenario.onActivity { activity ->
                    val expectedId = android.content.ContentUris.parseId(uri)
                    found = ViewModelProvider(activity)[ImportViewModel::class.java].state.value?.photos.orEmpty()
                        .any { it.uri?.lastPathSegment?.toLongOrNull() == expectedId && it.source == PhotoSource.DEVICE }
                }
                if (!found) Thread.sleep(50)
            }
            assertTrue("Expected MediaStore id ${android.content.ContentUris.parseId(uri)} in the gallery", found)
            assertTrue(library.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun galleryStartsOnDaysAndCanSwitchToYears() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.timeline_level_days).isSelected)
                assertTrue(activity.findViewById<View>(R.id.timeline_level_years).performClick())
                assertTrue(activity.findViewById<View>(R.id.timeline_level_years).isSelected)
                assertFalse(activity.findViewById<View>(R.id.timeline_level_days).isSelected)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.timeline_level_years).isSelected)
            }
        }
    }

    @Test fun feedStartsWithScaleControlWithoutPhotoCountRow() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(0, activity.resources.getIdentifier("feed_header", "id", activity.packageName))
                assertTrue(activity.findViewById<View>(R.id.timeline_level_scroll).isShown)
            }
        }
    }

    @Test fun mediaStoreCaptureDateIsKeptSeparateFromAddedDate() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val capturedAt = 1_577_934_245_000L
        val fixture = File(context.cacheDir, "lik-capture-date.jpg")
        Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).also { bitmap ->
            fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
        }
        ExifInterface(fixture.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
            saveAttributes()
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "lik-date-${System.nanoTime()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LikTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })).also(mediaUris::add)
        context.contentResolver.openOutputStream(uri)!!.use { output -> fixture.inputStream().use { it.copyTo(output) } }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)

        val expectedId = android.content.ContentUris.parseId(uri)
        val loaded = io.github.mesteriis.lik.gallery.GalleryCatalog.load(context, includeDevicePhotos = true)
            .single { it.uri?.lastPathSegment?.toLongOrNull() == expectedId }

        assertEquals(capturedAt, loaded.takenAt)
        assertTrue(loaded.addedAt > capturedAt)
    }

    @Test fun longPressSelectsByIdAndShowsSelectionActions() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val id = addPhoto(Color.GREEN)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                val position = (list.adapter as TimelineAdapter).positionForPhoto(id)
                assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.performLongClick())
                assertTrue(activity.findViewById<TextView>(R.id.selection_count).isShown)
            }
            assertTrue(waitFor(5_000) {
                var enabled = false
                scenario.onActivity { enabled = it.findViewById<Button>(R.id.delete_selected).isEnabled }
                enabled
            })
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.selection_count).isShown)
            }
        }
    }

    @Test fun viewModelDeletesSelectedCopiesOffTheUiContract() {
        val first = addPhoto(Color.GREEN)
        val second = addPhoto(Color.BLUE)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(ViewModelProvider(activity)[ImportViewModel::class.java].deletePhotos(setOf(first, second)))
            }
            val deadline = System.nanoTime() + 5_000_000_000
            while (System.nanoTime() < deadline && library.listFiles().orEmpty().any { it.extension == "image" }) {
                Thread.sleep(50)
            }
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[ImportViewModel::class.java].state.value!!
                assertFalse(state.busy)
                assertEquals(2, state.deleted)
                assertEquals(0, state.deleteFailed)
            }
            assertTrue(library.listFiles().orEmpty().none { it.extension == "image" })
        }
    }

    @Test fun mixedSourceSelectionDeletesOnlyThePrivateCopyAndKeepsDeviceSelected() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        // Exercise selection/deletion with both source rows visible, independent of period mosaics.
        context.getSharedPreferences("gallery_ui", 0).edit().putString("gallery.last.level", TimelineLevel.PHOTO.name).commit()
        val importedId = addPhoto(Color.GREEN)
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "lik-local-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LikTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })).also(mediaUris::add)
        context.contentResolver.openOutputStream(uri)!!.use { it.write(png(Color.BLUE)) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val mediaId = android.content.ContentUris.parseId(uri)

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            val deadline = System.nanoTime() + 5_000_000_000
            var localId: String? = null
            while (localId == null && System.nanoTime() < deadline) {
                scenario.onActivity { activity ->
                    localId = ViewModelProvider(activity)[ImportViewModel::class.java].state.value?.photos
                        ?.firstOrNull { it.source == PhotoSource.DEVICE && it.uri?.lastPathSegment?.toLongOrNull() == mediaId }?.id
                }
                if (localId == null) Thread.sleep(50)
            }
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                val adapter = list.adapter as TimelineAdapter
                assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(adapter.positionForPhoto(importedId))).itemView.performLongClick())
                list.scrollToPosition(adapter.positionForPhoto(requireNotNull(localId)))
            }
            var bindingState = "Device row not checked"
            val deviceBound = waitFor(5_000) {
                var bound = false
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                    val position = (list.adapter as TimelineAdapter).positionForPhoto(requireNotNull(localId))
                    bindingState = "device=$localId position=$position count=${list.adapter!!.itemCount} shown=${list.isShown} size=${list.width}x${list.height}"
                    if (position >= 0) {
                        list.scrollToPosition(position)
                        bound = list.findViewHolderForAdapterPosition(position) != null
                    }
                }
                bound
            }
            assertTrue(bindingState, deviceBound)
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                val adapter = list.adapter as TimelineAdapter
                val position = adapter.positionForPhoto(requireNotNull(localId))
                assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.performClick())
                assertEquals(context.getString(R.string.selected_count, 2), activity.findViewById<TextView>(R.id.selection_count).text)
            }
            assertTrue(waitFor(5_000) {
                var enabled = false
                scenario.onActivity { enabled = it.findViewById<Button>(R.id.delete_selected).isEnabled }
                enabled
            })
            scenario.onActivity { it.findViewById<Button>(R.id.delete_selected).performClick() }
            assertTrue(waitFor(5_000) {
                var clicked = false
                instrumentation.runOnMainSync {
                    android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull { it.findViewById<Button>(android.R.id.button1) }
                        .firstOrNull { it.text == context.getString(R.string.delete) }?.let { clicked = it.performClick() }
                }
                clicked
            })
            assertTrue(waitFor(5_000) { !PhotoLibrary.store(context).fileFor(importedId).exists() })
            context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
            assertTrue(waitFor(5_000) {
                var retained = false
                scenario.onActivity { activity ->
                    retained = activity.findViewById<TextView>(R.id.selection_count).text == context.getString(R.string.selected_count, 1) &&
                        !activity.findViewById<Button>(R.id.delete_selected).isEnabled
                }
                retained
            })
        }
    }

    @Test fun timelineAnchorAndControlsSurviveResizeAndLargeFontConfiguration() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        repeat(48) { index -> addPhoto(Color.rgb(index, 255 - index, index)) }
        instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.30").close()
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                assertTrue(waitFor(5_000) {
                    var loaded = false
                    scenario.onActivity { activity ->
                        loaded = (activity.findViewById<RecyclerView>(R.id.photo_timeline).adapter?.itemCount ?: 0) >= 48
                    }
                    loaded
                })
                var anchorId = ""
                scenario.onActivity { activity ->
                    assertTrue(activity.resources.configuration.fontScale >= 1.3f)
                    val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                    (list.layoutManager as GridLayoutManager).scrollToPositionWithOffset(24, 0)
                }
                assertTrue(waitFor(5_000) {
                    var scrolled = false
                    scenario.onActivity { activity ->
                        val manager = activity.findViewById<RecyclerView>(R.id.photo_timeline).layoutManager as GridLayoutManager
                        scrolled = manager.findFirstVisibleItemPosition() >= 20
                    }
                    scrolled
                })
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                    val position = (list.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                    anchorId = (list.adapter as TimelineAdapter).anchorId(position).orEmpty()
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                assertTrue(waitFor(5_000) {
                    var restored = false
                    scenario.onActivity { activity ->
                        assertTrue(activity.findViewById<View>(R.id.timeline_level_scroll).isShown)
                        val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                        val position = (list.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                        restored = anchorId == (list.adapter as TimelineAdapter).anchorId(position)
                    }
                    restored
                })
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        } finally {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.0").close()
        }
    }

    @Test fun futureSectionShowsPlaceholderAndFeedReturnsToTimeline() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.nav_albums).performClick())
                assertTrue(activity.findViewById<View>(R.id.section_placeholder).isShown)
                assertFalse(activity.findViewById<RecyclerView>(R.id.photo_timeline).isShown)
                assertTrue(activity.findViewById<View>(R.id.nav_feed).performClick())
                assertTrue(activity.findViewById<RecyclerView>(R.id.photo_timeline).isShown)
            }
        }
    }

    @Test fun failedThumbnailCanBeRetriedAfterFileBecomesReadable() {
        val id = "a".repeat(64)
        library.mkdirs()
        val file = File(library, "$id.image").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            Thread.sleep(250)
            file.writeBytes(png(Color.YELLOW))
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                val position = (list.adapter as TimelineAdapter).positionForPhoto(id)
                val thumbnail = requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView
                    .findViewById<ImageView>(R.id.photo_thumbnail)
                assertTrue(thumbnail.performClick())
            }
            val deadline = System.nanoTime() + 5_000_000_000
            var decoded = false
            while (!decoded && System.nanoTime() < deadline) {
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                    val position = (list.adapter as TimelineAdapter).positionForPhoto(id)
                    val image = requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.findViewById<ImageView>(R.id.photo_thumbnail)
                    decoded = image.drawable?.intrinsicWidth == 24 && image.drawable?.intrinsicHeight == 16
                }
                if (!decoded) Thread.sleep(50)
            }
            assertTrue(decoded)
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                val position = (list.adapter as TimelineAdapter).positionForPhoto(id)
                val image = requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.findViewById<ImageView>(R.id.photo_thumbnail)
                assertFalse(image.isClickable)
            }
        }
    }

    @Test fun largeLibraryKeepsUsableCellSizeAndOnlyInflatesVisibleRows() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        library.mkdirs()
        val bytes = png(Color.CYAN)
        repeat(120) { index ->
            File(library, "${index.toString(16).padStart(64, '0')}.image").writeBytes(bytes)
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            val deadline = System.nanoTime() + 5_000_000_000
            var loaded = false
            while (!loaded && System.nanoTime() < deadline) {
                scenario.onActivity { activity ->
                    loaded = ViewModelProvider(activity)[ImportViewModel::class.java].state.value?.photos
                        ?.count { it.source == PhotoSource.GOOGLE_IMPORT } == 120
                }
                if (!loaded) Thread.sleep(50)
            }
            scenario.onActivity { activity ->
                val grid = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                val minimumCellHeight = (100 * activity.resources.displayMetrics.density).toInt()
                val firstId = requireNotNull(ViewModelProvider(activity)[ImportViewModel::class.java].state.value)
                    .photos.first { it.source == PhotoSource.GOOGLE_IMPORT }.id
                val position = (grid.adapter as TimelineAdapter).positionForPhoto(firstId)
                assertTrue(requireNotNull(grid.findViewHolderForAdapterPosition(position)).itemView.height >= minimumCellHeight)
                assertTrue(grid.childCount < requireNotNull(grid.adapter).itemCount)
                grid.scrollToPosition(60)
            }
            val scrollDeadline = System.nanoTime() + 5_000_000_000
            var scrolled = false
            while (!scrolled && System.nanoTime() < scrollDeadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                    scrolled = (list.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() >= 20
                }
                if (!scrolled) Thread.sleep(50)
            }
            assertTrue(scrolled)
            Thread.sleep(1_000)
            instrumentation.waitForIdleSync()
            var anchorId = ""
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                val position = (list.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                anchorId = (list.adapter as TimelineAdapter).anchorId(position).orEmpty()
            }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            val restoreDeadline = System.nanoTime() + 5_000_000_000
            var restored = false
            while (!restored && System.nanoTime() < restoreDeadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.photo_timeline)
                    if (requireNotNull(list.adapter).itemCount > 0) {
                        val position = (list.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                        val restoredId = (list.adapter as TimelineAdapter).anchorId(position)
                        restored = anchorId == restoredId
                    }
                }
                if (!restored) Thread.sleep(50)
            }
            assertTrue(restored)
        }
    }
}
