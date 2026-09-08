package io.github.mesteriis.lik.catalog

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.paging.insertSeparators
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.mesteriis.lik.gallery.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Paging retains at most three photo pages; overview rows contain at most three covers. */
class CatalogPaging(private val dao: MediaDao, private val undatedLabel: String, private val privateFile: (String) -> File) {
    suspend fun flow(level: TimelineLevel, anchorId: String?, fallbackRank: Int): Flow<PagingData<TimelineEntry>> {
        val config = PagingConfig(pageSize = 60, initialLoadSize = 60, prefetchDistance = 15, maxSize = 180, enablePlaceholders = false)
        val anchor = withContext(Dispatchers.IO) { anchorId?.let(dao::get) }
        if (level == TimelineLevel.PHOTO || level == TimelineLevel.DAYS) {
            val rank = withContext(Dispatchers.IO) { anchor?.let { dao.rank(it.mediaId, it.sortAt) } ?: fallbackRank }
            return Pager(config, initialKey = rank.coerceAtLeast(0), pagingSourceFactory = dao::feed).flow.map { data ->
                val photos = data.map { record ->
                    withContext(Dispatchers.IO) {
                        val group = if (level == TimelineLevel.DAYS) dao.dayPosition(record.mediaId, record.sortAt, record.dayKey) else 0
                        val count = if (level == TimelineLevel.DAYS) dao.dayCount(record.dayKey) else 0
                        TimelineEntry.Photo(record.toGalleryPhoto(privateFile), group, count, dao.rank(record.mediaId, record.sortAt)) as TimelineEntry
                    }
                }
                if (level == TimelineLevel.PHOTO) photos else photos.insertSeparators { before, after ->
                    val next = (after as? TimelineEntry.Photo)?.photo ?: return@insertSeparators null
                    val previous = (before as? TimelineEntry.Photo)?.photo
                    val key = day(next)
                    if (previous == null || day(previous) != key) TimelineEntry.Header(key, label(key, TimelineLevel.DAYS), next.id) else null
                }
            }
        }
        val column = when (level) { TimelineLevel.WEEKS -> "weekKey"; TimelineLevel.MONTHS -> "monthKey"; else -> "yearKey" }
        val rank = withContext(Dispatchers.IO) {
            if (anchor == null) 0 else dao.periodRank(column, anchor.sortAt)
        }
        return Pager(config, initialKey = rank, pagingSourceFactory = { dao.periodFeed(SimpleSQLiteQuery(periodSql(column))) }).flow.map { data ->
            data.map { summary ->
                withContext(Dispatchers.IO) {
                    val covers = dao.covers(column, summary.periodKey).map { it.toGalleryPhoto(privateFile) }
                    // Room invalidates a generation if rows vanish between aggregate and cover reads.
                    if (covers.isEmpty()) TimelineEntry.Header(summary.periodKey, label(summary.periodKey, level), "")
                    else TimelineEntry.Period(summary.periodKey, label(summary.periodKey, level), summary.count,
                        covers.first(), covers, covers, level.closer(), dao.rank(covers.first().id, summary.newestAt))
                }
            }
        }
    }

    private fun day(photo: GalleryPhoto): String = photo.timelineAt?.let {
        java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString()
    } ?: "undated"

    var zone: java.time.ZoneId = java.time.ZoneId.systemDefault()

    private fun label(key: String, level: TimelineLevel): String {
        val locale = Locale.getDefault()
        if (key == "undated") return undatedLabel
        return when (level) {
            TimelineLevel.DAYS -> LocalDate.parse(key).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
            TimelineLevel.WEEKS -> {
                val first = LocalDate.parse(key)
                val format = DateTimeFormatter.ofPattern("d MMM uuuu", locale)
                "${first.format(format)} – ${first.plusDays(6).format(format)}"
            }
            TimelineLevel.MONTHS -> YearMonth.parse(key).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            else -> key
        }
    }
}
