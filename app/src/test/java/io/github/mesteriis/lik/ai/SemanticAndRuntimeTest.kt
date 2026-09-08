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
}
