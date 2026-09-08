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

object ProfileTransitions {
    fun select(
        current: CatalogSnapshot,
        target: ProfileId,
        enabled: Set<AiFeature>,
        pipelineFingerprints: Map<AiFeature, String> = emptyMap(),
    ): CatalogSnapshot {
        val targetInstalled = current.profile(target).phase in setOf(ProfilePhase.INSTALLED, ProfilePhase.ACTIVE, ProfilePhase.PREPARING)
        val reused = enabled.mapNotNull { feature ->
            val fingerprint = pipelineFingerprints[feature] ?: return@mapNotNull null
            current.generations.values.firstOrNull {
                it.feature == feature && it.pipelineFingerprint == fingerprint && it.complete
            }?.let { feature to it.id }
        }.toMap()
        val canActivate = targetInstalled && reused.keys.containsAll(enabled)
        val profiles = current.profiles + (target to current.profile(target).copy(
            phase = when { canActivate -> ProfilePhase.ACTIVE; targetInstalled -> ProfilePhase.PREPARING; else -> ProfilePhase.DOWNLOADING },
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
            enabledFeatures = enabled, pending = PendingProfile(target, enabled, reused),
        )
    }

    fun downloaded(current: CatalogSnapshot, target: ProfileId) = updatePending(current, target, ProfilePhase.SELF_TESTING)

    fun selfTested(current: CatalogSnapshot, target: ProfileId): CatalogSnapshot {
        val pending = requirePending(current, target)
        return if (pending.readyGenerations.keys.containsAll(pending.enabled)) activate(current, pending)
        else updatePending(current, target, ProfilePhase.PREPARING)
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
        val cancelledPhase = if (current.profile(target).phase == ProfilePhase.PREPARING) {
            ProfilePhase.INSTALLED
        } else {
            ProfilePhase.NOT_INSTALLED
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
        return current.copy(
            revision = current.revision + 1,
            pending = null,
            profiles = current.profiles + (target to current.profile(target).copy(ProfilePhase.ERROR, error = code)),
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
            while (running || waiting.maxWithOrNull(compareBy<Waiter> { it.priority.ordinal }.thenBy { -it.sequence }) != waiter) {
                (this as java.lang.Object).wait()
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
