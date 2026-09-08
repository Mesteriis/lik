package io.github.mesteriis.lik.ai

import android.content.Context
import io.github.mesteriis.lik.catalog.MediaDatabase
import java.io.File

object ModelMaintenance {
    fun removeInactive(context: Context, profile: ProfileId): Long {
        val catalog = ModelCatalog.get(context)
        val before = catalog.snapshot()
        require(before.active != profile && before.pending?.profile != profile)
        val database = MediaDatabase.get(context)
        val profileGenerations = database.aiIndexes().generations().filter { it.profileId == profile.wire }
        val retainedGenerations = before.activeGenerations.values.toSet() + before.pending?.readyGenerations.orEmpty().values
        profileGenerations.filter { it.generationId !in retainedGenerations }.forEach {
            database.aiIndexes().deleteGeneration(it.generationId, retainedGenerations.ifEmpty { setOf("__none__") })
            File(NativeIndexFiles.root(context), "${it.generationId}.exact").delete()
            File(NativeIndexFiles.root(context), "${it.generationId}.usearch").delete()
        }
        catalog.removeInactive(profile)
        val after = catalog.snapshot()
        val installed = after.profiles.filterValues { it.phase in setOf(ProfilePhase.INSTALLED, ProfilePhase.ACTIVE, ProfilePhase.PREPARING) }
            .keys.map { id -> catalog.trusted.artifacts(id).map { it.sha256 }.toSet() }
        val staged = File(context.filesDir, "ai/staging").listFiles().orEmpty().flatMap { operation ->
            operation.listFiles().orEmpty().map { it.name.substringBefore('.') }
        }.filter { it.matches(Regex("[a-f0-9]{64}")) }.toSet()
        val retained = ArtifactRetention.retained(installed, emptyList(), staged, IsolatedRuntimeClient.liveArtifactDigests())
        return ArtifactStore(File(context.filesDir, "ai")).cleanup(retained)
    }
}
