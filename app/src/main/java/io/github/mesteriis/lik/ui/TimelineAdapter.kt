package io.github.mesteriis.lik.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.paging.AsyncPagingDataDiffer
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.CatalogPaging
import io.github.mesteriis.lik.imports.PhotoLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import io.github.mesteriis.lik.R
import io.github.mesteriis.lik.gallery.GalleryCatalog
import io.github.mesteriis.lik.gallery.GalleryPhoto
import io.github.mesteriis.lik.gallery.TimelineEntry
import io.github.mesteriis.lik.gallery.TimelineLevel
import java.time.ZoneId
import java.util.concurrent.Executors

internal fun daySpanUnits(index: Int, columns: Int, groupSize: Int = Int.MAX_VALUE): Int {
    val pattern = if (columns >= 4) intArrayOf(2, 1, 1, 1, 1, 2, 1, 1, 1, 1)
        else intArrayOf(2, 1, 1, 1, 1, 1, 2)
    if (index == groupSize - 1) {
        var used = 0
        repeat(index) { previous ->
            val span = pattern[previous % pattern.size]
            if (used + span > columns) used = 0
            used = (used + span) % columns
        }
        return if (used == 0) columns else columns - used
    }
    return pattern[index % pattern.size]
}

class TimelineAdapter(
    private val context: Context,
    private val onPhotoClick: (GalleryPhoto) -> Unit,
    private val onPhotoLongClick: (GalleryPhoto) -> Unit,
    private val onPeriodClick: (TimelineEntry.Period) -> Unit,
    private val zoneId: ZoneId,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var entries: List<TimelineEntry> = emptyList()
    private var selected: Set<String> = emptySet()
    private var level = TimelineLevel.DAYS
    private var availableWidth = context.resources.displayMetrics.widthPixels
    private val main = Handler(Looper.getMainLooper())
    private val timelineWorker = Executors.newSingleThreadExecutor()
    private val thumbnailWorker = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<ThumbnailKey, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: ThumbnailKey, value: Bitmap) = value.allocationByteCount
    }
    private val thumbnailCache = object : ThumbnailCache<Bitmap> {
        override fun get(key: ThumbnailKey) = cache.get(key)
        override fun put(key: ThumbnailKey, value: Bitmap) { cache.put(key, value) }
        override fun remove(key: ThumbnailKey) { cache.remove(key) }
        override fun keys() = cache.snapshot().keys
        override fun clear() { cache.evictAll() }
    }
    private val thumbnailLoader = ThumbnailLoader(thumbnailWorker, thumbnailCache, THUMBNAIL_QUEUE_CAPACITY)
    private val callbacks = mutableMapOf<Long, () -> Unit>()
    private lateinit var timelineController: TimelineController
    private val accessEpoch = ThumbnailAccessEpoch()
    private var chronologicalPhotoIds: List<String> = emptyList()
    private var closed = false
    private val boundImages = mutableSetOf<ImageView>()
    private val imageDatabase = MediaDatabase.get(context)
    private val imageInvalidation = object : androidx.room.InvalidationTracker.Observer("media","sensitive_manual","sensitive_automatic","ai_media_exposure") {
        override fun onInvalidated(tables:Set<String>) { main.post {
            if(!closed) {
                thumbnailLoader.invalidate { true }
                boundImages.toList().forEach { view -> cancelLoad(view);view.setImageDrawable(null) }
                if(entries.isNotEmpty())notifyItemRangeChanged(0,entries.size)
            }
        } }
    }
    private val pagingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pagingJob: Job? = null
    private var pagingLevel: TimelineLevel? = null
    private var pagingAnchor: String? = null
    private var pagingCallback: (() -> Unit)? = null
    private var includeProtected=false
    private var privacyEpoch=io.github.mesteriis.lik.privacy.SensitiveMediaSession.current.snapshot().epoch
    private var pagingGeneration=0L
    private var paging = createPaging(pagingGeneration)

    private fun syncPage(source:AsyncPagingDataDiffer<TimelineEntry>) {
        entries = source.snapshot().items
        chronologicalPhotoIds = entries.mapNotNull { entry -> when (entry) {
            is TimelineEntry.Photo -> entry.photo.id
            is TimelineEntry.Period -> entry.cover.id
            else -> null
        } }
    }

    private fun createPaging(generation:Long):AsyncPagingDataDiffer<TimelineEntry>{
        lateinit var differ:AsyncPagingDataDiffer<TimelineEntry>
        fun publish(action:()->Unit){
            if(generation!=pagingGeneration||closed)return
            syncPage(differ);action()
        }
        differ=AsyncPagingDataDiffer(
            diffCallback=object:DiffUtil.ItemCallback<TimelineEntry>(){
                override fun areItemsTheSame(oldItem:TimelineEntry,newItem:TimelineEntry)=oldItem.stableKey==newItem.stableKey
                override fun areContentsTheSame(oldItem:TimelineEntry,newItem:TimelineEntry)=oldItem==newItem
            },
            updateCallback=object:ListUpdateCallback{
                override fun onInserted(position:Int,count:Int)=publish{notifyItemRangeInserted(position,count)}
                override fun onRemoved(position:Int,count:Int)=publish{notifyItemRangeRemoved(position,count)}
                override fun onMoved(fromPosition:Int,toPosition:Int)=publish{notifyItemMoved(fromPosition,toPosition)}
                override fun onChanged(position:Int,count:Int,payload:Any?)=publish{notifyItemRangeChanged(position,count,payload)}
            },
        )
        differ.addOnPagesUpdatedListener{
            if(generation==pagingGeneration&&!closed){syncPage(differ);pagingCallback?.also{pagingCallback=null}?.invoke()}
        }
        return differ
    }

    fun submitCatalog(timelineLevel: TimelineLevel, anchorId: String?, fallbackRank: Int, onPublished: () -> Unit): Boolean {
        if (pagingLevel == timelineLevel && (anchorId == null || anchorId == pagingAnchor || positionForPhoto(anchorId) >= 0)) {
            pagingAnchor = anchorId
            return false
        }
        pagingJob?.cancel()
        pagingAnchor = anchorId
        pagingLevel = timelineLevel
        level = timelineLevel
        pagingCallback = onPublished
        pagingJob = pagingScope.launch {
            val catalog = CatalogPaging(MediaDatabase.get(context).media(), context.getString(R.string.timeline_undated),includeProtected,PhotoLibrary.store(context)::fileFor).apply { zone = zoneId }
            catalog.flow(timelineLevel, anchorId, fallbackRank).collectLatest { paging.submitData(it) }
        }
        return true
    }

    init {
        imageDatabase.invalidationTracker.addObserver(imageInvalidation)
        timelineController = TimelineController(timelineWorker, zoneId) { revision ->
            val difference = calculateDiff(revision.previous, revision.entries, revision.level)
            main.post {
                val callback = callbacks.remove(revision.id)
                if (!closed && timelineController.publish(revision)) {
                    entries = revision.entries
                    level = revision.level
                    difference.dispatchUpdatesTo(this)
                    callback?.invoke()
                }
            }
        }
    }

    fun submit(photos: List<GalleryPhoto>, timelineLevel: TimelineLevel, onPublished: () -> Unit = {}): Boolean {
        chronologicalPhotoIds = photos.sortedWith(
            compareByDescending<GalleryPhoto> { it.timelineAt ?: Long.MIN_VALUE }.thenByDescending { it.id },
        ).map { it.id }
        val id = timelineController.submit(photos, timelineLevel) ?: return false
        callbacks.keys.filter { it < id }.toList().forEach(callbacks::remove)
        callbacks[id] = onPublished
        return true
    }

    private fun calculateDiff(
        previous: TimelineSnapshot,
        next: List<TimelineEntry>,
        nextLevel: TimelineLevel,
    ) = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.entries.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous.entries[oldItemPosition].stableKey == next[newItemPosition].stableKey
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous.entries[oldItemPosition] == next[newItemPosition] && previous.level == nextLevel
        })

    fun refreshDeviceAccessEpoch() {
        accessEpoch.refresh()
        thumbnailLoader.invalidate { it.photoId.startsWith(DEVICE_PHOTO_PREFIX) && !accessEpoch.accepts(it) }
        if (entries.isNotEmpty()) notifyItemRangeChanged(0, entries.size)
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun setSensitiveReveal(snapshot:io.github.mesteriis.lik.privacy.RevealSnapshot){
        if(includeProtected==snapshot.revealed&&privacyEpoch==snapshot.epoch)return
        includeProtected=snapshot.revealed;privacyEpoch=snapshot.epoch
        pagingJob?.cancel();pagingJob=null;pagingLevel=null;pagingAnchor=null
        pagingGeneration++;paging=createPaging(pagingGeneration)
        thumbnailLoader.invalidate{true};thumbnailCache.clear()
        entries=emptyList();chronologicalPhotoIds=emptyList();notifyDataSetChanged()
    }

    fun select(ids: Set<String>) {
        if (selected == ids) return
        val changed = entries.mapIndexedNotNull { index, entry ->
            val photo = (entry as? TimelineEntry.Photo)?.photo ?: return@mapIndexedNotNull null
            index.takeIf { (photo.id in selected) != (photo.id in ids) }
        }
        selected = ids
        changed.forEach(::notifyItemChanged)
    }

    fun updateAvailableWidth(width: Int) {
        if (width <= 0 || width == availableWidth) return
        availableWidth = width
        if (level == TimelineLevel.PHOTO && entries.isNotEmpty()) notifyItemRangeChanged(0, entries.size)
    }

    fun spanSize(position: Int, spanCount: Int): Int = when (val entry = entries[position]) {
        is TimelineEntry.Header -> spanCount
        is TimelineEntry.Photo -> if (level == TimelineLevel.PHOTO) photoSpanSize(spanCount) else {
            val columns = if (spanCount >= 12) 4 else 3
            val unit = spanCount / columns
            (unit * daySpanUnits(entry.indexInGroup, columns, entry.groupSize)).coerceAtMost(spanCount)
        }
        is TimelineEntry.Period -> {
            val columns = when {
                spanCount >= 12 && level != TimelineLevel.WEEKS -> 4
                spanCount >= 12 -> 3
                else -> 2
            }
            spanCount / columns
        }
    }

    fun anchorId(position: Int): String? = when (val entry = entries.getOrNull(position)) {
        is TimelineEntry.Header -> entry.anchorId
        is TimelineEntry.Photo -> entry.photo.id
        is TimelineEntry.Period -> entry.cover.id
        null -> null
    }

    fun positionForPhoto(id: String): Int {
        val photo = entries.indexOfFirst { it is TimelineEntry.Photo && it.photo.id == id }
        if (photo >= 0) return photo
        val period = entries.indexOfFirst { it is TimelineEntry.Period && it.photos.any { photo -> photo.id == id } }
        if (period >= 0) return period
        return entries.indexOfFirst { it is TimelineEntry.Header && it.anchorId == id }
    }

    fun anchorCandidate(position: Int, top: Int, bottom: Int): AnchorCandidate? = anchorId(position)?.let { id ->
        AnchorCandidate(id, chronologicalIndex(id), top, bottom)
    }

    /** Pinch focus intentionally excludes headers and overview cards. */
    fun renderedPhotoAnchorCandidate(position: Int, top: Int, bottom: Int): AnchorCandidate? {
        val entry = entries.getOrNull(position) ?: return null
        val id = (entry as? TimelineEntry.Photo)?.photo?.id ?: return null
        return renderedPhotoAnchorCandidate(entry, chronologicalIndex(id), top, bottom)
    }

    fun chronologicalIndex(id: String): Int = entries.firstNotNullOfOrNull { entry -> when (entry) {
        is TimelineEntry.Photo -> entry.catalogIndex?.takeIf { entry.photo.id == id }
        is TimelineEntry.Period -> entry.catalogIndex?.takeIf { entry.cover.id == id }
        else -> null
    } } ?: chronologicalPhotoIds.indexOf(id).coerceAtLeast(0)

    fun positionForAnchor(anchor: GalleryAnchor): Int {
        if (pagingLevel != null) {
            val exact = positionForPhoto(anchor.photoId)
            if (exact >= 0) return exact
            val containingPeriod = entries.indexOfFirst { entry ->
                entry is TimelineEntry.Period && entry.catalogIndex?.let {
                    anchor.chronologicalIndex >= it && anchor.chronologicalIndex < it + entry.count
                } == true
            }
            if (containingPeriod >= 0) return containingPeriod
            val nearest = chronologicalPhotoIds.minByOrNull { kotlin.math.abs(chronologicalIndex(it) - anchor.chronologicalIndex) }
                ?: return RecyclerView.NO_POSITION
            return positionForPhoto(nearest)
        }
        val resolvedId = anchor.resolveId(chronologicalPhotoIds) ?: return RecyclerView.NO_POSITION
        return positionForPhoto(resolvedId)
    }

    fun entryAt(position: Int): TimelineEntry = entries[position]
    fun hasEntries():Boolean=entries.isNotEmpty()

    override fun getItemCount() = entries.size
    override fun getItemViewType(position: Int) = when (entries[position]) {
        is TimelineEntry.Header -> TYPE_HEADER
        is TimelineEntry.Photo -> TYPE_PHOTO
        is TimelineEntry.Period -> TYPE_PERIOD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_timeline_header, parent, false))
            TYPE_PHOTO -> PhotoHolder(inflater.inflate(R.layout.item_timeline_photo, parent, false))
            else -> PeriodHolder(inflater.inflate(R.layout.item_timeline_period, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (pagingLevel != null) paging.getItem(position)
        when (val entry = entries[position]) {
            is TimelineEntry.Header -> (holder as HeaderHolder).bind(entry)
            is TimelineEntry.Photo -> (holder as PhotoHolder).bind(entry)
            is TimelineEntry.Period -> (holder as PeriodHolder).bind(entry)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        listOf(R.id.photo_thumbnail, R.id.period_cover, R.id.period_sample_2, R.id.period_sample_3)
            .mapNotNull { holder.itemView.findViewById<ImageView?>(it) }
            .forEach(::cancelLoad)
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        listOf(R.id.photo_thumbnail, R.id.period_cover, R.id.period_sample_2, R.id.period_sample_3)
            .mapNotNull { holder.itemView.findViewById<ImageView?>(it) }
            .forEach(::prioritizeAttached)
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(entry: TimelineEntry.Header) {
            (itemView as TextView).text = entry.label
            itemView.contentDescription = entry.label
        }
    }

    private inner class PhotoHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image = view.findViewById<ImageView>(R.id.photo_thumbnail)
        private val marker = view.findViewById<TextView>(R.id.photo_selected)
        fun bind(entry: TimelineEntry.Photo) {
            val photo = entry.photo
            val height = if (level == TimelineLevel.PHOTO) {
                photoTileSize(availableWidth.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels)
            } else dp(158)
            itemView.layoutParams = itemView.layoutParams.apply { this.height = height }
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            itemView.isActivated = photo.id in selected
            marker.visibility = if (itemView.isActivated) View.VISIBLE else View.GONE
            itemView.contentDescription = context.getString(R.string.imported_photo, bindingAdapterPosition + 1)
            itemView.setOnClickListener { onPhotoClick(photo) }
            itemView.setOnLongClickListener { onPhotoLongClick(photo); true }
            load(photo, image, 640)
        }
    }

    private inner class PeriodHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image = view.findViewById<ImageView>(R.id.period_cover)
        private val second = view.findViewById<ImageView>(R.id.period_sample_2)
        private val third = view.findViewById<ImageView>(R.id.period_sample_3)
        private val title = view.findViewById<TextView>(R.id.period_title)
        private val count = view.findViewById<TextView>(R.id.period_count)
        fun bind(entry: TimelineEntry.Period) {
            title.text = entry.label
            count.text = context.resources.getQuantityString(R.plurals.timeline_photo_count, entry.count, entry.count)
            itemView.contentDescription = "${entry.label}, ${count.text}"
            itemView.setOnClickListener { onPeriodClick(entry) }
            load(entry.cover, image, 720)
            val extra = if (level == TimelineLevel.WEEKS) entry.samples.drop(1) else emptyList()
            bindSample(second, extra.getOrNull(0))
            bindSample(third, extra.getOrNull(1))
        }

        private fun bindSample(view: ImageView, photo: GalleryPhoto?) {
            view.visibility = if (photo == null) View.GONE else View.VISIBLE
            if (photo == null) {
                cancelLoad(view)
                view.setImageBitmap(null)
            } else load(photo, view, 480)
        }
    }

    private fun load(
        photo: GalleryPhoto,
        view: ImageView,
        edge: Int,
        forceReload: Boolean = false,
    ) {
        val priority = thumbnailPriority(view.isAttachedToWindow)
        val key = ThumbnailKey(
            photo.id,
            photo.sourceRevision,
            edge,
            if (photo.source == io.github.mesteriis.lik.gallery.PhotoSource.DEVICE) accessEpoch.current else IMPORT_EPOCH,
        )
        val existing = view.tag as? ThumbnailBinding
        if (!forceReload && existing?.key == key && existing.priority == priority) return
        cancelLoad(view)
        boundImages += view
        view.setOnClickListener(null)
        view.isClickable = false
        view.setImageBitmap(null)
        val requiredRevealEpoch=privacyEpoch.takeIf { includeProtected }
        val request = thumbnailLoader.loadForBinding(key, view.isAttachedToWindow, {
            if(!io.github.mesteriis.lik.privacy.SensitiveImagePublication.accepts(imageDatabase,photo,requiredRevealEpoch))null
            else try { GalleryCatalog.decode(context, photo, edge)?.takeIf { bitmap ->
                io.github.mesteriis.lik.privacy.SensitiveImagePublication.accepts(imageDatabase,photo,requiredRevealEpoch).also { if(!it)bitmap.recycle() }
            } } catch (_: Exception) { null }
        }) { result ->
            main.post {
                if (!closed && acceptsThumbnailResult(photo, key) && (view.tag as? ThumbnailBinding)?.let{it.key==key&&it.privacyEpoch==privacyEpoch}==true) {
                    if(!io.github.mesteriis.lik.privacy.SensitiveImagePublication.accepts(imageDatabase,photo,requiredRevealEpoch)) {
                        thumbnailLoader.invalidate { it==key };view.setImageDrawable(null);return@post
                    }
                    if (result.value != null) {
                        view.setImageBitmap(result.value)
                    } else {
                        view.setImageResource(android.R.drawable.ic_menu_report_image)
                        view.setOnClickListener { load(photo, view, edge, forceReload = true) }
                    }
                }
            }
        }
        view.tag = ThumbnailBinding(key, photo, edge, request.priority, request,privacyEpoch)
    }

    fun forget(ids: Set<String>) {
        thumbnailLoader.invalidate { it.photoId in ids }
    }

    fun close() {
        closed = true
        imageDatabase.invalidationTracker.removeObserver(imageInvalidation)
        boundImages.clear()
        pagingScope.cancel()
        timelineController.close()
        timelineWorker.shutdownNow()
        thumbnailLoader.close()
    }
    private fun cancelLoad(view: ImageView) {
        boundImages -= view
        (view.tag as? ThumbnailBinding)?.request?.cancel()
        view.tag = null
    }
    private fun prioritizeAttached(view: ImageView) {
        val binding = view.tag as? ThumbnailBinding ?: return
        if (binding.priority != ThumbnailPriority.VISIBLE) {
            load(binding.photo, view, binding.edge)
        }
    }
    private fun acceptsThumbnailResult(photo: GalleryPhoto, key: ThumbnailKey) =
        photo.source != io.github.mesteriis.lik.gallery.PhotoSource.DEVICE || accessEpoch.accepts(key)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private data class ThumbnailBinding(
        val key: ThumbnailKey,
        val photo: GalleryPhoto,
        val edge: Int,
        val priority: ThumbnailPriority,
        val request: ThumbnailRequest<Bitmap>,
        val privacyEpoch:Long,
    )

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1
        private const val TYPE_PERIOD = 2
        private const val THUMBNAIL_QUEUE_CAPACITY = 24
        private const val IMPORT_EPOCH = 0L
        private const val DEVICE_PHOTO_PREFIX = "device:"
    }
}

internal fun photoSpanSize(spanCount: Int): Int = (spanCount / 3).coerceAtLeast(1)

internal fun photoTileSize(availableWidth: Int): Int = (availableWidth / 3).coerceAtLeast(1)
