package io.github.mesteriis.lik.ai

import android.content.Context
import io.github.mesteriis.lik.catalog.MediaDatabase
import java.io.File

object ModelMaintenance {
    fun removeInactive(context: Context, profile: ProfileId): Long = DownloadCoordinator.run(File(context.filesDir, "ai")) {
        val catalog = ModelCatalog.get(context)
        withPipelineLocks(catalog, profile) {
            removeInactiveLocked(context, profile, catalog)
        }
    }

    private fun removeInactiveLocked(context: Context, profile: ProfileId, catalog: ModelCatalog): Long {
        val before = catalog.snapshot()
        if (before.active == profile || before.pending?.profile == profile) return 0
        val database = MediaDatabase.get(context)
        val profileGenerations = database.aiIndexes().generations().filter { it.profileId == profile.wire }
        val retainedGenerations = before.activeGenerations.values.toSet() + before.pending?.readyGenerations.orEmpty().values
        val requestedGenerationIds = profileGenerations.map { it.generationId }.filter { it !in retainedGenerations }.toSet()
        val removalJournal = GenerationRemovalJournal(File(context.filesDir, "ai"))
        val removal = catalog.removeInactiveAtomically(profile, requestedGenerationIds) { removable ->
            removalJournal.begin(profile, removable, GenerationRemovalReason.INACTIVE_PROFILE)
        }
        if (!removal.accepted) return 0
        GenerationRetirement.drain(context, profile, removal.removable, catalog, database,
            GenerationRemovalReason.INACTIVE_PROFILE)
        IsolatedRuntimeClient(context).evict(catalog.trusted.artifacts(profile).map { it.sha256 }.toSet()).getOrThrow()
        val after = catalog.snapshot()
        val installed = after.profiles.filterValues { it.phase !in setOf(ProfilePhase.NOT_INSTALLED, ProfilePhase.ERROR) }
            .keys.map { id -> catalog.trusted.artifacts(id).map { it.sha256 }.toSet() }
        val staged = File(context.filesDir, "ai/staging").listFiles().orEmpty().flatMap { operation ->
            operation.listFiles().orEmpty().map { it.name.substringBefore('.') }
        }.filter { it.matches(Regex("[a-f0-9]{64}")) }.toSet()
        val retained = ArtifactRetention.retained(installed, emptyList(), staged, IsolatedRuntimeClient.liveArtifactDigests())
        return ArtifactStore(File(context.filesDir, "ai")).cleanup(retained)
    }

    fun recover(context: Context) = DownloadCoordinator.run(File(context.filesDir, "ai")) {
        val journal = GenerationRemovalJournal(File(context.filesDir, "ai"))
        val catalog = ModelCatalog.get(context)
        val database = MediaDatabase.get(context)
        journal.pending().forEach { entry ->
            withPipelineLocks(catalog, entry.profile) {
                if (entry.reason == GenerationRemovalReason.SUPERSEDED) {
                    GenerationRetirement.drain(context, entry.profile, entry.ids, catalog, database, entry.reason)
                } else {
                    val removal = catalog.removeInactiveAtomically(entry.profile, entry.ids)
                    if (!removal.accepted) {
                        journal.complete(entry.profile, entry.ids, entry.reason)
                    } else {
                        journal.complete(entry.profile, entry.ids - removal.removable, entry.reason)
                        GenerationRetirement.drain(context, entry.profile, removal.removable,
                            catalog, database, entry.reason)
                    }
                }
            }
        }
    }

    private fun <T> withPipelineLocks(catalog: ModelCatalog, profile: ProfileId, block: () -> T): T {
        val keys = catalog.trusted.profiles.getValue(profile).pipelines.values.map { it.fingerprint }
        return IndexRunCoordinator.runAll(keys, { false }, block)
    }
}
