package io.github.mesteriis.lik.ui

import io.github.mesteriis.lik.gallery.GalleryPhoto
import io.github.mesteriis.lik.gallery.PhotoSource
import io.github.mesteriis.lik.gallery.TimelineEntry
import io.github.mesteriis.lik.gallery.TimelineLevel
import java.time.ZoneId
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineControllerTest {
    @Test fun supersededRevisionIsNeverPublishedAndLatestInputIsCopiedBeforeWorkStarts() {
        val executor = QueuedExecutor()
        val published = mutableListOf<TimelineRevision>()
        val controller = TimelineController(executor, ZoneId.of("UTC")) { published += it }
        val first = mutableListOf(photo("first"))

        controller.submit(first, TimelineLevel.PHOTO)
        first += photo("mutated-after-submit")
        controller.submit(listOf(photo("latest")), TimelineLevel.PHOTO)

        executor.runNext()
        assertTrue(published.isEmpty())
        executor.runNext()

        assertEquals(1, published.size)
        assertEquals(listOf("latest"), published.single().entries.photoIds())
        assertTrue(controller.publish(published.single()))
    }

    @Test fun timelineBuildWaitsForTheWorkerInsteadOfRunningOnTheSubmittingThread() {
        val executor = QueuedExecutor()
        val published = mutableListOf<TimelineRevision>()
        val controller = TimelineController(executor, ZoneId.of("UTC")) { published += it }

        controller.submit(listOf(photo("queued")), TimelineLevel.PHOTO)

        assertTrue(published.isEmpty())
        executor.runNext()
        assertEquals(listOf("queued"), published.single().entries.photoIds())
    }

    @Test fun importProgressWithTheSamePhotosDoesNotScheduleAnotherTimelineBuild() {
        val executor = QueuedExecutor()
        val published = mutableListOf<TimelineRevision>()
        val controller = TimelineController(executor, ZoneId.of("UTC")) { published += it }
        val photos = listOf(photo("stable"))

        controller.submit(photos, TimelineLevel.DAYS)
        executor.runNext()
        assertTrue(controller.publish(published.single()))
        controller.submit(photos, TimelineLevel.DAYS)

        assertEquals(0, executor.size)
    }

    private fun photo(id: String) = GalleryPhoto(id, PhotoSource.GOOGLE_IMPORT)
    private fun List<TimelineEntry>.photoIds() = filterIsInstance<TimelineEntry.Photo>().map { it.photo.id }

    private class QueuedExecutor : Executor {
        private val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.addLast(command) }
        fun runNext() = work.removeFirst().run()
        val size get() = work.size
    }
}
