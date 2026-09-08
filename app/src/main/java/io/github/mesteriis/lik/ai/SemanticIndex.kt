package io.github.mesteriis.lik.ai

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

data class VectorHit(val mediaId: String, val score: Float)
data class NativeCandidate(val nativeKey: Long, val mediaId: String, val vector: FloatArray)

object CandidateReranker {
    fun rank(query: FloatArray, candidates: List<NativeCandidate>, limit: Int): List<VectorHit> {
        require(query.isNotEmpty() && query.all(Float::isFinite) && limit > 0)
        val norm = sqrt(query.sumOf { (it * it).toDouble() }).toFloat()
        require(norm > 0)
        return candidates.map { candidate ->
            require(candidate.vector.size == query.size && candidate.vector.all(Float::isFinite))
            val candidateNorm = sqrt(candidate.vector.sumOf { (it * it).toDouble() }).toFloat()
            require(candidateNorm > 0)
            var score = 0f
            for (index in query.indices) score += query[index] * candidate.vector[index] / (norm * candidateNorm)
            VectorHit(candidate.mediaId, score)
        }.sortedWith(compareByDescending<VectorHit> { it.score }.thenBy { it.mediaId }).take(limit)
    }
}

/** Deterministic exact reference used to validate every approximate/native index build. */
class ExactVectorIndex(val dimension: Int) {
    private val vectors = sortedMapOf<String, FloatArray>()
    init { require(dimension > 0) }

    @Synchronized fun upsert(mediaId: String, vector: FloatArray) {
        require(mediaId.isNotBlank() && vector.size == dimension && vector.all(Float::isFinite))
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        require(norm > 0f)
        vectors[mediaId] = FloatArray(dimension) { vector[it] / norm }
    }

    @Synchronized fun remove(mediaId: String) { vectors.remove(mediaId) }

    @Synchronized fun search(query: FloatArray, limit: Int): List<VectorHit> {
        require(query.size == dimension && query.all(Float::isFinite) && limit > 0)
        val norm = sqrt(query.sumOf { (it * it).toDouble() }).toFloat()
        require(norm > 0f)
        return vectors.map { (id, vector) ->
            var score = 0f
            for (i in 0 until dimension) score += vector[i] * query[i] / norm
            VectorHit(id, score)
        }.sortedWith(compareByDescending<VectorHit> { it.score }.thenBy { it.mediaId }).take(limit)
    }

    @Synchronized fun save(file: File) {
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.parentFile?.mkdirs()
        val stream = java.io.FileOutputStream(temporary)
        DataOutputStream(stream.buffered()).use { out ->
            out.writeInt(MAGIC); out.writeInt(dimension); out.writeInt(vectors.size)
            vectors.forEach { (id, vector) -> out.writeUTF(id); vector.forEach(out::writeFloat) }
            out.flush()
        }
        java.io.FileOutputStream(temporary, true).use { it.fd.sync() }
        check(temporary.renameTo(file)) { "Could not publish vector index" }
        file.parentFile?.let(DurableAiFiles::syncDirectory)
    }

    companion object {
        private const val MAGIC = 0x4c494b56
        fun load(file: File): ExactVectorIndex = DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC)
            val index = ExactVectorIndex(input.readInt())
            repeat(input.readInt()) {
                val id = input.readUTF()
                index.upsert(id, FloatArray(index.dimension) { input.readFloat() })
            }
            require(input.read() == -1) { "Trailing index bytes" }
            index
        }
    }
}

/** Native USearch is accepted only when it produces the same deterministic top-k contract. */
object SearchParity {
    fun accept(reference: List<VectorHit>, approximate: List<VectorHit>, limit: Int) =
        reference.take(limit).map { it.mediaId } == approximate.take(limit).map { it.mediaId }
}

class USearchBridge {
    external fun create(dimension: Int): Long
    external fun reserve(handle: Long, capacity: Long)
    external fun upsert(handle: Long, key: Long, vector: FloatArray)
    external fun remove(handle: Long, key: Long)
    external fun search(handle: Long, vector: FloatArray, limit: Int): LongArray
    external fun save(handle: Long, path: String)
    external fun load(handle: Long, path: String)
    external fun close(handle: Long)

    companion object {
        val available: Boolean by lazy { runCatching { System.loadLibrary("lik_usearch"); true }.getOrDefault(false) }
    }
}
