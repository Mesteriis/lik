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
        require(before.active != profile && before.pending?.profile != profile)
        val database = MediaDatabase.get(context)
        val profileGenerations = database.aiIndexes().generations().filter { it.profileId == profile.wire }
        val retainedGenerations = before.activeGenerations.values.toSet() + before.pending?.readyGenerations.orEmpty().values
        val removedGenerationIds = profileGenerations.map { it.generationId }.filter { it !in retainedGenerations }.toSet()
        val removalJournal = GenerationRemovalJournal(File(context.filesDir, "ai"))
        removalJournal.begin(profile, removedGenerationIds)
        // Catalog is the serving authority: clear every pointer first. A crash after this point can
        // leave only unreachable Room/file garbage, never a stale generation that can be reselected.
        catalog.removeInactive(profile, removedGenerationIds)
        if (removedGenerationIds.isNotEmpty()) database.runInTransaction {
            database.aiIndexes().deleteGenerations(removedGenerationIds)
        }
        removedGenerationIds.forEach { id ->
            NativeIndexFiles.remove(context, id)
        }
        removalJournal.finish(profile)
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
        journal.pending().forEach { (profile, ids) ->
            withPipelineLocks(catalog, profile) {
                if (catalog.snapshot().active != profile && catalog.snapshot().pending?.profile != profile) {
                    catalog.removeInactive(profile, ids)
                    if (ids.isNotEmpty()) database.runInTransaction { database.aiIndexes().deleteGenerations(ids) }
                    ids.forEach { NativeIndexFiles.remove(context, it) }
                    journal.finish(profile)
                }
            }
        }
    }

    private fun <T> withPipelineLocks(catalog: ModelCatalog, profile: ProfileId, block: () -> T): T {
        val keys = catalog.trusted.profiles.getValue(profile).pipelines.values.map { it.fingerprint }.distinct().sorted()
        fun acquire(index: Int): T = if (index == keys.size) block()
            else IndexRunCoordinator.run(keys[index], { false }) { acquire(index + 1) }
        return acquire(0)
    }
}
