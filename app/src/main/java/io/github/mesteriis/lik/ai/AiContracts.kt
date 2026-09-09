package io.github.mesteriis.lik.ai

import java.net.URI
import java.util.UUID

enum class ProfileId(val wire: String) {
    COMPACT("compact-v1"), BALANCED("balanced-v1"), EXTENDED("extended-v1");
    companion object { fun fromWire(value: String) = entries.first { it.wire == value } }
}

enum class AiFeature { SENSITIVE, SEARCH, OCR, PEOPLE }
enum class ProfilePhase { NOT_INSTALLED, DOWNLOADING, PAUSED, VERIFYING, SELF_TESTING, PREPARING, INSTALLED, ACTIVE, ERROR }

data class ProfileState(
    val phase: ProfilePhase = ProfilePhase.NOT_INSTALLED,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

data class PendingProfile(
    val profile: ProfileId,
    val enabled: Set<AiFeature>,
    val readyGenerations: Map<AiFeature, String> = emptyMap(),
)

data class IndexGeneration(
    val id: String,
    val feature: AiFeature,
    val pipelineFingerprint: String,
    val complete: Boolean,
    val completed: Int,
    val total: Int,
)

data class CatalogSnapshot(
    val catalogVersion: String,
    val revision: Long,
    val selected: ProfileId,
    val active: ProfileId?,
    val profiles: Map<ProfileId, ProfileState>,
    val enabledFeatures: Set<AiFeature>,
    val pending: PendingProfile? = null,
    val generations: Map<String, IndexGeneration> = emptyMap(),
    val activeGenerations: Map<AiFeature, String> = emptyMap(),
    val verifiedOracles: Map<ProfileId, String> = emptyMap(),
) {
    fun profile(id: ProfileId) = profiles.getValue(id)

    companion object {
        fun fresh(version: String) = CatalogSnapshot(
            version, 0, ProfileId.BALANCED, null,
            ProfileId.entries.associateWith { ProfileState() }, emptySet(),
        )

        fun readyForTest(id: ProfileId): CatalogSnapshot {
            val states = ProfileId.entries.associateWith {
                if (it == id) ProfileState(ProfilePhase.ACTIVE) else ProfileState()
            }
            return CatalogSnapshot("test", 1, id, id, states, emptySet())
        }
    }
}

object FeatureAvailability {
    fun unavailableRequested(state: CatalogSnapshot, unavailable: Set<AiFeature>): Set<AiFeature> =
        (state.pending?.enabled ?: state.enabledFeatures).intersect(unavailable)
}

object ProfileTransitions {
    fun select(
        current: CatalogSnapshot,
        target: ProfileId,
        enabled: Set<AiFeature>,
        pipelineFingerprints: Map<AiFeature, String> = emptyMap(),
        profileVerified: Boolean = true,
    ): CatalogSnapshot {
        val targetInstalled = current.profile(target).phase in setOf(ProfilePhase.INSTALLED, ProfilePhase.ACTIVE, ProfilePhase.PREPARING)
        val reused = enabled.mapNotNull { feature ->
            val fingerprint = pipelineFingerprints[feature] ?: return@mapNotNull null
            current.generations.values.firstOrNull {
                it.feature == feature && it.pipelineFingerprint == fingerprint && it.complete
            }?.let { feature to it.id }
        }.toMap()
        val canActivate = targetInstalled && profileVerified && reused.keys.containsAll(enabled)
        val previousPending = current.pending?.profile?.takeIf { it != target && it != current.active }
        val demoted = previousPending?.let { previous ->
            val state = current.profile(previous)
            previous to state.copy(phase = when (state.phase) {
                ProfilePhase.PREPARING, ProfilePhase.SELF_TESTING, ProfilePhase.VERIFYING -> ProfilePhase.INSTALLED
                ProfilePhase.DOWNLOADING, ProfilePhase.PAUSED, ProfilePhase.ERROR -> ProfilePhase.NOT_INSTALLED
                else -> state.phase
            }, completedBytes = if (state.phase == ProfilePhase.INSTALLED) state.completedBytes else 0,
                totalBytes = if (state.phase == ProfilePhase.INSTALLED) state.totalBytes else 0, error = null)
        }
        val profiles = current.profiles + listOfNotNull(demoted) + (target to current.profile(target).copy(
            phase = when {
                canActivate || target == current.active -> ProfilePhase.ACTIVE
                targetInstalled && profileVerified -> ProfilePhase.PREPARING
                targetInstalled -> ProfilePhase.SELF_TESTING
                else -> ProfilePhase.DOWNLOADING
            },
            error = null,
        )) + listOfNotNull(current.active?.takeIf { it != target }?.let {
            it to current.profile(it).copy(phase = ProfilePhase.ACTIVE)
        })
        return if (canActivate) current.copy(
            revision = current.revision + 1, selected = target, active = target,
            profiles = profiles, enabledFeatures = enabled, pending = null,
            activeGenerations = reused,
        ) else current.copy(
            revision = current.revision + 1, selected = target, profiles = profiles,
            pending = PendingProfile(target, enabled, reused),
        )
    }

    fun downloaded(current: CatalogSnapshot, target: ProfileId) = updatePending(current, target, ProfilePhase.SELF_TESTING)

    fun selfTested(current: CatalogSnapshot, target: ProfileId): CatalogSnapshot {
        val pending = requirePending(current, target)
        return if (pending.readyGenerations.keys.containsAll(pending.enabled)) activate(current, pending)
        else updatePending(current, target, ProfilePhase.PREPARING)
    }

    fun featuresChanged(
        current: CatalogSnapshot,
        enabled: Set<AiFeature>,
        pipelineFingerprints: Map<AiFeature, String>,
        profileVerified: Boolean = true,
    ): CatalogSnapshot {
        if (current.active == null && current.pending == null) return current.copy(
            revision = current.revision + 1, enabledFeatures = enabled,
        )
        val target = current.pending?.profile ?: current.active ?: current.selected
        return select(current, target, enabled, pipelineFingerprints, profileVerified)
    }

    fun generationReady(current: CatalogSnapshot, target: ProfileId, feature: AiFeature, generation: String): CatalogSnapshot {
        val pending = requirePending(current, target)
        require(feature in pending.enabled)
        val next = pending.copy(readyGenerations = pending.readyGenerations + (feature to generation))
        return if (next.readyGenerations.keys.containsAll(next.enabled)) activate(current, next)
        else current.copy(revision = current.revision + 1, pending = next)
    }

    fun cancel(current: CatalogSnapshot, target: ProfileId): CatalogSnapshot {
        requirePending(current, target)
        val cancelledPhase = when {
            current.active == target -> ProfilePhase.ACTIVE
            current.profile(target).phase == ProfilePhase.PREPARING -> ProfilePhase.INSTALLED
            else -> ProfilePhase.NOT_INSTALLED
        }
        return current.copy(
            revision = current.revision + 1,
            pending = null,
            profiles = current.profiles + (target to current.profile(target).copy(
                phase = cancelledPhase,
                error = null,
            )),
        )
    }

    fun fail(current: CatalogSnapshot, target: ProfileId, code: String): CatalogSnapshot {
        requirePending(current, target)
        val phase = if (current.active == target) ProfilePhase.ACTIVE else ProfilePhase.ERROR
        return current.copy(
            revision = current.revision + 1,
            pending = null,
            profiles = current.profiles + (target to current.profile(target).copy(phase = phase, error = code)),
        )
    }

    private fun updatePending(current: CatalogSnapshot, target: ProfileId, phase: ProfilePhase): CatalogSnapshot {
        requirePending(current, target)
        return current.copy(
            revision = current.revision + 1,
            profiles = current.profiles + (target to current.profile(target).copy(phase = phase, error = null)),
        )
    }

    private fun activate(current: CatalogSnapshot, pending: PendingProfile): CatalogSnapshot {
        val states = current.profiles.toMutableMap()
        current.active?.takeIf { it != pending.profile }?.let { states[it] = states.getValue(it).copy(phase = ProfilePhase.INSTALLED) }
        states[pending.profile] = states.getValue(pending.profile).copy(phase = ProfilePhase.ACTIVE, error = null)
        return current.copy(
            revision = current.revision + 1,
            active = pending.profile,
            enabledFeatures = pending.enabled,
            pending = null,
            profiles = states,
            activeGenerations = pending.readyGenerations,
        )
    }

    private fun requirePending(current: CatalogSnapshot, target: ProfileId) =
        requireNotNull(current.pending?.takeIf { it.profile == target }) { "Profile is not being prepared" }
}

data class IndexItem(val mediaId: String, val contentRevision: Long, val accessEpoch: Long, val pipelineFingerprint: String) {
    fun canPublish(id: String, revision: Long, epoch: Long, accessible: Boolean) =
        accessible && mediaId == id && contentRevision == revision && accessEpoch == epoch
}

object ArtifactRetention {
    fun retained(installedProfiles: List<Set<String>>, activeGenerations: List<Set<String>>, staged: Set<String>, leases: Set<String>): Set<String> =
        (installedProfiles.asSequence() + activeGenerations.asSequence()).flatten().toSet() + staged + leases
    fun collectable(existing: Set<String>, retained: Set<String>) = existing - retained
}

object CatalogGenerationCleanup {
    fun remove(state: CatalogSnapshot, removed: Set<String>): CatalogSnapshot = state.copy(
        revision = state.revision + 1,
        generations = state.generations - removed,
        activeGenerations = state.activeGenerations.filterValues { it !in removed },
        pending = state.pending?.let { it.copy(readyGenerations = it.readyGenerations.filterValues { id -> id !in removed }) },
    )
}

object CatalogOracleRepair {
    fun requireCurrent(state: CatalogSnapshot, oracleRevision: String,
                       profileInstalled: (ProfileId) -> Boolean): CatalogSnapshot {
        val verified = state.verifiedOracles.filter { (profile, revision) ->
            revision == oracleRevision && profileInstalled(profile)
        }
        val stale = ProfileId.entries.filterTo(mutableSetOf()) { profile ->
            profileInstalled(profile) && state.verifiedOracles[profile] != oracleRevision &&
                state.profile(profile).phase in setOf(ProfilePhase.INSTALLED, ProfilePhase.ACTIVE,
                    ProfilePhase.PREPARING, ProfilePhase.SELF_TESTING)
        }
        if (stale.isEmpty() && verified == state.verifiedOracles) return state
        val formerActive = state.active?.takeIf { it in stale }
        val target = state.pending?.profile?.takeIf { it in stale }
            ?: state.selected.takeIf { it in stale }
            ?: formerActive
        val requested = state.pending?.enabled ?: state.enabledFeatures
        val ready = if (target == null) emptyMap() else
            (state.pending?.takeIf { it.profile == target }?.readyGenerations.orEmpty() +
                state.activeGenerations.takeIf { formerActive == target }.orEmpty()).filterKeys { it in requested }
        val profiles = state.profiles.mapValues { (profile, value) -> when {
            profile == target -> value.copy(phase = ProfilePhase.SELF_TESTING, error = null)
            profile in stale && value.phase == ProfilePhase.ACTIVE -> value.copy(phase = ProfilePhase.INSTALLED, error = null)
            else -> value
        } }
        return state.copy(
            revision = state.revision + 1,
            active = state.active?.takeUnless { it in stale },
            profiles = profiles,
            pending = target?.let { PendingProfile(it, requested, ready) }
                ?: state.pending?.takeUnless { it.profile in stale },
            activeGenerations = if (formerActive == null) state.activeGenerations else emptyMap(),
            verifiedOracles = verified,
        )
    }
}

object CatalogMigrations {
    fun toVersion(state: CatalogSnapshot, version: String,
                  profileInstalled: (ProfileId) -> Boolean = { false },
                  generationCompatible: (ProfileId, IndexGeneration) -> Boolean = { _, _ -> false }): CatalogSnapshot {
        if (state.catalogVersion == version) return state
        val generations = state.generations.filterValues { generation -> generation.complete &&
            ProfileId.entries.any { generationCompatible(it, generation) } }
        val reusableActive = state.activeGenerations.filter { (feature, id) ->
            generations[id]?.let { it.feature == feature && state.active?.let { profile -> generationCompatible(profile, it) } == true } == true
        }
        val requested = state.pending?.enabled ?: state.enabledFeatures
        val oldActive = state.active?.takeIf(profileInstalled)
        val compatibleServing = oldActive?.let { reusableActive.filterKeys { it in state.enabledFeatures } }.orEmpty()
        val activeFullyCompatible = oldActive != null && compatibleServing.keys.containsAll(state.enabledFeatures)
        val preservedTarget = state.pending?.profile?.takeIf(profileInstalled)
        val profiles = ProfileId.entries.associateWith { id ->
            when {
                id == oldActive && activeFullyCompatible -> state.profile(id).copy(phase = ProfilePhase.ACTIVE, error = null)
                profileInstalled(id) -> state.profile(id).copy(phase = ProfilePhase.INSTALLED, error = null)
                else -> ProfileState()
            }
        }
        val selectedInstalled = profileInstalled(state.selected)
        val pending = when {
            preservedTarget != null -> PendingProfile(preservedTarget, requested,
                generations.values.filter { it.feature in requested && generationCompatible(preservedTarget, it) }
                    .associate { it.feature to it.id })
            activeFullyCompatible -> null
            oldActive != null -> PendingProfile(oldActive, requested, compatibleServing.filterKeys { it in requested })
            selectedInstalled && requested.isNotEmpty() -> PendingProfile(state.selected, requested,
                generations.values.filter { it.feature in requested && generationCompatible(state.selected, it) }
                    .associate { it.feature to it.id })
            else -> null
        }
        val withPreparing = pending?.profile?.let { id -> profiles + (id to profiles.getValue(id).copy(phase = ProfilePhase.PREPARING)) } ?: profiles
        return state.copy(catalogVersion = version, revision = state.revision + 1, active = oldActive,
            profiles = withPreparing, enabledFeatures = when {
                activeFullyCompatible -> state.enabledFeatures
                oldActive != null -> compatibleServing.keys
                else -> requested
            },
            pending = pending, generations = generations, activeGenerations = compatibleServing)
    }
}

object CatalogStorageRepair {
    fun repair(state: CatalogSnapshot, usable: (IndexGeneration) -> Boolean): CatalogSnapshot {
        val generations = state.generations.filterValues { !it.complete || usable(it) }
        val activeGenerations = state.activeGenerations.filterValues { id -> generations[id]?.complete == true }
        val pending = state.pending?.let { value ->
            value.copy(readyGenerations = value.readyGenerations.filterValues { id -> generations[id]?.complete == true })
        }
        val missingServingFeatures = if (state.active == null) emptySet() else state.enabledFeatures - activeGenerations.keys
        if (generations == state.generations && activeGenerations == state.activeGenerations &&
            pending == state.pending && missingServingFeatures.isEmpty()) return state
        val activePending = if (missingServingFeatures.isNotEmpty() && state.active != null) {
            val requested = pending?.enabled.orEmpty() + state.enabledFeatures
            PendingProfile(pending?.profile ?: state.active, requested,
                (pending?.readyGenerations.orEmpty() + activeGenerations).filterKeys { it in requested })
        } else pending
        return state.copy(revision = state.revision + 1, generations = generations,
            activeGenerations = activeGenerations, enabledFeatures = state.enabledFeatures - missingServingFeatures,
            pending = activePending)
    }
}

object CatalogArtifactRepair {
    fun repair(state: CatalogSnapshot, installed: Map<ProfileId, Boolean>, corrupted: Set<ProfileId>): CatalogSnapshot {
        val invalid = ProfileId.entries.filter { id ->
            state.profile(id).phase != ProfilePhase.NOT_INSTALLED && installed[id] != true
        }.toSet()
        if (invalid.isEmpty()) return state
        val brokenActive = state.active?.takeIf { it in invalid }
        val profiles = state.profiles.mapValues { (id, value) -> if (id !in invalid) value else
            ProfileState(if (id in corrupted) ProfilePhase.ERROR else ProfilePhase.NOT_INSTALLED,
                error = if (id in corrupted) "ARTIFACT_REPAIR_REQUIRED" else null) }
        return state.copy(revision = state.revision + 1,
            active = state.active?.takeUnless { it in invalid }, profiles = profiles,
            enabledFeatures = if (brokenActive == null) state.enabledFeatures else state.pending?.enabled ?: state.enabledFeatures,
            pending = state.pending?.takeUnless { it.profile in invalid },
            activeGenerations = if (brokenActive == null) state.activeGenerations else emptyMap(),
            verifiedOracles = state.verifiedOracles - invalid)
    }
}

object IndexCompletion {
    fun canPublish(available: Int, currentEmbeddings: Int, failures: Int, cancelled: Boolean,
                   storedEmbeddings: Int = currentEmbeddings) =
        available >= 0 && currentEmbeddings == available && storedEmbeddings == available &&
            failures == 0 && !cancelled
}

data class ArtifactSpec(val path: String, val size: Long, val sha256: String, val url: URI) {
    init {
        require(path.isNotBlank() && !path.startsWith('/') && ".." !in path.split('/'))
        require(size > 0)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(url.scheme == "https" && url.host == "huggingface.co")
        require(url.path.contains("/resolve/") && Regex("/[a-f0-9]{40}/").containsMatchIn(url.path))
    }
}

enum class DownloadDecision { APPEND, RESTART, REJECT }
object DownloadProtocol {
    fun response(spec: ArtifactSpec, offset: Long, code: Int, contentRange: String?, contentLength: Long, finalUrl: URI): DownloadDecision {
        if (!allowedRedirect(spec.url, finalUrl) || contentLength < 0) return DownloadDecision.REJECT
        if (offset == 0L) return if (code == 200 && contentLength == spec.size) DownloadDecision.APPEND else DownloadDecision.REJECT
        if (code == 200 && contentLength == spec.size) return DownloadDecision.RESTART
        if (code != 206) return DownloadDecision.REJECT
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange.orEmpty()) ?: return DownloadDecision.REJECT
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        val total = match.groupValues[3].toLong()
        return if (start == offset && total == spec.size && end - start + 1 == contentLength && end < total) DownloadDecision.APPEND else DownloadDecision.REJECT
    }

    fun allowedRedirect(source: URI, destination: URI): Boolean {
        if (source.scheme != "https" || source.host != "huggingface.co" || destination.scheme != "https") return false
        return destination.host == "huggingface.co" || destination.host?.endsWith(".huggingface.co") == true ||
            destination.host?.endsWith(".hf.co") == true || destination.host?.endsWith(".xethub.hf.co") == true
    }
}

object SpaceReservation {
    fun required(files: List<ArtifactSpec>, installedDigests: Set<String>, safetyMargin: Long): Long {
        require(safetyMargin >= 0)
        return try {
            files.filterNot { it.sha256 in installedDigests }.fold(safetyMargin) { total, file -> Math.addExact(total, file.size) }
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Artifact reservation overflows")
        }
    }
}

data class DownloadReservationPlan(val operation: String, val remainingByDigest: Map<String, Long>, val requiredBytes: Long) {
    companion object {
        fun create(operation: String, files: List<Pair<ArtifactSpec, Long>>, safetyMargin: Long): DownloadReservationPlan {
            require(operation.isNotBlank() && safetyMargin >= 0)
            val remaining = files.mapNotNull { (spec, existing) ->
                require(existing in 0..spec.size)
                (spec.size - existing).takeIf { it > 0 }?.let { spec.sha256 to it }
            }.toMap()
            val total = try { remaining.values.fold(safetyMargin, Math::addExact) }
            catch (_: ArithmeticException) { throw IllegalArgumentException("Artifact reservation overflows") }
            return DownloadReservationPlan(operation, remaining, total)
        }
    }
}

class DownloadOwnership {
    private val owners = mutableMapOf<String, String>()
    @Synchronized fun claim(operation: String, digest: String): Boolean {
        val owner = owners[digest]
        if (owner != null && owner != operation) return false
        owners[digest] = operation
        return true
    }
    @Synchronized fun release(operation: String, digest: String) { if (owners[digest] == operation) owners.remove(digest) }
}

enum class DownloadJournalStage { DOWNLOADING, VERIFYING, VERIFIED }
enum class RecoveryAction { RESUME, VERIFY, PUBLISH, DISCARD }
object DownloadRecovery {
    fun action(stage: DownloadJournalStage, actual: Long, expected: Long, digestMatches: Boolean): RecoveryAction = when {
        actual !in 0..expected -> RecoveryAction.DISCARD
        stage == DownloadJournalStage.DOWNLOADING && actual < expected -> RecoveryAction.RESUME
        stage == DownloadJournalStage.DOWNLOADING || stage == DownloadJournalStage.VERIFYING -> if (actual == expected) RecoveryAction.VERIFY else RecoveryAction.RESUME
        actual == expected && digestMatches -> RecoveryAction.PUBLISH
        else -> RecoveryAction.DISCARD
    }
}

enum class InferencePriority { BACKGROUND, MANUAL, INTERACTIVE }
data class InferenceRequest(val id: String, val priority: InferencePriority, val sequence: Long = 0)
class InferenceScheduler {
    private val queued = mutableListOf<InferenceRequest>()
    private var running: InferenceRequest? = null
    private var sequence = 0L
    @Synchronized fun offer(request: InferenceRequest): InferenceRequest = request.copy(sequence = sequence++).also { queued += it }
    @Synchronized fun acquire(): InferenceRequest? {
        if (running != null) return null
        return queued.maxWithOrNull(compareBy<InferenceRequest> { it.priority.ordinal }.thenBy { -it.sequence })?.also {
            queued.remove(it); running = it
        }
    }
    @Synchronized fun release(request: InferenceRequest) { if (running == request) running = null }
}

/** App-process admission gate; the isolated service remains the final one-task execution boundary. */
object InferenceGate {
    private data class Waiter(val priority: InferencePriority, val sequence: Long)
    private val waiting = mutableListOf<Waiter>()
    private var running = false
    private var sequence = 0L

    fun <T> run(priority: InferencePriority, block: () -> T): T {
        val waiter: Waiter
        synchronized(this) {
            waiter = Waiter(priority, sequence++)
            waiting += waiter
            try {
                while (running || waiting.maxWithOrNull(compareBy<Waiter> { it.priority.ordinal }.thenBy { -it.sequence }) != waiter) {
                    (this as java.lang.Object).wait()
                }
            } catch (interrupted: InterruptedException) {
                waiting.remove(waiter)
                (this as java.lang.Object).notifyAll()
                throw interrupted
            }
            waiting.remove(waiter); running = true
        }
        return try { block() } finally { synchronized(this) {
            running = false; (this as java.lang.Object).notifyAll()
        } }
    }
}

data class RuntimeLease(val id: String, val generation: Long, val digests: Set<String>)
class RuntimeLeases {
    private var generation = 0L
    private val leases = mutableMapOf<String, RuntimeLease>()
    @Synchronized fun acquire(digests: Set<String>) = RuntimeLease(UUID.randomUUID().toString(), generation, digests).also { leases[it.id] = it }
    @Synchronized fun release(lease: RuntimeLease) { leases.remove(lease.id) }
    @Synchronized fun valid(lease: RuntimeLease) = lease.generation == generation && leases[lease.id] == lease
    @Synchronized fun runtimeDied() { generation++; leases.clear() }
    @Synchronized fun liveDigests() = leases.values.flatMap { it.digests }.toSet()
}
