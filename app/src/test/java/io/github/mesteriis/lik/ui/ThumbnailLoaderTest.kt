package io.github.mesteriis.lik.ui

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailLoaderTest {
    @Test fun productionBindingSubmissionUsesItsAttachmentPriority() {
        val loader = ThumbnailLoader<String>(QueuedExecutor(), MapThumbnailCache(), queueCapacity = 1)
        val detached = loader.loadForBinding(ThumbnailKey("detached", 1, 128, 1), isAttached = false, { "prefetch" }) { }
        val attached = loader.loadForBinding(ThumbnailKey("attached", 1, 128, 1), isAttached = true, { "visible" }) { }

        assertEquals(ThumbnailPriority.PREFETCH, detached.priority)
        assertEquals(ThumbnailPriority.VISIBLE, attached.priority)
    }

    @Test fun invalidatingEpochDropsQueuedWorkAndLetsNewEpochQueue() {
        val executor = QueuedExecutor()
        val cache = MapThumbnailCache<String>()
        val loader = ThumbnailLoader(executor, cache, queueCapacity = 1)
        var queuedDecodes = 0
        var queuedDelivered = false
        var freshDecodes = 0

        loader.load(ThumbnailKey("active-one", 1, 128, 0), ThumbnailPriority.VISIBLE, { "one" }) { }
        loader.load(ThumbnailKey("active-two", 1, 128, 0), ThumbnailPriority.VISIBLE, { "two" }) { }
        val stale = ThumbnailKey("stale", 1, 128, 0)
        loader.load(stale, ThumbnailPriority.PREFETCH, { queuedDecodes++; "stale" }) { queuedDelivered = true }
        loader.invalidate { it.accessEpoch == 0L }
        val fresh = loader.load(ThumbnailKey("fresh", 1, 128, 1), ThumbnailPriority.VISIBLE, { freshDecodes++; "fresh" }) { }
        executor.runAll()

        assertEquals(ThumbnailRequestDisposition.QUEUED, fresh.disposition)
        assertEquals(0, queuedDecodes)
        assertTrue(!queuedDelivered)
        assertEquals(1, freshDecodes)
    }

    @Test fun invalidatingEpochDiscardsRunningResultAndDetachesItsListener() {
        val executor = QueuedExecutor()
        val cache = MapThumbnailCache<String>()
        val loader = ThumbnailLoader(executor, cache, queueCapacity = 1)
        val stale = ThumbnailKey("stale", 1, 128, 0)
        var delivered = false

        loader.load(stale, ThumbnailPriority.VISIBLE, { "old" }) { delivered = it.value != null }
        loader.invalidate { it.accessEpoch == 0L }
        executor.runAll()

        assertTrue(!delivered)
        assertEquals(null, cache.get(stale))
        assertEquals(1, loader.snapshot().cancelled)
    }

    @Test fun detachedBindingUsesPrefetchAndAttachedBindingUsesVisiblePriority() {
        assertEquals(ThumbnailPriority.PREFETCH, thumbnailPriority(isAttached = false))
        assertEquals(ThumbnailPriority.VISIBLE, thumbnailPriority(isAttached = true))
    }

    @Test fun repeatedAccessRefreshCreatesANewEpochAndRejectsStaleCompletion() {
        val executor = QueuedExecutor()
        val loader = ThumbnailLoader<String>(executor, MapThumbnailCache(), queueCapacity = 1)
        val access = ThumbnailAccessEpoch()
        val staleKey = ThumbnailKey("photo", 1, 128, access.current)
        var displayed = false

        loader.load(staleKey, ThumbnailPriority.VISIBLE, { "old" }) {
            if (access.accepts(staleKey) && it.value != null) displayed = true
        }
        access.refresh()
        executor.runAll()

        assertEquals(staleKey.accessEpoch + 1, access.current)
        assertTrue(!access.accepts(staleKey))
        assertTrue(!displayed)
    }

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
