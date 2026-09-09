package io.github.mesteriis.lik.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Sole durable source for profile selection, feature opt-in, installation and active generations. */
class ModelCatalog private constructor(private val root: File, private val databaseFile: File, val trusted: TrustedModelCatalog) {
    private val stateFile = File(root, "catalog-state-v1.json")
    private val activationIntentFile = File(root, "catalog-activation-intent-v1.json")
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
    @Synchronized fun update(transform: (CatalogSnapshot) -> CatalogSnapshot): CatalogSnapshot =
        updateBeforeCommit(transform) {}

    private fun updateBeforeCommit(
        transform: (CatalogSnapshot) -> CatalogSnapshot,
        beforeCommit: (CatalogSnapshot) -> Unit,
    ): CatalogSnapshot {
        val old = current
        val next = CatalogGenerationBounds.prune(transform(old), acceptedPipelineFingerprints)
        require(next.catalogVersion == trusted.version && next.revision > old.revision)
        beforeCommit(next)
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

    @Synchronized fun generationReady(
        profile: ProfileId,
        feature: AiFeature,
        generation: IndexGeneration,
        beforeCommit: (CatalogSnapshot) -> Unit = {},
    ): CatalogSnapshot = updateBeforeCommit({ state ->
            val withGeneration = state.copy(generations = state.generations + (generation.id to generation))
            if (withGeneration.pending?.profile == profile && feature in withGeneration.pending.enabled)
                ProfileTransitions.generationReady(withGeneration, profile, feature, generation.id)
            else withGeneration.copy(revision = withGeneration.revision + 1,
                activeGenerations = if (withGeneration.active == profile && feature in withGeneration.enabledFeatures)
                    withGeneration.activeGenerations + (feature to generation.id) else withGeneration.activeGenerations)
        }, beforeCommit)

    /** Final membership validation, Room completion, clustering and catalog publication share one lock/transaction. */
    @Synchronized internal fun completeRoomGeneration(
        database: io.github.mesteriis.lik.catalog.MediaDatabase,
        profile: ProfileId,
        feature: AiFeature,
        generationId: String,
        finalizePayload: (OcrPeopleDao) -> Unit = {},
    ): RoomGenerationCompletion? {
        require(feature == AiFeature.OCR || feature == AiFeature.PEOPLE)
        var completed: AiIndexGenerationRecord? = null;var pruned=emptySet<String>()
        val old=current
        var next:CatalogSnapshot?=null
        try {
            database.runInTransaction {
                val index = database.aiIndexes(); val payload = database.ocrPeople()
                payload.invalidIndexableRunIds(generationId).forEach { mediaId ->
                    payload.deleteOcr(generationId, mediaId); payload.deleteFaces(generationId, mediaId); payload.deleteRun(generationId, mediaId)
                }
                val record = index.generation(generationId) ?: return@runInTransaction
                if (record.feature != feature.name || record.status == GenerationStatus.ERROR) return@runInTransaction
                val total = index.aiIndexableCount()
                if (payload.currentIndexableRunCount(generationId) != total || payload.storedRunCount(generationId) != total) return@runInTransaction
                finalizePayload(payload)
                if (payload.currentIndexableRunCount(generationId) != total || index.aiIndexableCount() != total) return@runInTransaction
                val ready = record.copy(status=GenerationStatus.COMPLETE,completed=total,total=total,error=null)
                val candidate = CatalogGenerationBounds.prune(run { val state=old
                val contract=IndexGeneration(ready.generationId,feature,ready.pipelineFingerprint,true,total,total)
                val withGeneration=state.copy(generations=state.generations+(contract.id to contract))
                if(withGeneration.pending?.profile==profile && feature in withGeneration.pending.enabled)
                    ProfileTransitions.generationReady(withGeneration,profile,feature,contract.id)
                else withGeneration.copy(revision=withGeneration.revision+1,
                    activeGenerations=if(withGeneration.active==profile&&feature in withGeneration.enabledFeatures)withGeneration.activeGenerations+(feature to contract.id) else withGeneration.activeGenerations)
                }, acceptedPipelineFingerprints)
                require(candidate.revision>old.revision)
                pruned=CatalogPrunedGenerations.between(old.generations.keys,candidate.generations.keys)
                writeActivationIntent(ActivationIntent(old,candidate,profile,feature,generationId,pruned))
                crashAt(ActivationCrashPoint.AFTER_INTENT)
                index.saveGeneration(ready)
                write(candidate)
                crashAt(ActivationCrashPoint.AFTER_CATALOG_WRITE_BEFORE_ROOM_COMMIT)
                next=candidate
                completed=ready
            }
            crashAt(ActivationCrashPoint.AFTER_ROOM_COMMIT_BEFORE_INTENT_CLEAR)
            GenerationRetirement.journal(root,profile,pruned)
            PreallocatedMetadata.clear(activationIntentFile)
        } catch(failure:Throwable) {
            if(failure !is SimulatedActivationCrash) runCatching {
                current=recoverActivationIntent()?:old
            }
            throw failure
        }
        next?.let{committed->current=committed;listeners.toList().forEach{it(committed)}}
        return completed?.let { RoomGenerationCompletion(it,pruned) }
    }
    fun operationPhase(profile: ProfileId, phase: ProfilePhase, completed: Long = 0, total: Long = 0, error: String? = null) = update { state ->
        state.copy(revision = state.revision + 1, profiles = state.profiles + (profile to ProfileState(phase, completed, total, error)))
    }
    @Synchronized fun removeInactiveAtomically(
        profile: ProfileId,
        requestedGenerations: Set<String>,
        beforeCommit: (Set<String>) -> Unit = {},
    ): CatalogInactiveRemovalResult {
        val planned = CatalogInactiveRemoval.apply(current, profile, requestedGenerations)
        if (!planned.accepted) return planned
        val next = CatalogGenerationBounds.prune(planned.snapshot, acceptedPipelineFingerprints)
        beforeCommit(planned.removable)
        write(next)
        current = next
        listeners.toList().forEach { it(next) }
        return planned.copy(snapshot = next)
    }
    fun discardGenerations(ids: Set<String>): CatalogSnapshot = update { state ->
        CatalogGenerationCleanup.remove(state, ids)
    }
    @Synchronized internal fun retireIfUnretained(id: String, action: () -> Unit): Boolean {
        val retained = current.generations.keys + current.activeGenerations.values +
            current.pending?.readyGenerations.orEmpty().values
        if (id in retained) return false
        action()
        return true
    }
    fun closeForTests() { instances.entries.removeIf { it.value === this } }

    internal fun seedForTests(value:CatalogSnapshot){require(value.catalogVersion==trusted.version);write(value);current=value}
    internal fun activationIntentPresentForTests()=readActivationIntent()!=null

    private fun initialize(): CatalogSnapshot {
        val persisted = recoverActivationIntent() ?: readOrFresh()
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
        if (generation.feature != AiFeature.SEARCH) return RoomGenerationStorage.usable(databaseFile, generation)
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

    private fun writeActivationIntent(intent:ActivationIntent){
        PreallocatedMetadata.prepare(activationIntentFile)
        val value=JSONObject().put("schema",1).put("previous",JSONObject(encode(intent.previous)))
            .put("target",JSONObject(encode(intent.target))).put("profile",intent.profile.wire)
            .put("feature",intent.feature.name).put("generation",intent.generationId)
            .put("pruned",JSONArray(intent.pruned.toList().sorted()))
        PreallocatedMetadata.write(activationIntentFile,value.toString().toByteArray())
    }

    private fun readActivationIntent():ActivationIntent?=runCatching{
        if(!activationIntentFile.isFile)return@runCatching null
        val bytes=PreallocatedMetadata.read(activationIntentFile);if(bytes.isEmpty())return@runCatching null
        val value=JSONObject(bytes.toString(Charsets.UTF_8));require(value.getInt("schema")==1)
        ActivationIntent(decode(value.getJSONObject("previous").toString()),decode(value.getJSONObject("target").toString()),
            ProfileId.fromWire(value.getString("profile")),AiFeature.valueOf(value.getString("feature")),value.getString("generation"),
            value.getJSONArray("pruned").let{array->List(array.length()){array.getString(it)}.toSet()})
    }.getOrNull()

    private fun recoverActivationIntent():CatalogSnapshot?{
        val intent=readActivationIntent()?:return null
        val targetUsable=intent.target.generations[intent.generationId]?.let(::generationUsable)==true &&
            intent.target.activeGenerations.values.all{id->intent.target.generations[id]?.let(::generationUsable)==true}
        val resolved=if(targetUsable)intent.target else intent.previous
        write(resolved)
        if(targetUsable)GenerationRetirement.journal(root,intent.profile,intent.pruned)
        PreallocatedMetadata.clear(activationIntentFile)
        return resolved
    }

    private fun crashAt(point:ActivationCrashPoint){if(activationCrashPointForTests==point)throw SimulatedActivationCrash(point)}

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
        @Volatile internal var activationCrashPointForTests:ActivationCrashPoint?=null
        private val instances = mutableMapOf<String, ModelCatalog>()
        @Synchronized fun get(context: Context): ModelCatalog {
            val app = context.applicationContext
            return instances.getOrPut(app.filesDir.absolutePath) { ModelCatalog(File(app.filesDir, "ai"), app.getDatabasePath("media.db"), TrustedModelCatalog.load(app)) }
        }
        internal fun openForTests(root:File,databaseFile:File,trusted:TrustedModelCatalog)=ModelCatalog(root,databaseFile,trusted)
    }
}

data class RoomGenerationCompletion(val record:AiIndexGenerationRecord,val pruned:Set<String>)
object CatalogPrunedGenerations { fun between(before:Set<String>,after:Set<String>):Set<String> = before-after }
private data class ActivationIntent(val previous:CatalogSnapshot,val target:CatalogSnapshot,val profile:ProfileId,val feature:AiFeature,val generationId:String,val pruned:Set<String>)
enum class ActivationCrashPoint { AFTER_INTENT, AFTER_CATALOG_WRITE_BEFORE_ROOM_COMMIT, AFTER_ROOM_COMMIT_BEFORE_INTENT_CLEAR }
class SimulatedActivationCrash(val point:ActivationCrashPoint):RuntimeException("SIMULATED_ACTIVATION_CRASH:${point.name}")

/** Read-only startup oracle for generation kinds whose durable payload is stored in Room. */
internal object RoomGenerationStorage {
    fun usable(databaseFile: File, generation: IndexGeneration): Boolean {
        if (generation.feature == AiFeature.SEARCH || !databaseFile.isFile) return false
        return runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(databaseFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                val metadataMatches = db.rawQuery("SELECT feature,pipelineFingerprint,status,completed,total FROM ai_index_generation WHERE generationId=?",
                    arrayOf(generation.id)).use { row ->
                    row.moveToFirst() && row.getString(0) == generation.feature.name &&
                        row.getString(1) == generation.pipelineFingerprint && row.getString(2) == GenerationStatus.COMPLETE.name &&
                        row.getInt(3) == generation.completed && row.getInt(4) == generation.total
                }
                if (!metadataMatches) return@use false
                db.rawQuery("SELECT COUNT(*) FROM ai_feature_media_run r JOIN media m ON m.mediaId=r.mediaId " +
                    "JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision " +
                    "WHERE r.generationId=? AND r.feature=? AND r.error IS NULL AND m.availability='AVAILABLE' " +
                    "AND m.contentRevision=r.contentRevision AND m.accessGrantEpoch=r.accessEpoch " +
                    "AND m.trashedAt IS NULL AND x.exposure='SAFE'",
                    arrayOf(generation.id, generation.feature.name)).use { count ->
                    count.moveToFirst() && count.getInt(0) == generation.total
                }
            }
        }.getOrDefault(false)
    }
}
