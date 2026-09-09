package io.github.mesteriis.lik.ui

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaOperation
import io.github.mesteriis.lik.catalog.OrganizationRepository
import kotlinx.coroutines.*

open class MainActivity : ComponentActivity() {
    private lateinit var model: ImportViewModel
    private lateinit var timeline: TimelineAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private var selection = GallerySelection()
    private var ui = GalleryUiState()
    private var photos: List<GalleryPhoto> = emptyList()
    private var photoAccess = DevicePhotoAccess.DENIED
    private var currentScreenState: GalleryScreenState = GalleryScreenState.Loading(DevicePhotoAccess.DENIED)
    private var pendingRestore = false
    private lateinit var libraryZone: ZoneId
    private var importSummaryEvents = ImportSummaryEvents()
    private lateinit var timelinePinch: (android.view.MotionEvent) -> Boolean
    private lateinit var organization: OrganizationPanel
    private val organizationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capabilityRevision = 0
    private var pendingExportId: String? = null
    private var exporting = false
    private val exportDestination = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingExportId
        pendingExportId = null
        updateSelection()
        val uri = result.data?.data.takeIf { result.resultCode == RESULT_OK }
        if (id != null && uri != null) organizationScope.launch {
            exporting = true; updateSelection()
            try {
                withContext(Dispatchers.IO) { io.github.mesteriis.lik.exports.PhotoExport(this@MainActivity).save(id, uri) }
                Toast.makeText(this@MainActivity, R.string.export_saved, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@MainActivity, R.string.export_failed, Toast.LENGTH_LONG).show() }
            finally { exporting = false; updateSelection() }
        }
    }

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
        pendingExportId = savedInstanceState?.getString("export.pending")
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.main_content).applySystemBarInsets()
        model = ViewModelProvider(this)[ImportViewModel::class.java]
        importSummaryEvents = ImportSummaryEvents(
            savedInstanceState?.getLong(STATE_RENDERED_IMPORT_SUMMARY)?.takeIf { it > 0 },
        )
        libraryZone = loadLibraryZone()
        ui = restoreUi(savedInstanceState)
        selection = GallerySelection(savedInstanceState?.getStringArrayList(STATE_SELECTION)?.toSet().orEmpty())
        organization = OrganizationPanel(this, findViewById(R.id.section_placeholder), { selection.ids }) { id ->
            selection = selection.toggle(id)
            updateSelection()
        }
        organization.restore(savedInstanceState)
        findViewById<View>(R.id.share_selected).setOnClickListener { exportSelection(false) }
        findViewById<View>(R.id.save_copy).setOnClickListener { exportSelection(true) }

        timeline = TimelineAdapter(this, ::onPhotoClick, ::onPhotoLongClick, ::onPeriodClick, libraryZone)
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
                anchor?.let(::restoreAnchor)
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
        } else if (savedInstanceState == null && !hasPhotoAccess() && !hasRequestedPhotoAccess()) {
            requestPhotoAccess()
        }
        renderSection()
    }

    private fun bindChrome() {
        findViewById<View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.import_photos).setOnClickListener { showImportInstructions() }
        findViewById<View>(R.id.allow_photo_access).setOnClickListener {
            if (photoAccess == DevicePhotoAccess.PERMANENTLY_DENIED) openPhotoAccessSettings() else requestPhotoAccess()
        }
        findViewById<View>(R.id.cancel_selection).setOnClickListener {
            selection = GallerySelection()
            updateSelection()
            organization.refresh()
        }
        findViewById<View>(R.id.delete_selected).setOnClickListener { confirmDeleteSelection() }
        findViewById<View>(R.id.organize_selected).setOnClickListener { organization.actions(selection.ids) }
        findViewById<View>(R.id.open_search).setOnClickListener { selectSection(GallerySection.SEARCH) }
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
        fun finishPinch(focusY: Int) {
            if (cancelled || completed) return
            completed = true
            val focusAnchor = captureAnchor(focusY)
            when {
                scale > 1.12f -> setLevel(ui.zoomIn().level, focusAnchor)
                scale < 0.89f -> setLevel(ui.zoomOut().level, focusAnchor)
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
                finishPinch(detector.focusY.toInt())
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
                    finishPinch((event.getY(0)).toInt())
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
                text = getString(R.string.import_summary, summary.added, summary.duplicates, summary.failed, summary.restored)
                visibility = View.VISIBLE
            }
        }
        photos = state.photos
        selection = selection.retainAvailable(selection.ids - state.deletedIds)
        if (!state.busy && state.deleted + state.deleteFailed > 0) {
            timeline.forget(state.deletedIds)
            selection = GallerySelection((selection.ids - state.deletedIds) + state.deleteFailedIds)
        }
        updateSelection()
        findViewById<View>(R.id.import_photos).isEnabled = !state.busy
        currentScreenState = GalleryScreenState.resolve(
            access = photoAccess,
            scanning = state.scanning,
            photos = state.photos.size,
            imports = state.photos.count { it.source == io.github.mesteriis.lik.gallery.PhotoSource.GOOGLE_IMPORT },
            sourceError = state.deviceSourceError,
            importedSourceError = state.importedSourceError,
        )
        renderAccessState()
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
        if (!state.busy && !state.scanning) organization.refresh()
    }

    private fun renderTimeline() {
        val publish = {
            timeline.select(selection.ids)
            if (pendingRestore || ui.anchorId != null) {
                pendingRestore = false
                ui.anchorId?.let {
                    restoreAnchor(GalleryAnchor(it, ui.anchorChronologicalIndex, ui.anchorOffset))
                }
            }
        }
        if (!timeline.submitCatalog(ui.level, ui.anchorId, ui.anchorChronologicalIndex, publish)) publish()
        timeline.select(selection.ids)
        val buttons = mapOf(
            TimelineLevel.PHOTO to R.id.timeline_level_photo,
            TimelineLevel.DAYS to R.id.timeline_level_days,
            TimelineLevel.WEEKS to R.id.timeline_level_weeks,
            TimelineLevel.MONTHS to R.id.timeline_level_months,
            TimelineLevel.YEARS to R.id.timeline_level_years,
        )
        buttons.forEach { (level, id) -> findViewById<View>(id).isSelected = level == ui.level }
    }

    @SuppressLint("UseKtx")
    private fun setLevel(level: TimelineLevel, preferredAnchor: GalleryAnchor? = null) {
        if (level == ui.level && preferredAnchor == null) return
        val anchor = preferredAnchor ?: captureAnchor()
        ui = ui.copy(
            level = level,
            anchorId = anchor?.photoId ?: ui.anchorId,
            anchorOffset = anchor?.relativeOffset ?: ui.anchorOffset,
            anchorChronologicalIndex = anchor?.chronologicalIndex ?: ui.anchorChronologicalIndex,
        )
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LEVEL, level.name).apply()
        pendingRestore = true
        renderTimeline()
    }

    private fun selectSection(section: GallerySection) {
        if (section == ui.section) return
        captureAnchor()?.let { ui = ui.copy(anchorId = it.photoId, anchorOffset = it.relativeOffset, anchorChronologicalIndex = it.chronologicalIndex) }
        ui = ui.copy(section = section)
        renderSection()
    }

    private fun renderSection() {
        val feed = ui.section == GallerySection.FEED
        if (ui.section !in setOf(GallerySection.ALBUMS, GallerySection.SEARCH, GallerySection.PEOPLE, GallerySection.MORE)) organization.hide()
        findViewById<View>(R.id.timeline_level_scroll).visibility = if (feed) View.VISIBLE else View.GONE
        recycler.visibility = if (feed) View.VISIBLE else View.GONE
        findViewById<View>(R.id.section_placeholder).visibility = if (feed) View.GONE else View.VISIBLE
        if (!feed) {
            if (ui.section in setOf(GallerySection.ALBUMS, GallerySection.SEARCH, GallerySection.PEOPLE, GallerySection.MORE)) organization.show(ui.section)
            else findViewById<android.widget.LinearLayout>(R.id.section_placeholder).apply {
                removeAllViews()
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.section_future, sectionName(ui.section))
                    textSize = 20f
                    setPadding(24, 24, 24, 24)
                })
            }
        }
        mapOf(
            GallerySection.FEED to R.id.nav_feed,
            GallerySection.ALBUMS to R.id.nav_albums,
            GallerySection.PLACES to R.id.nav_places,
            GallerySection.PEOPLE to R.id.nav_people,
            GallerySection.MORE to R.id.nav_more,
        ).forEach { (section, id) -> findViewById<View>(id).isSelected = section == ui.section }
        if (feed) {
            renderAccessState()
            pendingRestore = true
            renderTimeline()
        } else findViewById<View>(R.id.empty_gallery).visibility = View.GONE
    }

    private fun sectionName(section: GallerySection) = getString(when (section) {
        GallerySection.FEED -> R.string.nav_feed
        GallerySection.ALBUMS -> R.string.nav_albums
        GallerySection.SEARCH -> R.string.search_title
        GallerySection.PLACES -> R.string.nav_places
        GallerySection.PEOPLE -> R.string.nav_people
        GallerySection.MORE -> R.string.nav_more
    })

    private fun onPhotoClick(photo: GalleryPhoto) {
        if (selection.ids.isNotEmpty()) {
            selection = selection.toggle(photo.id)
            updateSelection()
            return
        }
        viewer.launch(Intent(this, PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, photo.id))
    }

    private fun onPhotoLongClick(photo: GalleryPhoto) {
        selection = selection.toggle(photo.id)
        updateSelection()
    }

    private fun onPeriodClick(period: TimelineEntry.Period) = setLevel(
        period.targetLevel,
        GalleryAnchor(period.cover.id, timeline.chronologicalIndex(period.cover.id), 0),
    )

    private fun updateSelection() {
        timeline.select(selection.ids)
        findViewById<View>(R.id.selection_bar).visibility = if (selection.ids.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.selection_count).text = getString(R.string.selected_count, selection.ids.size)
        val revision = ++capabilityRevision
        val ids = selection.ids
        findViewById<View>(R.id.delete_selected).isEnabled = false
        findViewById<View>(R.id.organize_selected).isEnabled = ids.isNotEmpty() && model.state.value?.busy != true
        findViewById<View>(R.id.share_selected).isEnabled = ids.isNotEmpty() && !exporting && model.state.value?.busy != true
        findViewById<View>(R.id.save_copy).isEnabled = ids.size == 1 && !exporting && pendingExportId == null && model.state.value?.busy != true
        if (ids.isNotEmpty() && model.state.value?.busy != true) organizationScope.launch {
            val eligible = withContext(Dispatchers.IO) {
                runCatching { OrganizationRepository(MediaDatabase.get(this@MainActivity)).eligible(ids, MediaOperation.DELETE_COPY) }.getOrDefault(emptySet())
            }
            if (revision == capabilityRevision) findViewById<View>(R.id.delete_selected).isEnabled = eligible.isNotEmpty() && model.state.value?.busy != true
        }
    }

    private fun exportSelection(save: Boolean) {
        val ids = selection.ids
        if (ids.isEmpty() || exporting || (save && (ids.size != 1 || pendingExportId != null))) return
        exporting = true; updateSelection()
        organizationScope.launch {
            try {
                val export = io.github.mesteriis.lik.exports.PhotoExport(this@MainActivity)
                val intent = withContext(Dispatchers.IO) { if (save) export.destinationIntent(ids.single()) else export.share(ids) }
                if (save) {
                    pendingExportId = ids.single()
                    exportDestination.launch(intent)
                } else startActivity(Intent.createChooser(intent, getString(R.string.share_selected)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                pendingExportId = null
                Toast.makeText(this@MainActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
            } finally { exporting = false; updateSelection() }
        }
    }

    private fun confirmDeleteSelection() {
        val requested = selection.ids
        if (requested.isEmpty()) return
        organizationScope.launch {
            val repository = OrganizationRepository(MediaDatabase.get(this@MainActivity))
            val ids = withContext(Dispatchers.IO) { repository.eligible(requested, MediaOperation.DELETE_COPY) }
            if (ids.isEmpty() || model.state.value?.busy == true) return@launch
            AlertDialog.Builder(this@MainActivity)
                .setTitle(resources.getQuantityString(R.plurals.delete_photos_title, ids.size, ids.size))
                .setMessage(R.string.delete_selected_copies_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> organizationScope.launch {
                    val current = withContext(Dispatchers.IO) { repository.eligible(ids, MediaOperation.DELETE_COPY) }
                    model.deletePhotos(current)
                } }
                .show()
        }
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

    private fun captureAnchor(focusY: Int? = null): GalleryAnchor? {
        if (focusY == null) {
            val position = layoutManager.findFirstVisibleItemPosition()
            if (position == RecyclerView.NO_POSITION) return null
            val view = layoutManager.findViewByPosition(position) ?: return null
            val candidate = timeline.anchorCandidate(position, view.top, view.bottom) ?: return null
            return GalleryAnchor(candidate.photoId, candidate.chronologicalIndex, view.top)
        }
        val candidates = (0 until recycler.childCount).mapNotNull { index ->
            val child = recycler.getChildAt(index)
            val position = recycler.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) null else timeline.renderedPhotoAnchorCandidate(position, child.top, child.bottom)
        }
        return GalleryAnchor.capture(focusY, candidates)
    }

    private fun restoreAnchor(anchor: GalleryAnchor) {
        recycler.post {
            val position = timeline.positionForAnchor(anchor)
            if (position >= 0) layoutManager.scrollToPositionWithOffset(position, anchor.relativeOffset)
        }
    }

    private fun restoreUi(state: Bundle?): GalleryUiState {
        val defaultLevel = runCatching {
            TimelineLevel.valueOf(state?.getString(STATE_LEVEL) ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LEVEL, null).orEmpty())
        }.getOrDefault(TimelineLevel.DAYS)
        val section = runCatching { GallerySection.valueOf(state?.getString(STATE_SECTION).orEmpty()) }.getOrDefault(GallerySection.FEED)
        return GalleryUiState(
            defaultLevel,
            section,
            state?.getString(STATE_SCROLL_ID),
            state?.getInt(STATE_SCROLL_OFFSET) ?: 0,
            state?.getInt(STATE_SCROLL_CHRONOLOGICAL_INDEX) ?: 0,
        )
    }

    @SuppressLint("UseKtx")
    private fun loadLibraryZone(): ZoneId {
        return io.github.mesteriis.lik.catalog.libraryZone(this)
    }

    override fun onResume() {
        super.onResume()
        findViewById<ImageView>(R.id.app_emblem).setImageResource(AppIconManager(this).selected().emblemRes)
        refreshGallery()
        organization.refresh()
    }

    override fun onStop() { model.stopObserving(); super.onStop() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export.pending", pendingExportId)
        captureAnchor()?.let { ui = ui.copy(anchorId = it.photoId, anchorOffset = it.relativeOffset, anchorChronologicalIndex = it.chronologicalIndex) }
        outState.putString(STATE_LEVEL, ui.level.name)
        outState.putString(STATE_SECTION, ui.section.name)
        outState.putString(STATE_SCROLL_ID, ui.anchorId)
        outState.putInt(STATE_SCROLL_OFFSET, ui.anchorOffset)
        outState.putInt(STATE_SCROLL_CHRONOLOGICAL_INDEX, ui.anchorChronologicalIndex)
        outState.putStringArrayList(STATE_SELECTION, ArrayList(selection.ids))
        organization.save(outState)
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
    @SuppressLint("UseKtx")
    private fun requestPhotoAccess() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_REQUESTED_PHOTO_ACCESS, true).apply()
        photoPermission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    }
    private fun currentPhotoAccess() = DevicePhotoAccess.fromPermissions(
        fullGranted = hasFullPhotoAccess(),
        selectedGranted = checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED,
        requestedBefore = hasRequestedPhotoAccess(),
        shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES),
    )
    private fun hasRequestedPhotoAccess() =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_REQUESTED_PHOTO_ACCESS, false)
    private fun openPhotoAccessSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
    private fun refreshGallery() {
        val access = currentPhotoAccess()
        if (access != photoAccess) {
            photoAccess = access
        }
        // Android can change the selected-photo set without changing its PARTIAL enum state.
        timeline.refreshDeviceAccessEpoch()
        model.refresh(access.canReadDevicePhotos)
    }
    private fun renderAccessState() {
        val button = findViewById<android.widget.Button>(R.id.allow_photo_access)
        button.visibility = if (photoAccess == DevicePhotoAccess.FULL) View.GONE else View.VISIBLE
        button.setText(if (photoAccess == DevicePhotoAccess.PERMANENTLY_DENIED) R.string.open_photo_access_settings else R.string.allow_photo_access)
        val status = findViewById<TextView>(R.id.gallery_access_status)
        val message = when (val screen = currentScreenState) {
            is GalleryScreenState.Loading -> R.string.gallery_scanning
            is GalleryScreenState.Partial -> R.string.gallery_partial_access
            is GalleryScreenState.Denied -> R.string.gallery_access_denied
            is GalleryScreenState.PermanentlyDenied -> R.string.gallery_access_permanently_denied
            is GalleryScreenState.SourceError -> if (screen.importedSourceError) R.string.gallery_imported_source_error else R.string.gallery_source_error
            else -> null
        }
        status.text = message?.let(::getString).orEmpty()
        status.visibility = if (message == null) View.GONE else View.VISIBLE
        val showEmpty = currentScreenState is GalleryScreenState.Empty ||
            (photos.isEmpty() && (currentScreenState is GalleryScreenState.Denied || currentScreenState is GalleryScreenState.PermanentlyDenied || currentScreenState is GalleryScreenState.SourceError))
        findViewById<TextView>(R.id.empty_gallery).apply {
            text = if (showEmpty && currentScreenState is GalleryScreenState.SourceError) getString(requireNotNull(message)) else getString(R.string.gallery_placeholder)
            visibility = if (ui.section == GallerySection.FEED && showEmpty) View.VISIBLE else View.GONE
        }
    }
    private fun spansFor(widthPx: Int) = if (widthPx / resources.displayMetrics.density >= 600f) 12 else 6
    override fun onDestroy() { organization.close(); organizationScope.cancel(); timeline.close(); super.onDestroy() }

    companion object {
        private const val STATE_SELECTION = "gallery.selection"
        private const val STATE_LEVEL = "gallery.level"
        private const val STATE_SECTION = "gallery.section"
        private const val STATE_SCROLL_ID = "gallery.scroll.id"
        private const val STATE_SCROLL_OFFSET = "gallery.scroll.offset"
        private const val STATE_SCROLL_CHRONOLOGICAL_INDEX = "gallery.scroll.chronological_index"
        private const val STATE_RENDERED_IMPORT_SUMMARY = "import.rendered_summary"
        private const val PREF_LEVEL = "gallery.last.level"
        private const val PREF_LIBRARY_ZONE = "gallery.library.zone"
        private const val PREF_REQUESTED_PHOTO_ACCESS = "gallery.requested_photo_access"
        private const val PREFS = "gallery_ui"
        private const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"
    }
}
