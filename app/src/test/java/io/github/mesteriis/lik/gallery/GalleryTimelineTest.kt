package io.github.mesteriis.lik.gallery

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTimelineTest {
    private val utc = ZoneId.of("UTC")
    private val en = Locale.UK

    private fun photo(id: String, taken: String? = null, added: String? = null) = GalleryPhoto(
        id = id,
        source = PhotoSource.DEVICE,
        takenAt = taken?.let { Instant.parse(it).toEpochMilli() },
        addedAt = added?.let { Instant.parse(it).toEpochMilli() } ?: 0,
    )

    @Test fun captureDateWinsAndAddedDateIsTheFallback() {
        val photos = listOf(
            photo("taken", taken = "2020-04-03T12:00:00Z", added = "2026-09-08T12:00:00Z"),
            photo("fallback", added = "2021-05-04T12:00:00Z"),
        )

        val entries = GalleryTimeline.build(photos, TimelineLevel.DAYS, utc, en)
        val headers = entries.filterIsInstance<TimelineEntry.Header>()

        assertEquals(listOf("2021-05-04", "2020-04-03"), headers.map { it.key })
    }

    @Test fun everyPhotoAppearsOnceAndUndatedPhotosComeLast() {
        val photos = listOf(
            photo("b", added = "2026-09-08T10:00:00Z"),
            photo("a", added = "2026-09-08T10:00:00Z"),
            photo("undated-b"),
            photo("undated-a"),
        )

        val entries = GalleryTimeline.build(photos, TimelineLevel.DAYS, utc, en)

        assertEquals(listOf("b", "a", "undated-b", "undated-a"),
            entries.filterIsInstance<TimelineEntry.Photo>().map { it.photo.id })
        assertEquals(listOf("2026-09-08", GalleryTimeline.UNDATED_KEY),
            entries.filterIsInstance<TimelineEntry.Header>().map { it.key })
    }

    @Test fun weeksCrossTheYearBoundaryWithoutDuplicatingPhotos() {
        val photos = listOf(
            photo("sun", added = "2026-01-04T12:00:00Z"),
            photo("mon", added = "2025-12-29T12:00:00Z"),
            photo("next", added = "2026-01-05T12:00:00Z"),
        )

        val periods = GalleryTimeline.build(photos, TimelineLevel.WEEKS, utc, en)
            .filterIsInstance<TimelineEntry.Period>()

        assertEquals(listOf(1, 2), periods.map { it.count })
        assertEquals(listOf("next", "sun", "mon"), periods.flatMap { it.photos }.map { it.id })
        assertTrue(periods.last().label.contains("2025"))
        assertTrue(periods.last().label.contains("2026"))
    }

    @Test fun monthAndYearPeriodsUseNewestCoverAndTotalCount() {
        val photos = listOf(
            photo("new", added = "2026-09-20T12:00:00Z"),
            photo("middle", added = "2026-09-10T12:00:00Z"),
            photo("old", added = "2026-09-01T12:00:00Z"),
            photo("previous", added = "2025-01-01T12:00:00Z"),
        )

        val month = GalleryTimeline.build(photos, TimelineLevel.MONTHS, utc, en)
            .filterIsInstance<TimelineEntry.Period>().first()
        val years = GalleryTimeline.build(photos, TimelineLevel.YEARS, utc, en)
            .filterIsInstance<TimelineEntry.Period>()

        assertEquals("new", month.cover.id)
        assertEquals(3, month.count)
        assertEquals(listOf("2026", "2025"), years.map { it.key })
        assertEquals(listOf(3, 1), years.map { it.count })
    }

    @Test fun weekSamplesAreOldestMiddleNewestWithoutDuplicates() {
        val photos = (1..5).map { day ->
            photo("p$day", added = "2026-09-0${day}T12:00:00Z")
        }

        val week = GalleryTimeline.build(photos, TimelineLevel.WEEKS, utc, en)
            .filterIsInstance<TimelineEntry.Period>().single()

        assertEquals(listOf("p1", "p3", "p5"), week.samples.map { it.id })
    }

    @Test fun undatedOverviewIsOneCardThatTargetsDays() {
        val photos = listOf(photo("dated", added = "2026-09-08T12:00:00Z"), photo("undated"))

        val cards = GalleryTimeline.build(photos, TimelineLevel.YEARS, utc, en)
            .filterIsInstance<TimelineEntry.Period>()
        val undated = cards.last()

        assertEquals(GalleryTimeline.UNDATED_KEY, undated.key)
        assertEquals(1, undated.count)
        assertEquals(TimelineLevel.DAYS, undated.targetLevel)
    }
}
