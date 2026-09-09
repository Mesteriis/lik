package io.github.mesteriis.lik.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Sole durable source for profile selection, feature opt-in, installation and active generations. */
class ModelCatalog private constructor(private val root: File, val trusted: TrustedModelCatalog) {
    private val stateFile = File(root, "catalog-state-v1.json")
    private val listeners = mutableSetOf<(CatalogSnapshot) -> Unit>()
    private val acceptedPipelineFingerprints: Map<AiFeature, Set<String>> by lazy {
        AiFeature.entries.associateWith { feature ->
            trusted.profiles.values.mapNotNull { it.pipelines[feature] }
                .flatMap { sequenceOf(it.fingerprint) + it.compatibleFingerprints.asSequence() }.toSet()
        }
    }
    @Volatile private var current = initialize()

    fun snapshot(): CatalogSnapshot = current
    internal fun stateFileForMetadata(): File = stateFile
    @Synchronized fun update(transform: (CatalogSnapshot) -> CatalogSnapshot): CatalogSnapshot {
        val old = current
        val next = CatalogGenerationBounds.prune(transform(old), acceptedPipelineFingerprints)
        require(next.catalogVersion == trusted.version && next.revision > old.revision)
        write(next)
        current = next
        listeners.toList().forEach { it(next) }
        return next
    }
    @Synchronized fun observe(listener: (CatalogSnapshot) -> Unit): AutoCloseable {
        listeners += listener; listener(current)
        return AutoCloseable { synchronized(this) { listeners -= listener } }
    }
    fun select(profile: ProfileId): CatalogSnapshot = update { state ->
        val fingerprints = trusted.profiles.getValue(profile).pipelines.mapValues { it.value.fingerprint }
        val compatible = trusted.profiles.getValue(profile).pipelines.mapValues { it.value.compatibleFingerprints }
        ProfileTransitions.select(state, profile, state.enabledFeatures, fingerprints,
            state.verifiedOracles[profile] == trusted.oracleRevision, compatible)
    }
    fun setFeature(feature: AiFeature, enabled: Boolean): CatalogSnapshot = update { state ->
        val requested = state.pending?.enabled ?: state.enabledFeatures
        val features = if (enabled) requested + feature else requested - feature
        val target = state.pending?.profile ?: state.active ?: state.selected
        val fingerprints = trusted.profiles.getValue(target).pipelines.mapValues { it.value.fingerprint }
        val compatible = trusted.profiles.getValue(target).pipelines.mapValues { it.value.compatibleFingerprints }
        ProfileTransitions.featuresChanged(state, features, fingerprints,
            state.verifiedOracles[target] == trusted.oracleRevision, compatible)
    }

    fun selfTested(profile: ProfileId): CatalogSnapshot = update { state ->
        val verified = state.copy(verifiedOracles = state.verifiedOracles + (profile to trusted.oracleRevision))
        if (verified.pending?.profile == profile) ProfileTransitions.selfTested(verified, profile)
        else verified.copy(revision = verified.revision + 1,
            profiles = state.profiles + (profile to state.profile(profile).copy(
                phase = if (state.active == profile) ProfilePhase.ACTIVE else ProfilePhase.INSTALLED, error = null)))
    }

    fun cancelPreparation(profile: ProfileId): CatalogSnapshot = update { state ->
        ProfileTransitions.cancel(state, profile)
    }

    fun saveGeneration(generation: IndexGeneration): CatalogSnapshot = update { state ->
        state.copy(revision = state.revision + 1, generations = state.generations + (generation.id to generation))
    }

    fun generationReady(profile: ProfileId, feature: AiFeature, generation: IndexGeneration): CatalogSnapshot = update { state ->
        val withGeneration = state.copy(generations = state.generations + (generation.id to generation))
        if (withGeneration.pending?.profile == profile && feature in withGeneration.pending.enabled)
            ProfileTransitions.generationReady(withGeneration, profile, feature, generation.id)
        else withGeneration.copy(revision = withGeneration.revision + 1,
            activeGenerations = if (withGeneration.active == profile && feature in withGeneration.enabledFeatures)
                withGeneration.activeGenerations + (feature to generation.id) else withGeneration.activeGenerations)
    }
    fun operationPhase(profile: ProfileId, phase: ProfilePhase, completed: Long = 0, total: Long = 0, error: String? = null) = update { state ->
        state.copy(revision = state.revision + 1, profiles = state.profiles + (profile to ProfileState(phase, completed, total, error)))
    }
    fun removeInactive(profile: ProfileId, removedGenerations: Set<String> = emptySet()): CatalogSnapshot = update { state ->
        require(state.active != profile && state.pending?.profile != profile)
        CatalogGenerationCleanup.remove(state, removedGenerations).copy(
            profiles = state.profiles + (profile to ProfileState()),
            selected = if (state.selected == profile) (state.active ?: ProfileId.BALANCED) else state.selected,
            verifiedOracles = state.verifiedOracles - profile)
    }
    fun discardGenerations(ids: Set<String>): CatalogSnapshot = update { state ->
        CatalogGenerationCleanup.remove(state, ids)
    }
    fun closeForTests() { instances.entries.removeIf { it.value === this } }

    private fun initialize(): CatalogSnapshot {
        val persisted = readOrFresh()
        val store = ArtifactStore(root)
        val corruptDigests = trusted.allArtifacts.values.mapNotNull { spec ->
            if (store.file(spec.sha256).exists() && !store.installed(spec.sha256, spec.size) && store.repair(spec)) spec.sha256 else null
        }.toSet()
        val corrupted = ProfileId.entries.filterTo(mutableSetOf()) { profile ->
            trusted.artifacts(profile).any { it.sha256 in corruptDigests }
        }
        val installed = ProfileId.entries.associateWith { profile -> trusted.artifacts(profile)
            .map { store.installed(it.sha256, it.size) }.all { it } }
        val versioned = if (persisted.catalogVersion == trusted.version) persisted
            else CatalogMigrations.toVersion(persisted, trusted.version,
                { installed.getValue(it) }, ::generationCompatible)
        val artifactRepaired = CatalogArtifactRepair.repair(versioned, installed, corrupted)
        val oracleRepaired = CatalogOracleRepair.requireCurrent(artifactRepaired, trusted.oracleRevision) { installed.getValue(it) }
        val repaired = CatalogGenerationBounds.prune(
            CatalogStorageRepair.repair(oracleRepaired, ::generationUsable), acceptedPipelineFingerprints)
        if (repaired != persisted) write(repaired)
        return repaired
    }

    private fun generationCompatible(profile: ProfileId, generation: IndexGeneration): Boolean =
        trusted.profiles.getValue(profile).pipelines[generation.feature]?.accepts(generation.pipelineFingerprint) == true &&
            generationUsable(generation)

    private fun generationUsable(generation: IndexGeneration): Boolean {
        if (!generation.complete) return false
        val directory = File(root, "indexes/${generation.id}.ready")
        val membership = runCatching { NativeMembership.read(directory) }.getOrNull() ?: return false
        if (membership.generationId != generation.id || membership.count != generation.total) return false
        return if (generation.total == 0) File(directory, "empty").isFile
        else File(directory, "index.usearch").isFile && File(directory, "verified").isFile
    }

    private fun readOrFresh(): CatalogSnapshot = runCatching {
        if (!stateFile.isFile) return@runCatching CatalogSnapshot.fresh(trusted.version)
        decode(PreallocatedMetadata.read(stateFile).toString(Charsets.UTF_8))
    }.getOrElse {
        stateFile.takeIf(File::exists)?.let {
            it.renameTo(File(root, "catalog-state-corrupt-${System.currentTimeMillis()}.json"))
            DurableAiFiles.syncDirectory(root)
        }
        CatalogSnapshot.fresh(trusted.version)
    }

    private fun write(state: CatalogSnapshot) {
        PreallocatedMetadata.write(stateFile, encode(state).toByteArray(Charsets.UTF_8))
    }

    private fun encode(state: CatalogSnapshot) = JSONObject().apply {
        put("schema", 2); put("catalogVersion", state.catalogVersion); put("revision", state.revision)
        put("selected", state.selected.wire); put("active", state.active?.wire)
        put("enabled", JSONArray(state.enabledFeatures.map { it.name }))
        put("profiles", JSONObject().apply { state.profiles.forEach { (id, value) ->
            put(id.wire, JSONObject().put("phase", value.phase.name).put("completed", value.completedBytes)
                .put("total", value.totalBytes).put("error", value.error))
        } })
        state.pending?.let { value -> put("pending", JSONObject().put("profile", value.profile.wire)
            .put("enabled", JSONArray(value.enabled.map { it.name }))
            .put("ready", JSONObject().apply { value.readyGenerations.forEach { (feature, id) -> put(feature.name, id) } })) }
        put("generations", JSONArray(state.generations.values.map { value -> JSONObject()
            .put("id", value.id).put("feature", value.feature.name).put("pipeline", value.pipelineFingerprint)
            .put("complete", value.complete).put("completed", value.completed).put("total", value.total) }))
        put("activeGenerations", JSONObject().apply { state.activeGenerations.forEach { (feature, id) -> put(feature.name, id) } })
        put("verifiedOracles", JSONObject().apply { state.verifiedOracles.forEach { (profile, revision) -> put(profile.wire, revision) } })
    }.toString()

    private fun decode(text: String): CatalogSnapshot {
        val root = JSONObject(text); require(root.getInt("schema") in 1..2)
        val profilesJson = root.getJSONObject("profiles")
        val profiles = ProfileId.entries.associateWith { id -> profilesJson.getJSONObject(id.wire).let {
            ProfileState(ProfilePhase.valueOf(it.getString("phase")), it.getLong("completed"), it.getLong("total"), it.optString("error").takeIf(String::isNotBlank))
        } }
        val generations = root.getJSONArray("generations").let { array -> List(array.length()) { array.getJSONObject(it) } }.associate { value ->
            val generation = IndexGeneration(value.getString("id"), AiFeature.valueOf(value.getString("feature")), value.getString("pipeline"), value.getBoolean("complete"), value.getInt("completed"), value.getInt("total"))
            generation.id to generation
        }
        fun featureMap(name: String) = root.optJSONObject(name)?.let { value -> value.keys().asSequence().associate { AiFeature.valueOf(it) to value.getString(it) } }.orEmpty()
        val pending = root.optJSONObject("pending")?.let { value -> PendingProfile(ProfileId.fromWire(value.getString("profile")),
            value.getJSONArray("enabled").let { a -> List(a.length()) { AiFeature.valueOf(a.getString(it)) }.toSet() },
            value.getJSONObject("ready").let { ready -> ready.keys().asSequence().associate { AiFeature.valueOf(it) to ready.getString(it) } }) }
        return CatalogSnapshot(root.getString("catalogVersion"), root.getLong("revision"), ProfileId.fromWire(root.getString("selected")),
            root.optString("active").takeIf(String::isNotBlank)?.let(ProfileId::fromWire), profiles,
            root.getJSONArray("enabled").let { a -> List(a.length()) { AiFeature.valueOf(a.getString(it)) }.toSet() },
            pending, generations, featureMap("activeGenerations"),
            root.optJSONObject("verifiedOracles")?.let { values -> values.keys().asSequence().associate {
                ProfileId.fromWire(it) to values.getString(it)
            } }.orEmpty())
    }

    companion object {
        private val instances = mutableMapOf<String, ModelCatalog>()
        @Synchronized fun get(context: Context): ModelCatalog {
            val app = context.applicationContext
            return instances.getOrPut(app.filesDir.absolutePath) { ModelCatalog(File(app.filesDir, "ai"), TrustedModelCatalog.load(app)) }
        }
    }
}
