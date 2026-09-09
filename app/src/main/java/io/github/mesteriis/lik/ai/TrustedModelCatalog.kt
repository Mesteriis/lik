package io.github.mesteriis.lik.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class PipelineSpec(
    val feature: AiFeature,
    val fingerprint: String,
    val dimension: Int?,
    val compatibleFingerprints: Set<String>,
) {
    fun accepts(candidate: String) = candidate == fingerprint || candidate in compatibleFingerprints
}
data class SmokeExpectedSample(val index: Int, val value: Float)
data class SmokeReferenceSpec(
    val file: String,
    val size: Long,
    val sha256: String,
    val format: String,
    val comparison: String,
    val minimumCosine: Float,
    val atol: Float,
    val rtol: Float,
    val minimumNormRatio: Float,
    val maximumNormRatio: Float,
    val scaleAware: Boolean,
    val sampleAbsoluteTolerance: Float,
    val sampleRelativeTolerance: Float,
    val minimumRangeRatio: Float,
    val maximumRangeRatio: Float,
    val expectedSamples: List<SmokeExpectedSample>,
) {
    init {
        require(file.isNotBlank() && '/' !in file && file != "." && file != "..")
        require(size > 0 && size % Float.SIZE_BYTES == 0L)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(format == "little-endian-float32-output-order")
        require(minimumNormRatio in 0f..1f && maximumNormRatio >= 1f)
        require(sampleAbsoluteTolerance > 0 && sampleRelativeTolerance > 0)
        require(minimumRangeRatio in 0f..1f && maximumRangeRatio >= 1f)
        require(expectedSamples.size >= 2 && expectedSamples.map { it.index }.distinct().size == expectedSamples.size)
    }
}
data class SmokeInputSpec(val name: String, val type: String, val shape: IntArray, val pattern: String)
data class GraphSmokeSpec(val artifactPath: String, val inputs: List<SmokeInputSpec>,
                          val outputName: String, val outputShape: IntArray,
                          val referenceOffsetFloats: Int,
                          val reference: SmokeReferenceSpec)
data class ComponentSpec(
    val id: String,
    val displayName: String,
    val fingerprint: String,
    val roles: Set<String>,
    val artifacts: List<ArtifactSpec>,
    val smokeGraphs: List<GraphSmokeSpec>,
)
data class ProfileSpec(
    val id: ProfileId,
    val componentIds: List<String>,
    val pipelines: Map<AiFeature, PipelineSpec>,
    val uniqueBytes: Long,
)

class TrustedModelCatalog private constructor(
    val version: String,
    val oracleRevision: String,
    val defaultProfile: ProfileId,
    val components: Map<String, ComponentSpec>,
    val profiles: Map<ProfileId, ProfileSpec>,
) {
    val allArtifacts: Map<String, ArtifactSpec> = components.values.flatMap { it.artifacts }.associateBy { it.sha256 }
    fun artifacts(profile: ProfileId): List<ArtifactSpec> = profiles.getValue(profile).componentIds
        .flatMap { components.getValue(it).artifacts }.distinctBy { it.sha256 }
    fun smokeGraphs(profile: ProfileId): List<GraphSmokeSpec> = profiles.getValue(profile).componentIds
        .flatMap { components.getValue(it).smokeGraphs }

    companion object {
        fun load(context: Context): TrustedModelCatalog = context.assets.open("models/catalog-v1.json").bufferedReader().use {
            parse(it.readText())
        }

        internal fun parse(text: String): TrustedModelCatalog {
            val root = JSONObject(text)
            require(root.getInt("schemaVersion") == 1)
            require(root.getString("delivery") == "settings-download-from-huggingface")
            val activation = root.getJSONArray("activationSmokeReferences").objects().associate { value ->
                val path = value.getString("path")
                require(path.isNotBlank() && !path.startsWith('/') && ".." !in path.split('/'))
                path to value
            }
            val components = buildMap {
                val values = root.getJSONArray("components")
                repeat(values.length()) { index ->
                    val value = values.getJSONObject(index)
                    val id = value.getString("id")
                    val contract = value.getJSONObject("contract")
                    val roles = contract.getJSONArray("roles").strings().toSet()
                    val artifacts = value.getJSONArray("artifacts").objects().map { file ->
                        ArtifactSpec(file.getString("path"), file.getLong("size"), file.getString("sha256"), URI(file.getString("url")))
                    }
                    val smoke = value.getJSONArray("artifacts").objects().mapNotNull { file ->
                        if (!file.getString("path").endsWith(".onnx")) return@mapNotNull null
                        val graph = file.getJSONObject("onnx")
                        val inputs = graph.getJSONArray("inputs").objects().map { input ->
                            SmokeInputSpec(input.getString("name"), input.getString("type"),
                                input.getJSONArray("smokeShape").let { values -> IntArray(values.length()) { values.getInt(it) } },
                                input.getString("smokePattern").also { pattern -> require(pattern in setOf("deterministic-ramp-v1", "byte-ramp-v1", "zeros-v1", "ones-v1")) })
                        }
                        val output = graph.optString("primaryOutput").takeIf(String::isNotBlank)
                            ?: graph.getJSONArray("outputs").getJSONObject(0).getString("name")
                        val outputs = graph.getJSONArray("outputs").objects()
                        val outputDescriptor = outputs.single { it.getString("name") == output }
                        val outputShape = outputDescriptor.getJSONArray("smokeShape").let { values -> IntArray(values.length()) { values.getInt(it) } }
                        val referenceOffset = outputs.takeWhile { it.getString("name") != output }.sumOf { descriptor ->
                            descriptor.getJSONArray("smokeShape").let { values ->
                                (0 until values.length()).fold(1) { count, at -> Math.multiplyExact(count, values.getInt(at)) }
                            }
                        }
                        val reference = graph.getJSONObject("smokeReference")
                        val activationReference = activation.getValue(file.getString("path"))
                        require(activationReference.getString("outputName") == output)
                        val outputCount = outputShape.fold(1) { count, value -> Math.multiplyExact(count, value) }
                        require(activationReference.getInt("outputSize") == outputCount &&
                            activationReference.getString("referenceSha256") == reference.getString("sha256"))
                        val samples = activationReference.getJSONArray("samples").objects().map { sample ->
                            val index = sample.getInt("index")
                            require(index in 0 until outputCount)
                            val bits = sample.getString("floatBits")
                            require(bits.matches(Regex("[0-9a-f]{8}")))
                            SmokeExpectedSample(index, Float.fromBits(bits.toLong(16).toInt()))
                        }
                        GraphSmokeSpec(file.getString("path"), inputs, output, outputShape, referenceOffset,
                            SmokeReferenceSpec(reference.getString("file"), reference.getLong("size"), reference.getString("sha256"),
                                reference.getString("format"), reference.getString("comparison"), reference.getDouble("minimumCosine").toFloat(),
                                reference.getDouble("atol").toFloat(), reference.getDouble("rtol").toFloat(),
                                activationReference.getDouble("minimumNormRatio").toFloat(),
                                activationReference.getDouble("maximumNormRatio").toFloat(),
                                activationReference.getBoolean("scaleAware"),
                                activationReference.getDouble("sampleAbsoluteTolerance").toFloat(),
                                activationReference.getDouble("sampleRelativeTolerance").toFloat(),
                                activationReference.getDouble("minimumRangeRatio").toFloat(),
                                activationReference.getDouble("maximumRangeRatio").toFloat(), samples))
                    }
                    check(put(id, ComponentSpec(id, contract.getString("sourceRepo"), value.getString("fingerprint"), roles, artifacts, smoke)) == null)
                }
            }
            val profiles = buildMap {
                val values = root.getJSONArray("profiles")
                repeat(values.length()) { index ->
                    val value = values.getJSONObject(index)
                    val id = ProfileId.fromWire(value.getString("id"))
                    val componentIds = value.getJSONArray("components").strings()
                    componentIds.forEach { require(it in components) }
                    val pipelineObject = value.getJSONObject("pipelines")
                    val pipelines = mapOf(
                        AiFeature.SEARCH to pipelineObject.pipeline("search", AiFeature.SEARCH),
                        AiFeature.OCR to pipelineObject.pipeline("ocr", AiFeature.OCR),
                        AiFeature.PEOPLE to pipelineObject.pipeline("people", AiFeature.PEOPLE),
                        AiFeature.SENSITIVE to pipelineObject.pipeline("sensitive", AiFeature.SENSITIVE),
                    )
                    val uniqueBytes = componentIds.flatMap { components.getValue(it).artifacts }.distinctBy { it.sha256 }
                        .fold(0L) { sum, file -> Math.addExact(sum, file.size) }
                    check(put(id, ProfileSpec(id, componentIds, pipelines, uniqueBytes)) == null)
                }
            }
            require(profiles.keys == ProfileId.entries.toSet())
            require(components.values.flatMap { it.smokeGraphs }.map { it.artifactPath }.toSet() == activation.keys)
            val default = ProfileId.fromWire(root.getString("defaultProfile"))
            require(default == ProfileId.BALANCED)
            return TrustedModelCatalog(root.getString("catalogVersion"), root.getString("oracleRevision"), default, components, profiles)
        }

        private fun org.json.JSONArray.strings() = List(length()) { getString(it) }
        private fun org.json.JSONArray.objects() = List(length()) { getJSONObject(it) }
        private fun JSONObject.pipeline(name: String, feature: AiFeature): PipelineSpec {
            val value = getJSONObject(name)
            return PipelineSpec(feature, value.getString("fingerprint"), value.optInt("dimension").takeIf { value.has("dimension") },
                value.optJSONArray("compatibleFingerprints")?.strings()?.toSet().orEmpty().also { fingerprints ->
                    require(fingerprints.all { it.matches(Regex("[a-f0-9]{64}")) })
                })
        }
    }
}

object SmokeReferenceVerifier {
    fun matchesExpectedSamples(reference: SmokeReferenceSpec, actual: FloatArray): Boolean {
        if (!actual.all(Float::isFinite) || reference.expectedSamples.any { it.index !in actual.indices }) return false
        val observed = reference.expectedSamples.map { actual[it.index] }
        val expected = reference.expectedSamples.map { it.value }
        val observedNorm = kotlin.math.sqrt(observed.sumOf { it.toDouble() * it })
        val expectedNorm = kotlin.math.sqrt(expected.sumOf { it.toDouble() * it })
        val ratio = observedNorm / expectedNorm
        if (expectedNorm <= 0 || ratio < reference.minimumNormRatio || ratio > reference.maximumNormRatio) return false
        if (reference.scaleAware) {
            val expectedRange = (expected.maxOrNull() ?: return false) - (expected.minOrNull() ?: return false)
            val observedRange = (observed.maxOrNull() ?: return false) - (observed.minOrNull() ?: return false)
            val rangeRatio = observedRange / expectedRange
            if (expectedRange <= reference.sampleAbsoluteTolerance || rangeRatio < reference.minimumRangeRatio ||
                rangeRatio > reference.maximumRangeRatio) return false
            if (observed.indices.any { kotlin.math.abs(observed[it] - expected[it]) >
                    reference.sampleAbsoluteTolerance + reference.sampleRelativeTolerance * kotlin.math.abs(expected[it]) }) return false
        }
        return when (reference.comparison) {
            "allclose" -> observed.indices.all { kotlin.math.abs(observed[it] - expected[it]) <=
                reference.atol + reference.rtol * kotlin.math.abs(expected[it]) }
            "cosine" -> {
                var dot = 0.0; var left = 0.0; var right = 0.0
                observed.indices.forEach { dot += observed[it] * expected[it]; left += observed[it] * observed[it]; right += expected[it] * expected[it] }
                left > 0 && right > 0 && dot / kotlin.math.sqrt(left * right) >= reference.minimumCosine
            }
            else -> false
        }
    }

    fun matches(reference: SmokeReferenceSpec, actual: FloatArray, file: File, offsetFloats: Int = 0): Boolean = runCatching {
        if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath()) || file.length() != reference.size) return@runCatching false
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (digest != reference.sha256) return@runCatching false
        val values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        if (offsetFloats < 0 || offsetFloats + actual.size > values.remaining() || !actual.all(Float::isFinite)) return@runCatching false
        values.position(offsetFloats)
        val expected = FloatArray(actual.size).also(values::get)
        when (reference.comparison) {
            "allclose" -> actual.indices.all { kotlin.math.abs(actual[it] - expected[it]) <=
                reference.atol + reference.rtol * kotlin.math.abs(expected[it]) }
            "cosine" -> {
                var dot = 0.0; var left = 0.0; var right = 0.0
                actual.indices.forEach { dot += actual[it] * expected[it]; left += actual[it] * actual[it]; right += expected[it] * expected[it] }
                left > 0 && right > 0 && dot / kotlin.math.sqrt(left * right) >= reference.minimumCosine
            }
            else -> false
        }
    }.getOrDefault(false)
}
