package io.github.mesteriis.lik.gallery

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

enum class TimelineLevel { PHOTO, DAYS, WEEKS, MONTHS, YEARS;
    fun closer() = entries.getOrNull(ordinal - 1) ?: this
    fun farther() = entries.getOrNull(ordinal + 1) ?: this
}

sealed interface TimelineEntry {
    val stableKey: String

    data class Header(
        val key: String,
        val label: String,
        val anchorId: String,
    ) : TimelineEntry {
        override val stableKey = "header:$key"
    }

    data class Photo(
        val photo: GalleryPhoto,
        val indexInGroup: Int,
        val groupSize: Int,
    ) : TimelineEntry {
        override val stableKey = "photo:${photo.id}"
    }

    data class Period(
        val key: String,
        val label: String,
        val count: Int,
        val cover: GalleryPhoto,
        val samples: List<GalleryPhoto>,
        val photos: List<GalleryPhoto>,
        val targetLevel: TimelineLevel,
    ) : TimelineEntry {
        override val stableKey = "period:$key"
    }
}

object GalleryTimeline {
    const val UNDATED_KEY = "undated"

    fun build(
        source: List<GalleryPhoto>,
        level: TimelineLevel,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<TimelineEntry> {
        val photos = source.sortedWith(
            compareByDescending<GalleryPhoto> { it.timelineAt ?: Long.MIN_VALUE }
                .thenByDescending { it.id },
        )
        if (level == TimelineLevel.PHOTO) return photos.mapIndexed { index, photo ->
            TimelineEntry.Photo(photo, index, photos.size)
        }

        val dated = photos.filter { it.timelineAt != null }
        val undated = photos.filter { it.timelineAt == null }
        val groups = when (level) {
            TimelineLevel.DAYS -> dated.groupBy { localDate(it, zoneId).toString() }
            TimelineLevel.WEEKS -> {
                val firstDay = WeekFields.of(locale).firstDayOfWeek
                dated.groupBy {
                    localDate(it, zoneId).with(TemporalAdjusters.previousOrSame(firstDay)).toString()
                }
            }
            TimelineLevel.MONTHS -> dated.groupBy { YearMonth.from(localDate(it, zoneId)).toString() }
            TimelineLevel.YEARS -> dated.groupBy { localDate(it, zoneId).year.toString() }
            TimelineLevel.PHOTO -> error("Handled above")
        }
        val result = mutableListOf<TimelineEntry>()
        groups.forEach { (key, group) ->
            if (level == TimelineLevel.DAYS) {
                result += TimelineEntry.Header(key, dayLabel(LocalDate.parse(key), locale), group.first().id)
                result += group.mapIndexed { index, photo -> TimelineEntry.Photo(photo, index, group.size) }
            } else {
                result += period(key, group, level, locale)
            }
        }
        if (undated.isNotEmpty()) {
            if (level == TimelineLevel.DAYS) {
                result += TimelineEntry.Header(UNDATED_KEY, undatedLabel(locale), undated.first().id)
                result += undated.mapIndexed { index, photo -> TimelineEntry.Photo(photo, index, undated.size) }
            } else {
                result += TimelineEntry.Period(
                    key = UNDATED_KEY,
                    label = undatedLabel(locale),
                    count = undated.size,
                    cover = undated.first(),
                    samples = samples(undated),
                    photos = undated,
                    targetLevel = TimelineLevel.DAYS,
                )
            }
        }
        return result
    }

    private fun localDate(photo: GalleryPhoto, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(requireNotNull(photo.timelineAt)).atZone(zoneId).toLocalDate()

    private fun period(
        key: String,
        photos: List<GalleryPhoto>,
        level: TimelineLevel,
        locale: Locale,
    ) = TimelineEntry.Period(
        key = key,
        label = when (level) {
            TimelineLevel.WEEKS -> weekLabel(LocalDate.parse(key), locale)
            TimelineLevel.MONTHS -> YearMonth.parse(key).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            TimelineLevel.YEARS -> key
            else -> error("Period is only valid for overview levels")
        },
        count = photos.size,
        cover = photos.first(),
        samples = samples(photos),
        photos = photos,
        targetLevel = level.closer(),
    )

    private fun samples(newestFirst: List<GalleryPhoto>): List<GalleryPhoto> {
        val chronological = newestFirst.asReversed()
        if (chronological.size <= 3) return chronological
        return listOf(chronological.first(), chronological[chronological.lastIndex / 2], chronological.last())
    }

    private fun dayLabel(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

    private fun weekLabel(start: LocalDate, locale: Locale): String {
        val end = start.plusDays(6)
        val format = DateTimeFormatter.ofPattern("d MMM uuuu", locale)
        return "${start.format(format)} – ${end.format(format)}"
    }

    private fun undatedLabel(locale: Locale) = if (locale.language == "ru") "Без даты" else "No date"
}
