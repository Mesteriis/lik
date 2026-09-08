package io.github.mesteriis.lik.ai

import android.content.Context
import org.json.JSONObject
import java.net.URI

data class PipelineSpec(val feature: AiFeature, val fingerprint: String, val dimension: Int?)
data class SmokeReferenceSpec(val comparison: String, val minimumCosine: Float, val atol: Float, val rtol: Float)
data class GraphSmokeSpec(val artifactPath: String, val inputName: String, val shape: IntArray,
                          val outputName: String, val outputShape: IntArray,
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
                        val input = graph.getJSONArray("inputs").getJSONObject(0)
                        val shape = input.getJSONArray("smokeShape").let { values -> IntArray(values.length()) { values.getInt(it) } }
                        val output = graph.optString("primaryOutput").takeIf(String::isNotBlank)
                            ?: graph.getJSONArray("outputs").getJSONObject(0).getString("name")
                        val outputDescriptor = graph.getJSONArray("outputs").objects().single { it.getString("name") == output }
                        val outputShape = outputDescriptor.getJSONArray("smokeShape").let { values -> IntArray(values.length()) { values.getInt(it) } }
                        val reference = graph.getJSONObject("smokeReference")
                        GraphSmokeSpec(file.getString("path"), input.getString("name"), shape, output, outputShape,
                            SmokeReferenceSpec(reference.getString("comparison"), reference.getDouble("minimumCosine").toFloat(),
                                reference.getDouble("atol").toFloat(), reference.getDouble("rtol").toFloat()))
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
            val default = ProfileId.fromWire(root.getString("defaultProfile"))
            require(default == ProfileId.BALANCED)
            return TrustedModelCatalog(root.getString("catalogVersion"), default, components, profiles)
        }

        private fun org.json.JSONArray.strings() = List(length()) { getString(it) }
        private fun org.json.JSONArray.objects() = List(length()) { getJSONObject(it) }
        private fun JSONObject.pipeline(name: String, feature: AiFeature): PipelineSpec {
            val value = getJSONObject(name)
            return PipelineSpec(feature, value.getString("fingerprint"), value.optInt("dimension").takeIf { value.has("dimension") })
        }
    }
}
