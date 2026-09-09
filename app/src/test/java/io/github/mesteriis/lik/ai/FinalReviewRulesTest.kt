package io.github.mesteriis.lik.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FinalReviewRulesTest {
    @Test fun catalogRepairOnlyReplaysARevisionWhoseRootInheritedFailure() {
        val root=java.util.UUID.randomUUID();val downstream=java.util.UUID.randomUUID()
        assertTrue(AiCatalogScheduler.shouldRepair(root,root,androidx.work.WorkInfo.State.FAILED,0))
        assertFalse(AiCatalogScheduler.shouldRepair(root,downstream,androidx.work.WorkInfo.State.FAILED,0))
        assertFalse(AiCatalogScheduler.shouldRepair(root,root,androidx.work.WorkInfo.State.FAILED,1))
        assertFalse(AiCatalogScheduler.shouldRepair(root,root,androidx.work.WorkInfo.State.SUCCEEDED,0))
    }

    @Test fun catalogPlanningKeepsServingAndPreparingFeaturesAndHonorsOptOut() {
        assertTrue(AiCatalogScheduler.plans(CatalogSnapshot.fresh("v")).isEmpty())
        val active=CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(enabledFeatures=setOf(AiFeature.SEARCH),
            profiles=CatalogSnapshot.readyForTest(ProfileId.COMPACT).profiles+(ProfileId.BALANCED to ProfileState(ProfilePhase.PREPARING)),
            pending=PendingProfile(ProfileId.BALANCED,setOf(AiFeature.OCR,AiFeature.PEOPLE)))
        assertEquals(mapOf(ProfileId.COMPACT to setOf(AiFeature.SEARCH),ProfileId.BALANCED to setOf(AiFeature.OCR,AiFeature.PEOPLE)),AiCatalogScheduler.plans(active))
        assertTrue(AiCatalogScheduler.plans(active.copy(enabledFeatures=emptySet(),pending=null)).isEmpty())
        assertEquals(mapOf(ProfileId.COMPACT to setOf(AiFeature.SEARCH)),AiCatalogScheduler.plans(active.copy(profiles=active.profiles+(ProfileId.BALANCED to ProfileState(ProfilePhase.DOWNLOADING)))))
    }
    @Test fun runtimeCancellationDropsQueuedWorkAndForgetsLateCancelIds() {
        val requests=RuntimeCancellation()
        requests.enqueue(1);requests.cancel(1)
        var ran=false
        assertThrows(InterruptedException::class.java){requests.running(1,{}){ran=true}}
        assertFalse(ran);requests.finish(1);requests.cancel(1)
        assertEquals(0,requests.size())
        requests.enqueue(2)
        var terminated=false
        requests.running(2,{terminated=true}){requests.cancel(2);assertTrue(terminated)}
        requests.finish(2)
        repeat(10_000){requests.cancel(it.toLong())}
        assertEquals(0,requests.size())
        requests.enqueue(3)
        assertEquals("interactive",requests.running(3,{}){"interactive"})
        requests.finish(3)
        assertEquals(0,requests.size())
    }
    @Test fun interactiveAdmissionCancelsBackgroundImageAndTextWaits() {
        for (kind in listOf("image", "text")) {
            val pool=Executors.newFixedThreadPool(2);val running=CountDownLatch(1);val cancelled=CountDownLatch(1)
            try {
                pool.submit { runCatching { InferenceGate.run(InferencePriority.BACKGROUND) {
                    running.countDown();RuntimeRequestAwait.await(CountDownLatch(1),20){cancelled.countDown()}
                } } }
                assertTrue(running.await(1,TimeUnit.SECONDS))
                val interactive=pool.submit<String>{InferenceGate.run(InferencePriority.INTERACTIVE){kind}}
                assertEquals(kind,interactive.get(1,TimeUnit.SECONDS))
                assertTrue(cancelled.await(1,TimeUnit.SECONDS))
            } finally { pool.shutdownNow() }
        }
    }
    @Test fun alreadyCancelledInferenceNeverStartsEvenWhenGateIsIdle() {
        var ran=false
        Thread.currentThread().interrupt()
        try {
            runCatching { InferenceGate.run(InferencePriority.BACKGROUND) { ran=true } }
            assertFalse("cancelled background request reached inference",ran)
        } finally { Thread.interrupted() }
    }
    @Test fun clusteringHonorsCancellationBeforePairwiseWork() {
        val faces = List(400) { FaceVector("a$it", FloatArray(128) { 1f }) }
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedException::class.java) { FaceClusterer.cluster(faces, .363f, emptySet()) } }
        finally { Thread.interrupted() }
    }

    @Test fun largeIdenticalFaceLibraryCompletesWithinBoundedWork() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<List<List<String>>> {
                FaceClusterer.cluster(List(8_000) { FaceVector("a${it.toString().padStart(5, '0')}", FloatArray(128) { 1f }) }, .363f, emptySet())
            }
            assertEquals(8_000, future.get(2, TimeUnit.SECONDS).sumOf { it.size })
        } finally { pool.shutdownNow() }
    }
    @Test fun contendingSamePipelineNeverOverlaps() {
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val inside = AtomicInteger()
        val overlaps = AtomicInteger()
        try {
            val tasks = (0..3).map { executor.submit {
                start.await()
                repeat(300_000) {
                    IndexRunCoordinator.run("contended-fingerprint", { false }) {
                        if (inside.incrementAndGet() != 1) overlaps.incrementAndGet()
                        inside.decrementAndGet()
                    }
                }
            } }
            start.countDown()
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals("same fingerprint acquired through different locks", 0, overlaps.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun readyProfileRoundTripsLeaveExactlyOneActiveCard() {
        var state = CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(
            profiles = ProfileId.entries.associateWith { ProfileState(ProfilePhase.INSTALLED) } +
                (ProfileId.COMPACT to ProfileState(ProfilePhase.ACTIVE)),
            generations = mapOf("ocr" to IndexGeneration("ocr", AiFeature.OCR, "shared", true, 0, 0)),
        )
        listOf(ProfileId.BALANCED, ProfileId.EXTENDED, ProfileId.COMPACT).forEach { target ->
            state = ProfileTransitions.select(state, target, setOf(AiFeature.OCR), mapOf(AiFeature.OCR to "shared"))
            assertEquals(setOf(target), state.profiles.filterValues { it.phase == ProfilePhase.ACTIVE }.keys)
            assertNull(state.pending)
        }
    }

    @Test fun posteriorConfidenceMatchesPublisherCtcPostprocess() {
        val probabilities = arrayOf(floatArrayOf(.1f, .8f, .1f), floatArrayOf(.1f, .7f, .2f),
            floatArrayOf(1f, 0f, 0f), floatArrayOf(.05f, .05f, .9f))
        val decoded = CtcDecoder.decode(probabilities, listOf("Я", "A"))
        assertEquals("ЯA", decoded.text)
        assertEquals(.85f, decoded.confidence, .00001f)
        val perfect = CtcDecoder.decode(arrayOf(FloatArray(852) { if (it == 1) 1f else 0f }), List(851) { "x" })
        assertEquals(1f, perfect.confidence, 0f)
    }

    @Test fun persistedDuplicateActiveCardsAreRepairedWithoutChangingServingPointer() {
        val corrupt = CatalogSnapshot.readyForTest(ProfileId.BALANCED).copy(
            profiles = ProfileId.entries.associateWith { ProfileState(ProfilePhase.ACTIVE) },
            verifiedOracles = ProfileId.entries.associateWith { "current" },
        )
        val repaired = CatalogOracleRepair.requireCurrent(corrupt, "current") { true }
        assertEquals(ProfileId.BALANCED, repaired.active)
        assertEquals(setOf(ProfileId.BALANCED), repaired.profiles.filterValues { it.phase == ProfilePhase.ACTIVE }.keys)
    }

    @Test fun malformedPosteriorCannotProduceTrustedText() {
        listOf(floatArrayOf(Float.NaN, 1f), floatArrayOf(-1f, 2f), floatArrayOf(.9f, .9f),
            floatArrayOf(0f, Float.POSITIVE_INFINITY)).forEach { values ->
            assertThrows(IllegalArgumentException::class.java) { CtcDecoder.decode(arrayOf(values), listOf("x")) }
        }
    }
}
