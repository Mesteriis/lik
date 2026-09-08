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

    @Test fun featureChangeKeepsPendingTargetAndCanAtomicallyFinishIt() {
        var state = CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(
            profiles = CatalogSnapshot.readyForTest(ProfileId.COMPACT).profiles +
                (ProfileId.EXTENDED to ProfileState(ProfilePhase.INSTALLED)),
            generations = mapOf("search" to IndexGeneration("search", AiFeature.SEARCH, "search-x", true, 3, 3)),
        )
        state = ProfileTransitions.select(state, ProfileId.EXTENDED, setOf(AiFeature.SEARCH, AiFeature.OCR),
            mapOf(AiFeature.SEARCH to "search-x", AiFeature.OCR to "ocr-x"))
        assertEquals(ProfileId.EXTENDED, state.pending?.profile)
        val changed = ProfileTransitions.featuresChanged(state, setOf(AiFeature.SEARCH),
            mapOf(AiFeature.SEARCH to "search-x"))
        assertEquals(ProfileId.EXTENDED, changed.active)
        assertNull(changed.pending)
        assertEquals("search", changed.activeGenerations[AiFeature.SEARCH])
    }

    @Test fun requestedFeaturesDoNotChangeServingSnapshotUntilPendingProfileIsReady() {
        var state = CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(
            enabledFeatures = emptySet(),
            profiles = CatalogSnapshot.readyForTest(ProfileId.COMPACT).profiles +
                (ProfileId.EXTENDED to ProfileState(ProfilePhase.INSTALLED)),
        )
        state = ProfileTransitions.featuresChanged(state, setOf(AiFeature.SEARCH),
            mapOf(AiFeature.SEARCH to "search-x"))

        assertEquals(emptySet<AiFeature>(), state.enabledFeatures)
        assertEquals(setOf(AiFeature.SEARCH), state.pending!!.enabled)
        assertEquals(ProfileId.COMPACT, state.active)

        state = ProfileTransitions.generationReady(state, ProfileId.COMPACT, AiFeature.SEARCH, "search-generation")
        assertEquals(setOf(AiFeature.SEARCH), state.enabledFeatures)
        assertEquals(ProfileId.COMPACT, state.active)
        assertNull(state.pending)
    }

    @Test fun cancellingOrFailingNewFeaturePreparationKeepsActiveProfileServing() {
        val active = CatalogSnapshot.readyForTest(ProfileId.COMPACT)
        val pending = ProfileTransitions.featuresChanged(active, setOf(AiFeature.SEARCH),
            mapOf(AiFeature.SEARCH to "missing"))
        val cancelled = ProfileTransitions.cancel(pending, ProfileId.COMPACT)
        assertEquals(ProfileId.COMPACT, cancelled.active)
        assertEquals(ProfilePhase.ACTIVE, cancelled.profile(ProfileId.COMPACT).phase)
        val failed = ProfileTransitions.fail(pending, ProfileId.COMPACT, "INDEX_FAILED")
        assertEquals(ProfileId.COMPACT, failed.active)
        assertEquals(ProfilePhase.ACTIVE, failed.profile(ProfileId.COMPACT).phase)
        assertEquals("INDEX_FAILED", failed.profile(ProfileId.COMPACT).error)
    }

    @Test fun catalogVersionMigrationPreservesUserChoiceFeatureAndInstallKnowledge() {
        val old = CatalogSnapshot.readyForTest(ProfileId.EXTENDED).copy(
            catalogVersion = "old", enabledFeatures = setOf(AiFeature.SEARCH),
            profiles = ProfileId.entries.associateWith { if (it == ProfileId.EXTENDED) ProfileState(ProfilePhase.ACTIVE, 9, 9) else ProfileState() },
        )
        val migrated = CatalogMigrations.toVersion(old, "new")
        assertEquals("new", migrated.catalogVersion)
        assertEquals(ProfileId.EXTENDED, migrated.selected)
        assertEquals(setOf(AiFeature.SEARCH), migrated.enabledFeatures)
        assertEquals(ProfilePhase.INSTALLED, migrated.profile(ProfileId.EXTENDED).phase)
        assertNull(migrated.active)
        assertTrue(migrated.generations.isEmpty())
    }

    @Test fun removingGenerationsClearsEveryCatalogPointer() {
        val generation = IndexGeneration("old", AiFeature.SEARCH, "p", true, 1, 1)
        val state = CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(
            generations = mapOf("old" to generation), activeGenerations = mapOf(AiFeature.SEARCH to "old"),
            pending = PendingProfile(ProfileId.EXTENDED, setOf(AiFeature.SEARCH), mapOf(AiFeature.SEARCH to "old")),
        )
        val cleaned = CatalogGenerationCleanup.remove(state, setOf("old"))
        assertTrue(cleaned.generations.isEmpty())
        assertTrue(cleaned.activeGenerations.isEmpty())
        assertTrue(cleaned.pending!!.readyGenerations.isEmpty())
    }

    @Test fun missingPublishedIndexCannotRemainReusableOrActiveAfterRestart() {
        val generation = IndexGeneration("missing", AiFeature.SEARCH, "p", true, 2, 2)
        val state = CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(
            enabledFeatures = setOf(AiFeature.SEARCH), generations = mapOf("missing" to generation),
            activeGenerations = mapOf(AiFeature.SEARCH to "missing"),
        )
        val repaired = CatalogStorageRepair.repair(state) { false }
        assertTrue(repaired.generations.isEmpty())
        assertTrue(repaired.activeGenerations.isEmpty())
        assertTrue(repaired.enabledFeatures.isEmpty())
        assertEquals(setOf(AiFeature.SEARCH), repaired.pending!!.enabled)
        assertEquals(ProfileId.COMPACT, repaired.pending.profile)
    }

    @Test fun indexCannotCompleteWithFailureRaceOrMissingCurrentRows() {
        assertTrue(IndexCompletion.canPublish(3, 3, 0, cancelled = false))
        assertFalse(IndexCompletion.canPublish(3, 2, 0, cancelled = false))
        assertFalse(IndexCompletion.canPublish(3, 3, 1, cancelled = false))
        assertFalse(IndexCompletion.canPublish(3, 3, 0, cancelled = true))
    }
}
