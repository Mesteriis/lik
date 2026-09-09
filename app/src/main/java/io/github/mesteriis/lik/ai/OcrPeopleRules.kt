package io.github.mesteriis.lik.ai

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

enum class AiExposure { QUARANTINED, SAFE, SENSITIVE }

object OcrText {
    fun normalizeDisplay(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .lineSequence().map { it.trim().replace(Regex("[\\t ]+"), " ") }.filter(String::isNotEmpty).joinToString("\n")
    fun searchKey(value: String): String = normalizeDisplay(value).replace('\n', ' ').lowercase(Locale.ROOT)
    fun matches(value: String, query: String): Boolean = searchKey(value).contains(searchKey(query))
}

data class OcrDecode(val text: String, val confidence: Float)

object CtcDecoder {
    /** Dictionary excludes CTC blank; its line at index zero maps to model class one. */
    fun decode(probabilities: Array<FloatArray>, dictionary: List<String>): OcrDecode {
        // The pinned PP-OCRv5 recognizer graph ends in Softmax, as does publisher postprocessing.
        require(probabilities.all { step -> step.size == dictionary.size + 1 &&
            step.all { it.isFinite() && it in 0f..1f } &&
            kotlin.math.abs(step.sumOf(Float::toDouble) - 1.0) <= .001 })
        val text = StringBuilder(); var previous = -1; var confidence = 0.0; var count = 0
        for (step in probabilities) {
            val id = step.indices.maxByOrNull(step::get) ?: continue
            val probability = step[id]
            if (id != 0 && id != previous) {
                text.append(dictionary[id - 1]); confidence += probability; count++
            }
            previous = id
        }
        return OcrDecode(text.toString(), if (count == 0) 0f else (confidence / count).toFloat())
    }

}

data class AiPublicationToken(
    val mediaId: String, val contentRevision: Long, val accessEpoch: Long,
    val pipelineFingerprint: String, val generationId: String,
) {
    fun matches(id: String, revision: Long, epoch: Long, available: Boolean, exposure: AiExposure): Boolean =
        mediaId == id && contentRevision == revision && accessEpoch == epoch && available && exposure == AiExposure.SAFE
}

data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f && right > left && bottom > top) }
}

object FaceAnchor {
    /** Percent-level quantization tolerates detector drift while keeping distinct faces separate. */
    fun from(mediaId: String, box: FaceBox): String {
        val coordinates = listOf(box.left, box.top, box.right, box.bottom).joinToString(":") { kotlin.math.round(it * 100).toInt().toString() }
        return MessageDigest.getInstance("SHA-256").digest("$mediaId:$coordinates".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

data class FaceVector(val anchorId: String, val embedding: FloatArray)
data class ManualFacePair(val first: String, val second: String) {
    companion object { fun ordered(left: String, right: String) = if (left <= right) ManualFacePair(left, right) else ManualFacePair(right, left) }
}

object FaceClusterer {
    /** Complete-link within bounded windows. Oversized identities may split, but an unchecked
     * pair never merges identities. Manual identities/corrections remain the durable authority. */
    fun cluster(faces: List<FaceVector>, threshold: Float, cannotLink: Set<ManualFacePair>): List<List<String>> {
        require(threshold in -1f..1f)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("CLUSTER_CANCELLED")
        val ordered = faces.sortedBy(FaceVector::anchorId)
        return ordered.chunked(WINDOW).flatMap { clusterWindow(it,threshold,cannotLink) }
    }

    const val WINDOW=128
    private fun clusterWindow(ordered:List<FaceVector>,threshold:Float,cannotLink:Set<ManualFacePair>):List<List<String>> {
        val clusters = mutableListOf<MutableList<FaceVector>>()
        for (face in ordered) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("CLUSTER_CANCELLED")
            val target = clusters.firstOrNull { cluster -> cluster.all { existing ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException("CLUSTER_CANCELLED")
                ManualFacePair.ordered(face.anchorId, existing.anchorId) !in cannotLink && cosine(face.embedding, existing.embedding) >= threshold
            } }
            if (target == null) clusters += mutableListOf(face) else target += face
        }
        return clusters.map { it.map(FaceVector::anchorId) }.sortedBy { it.first() }
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size && left.isNotEmpty())
        var dot = 0.0; var a = 0.0; var b = 0.0
        left.indices.forEach { dot += left[it] * right[it]; a += left[it] * left[it]; b += right[it] * right[it] }
        return if (a == 0.0 || b == 0.0) -1f else (dot / sqrt(a * b)).toFloat()
    }
}

data class ComputedFace(val detectionId: String, val anchorId: String, val computedClusterId: String)
data class ResolvedFace(val detectionId: String, val anchorId: String, val personId: String, val excluded: Boolean)

data class ManualPeopleState(
    val assignments: Map<String, String> = emptyMap(), val excluded: Set<String> = emptySet(),
    val merges: Map<String, String> = emptyMap(), val cannotLinks: Set<ManualFacePair> = emptySet(),
) {
    fun assign(anchor: String, person: String) = copy(assignments = assignments + (anchor to person), excluded = excluded - anchor)
    fun exclude(anchor: String) = copy(excluded = excluded + anchor, assignments = assignments - anchor)
    fun include(anchor: String) = copy(excluded = excluded - anchor)
    fun clear(anchor: String) = copy(excluded = excluded - anchor, assignments = assignments - anchor)
    fun merge(from: String, into: String) = copy(merges = merges + (from to canonical(into)))
    fun unmerge(from: String) = copy(merges = merges - from)
    fun cannotLink(left: String, right: String) = copy(cannotLinks = cannotLinks + ManualFacePair.ordered(left, right))
    fun canonical(id: String): String {
        var next = id; val visited = mutableSetOf<String>()
        while (next in merges && visited.add(next)) next = merges.getValue(next)
        return next
    }
}

object PeopleResolution {
    fun apply(computed: List<ComputedFace>, manual: ManualPeopleState): List<ResolvedFace> {
        val durableByCluster = computed.groupBy(ComputedFace::computedClusterId).mapValues { (_, faces) ->
            faces.mapNotNull { manual.assignments[it.anchorId] }.map(manual::canonical).distinct().sorted().firstOrNull()
        }
        return computed.map { face ->
        val automatic = manual.assignments[face.anchorId] ?: durableByCluster[face.computedClusterId] ?: face.computedClusterId
        ResolvedFace(face.detectionId, face.anchorId, manual.canonical(automatic), face.anchorId in manual.excluded)
        }
    }
}
