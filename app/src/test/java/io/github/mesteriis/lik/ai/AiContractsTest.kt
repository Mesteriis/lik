package io.github.mesteriis.lik.ai

import org.junit.Assert.*
import org.junit.Test

class AiContractsTest {
    @Test fun balancedIsTheOnlyFreshInstallSelectionAndIsNotReady() {
        val state = CatalogSnapshot.fresh("catalog-v1")
        assertEquals(ProfileId.BALANCED, state.selected)
        assertNull(state.active)
        assertEquals(ProfilePhase.NOT_INSTALLED, state.profile(ProfileId.BALANCED).phase)
    }

    @Test fun allSixProfileSwitchDirectionsKeepOldProfileUntilGenerationReady() {
        for (from in ProfileId.entries) for (to in ProfileId.entries) if (from != to) {
            var state = CatalogSnapshot.readyForTest(from)
            state = ProfileTransitions.select(state, to, setOf(AiFeature.SEARCH, AiFeature.OCR))
            assertEquals(from, state.active)
            assertEquals(to, state.pending?.profile)
            assertEquals(ProfilePhase.DOWNLOADING, state.profile(to).phase)
            state = ProfileTransitions.downloaded(state, to)
            assertEquals(from, state.active)
            state = ProfileTransitions.selfTested(state, to)
            assertEquals(ProfilePhase.PREPARING, state.profile(to).phase)
            state = ProfileTransitions.generationReady(state, to, AiFeature.SEARCH, "search-$to")
            assertEquals(from, state.active)
            state = ProfileTransitions.generationReady(state, to, AiFeature.OCR, "ocr-$to")
            assertEquals(to, state.active)
            assertNull(state.pending)
        }
    }

    @Test fun cancellationAndFailureNeverBreakServingProfile() {
        val old = CatalogSnapshot.readyForTest(ProfileId.COMPACT)
        val pending = ProfileTransitions.select(old, ProfileId.EXTENDED, setOf(AiFeature.SEARCH))
        val cancelled = ProfileTransitions.cancel(pending, ProfileId.EXTENDED)
        assertEquals(ProfileId.COMPACT, cancelled.active)
        assertEquals(ProfileId.EXTENDED, cancelled.selected)
        assertNull(cancelled.pending)
        assertEquals(ProfilePhase.NOT_INSTALLED, cancelled.profile(ProfileId.EXTENDED).phase)
        val failed = ProfileTransitions.fail(pending, ProfileId.EXTENDED, "HASH_MISMATCH")
        assertEquals(ProfileId.COMPACT, failed.active)
        assertEquals("HASH_MISMATCH", failed.profile(ProfileId.EXTENDED).error)
    }

    @Test fun cancellingPreparationKeepsVerifiedProfileInstalled() {
        val old = CatalogSnapshot.readyForTest(ProfileId.COMPACT)
        var pending = ProfileTransitions.select(old, ProfileId.EXTENDED, setOf(AiFeature.SEARCH))
        pending = ProfileTransitions.downloaded(pending, ProfileId.EXTENDED)
        pending = ProfileTransitions.selfTested(pending, ProfileId.EXTENDED)

        val cancelled = ProfileTransitions.cancel(pending, ProfileId.EXTENDED)

        assertEquals(ProfileId.COMPACT, cancelled.active)
        assertNull(cancelled.pending)
        assertEquals(ProfilePhase.INSTALLED, cancelled.profile(ProfileId.EXTENDED).phase)
    }

    @Test fun compatibleGenerationsAreReusedByPipelineFingerprint() {
        var state = CatalogSnapshot.readyForTest(ProfileId.COMPACT)
        state = state.copy(generations = state.generations + ("shared-ocr" to IndexGeneration("shared-ocr", AiFeature.OCR, "ocr-shared", true, 9, 10)))
        state = state.copy(profiles = state.profiles + (ProfileId.BALANCED to ProfileState(ProfilePhase.INSTALLED)))
        val pending = ProfileTransitions.select(state, ProfileId.BALANCED, setOf(AiFeature.OCR), mapOf(AiFeature.OCR to "ocr-shared"))
        assertEquals(ProfileId.BALANCED, pending.active)
        assertEquals("shared-ocr", pending.activeGenerations[AiFeature.OCR])
    }

    @Test fun indexResultRejectsRevisionAndAccessEpochRaces() {
        val task = IndexItem("m", 4, 7, "pipeline")
        assertTrue(task.canPublish("m", 4, 7, true))
        assertFalse(task.canPublish("m", 5, 7, true))
        assertFalse(task.canPublish("m", 4, 8, true))
        assertFalse(task.canPublish("m", 4, 7, false))
    }

    @Test fun cleanupRetainsEveryReferencedDigest() {
        val retained = ArtifactRetention.retained(
            installedProfiles = listOf(setOf("a", "shared"), setOf("b", "shared")),
            activeGenerations = listOf(setOf("index", "shared")),
            staged = setOf("staged"), leases = setOf("leased"),
        )
        assertEquals(setOf("a", "b", "shared", "index", "staged", "leased"), retained)
        assertEquals(setOf("orphan"), ArtifactRetention.collectable(setOf("a", "shared", "orphan"), retained))
    }
}
