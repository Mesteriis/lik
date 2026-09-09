package io.github.mesteriis.lik.privacy

import kotlin.math.exp

enum class SensitiveDecision { QUARANTINED, SAFE, SENSITIVE }

data class AutomaticSensitiveDecision(
    val pipelineFingerprint: String,
    val contentRevision: Long,
    val accessEpoch: Long,
    val decision: SensitiveDecision,
)

data class ManualSensitiveOverride(val contentRevision: Long, val decision: SensitiveDecision)

object SensitiveMediaPolicy {
    const val POLICY_REVISION = "quarantine-until-calibrated-v1"

    fun resolve(
        automatic: AutomaticSensitiveDecision?,
        manual: ManualSensitiveOverride?,
        contentRevision: Long,
        accessEpoch: Long,
    ): SensitiveDecision {
        if (manual?.contentRevision == contentRevision) return manual.decision
        return automatic?.takeIf {
            it.contentRevision == contentRevision && it.accessEpoch == accessEpoch
        }?.decision ?: SensitiveDecision.QUARANTINED
    }

    fun mayReveal(decision: SensitiveDecision, revealLease: Boolean): Boolean =
        decision == SensitiveDecision.SAFE || revealLease

    /** Labels are ordered NSFW, SFW. There is deliberately no guessed release threshold. */
    fun fromClassifier(logits: FloatArray, releaseThreshold: Float?): SensitiveDecision {
        if (logits.size != 2 || logits.any { !it.isFinite() } || releaseThreshold == null) {
            return SensitiveDecision.QUARANTINED
        }
        require(releaseThreshold in 0f..1f)
        val max = logits.maxOrNull()!!.toDouble()
        val probabilities = logits.map { exp(it - max) }
        val nsfw = probabilities[0] / probabilities.sum()
        return if (nsfw >= releaseThreshold) SensitiveDecision.SENSITIVE else SensitiveDecision.SAFE
    }
}
