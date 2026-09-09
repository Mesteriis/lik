package io.github.mesteriis.lik.similarity

import io.github.mesteriis.lik.catalog.MediaAvailability
import io.github.mesteriis.lik.catalog.MediaRecord
import io.github.mesteriis.lik.catalog.MediaSource
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.cos

object ContentDigest {
    fun sha256(input: InputStream, cancelled: () -> Boolean = { false }): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (cancelled()) throw InterruptedException("Fingerprint calculation cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Versioned 63-bit DCT perceptual hash. It is a search hint and is never a media identity. */
object PerceptualFingerprintV2 {
    /** Revision 2 keeps the DCT bits and replaces the incomplete byte-band index with 16 nibbles. */
    const val VERSION = 2
    const val SIMILAR_DISTANCE = 14
    private const val SAMPLE = 32
    private const val LOW = 8

    fun fromLuma(width: Int, height: Int, luma: IntArray): ByteArray {
        require(width > 0 && height > 0 && luma.size == width * height)
        val sample = DoubleArray(SAMPLE * SAMPLE)
        for (y in 0 until SAMPLE) for (x in 0 until SAMPLE) {
            val sx = ((x + .5) * width / SAMPLE).toInt().coerceIn(0, width - 1)
            val sy = ((y + .5) * height / SAMPLE).toInt().coerceIn(0, height - 1)
            sample[y * SAMPLE + x] = luma[sy * width + sx].coerceIn(0, 255).toDouble()
        }
        val coefficients = ArrayList<Double>(LOW * LOW - 1)
        for (v in 0 until LOW) for (u in 0 until LOW) {
            if (u == 0 && v == 0) continue
            var sum = 0.0
            for (y in 0 until SAMPLE) for (x in 0 until SAMPLE) {
                sum += sample[y * SAMPLE + x] *
                    cos((2 * x + 1) * u * Math.PI / (2 * SAMPLE)) *
                    cos((2 * y + 1) * v * Math.PI / (2 * SAMPLE))
            }
            coefficients += sum
        }
        val median = coefficients.sorted()[coefficients.size / 2]
        val result = ByteArray(8)
        coefficients.forEachIndexed { index, value ->
            if (value >= median) result[index / 8] = (result[index / 8].toInt() or (1 shl (index % 8))).toByte()
        }
        return result
    }

    fun distance(left: ByteArray, right: ByteArray): Int {
        require(left.size == 8 && right.size == 8)
        return left.indices.sumOf { Integer.bitCount((left[it].toInt() xor right[it].toInt()) and 0xff) }
    }
}

object FingerprintBands {
    const val COUNT=16
    fun keys(bits:ByteArray):Set<Int>{
        require(bits.size==8)
        return (0 until COUNT).mapTo(linkedSetOf()){index->
            val value=(bits[index/2].toInt() ushr ((index%2)*4)) and 0x0f
            (index shl 4) or value
        }
    }
    fun records(mediaId:String,version:Int,bits:ByteArray)=keys(bits).map{key->FingerprintBandRecord(mediaId,version,key ushr 4,key and 0x0f)}
}

object SimilarityBudgets {
    const val TOP_K=8
    const val CANDIDATE_PAGE=128
    const val CANDIDATES_PER_ITEM_STEP=512
    const val CANDIDATES_PER_RUN=1024
    const val FINGERPRINTS_PER_RUN=8
    const val COMPARISONS_PER_TRANCHE=8192
    const val MAX_AUTO_CONTINUATIONS=7
    fun maximumStoredVisualRelations(mediaCount:Int)=mediaCount.toLong()*TOP_K
    fun maximumCandidatePagesPerRun()=(CANDIDATES_PER_RUN+CANDIDATE_PAGE-1)/CANDIDATE_PAGE
    fun maximumAutomaticJobs()=MAX_AUTO_CONTINUATIONS+1
    fun maximumAutomaticComparisons()=minOf(COMPARISONS_PER_TRANCHE.toLong(),CANDIDATES_PER_RUN.toLong()*maximumAutomaticJobs())
    fun uniqueBucketComparisons(mediaCount:Int,uniqueHashes:Int)=mediaCount.toLong()*(uniqueHashes-1).coerceAtLeast(0)
}

data class SimilarityPagingState(val exactResultCount:Int,val visualResultCount:Int,val pageSize:Int){
    val hasNextExact get()=exactResultCount>pageSize
    val hasNextVisual get()=visualResultCount>pageSize
}

data class MediaPair(val left: String, val right: String) {
    companion object {
        fun ordered(a: String, b: String): MediaPair {
            require(a != b)
            return if (a < b) MediaPair(a, b) else MediaPair(b, a)
        }
    }
}

data class FingerprintToken(val mediaId: String, val contentRevision: Long, val accessEpoch: Long)

object SimilarityRules {
    fun isSimilar(distance: Int) = distance <= PerceptualFingerprintV2.SIMILAR_DISTANCE
    fun canPublish(current: MediaRecord, safe: Boolean, token: FingerprintToken) = safe &&
        current.mediaId == token.mediaId && current.contentRevision == token.contentRevision &&
        current.accessGrantEpoch == token.accessEpoch && current.availability == MediaAvailability.AVAILABLE &&
        current.trashedAt == null
}

data class ComparisonCapabilities(val canCompare: Boolean, val canMoveToTrash: Boolean) {
    companion object {
        fun forMedia(row: MediaRecord, safe: Boolean): ComparisonCapabilities {
            val visible = safe && row.availability == MediaAvailability.AVAILABLE && row.trashedAt == null
            return ComparisonCapabilities(visible, visible && row.source == MediaSource.GOOGLE_IMPORT && row.privateFileId != null)
        }
    }
}
