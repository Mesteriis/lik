package io.github.mesteriis.lik.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import io.github.mesteriis.lik.R
import io.github.mesteriis.lik.gallery.GalleryCatalog
import io.github.mesteriis.lik.gallery.GalleryPhoto
import io.github.mesteriis.lik.gallery.TimelineEntry
import io.github.mesteriis.lik.gallery.TimelineLevel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var entries: List<TimelineEntry> = emptyList()
    private var selected: Set<String> = emptySet()
    private var level = TimelineLevel.DAYS
    private var availableWidth = context.resources.displayMetrics.widthPixels
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var closed = false

    init { setHasStableIds(true) }

    fun submit(items: List<TimelineEntry>, timelineLevel: TimelineLevel) {
        val previous = entries
        val previousLevel = level
        val difference = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous[oldItemPosition].stableKey == items[newItemPosition].stableKey
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous[oldItemPosition] == items[newItemPosition] && previousLevel == timelineLevel
        })
        entries = items
        level = timelineLevel
        difference.dispatchUpdatesTo(this)
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

    fun entryAt(position: Int): TimelineEntry = entries[position]

    override fun getItemCount() = entries.size
    override fun getItemId(position: Int) = entries[position].stableKey.hashCode().toLong()
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
        when (val entry = entries[position]) {
            is TimelineEntry.Header -> (holder as HeaderHolder).bind(entry)
            is TimelineEntry.Photo -> (holder as PhotoHolder).bind(entry)
            is TimelineEntry.Period -> (holder as PeriodHolder).bind(entry)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        holder.itemView.findViewById<ImageView?>(R.id.photo_thumbnail)?.tag = null
        holder.itemView.findViewById<ImageView?>(R.id.period_cover)?.tag = null
        holder.itemView.findViewById<ImageView?>(R.id.period_sample_2)?.tag = null
        holder.itemView.findViewById<ImageView?>(R.id.period_sample_3)?.tag = null
        super.onViewRecycled(holder)
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
                view.tag = null
                view.setImageBitmap(null)
            } else load(photo, view, 480)
        }
    }

    private fun load(photo: GalleryPhoto, view: ImageView, edge: Int) {
        val key = "${photo.id}:$edge"
        view.tag = key
        view.setOnClickListener(null)
        view.isClickable = false
        view.setImageBitmap(null)
        cache.get(key)?.let { view.setImageBitmap(it); return }
        worker.execute {
            val bitmap = try { GalleryCatalog.decode(context, photo, edge) } catch (_: Exception) { null }
            main.post {
                if (!closed && view.tag == key) {
                    if (bitmap != null) {
                        cache.put(key, bitmap)
                        view.setImageBitmap(bitmap)
                    } else {
                        view.setImageResource(android.R.drawable.ic_menu_report_image)
                        view.setOnClickListener { load(photo, view, edge) }
                    }
                }
            }
        }
    }

    fun forget(ids: Set<String>) {
        cache.snapshot().keys.filter { key -> ids.any { key.startsWith("$it:") } }.forEach(cache::remove)
    }

    fun close() { closed = true; worker.shutdownNow(); cache.evictAll() }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    companion object { private const val TYPE_HEADER = 0; private const val TYPE_PHOTO = 1; private const val TYPE_PERIOD = 2 }
}

internal fun photoSpanSize(spanCount: Int): Int = (spanCount / 3).coerceAtLeast(1)

internal fun photoTileSize(availableWidth: Int): Int = (availableWidth / 3).coerceAtLeast(1)
