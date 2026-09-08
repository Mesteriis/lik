package io.github.mesteriis.lik.ui

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailLoaderTest {
    @Test fun duplicateVisibleRequestsShareOneDecodeAndBothReceiveTheBitmap() {
        val executor = QueuedExecutor()
        val loader = ThumbnailLoader<String>(executor, MapThumbnailCache(), queueCapacity = 2)
        val received = mutableListOf<String>()
        var decodes = 0
        val key = ThumbnailKey("photo", 3, 256, 7)

        val first = loader.load(key, ThumbnailPriority.VISIBLE, { decodes++; "bitmap" }) { received += it.value!! }
        val second = loader.load(key, ThumbnailPriority.VISIBLE, { decodes++; "wrong" }) { received += it.value!! }
        executor.runAll()

        assertEquals(ThumbnailRequestDisposition.QUEUED, first.disposition)
        assertEquals(ThumbnailRequestDisposition.COALESCED, second.disposition)
        assertEquals(1, decodes)
        assertEquals(listOf("bitmap", "bitmap"), received)
        assertEquals(1, loader.snapshot().coalesced)
    }

    @Test fun recycledQueuedRequestIsCancelledBeforeItsDecodeStarts() {
        val executor = QueuedExecutor()
        val loader = ThumbnailLoader<String>(executor, MapThumbnailCache(), queueCapacity = 1)
        var thirdDecodes = 0
        var delivered = false

        loader.load(ThumbnailKey("active-one", 1, 128, 1), ThumbnailPriority.VISIBLE, { "one" }) { }
        loader.load(ThumbnailKey("active-two", 1, 128, 1), ThumbnailPriority.VISIBLE, { "two" }) { }
        val recycled = loader.load(ThumbnailKey("recycled", 1, 128, 1), ThumbnailPriority.VISIBLE, { thirdDecodes++; "three" }) {
            delivered = true
        }
        recycled.cancel()
        executor.runAll()

        assertEquals(0, thirdDecodes)
        assertTrue(!delivered)
        assertEquals(1, loader.snapshot().cancelled)
    }

    @Test fun visibleRequestEvictsQueuedPrefetchWhenCapacityIsFull() {
        val executor = QueuedExecutor()
        val loader = ThumbnailLoader<String>(executor, MapThumbnailCache(), queueCapacity = 1)
        var prefetchCancelled = false
        var prefetchDecodes = 0
        var visibleDecodes = 0

        loader.load(ThumbnailKey("active-one", 1, 128, 1), ThumbnailPriority.VISIBLE, { "one" }) { }
        loader.load(ThumbnailKey("active-two", 1, 128, 1), ThumbnailPriority.VISIBLE, { "two" }) { }
        loader.load(ThumbnailKey("prefetch", 1, 128, 1), ThumbnailPriority.PREFETCH, { prefetchDecodes++; "prefetch" }) {
            prefetchCancelled = it.cancelled
        }
        val visible = loader.load(ThumbnailKey("visible", 1, 128, 1), ThumbnailPriority.VISIBLE, { visibleDecodes++; "visible" }) { }
        executor.runAll()

        assertEquals(ThumbnailRequestDisposition.QUEUED, visible.disposition)
        assertTrue(prefetchCancelled)
        assertEquals(0, prefetchDecodes)
        assertEquals(1, visibleDecodes)
        assertEquals(1, loader.snapshot().cancelled)
    }

    @Test fun saturatedLoaderRejectsExtraWorkWithoutDecodingOnTheCallerThread() {
        val executor = QueuedExecutor()
        val loader = ThumbnailLoader<String>(executor, MapThumbnailCache(), queueCapacity = 1)
        var rejectedDecodes = 0

        loader.load(ThumbnailKey("active-one", 1, 128, 1), ThumbnailPriority.VISIBLE, { "one" }) { }
        loader.load(ThumbnailKey("active-two", 1, 128, 1), ThumbnailPriority.VISIBLE, { "two" }) { }
        loader.load(ThumbnailKey("queued", 1, 128, 1), ThumbnailPriority.VISIBLE, { "queued" }) { }
        val rejected = loader.load(ThumbnailKey("rejected", 1, 128, 1), ThumbnailPriority.PREFETCH, { rejectedDecodes++; "bad" }) { }

        assertEquals(ThumbnailRequestDisposition.REJECTED, rejected.disposition)
        assertEquals(0, rejectedDecodes)
        assertEquals(1, loader.snapshot().rejected)
    }

    private class QueuedExecutor : Executor {
        private val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.addLast(command) }
        fun runAll() { while (work.isNotEmpty()) work.removeFirst().run() }
    }
}
