package io.github.mesteriis.lik.ai

import org.junit.Assert.*
import org.junit.Test

class AiContractsTest {
    @Test fun unavailableFeaturesAreDetectedFromPendingRequest() {
        val state = CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(
            pending = PendingProfile(ProfileId.COMPACT, setOf(AiFeature.OCR, AiFeature.PEOPLE)),
        )

        assertEquals(setOf(AiFeature.OCR, AiFeature.PEOPLE),
            FeatureAvailability.unavailableRequested(state, setOf(AiFeature.OCR, AiFeature.PEOPLE)))
    }

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

    @Test fun freshFeatureOptInRemainsSelectedAndUninstalledUntilExplicitDownload() {
        val state = ProfileTransitions.featuresChanged(CatalogSnapshot.fresh("v"), setOf(AiFeature.SEARCH),
            mapOf(AiFeature.SEARCH to "search"))
        assertEquals(ProfileId.BALANCED, state.selected)
        assertEquals(ProfilePhase.NOT_INSTALLED, state.profile(ProfileId.BALANCED).phase)
        assertEquals(setOf(AiFeature.SEARCH), state.enabledFeatures)
        assertNull(state.pending)
    }

    @Test fun replacingPendingProfileDemotesPreviousTarget() {
        val active = CatalogSnapshot.readyForTest(ProfileId.COMPACT)
        val first = ProfileTransitions.select(active, ProfileId.BALANCED, setOf(AiFeature.SEARCH))
        val second = ProfileTransitions.select(first, ProfileId.EXTENDED, setOf(AiFeature.SEARCH))
        assertEquals(ProfileId.EXTENDED, second.pending?.profile)
        assertEquals(ProfilePhase.NOT_INSTALLED, second.profile(ProfileId.BALANCED).phase)
        assertEquals(ProfileId.COMPACT, second.active)
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
        val generation = IndexGeneration("search", AiFeature.SEARCH, "search-current", true, 9, 9)
        val old = CatalogSnapshot.readyForTest(ProfileId.EXTENDED).copy(
            catalogVersion = "old", enabledFeatures = setOf(AiFeature.SEARCH),
            profiles = ProfileId.entries.associateWith { if (it == ProfileId.EXTENDED) ProfileState(ProfilePhase.ACTIVE, 9, 9) else ProfileState() },
            generations = mapOf(generation.id to generation),
            activeGenerations = mapOf(AiFeature.SEARCH to generation.id),
        )
        val migrated = CatalogMigrations.toVersion(old, "new", { it == ProfileId.EXTENDED },
            { _, generation -> generation.pipelineFingerprint == "search-current" })
        assertEquals("new", migrated.catalogVersion)
        assertEquals(ProfileId.EXTENDED, migrated.selected)
        assertEquals(setOf(AiFeature.SEARCH), migrated.enabledFeatures)
        assertEquals(ProfilePhase.ACTIVE, migrated.profile(ProfileId.EXTENDED).phase)
        assertEquals(ProfileId.EXTENDED, migrated.active)
        assertEquals(setOf("search"), migrated.generations.keys)
    }

    @Test fun catalogMigrationNeverMarksMissingArtifactsInstalled() {
        val old = CatalogSnapshot.readyForTest(ProfileId.EXTENDED).copy(catalogVersion = "old",
            enabledFeatures = setOf(AiFeature.SEARCH))
        val migrated = CatalogMigrations.toVersion(old, "new", { false }, { _, _ -> false })
        assertEquals(ProfilePhase.NOT_INSTALLED, migrated.profile(ProfileId.EXTENDED).phase)
        assertNull(migrated.active)
        assertNull(migrated.pending)
        assertEquals(setOf(AiFeature.SEARCH), migrated.enabledFeatures)
    }

    @Test fun catalogMigrationPreservesCompatiblePendingTarget() {
        val old = CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(catalogVersion = "old",
            selected = ProfileId.EXTENDED, pending = PendingProfile(ProfileId.EXTENDED, setOf(AiFeature.SEARCH)),
            profiles = CatalogSnapshot.readyForTest(ProfileId.COMPACT).profiles +
                (ProfileId.EXTENDED to ProfileState(ProfilePhase.PREPARING)))
        val migrated = CatalogMigrations.toVersion(old, "new", { true }, { _, _ -> false })
        assertEquals(ProfileId.COMPACT, migrated.active)
        assertEquals(ProfileId.EXTENDED, migrated.selected)
        assertEquals(ProfileId.EXTENDED, migrated.pending?.profile)
        assertEquals(ProfilePhase.PREPARING, migrated.profile(ProfileId.EXTENDED).phase)
    }

    @Test fun staleOracleStampCannotRemainActiveAndIsPreparedForOfflineRevalidation() {
        val generation = IndexGeneration("search", AiFeature.SEARCH, "search-current", true, 9, 9)
        val old = CatalogSnapshot.readyForTest(ProfileId.EXTENDED).copy(
            enabledFeatures = setOf(AiFeature.SEARCH),
            generations = mapOf(generation.id to generation),
            activeGenerations = mapOf(AiFeature.SEARCH to generation.id),
            verifiedOracles = mapOf(ProfileId.EXTENDED to "oracle-v1"),
        )

        val migrated = CatalogOracleRepair.requireCurrent(old, "oracle-v2") { true }

        assertNull(migrated.active)
        assertEquals(ProfileId.EXTENDED, migrated.selected)
        assertEquals(setOf(AiFeature.SEARCH), migrated.enabledFeatures)
        assertEquals(ProfilePhase.SELF_TESTING, migrated.profile(ProfileId.EXTENDED).phase)
        assertEquals(ProfileId.EXTENDED, migrated.pending?.profile)
        assertEquals(mapOf(AiFeature.SEARCH to "search"), migrated.pending?.readyGenerations)
        assertTrue(migrated.verifiedOracles.isEmpty())
        assertEquals(mapOf("search" to generation), migrated.generations)
    }

    @Test fun currentOracleStampKeepsCompatibleActiveProfileServing() {
        val state = CatalogSnapshot.readyForTest(ProfileId.BALANCED).copy(
            enabledFeatures = setOf(AiFeature.SEARCH),
            verifiedOracles = mapOf(ProfileId.BALANCED to "oracle-v3"),
        )

        val checked = CatalogOracleRepair.requireCurrent(state, "oracle-v3") { true }

        assertSame(state, checked)
        assertEquals(ProfileId.BALANCED, checked.active)
        assertEquals(ProfilePhase.ACTIVE, checked.profile(ProfileId.BALANCED).phase)
        assertNull(checked.pending)
    }

    @Test fun installedProfileWithoutCurrentOracleCannotActivateOnReselection() {
        val current = CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(
            verifiedOracles = mapOf(ProfileId.COMPACT to "oracle-v3"),
            profiles = CatalogSnapshot.readyForTest(ProfileId.COMPACT).profiles +
                (ProfileId.EXTENDED to ProfileState(ProfilePhase.INSTALLED)),
        )

        val selected = ProfileTransitions.select(
            current,
            ProfileId.EXTENDED,
            emptySet(),
            emptyMap(),
            profileVerified = false,
        )

        assertEquals(ProfileId.COMPACT, selected.active)
        assertEquals(ProfileId.EXTENDED, selected.selected)
        assertEquals(ProfileId.EXTENDED, selected.pending?.profile)
        assertEquals(ProfilePhase.SELF_TESTING, selected.profile(ProfileId.EXTENDED).phase)
    }

    @Test fun oracleOnlyCatalogUpdateKeepsExplicitlyCompatibleGenerationFingerprint() {
        val pipeline = PipelineSpec(AiFeature.SEARCH, "b".repeat(64), 768, setOf("a".repeat(64)))

        assertTrue(pipeline.accepts("a".repeat(64)))
        assertTrue(pipeline.accepts("b".repeat(64)))
        assertFalse(pipeline.accepts("c".repeat(64)))
    }

    @Test fun installedProfileSelectionReusesMigrationCompatibleGeneration() {
        val oldFingerprint = "a".repeat(64)
        val generation = IndexGeneration("old-ready", AiFeature.SEARCH, oldFingerprint, true, 20, 20)
        val state = CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(
            profiles = CatalogSnapshot.readyForTest(ProfileId.COMPACT).profiles +
                (ProfileId.EXTENDED to ProfileState(ProfilePhase.INSTALLED)),
            generations = mapOf(generation.id to generation),
        )

        val selected = ProfileTransitions.select(
            state,
            ProfileId.EXTENDED,
            setOf(AiFeature.SEARCH),
            mapOf(AiFeature.SEARCH to "b".repeat(64)),
            compatibleFingerprints = mapOf(AiFeature.SEARCH to setOf(oldFingerprint)),
        )

        assertEquals(ProfileId.EXTENDED, selected.active)
        assertNull(selected.pending)
        assertEquals(generation.id, selected.activeGenerations[AiFeature.SEARCH])
    }

    @Test fun catalogGenerationHistoryIsStructurallyBoundedAndKeepsRequiredReuse() {
        val current = "c".repeat(64)
        val compatible = "b".repeat(64)
        val active = IndexGeneration("active", AiFeature.SEARCH, compatible, true, 5, 5)
        val pending = IndexGeneration("pending", AiFeature.OCR, current, true, 5, 5)
        val history = linkedMapOf(active.id to active, pending.id to pending)
        repeat(1_200) { at ->
            val feature = if (at % 2 == 0) AiFeature.SEARCH else AiFeature.OCR
            val fingerprint = if (at % 3 == 0) current else compatible
            history["history-$at"] = IndexGeneration("history-$at", feature, fingerprint,
                complete = at % 5 != 0, completed = at, total = 1_200)
        }
        val state = CatalogSnapshot.readyForTest(ProfileId.COMPACT).copy(
            generations = history,
            activeGenerations = mapOf(AiFeature.SEARCH to active.id),
            pending = PendingProfile(ProfileId.EXTENDED, setOf(AiFeature.OCR),
                mapOf(AiFeature.OCR to pending.id)),
        )

        val bounded = CatalogGenerationBounds.prune(state, mapOf(
            AiFeature.SEARCH to setOf(current, compatible),
            AiFeature.OCR to setOf(current, compatible),
        ))

        assertTrue(bounded.generations.size <= 10)
        assertEquals(active, bounded.generations[active.id])
        assertEquals(pending, bounded.generations[pending.id])
        assertTrue(bounded.generations.values.any { !it.complete })
        assertTrue(bounded.generations.values.any { it.complete && it.pipelineFingerprint == current })
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

    @Test fun corruptActiveArtifactsRequireRepairWithoutLosingRequestedFeatures() {
        val active = CatalogSnapshot.readyForTest(ProfileId.BALANCED).copy(
            enabledFeatures = setOf(AiFeature.SEARCH),
            activeGenerations = mapOf(AiFeature.SEARCH to "generation"),
        )
        val repaired = CatalogArtifactRepair.repair(active,
            ProfileId.entries.associateWith { it != ProfileId.BALANCED }, setOf(ProfileId.BALANCED))
        assertNull(repaired.active)
        assertEquals(ProfilePhase.ERROR, repaired.profile(ProfileId.BALANCED).phase)
        assertEquals("ARTIFACT_REPAIR_REQUIRED", repaired.profile(ProfileId.BALANCED).error)
        assertEquals(setOf(AiFeature.SEARCH), repaired.enabledFeatures)
        assertTrue(repaired.activeGenerations.isEmpty())
    }

    @Test fun indexCannotCompleteWithFailureRaceOrMissingCurrentRows() {
        assertTrue(IndexCompletion.canPublish(3, 3, 0, cancelled = false))
        assertFalse(IndexCompletion.canPublish(3, 2, 0, cancelled = false))
        assertFalse(IndexCompletion.canPublish(3, 3, 1, cancelled = false))
        assertFalse(IndexCompletion.canPublish(3, 3, 0, cancelled = true))
        assertFalse(IndexCompletion.canPublish(3, 3, 0, cancelled = false, storedEmbeddings = 4))
    }
}
