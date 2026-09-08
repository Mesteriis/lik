package io.github.mesteriis.lik.ui

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.mesteriis.lik.R
import io.github.mesteriis.lik.gallery.GalleryPhoto
import io.github.mesteriis.lik.gallery.GallerySelection
import io.github.mesteriis.lik.gallery.GalleryTimeline
import io.github.mesteriis.lik.gallery.PhotoViewerActivity
import io.github.mesteriis.lik.gallery.TimelineEntry
import io.github.mesteriis.lik.gallery.TimelineLevel
import io.github.mesteriis.lik.imports.ImportFailureKind
import io.github.mesteriis.lik.imports.ImportInput
import io.github.mesteriis.lik.imports.ImportAdmission
import io.github.mesteriis.lik.imports.ImportSummaryEvents
import io.github.mesteriis.lik.imports.ImportViewModel
import io.github.mesteriis.lik.imports.ShareImportActivity
import io.github.mesteriis.lik.settings.AppIconManager
import io.github.mesteriis.lik.settings.SettingsActivity
import java.time.ZoneId

open class MainActivity : ComponentActivity() {
    private lateinit var model: ImportViewModel
    private lateinit var timeline: TimelineAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private var selection = GallerySelection()
    private var ui = GalleryUiState()
    private var photos: List<GalleryPhoto> = emptyList()
    private var pendingRestore = false
    private lateinit var libraryZone: ZoneId
    private var importSummaryEvents = ImportSummaryEvents()
    private lateinit var timelinePinch: (android.view.MotionEvent) -> Boolean

    private val photoPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshGallery()
    }
    private val viewer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(PhotoViewerActivity.EXTRA_RESULT_PHOTO_ID)?.let { id ->
            ui = ui.copy(anchorId = id)
            pendingRestore = true
        }
        refreshGallery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.main_content).applySystemBarInsets()
        model = ViewModelProvider(this)[ImportViewModel::class.java]
        importSummaryEvents = ImportSummaryEvents(
            savedInstanceState?.getLong(STATE_RENDERED_IMPORT_SUMMARY)?.takeIf { it > 0 },
        )
        libraryZone = loadLibraryZone()
        ui = restoreUi(savedInstanceState)
        selection = GallerySelection(savedInstanceState?.getStringArrayList(STATE_SELECTION)?.toSet().orEmpty())

        timeline = TimelineAdapter(this, ::onPhotoClick, ::onPhotoLongClick, ::onPeriodClick)
        layoutManager = GridLayoutManager(this, spansFor(resources.displayMetrics.widthPixels)).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = timeline.spanSize(position, spanCount)
            }
        }
        recycler = findViewById<RecyclerView>(R.id.photo_timeline).apply {
            layoutManager = this@MainActivity.layoutManager
            adapter = timeline
            itemAnimator = null
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                val width = right - left
                if (width <= 0 || width == oldRight - oldLeft) return@addOnLayoutChangeListener
                val anchor = captureAnchor()
                val newSpans = spansFor(width)
                if (newSpans != this@MainActivity.layoutManager.spanCount) {
                    this@MainActivity.layoutManager.spanCount = newSpans
                }
                timeline.updateAvailableWidth(width)
                anchor?.let { restoreAnchor(it.first, it.second) }
            }
        }
        installPinchGesture()
        bindChrome()
        bindNavigation()
        model.state.observe(this, ::render)

        if (this is ShareImportActivity && savedInstanceState == null) {
            if (intent.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE) && intent.type?.startsWith("image/") == true) {
                accept(intent)
            } else {
                Toast.makeText(this, R.string.import_invalid, Toast.LENGTH_LONG).show()
            }
        } else if (savedInstanceState == null && !hasPhotoAccess()) {
            requestPhotoAccess()
        }
        renderSection()
    }

    private fun bindChrome() {
        findViewById<View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.import_photos).setOnClickListener { showImportInstructions() }
        findViewById<View>(R.id.allow_photo_access).setOnClickListener { requestPhotoAccess() }
        findViewById<View>(R.id.cancel_selection).setOnClickListener {
            selection = GallerySelection()
            updateSelection()
        }
        findViewById<View>(R.id.delete_selected).setOnClickListener { confirmDeleteSelection() }
        val levels = mapOf(
            R.id.timeline_level_photo to TimelineLevel.PHOTO,
            R.id.timeline_level_days to TimelineLevel.DAYS,
            R.id.timeline_level_weeks to TimelineLevel.WEEKS,
            R.id.timeline_level_months to TimelineLevel.MONTHS,
            R.id.timeline_level_years to TimelineLevel.YEARS,
        )
        levels.forEach { (id, level) -> findViewById<View>(id).setOnClickListener { setLevel(level) } }
    }

    private fun bindNavigation() {
        mapOf(
            R.id.nav_feed to GallerySection.FEED,
            R.id.nav_albums to GallerySection.ALBUMS,
            R.id.nav_places to GallerySection.PLACES,
            R.id.nav_people to GallerySection.PEOPLE,
            R.id.nav_more to GallerySection.MORE,
        ).forEach { (id, section) -> findViewById<View>(id).setOnClickListener { selectSection(section) } }
    }

    private fun installPinchGesture() {
        var scale = 1f
        var cancelled = false
        var completed = false
        var capturing = false
        var initialSpan = 0f
        fun span(event: android.view.MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val horizontal = event.getX(1) - event.getX(0)
            val vertical = event.getY(1) - event.getY(0)
            return kotlin.math.hypot(horizontal, vertical)
        }
        fun finishPinch() {
            if (cancelled || completed) return
            completed = true
            when {
                scale > 1.12f -> setLevel(ui.zoomIn().level)
                scale < 0.89f -> setLevel(ui.zoomOut().level)
            }
        }
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scale = 1f
                initialSpan = detector.currentSpan
                cancelled = false
                completed = false
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean { scale *= detector.scaleFactor; return true }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                finishPinch()
            }
        })
        timelinePinch = { event ->
            cancelled = event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            if (event.actionMasked == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                scale = 1f
                initialSpan = span(event)
                completed = false
            }
            detector.onTouchEvent(event)
            if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE && initialSpan > 0f) {
                scale = span(event) / initialSpan
            }
            capturing = capturing || event.pointerCount > 1 || detector.isInProgress
            val consume = capturing
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_POINTER_UP,
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    finishPinch()
                    capturing = false
                    initialSpan = 0f
                }
            }
            consume
        }
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: android.view.MotionEvent) =
                dispatchTimelinePinch(event)

            override fun onTouchEvent(rv: RecyclerView, event: android.view.MotionEvent) {
                dispatchTimelinePinch(event)
            }
        })
    }

    internal fun dispatchTimelinePinch(event: android.view.MotionEvent) = timelinePinch(event)

    private fun render(state: io.github.mesteriis.lik.imports.ImportState) {
        if (state.busy) findViewById<View>(R.id.import_summary).visibility = View.GONE
        importSummaryEvents.next(state.summary)?.let { summary ->
            findViewById<TextView>(R.id.import_summary).apply {
                text = getString(R.string.import_summary, summary.added, summary.duplicates, summary.failed)
                visibility = View.VISIBLE
            }
        }
        photos = state.photos
        selection = selection.retainAvailable(state.photos.filter { it.canDeleteCopy }.mapTo(mutableSetOf()) { it.id })
        if (!state.busy && state.deleted + state.deleteFailed > 0) {
            timeline.forget(state.deletedIds)
            selection = GallerySelection(state.deleteFailedIds)
        }
        updateSelection()
        findViewById<View>(R.id.import_photos).isEnabled = !state.busy
        findViewById<View>(R.id.empty_gallery).visibility = if (ui.section == GallerySection.FEED && state.photos.isEmpty() && !state.busy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.import_error).apply {
            visibility = if (!state.busy && state.failed > 0) View.VISIBLE else View.GONE
            text = state.failureKinds.map { kind ->
                getString(when (kind) {
                    ImportFailureKind.SOURCE_UNAVAILABLE -> R.string.import_source_failure
                    ImportFailureKind.INVALID_IMAGE -> R.string.import_invalid_image_failure
                    ImportFailureKind.TOO_LARGE -> R.string.import_too_large_failure
                    ImportFailureKind.STORAGE -> R.string.import_storage_failure
                })
            }.joinToString("\n")
        }
        findViewById<ProgressBar>(R.id.import_progress).apply {
            visibility = if (state.busy) View.VISIBLE else View.GONE
            max = state.total
            progress = state.processed
        }
        renderTimeline()
    }

    private fun renderTimeline() {
        val entries = GalleryTimeline.build(photos, ui.level, libraryZone)
        timeline.submit(entries, ui.level)
        timeline.select(selection.ids)
        val buttons = mapOf(
            TimelineLevel.PHOTO to R.id.timeline_level_photo,
            TimelineLevel.DAYS to R.id.timeline_level_days,
            TimelineLevel.WEEKS to R.id.timeline_level_weeks,
            TimelineLevel.MONTHS to R.id.timeline_level_months,
            TimelineLevel.YEARS to R.id.timeline_level_years,
        )
        buttons.forEach { (level, id) -> findViewById<View>(id).isSelected = level == ui.level }
        if (pendingRestore || ui.anchorId != null) {
            pendingRestore = false
            ui.anchorId?.let { restoreAnchor(it, ui.anchorOffset) }
        }
    }

    @SuppressLint("UseKtx")
    private fun setLevel(level: TimelineLevel, preferredAnchor: String? = null) {
        if (level == ui.level && preferredAnchor == null) return
        val anchor = captureAnchor()
        ui = ui.copy(level = level, anchorId = preferredAnchor ?: anchor?.first ?: ui.anchorId, anchorOffset = anchor?.second ?: 0)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LEVEL, level.name).apply()
        pendingRestore = true
        renderTimeline()
    }

    private fun selectSection(section: GallerySection) {
        if (section == ui.section) return
        captureAnchor()?.let { ui = ui.copy(anchorId = it.first, anchorOffset = it.second) }
        ui = ui.copy(section = section)
        renderSection()
    }

    private fun renderSection() {
        val feed = ui.section == GallerySection.FEED
        findViewById<View>(R.id.timeline_level_scroll).visibility = if (feed) View.VISIBLE else View.GONE
        recycler.visibility = if (feed) View.VISIBLE else View.GONE
        findViewById<View>(R.id.section_placeholder).visibility = if (feed) View.GONE else View.VISIBLE
        if (!feed) findViewById<TextView>(R.id.section_title).text = sectionName(ui.section)
        mapOf(
            GallerySection.FEED to R.id.nav_feed,
            GallerySection.ALBUMS to R.id.nav_albums,
            GallerySection.PLACES to R.id.nav_places,
            GallerySection.PEOPLE to R.id.nav_people,
            GallerySection.MORE to R.id.nav_more,
        ).forEach { (section, id) -> findViewById<View>(id).isSelected = section == ui.section }
        if (feed) {
            findViewById<View>(R.id.empty_gallery).visibility = if (photos.isEmpty() && model.state.value?.busy != true) View.VISIBLE else View.GONE
            pendingRestore = true
            renderTimeline()
        } else findViewById<View>(R.id.empty_gallery).visibility = View.GONE
    }

    private fun sectionName(section: GallerySection) = getString(when (section) {
        GallerySection.FEED -> R.string.nav_feed
        GallerySection.ALBUMS -> R.string.nav_albums
        GallerySection.PLACES -> R.string.nav_places
        GallerySection.PEOPLE -> R.string.nav_people
        GallerySection.MORE -> R.string.nav_more
    })

    private fun onPhotoClick(photo: GalleryPhoto) {
        if (selection.ids.isNotEmpty()) {
            if (!photo.canDeleteCopy) {
                Toast.makeText(this, R.string.local_photo_not_selectable, Toast.LENGTH_SHORT).show()
                return
            }
            selection = selection.toggle(photo.id)
            updateSelection()
            return
        }
        viewer.launch(Intent(this, PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, photo.id))
    }

    private fun onPhotoLongClick(photo: GalleryPhoto) {
        if (!photo.canDeleteCopy) {
            Toast.makeText(this, R.string.device_photo_kept_in_system_gallery, Toast.LENGTH_SHORT).show()
            return
        }
        selection = selection.toggle(photo.id)
        updateSelection()
    }

    private fun onPeriodClick(period: TimelineEntry.Period) = setLevel(period.targetLevel, period.cover.id)

    private fun updateSelection() {
        timeline.select(selection.ids)
        findViewById<View>(R.id.selection_bar).visibility = if (selection.ids.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.selection_count).text = getString(R.string.selected_count, selection.ids.size)
        findViewById<View>(R.id.delete_selected).isEnabled = selection.ids.isNotEmpty() && model.state.value?.busy != true
    }

    private fun confirmDeleteSelection() {
        val ids = selection.ids
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.delete_photos_title, ids.size, ids.size))
            .setMessage(R.string.delete_photos_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> model.deletePhotos(ids) }
            .show()
    }

    private fun showImportInstructions() {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_sheet_title)
            .setMessage(R.string.import_sheet_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open_google_photos) { _, _ -> openGooglePhotos() }
            .show()
    }

    private fun openGooglePhotos() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(GOOGLE_PHOTOS_PACKAGE) ?: throw ActivityNotFoundException()
            startActivity(launch)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.google_photos_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun captureAnchor(): Pair<String, Int>? {
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val id = timeline.anchorId(position) ?: return null
        val offset = layoutManager.findViewByPosition(position)?.top ?: 0
        return id to offset
    }

    private fun restoreAnchor(id: String, offset: Int) {
        recycler.post {
            val position = timeline.positionForPhoto(id)
            if (position >= 0) layoutManager.scrollToPositionWithOffset(position, offset)
        }
    }

    private fun restoreUi(state: Bundle?): GalleryUiState {
        val defaultLevel = runCatching {
            TimelineLevel.valueOf(state?.getString(STATE_LEVEL) ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LEVEL, null).orEmpty())
        }.getOrDefault(TimelineLevel.DAYS)
        val section = runCatching { GallerySection.valueOf(state?.getString(STATE_SECTION).orEmpty()) }.getOrDefault(GallerySection.FEED)
        return GalleryUiState(defaultLevel, section, state?.getString(STATE_SCROLL_ID), state?.getInt(STATE_SCROLL_OFFSET) ?: 0)
    }

    @SuppressLint("UseKtx")
    private fun loadLibraryZone(): ZoneId {
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = preferences.getString(PREF_LIBRARY_ZONE, null)
        if (saved != null) return runCatching { ZoneId.of(saved) }.getOrDefault(ZoneId.systemDefault())
        return ZoneId.systemDefault().also { preferences.edit().putString(PREF_LIBRARY_ZONE, it.id).apply() }
    }

    override fun onResume() {
        super.onResume()
        findViewById<ImageView>(R.id.app_emblem).setImageResource(AppIconManager(this).selected().emblemRes)
        refreshGallery()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureAnchor()?.let { ui = ui.copy(anchorId = it.first, anchorOffset = it.second) }
        outState.putString(STATE_LEVEL, ui.level.name)
        outState.putString(STATE_SECTION, ui.section.name)
        outState.putString(STATE_SCROLL_ID, ui.anchorId)
        outState.putInt(STATE_SCROLL_OFFSET, ui.anchorOffset)
        outState.putStringArrayList(STATE_SELECTION, ArrayList(selection.ids))
        importSummaryEvents.renderedOperationId?.let { outState.putLong(STATE_RENDERED_IMPORT_SUMMARY, it) }
        super.onSaveInstanceState(outState)
    }

    private fun accept(data: Intent?) {
        when (model.importPhotos(data?.let(ImportInput::uris).orEmpty())) {
            is ImportAdmission.Accepted -> Unit
            ImportAdmission.InvalidInput -> Toast.makeText(this, R.string.import_invalid, Toast.LENGTH_LONG).show()
            ImportAdmission.Busy -> Toast.makeText(this, R.string.import_busy, Toast.LENGTH_LONG).show()
        }
    }
    private fun hasFullPhotoAccess() = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    private fun hasPhotoAccess() = hasFullPhotoAccess() || checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    private fun requestPhotoAccess() = photoPermission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    private fun refreshGallery() {
        findViewById<View>(R.id.allow_photo_access).visibility = if (hasFullPhotoAccess()) View.GONE else View.VISIBLE
        model.refresh(hasPhotoAccess())
    }
    private fun spansFor(widthPx: Int) = if (widthPx / resources.displayMetrics.density >= 600f) 12 else 6
    override fun onDestroy() { timeline.close(); super.onDestroy() }

    companion object {
        private const val STATE_SELECTION = "gallery.selection"
        private const val STATE_LEVEL = "gallery.level"
        private const val STATE_SECTION = "gallery.section"
        private const val STATE_SCROLL_ID = "gallery.scroll.id"
        private const val STATE_SCROLL_OFFSET = "gallery.scroll.offset"
        private const val STATE_RENDERED_IMPORT_SUMMARY = "import.rendered_summary"
        private const val PREF_LEVEL = "gallery.last.level"
        private const val PREF_LIBRARY_ZONE = "gallery.library.zone"
        private const val PREFS = "gallery_ui"
        private const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"
    }
}
