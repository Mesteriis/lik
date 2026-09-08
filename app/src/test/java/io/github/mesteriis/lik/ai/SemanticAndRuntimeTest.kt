package io.github.mesteriis.lik.ai

import org.junit.Assert.*
import org.junit.Test

class SemanticAndRuntimeTest {
    @Test fun exactSearchUsesCosineAndStableMediaIdTies() {
        val index = ExactVectorIndex(2)
        index.upsert("z", floatArrayOf(1f, 0f))
        index.upsert("a", floatArrayOf(1f, 0f))
        index.upsert("b", floatArrayOf(0f, 1f))
        assertEquals(listOf("a", "z", "b"), index.search(floatArrayOf(1f, 0f), 3).map { it.mediaId })
        index.remove("a")
        assertEquals(listOf("z", "b"), index.search(floatArrayOf(1f, 0f), 3).map { it.mediaId })
    }

    @Test fun dimensionsAndFiniteValuesAreEnforced() {
        val index = ExactVectorIndex(2)
        assertThrows(IllegalArgumentException::class.java) { index.upsert("bad", floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) { index.upsert("bad", floatArrayOf(Float.NaN, 1f)) }
    }

    @Test fun interactiveWorkPreemptsAtBackgroundBoundaryAndOnlyOneLeaseRuns() {
        val scheduler = InferenceScheduler()
        val background = scheduler.offer(InferenceRequest("bg", InferencePriority.BACKGROUND))
        assertEquals(background, scheduler.acquire())
        val interactive = scheduler.offer(InferenceRequest("q", InferencePriority.INTERACTIVE))
        assertNull(scheduler.acquire())
        scheduler.release(background)
        assertEquals(interactive, scheduler.acquire())
    }

    @Test fun leasesAreInvalidAfterRuntimeProcessGenerationChanges() {
        val coordinator = RuntimeLeases()
        val lease = coordinator.acquire(setOf("digest"))
        assertTrue(coordinator.valid(lease))
        coordinator.runtimeDied()
        assertFalse(coordinator.valid(lease))
        assertTrue(coordinator.liveDigests().isEmpty())
    }

    @Test fun approximateResultsMustMeetExactReferenceContract() {
        val exact = listOf(VectorHit("a", .9f), VectorHit("b", .8f), VectorHit("c", .7f))
        assertTrue(SearchParity.accept(exact, exact, 3))
        assertFalse(SearchParity.accept(exact, listOf(exact[1], exact[0], exact[2]), 3))
    }

    @Test fun interruptedInferenceWaiterIsRemovedAndDoesNotBlockFollowingWork() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val running = Thread { InferenceGate.run(InferencePriority.BACKGROUND) { entered.countDown(); release.await() } }
        running.start(); assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val waiting = Thread { runCatching { InferenceGate.run(InferencePriority.MANUAL) {} } }
        waiting.start(); Thread.sleep(50); waiting.interrupt(); waiting.join(2_000)
        release.countDown(); running.join(2_000)
        assertFalse(waiting.isAlive)
        assertEquals(7, InferenceGate.run(InferencePriority.INTERACTIVE) { 7 })
    }

    @Test fun exactCandidateRerankDoesNotNeedWholeIndex() {
        val candidates = listOf(
            NativeCandidate(7, "b", floatArrayOf(.8f, .2f)),
            NativeCandidate(9, "a", floatArrayOf(1f, 0f)),
        )
        assertEquals(listOf("a", "b"), CandidateReranker.rank(floatArrayOf(1f, 0f), candidates, 2).map { it.mediaId })
    }

    @Test fun samePipelineIndexRunsNeverOverlap() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(2)
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        fun start(first: Boolean) = Thread {
            IndexRunCoordinator.run("same-generation", { false }) {
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                if (first) { entered.countDown(); release.await() }
                active.decrementAndGet()
            }
            done.countDown()
        }.also(Thread::start)
        start(true)
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        start(false)
        Thread.sleep(50)
        assertEquals(1, maximum.get())
        release.countDown()
        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, maximum.get())
    }
}
