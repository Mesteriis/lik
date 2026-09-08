package io.github.mesteriis.lik.ai

import android.content.Context
import org.json.JSONObject
import java.net.URI

data class PipelineSpec(val feature: AiFeature, val fingerprint: String, val dimension: Int?)
data class ComponentSpec(val id: String, val fingerprint: String, val roles: Set<String>, val artifacts: List<ArtifactSpec>)
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
                    val roles = value.getJSONObject("contract").getJSONArray("roles").strings().toSet()
                    val artifacts = value.getJSONArray("artifacts").objects().map { file ->
                        ArtifactSpec(file.getString("path"), file.getLong("size"), file.getString("sha256"), URI(file.getString("url")))
                    }
                    check(put(id, ComponentSpec(id, value.getString("fingerprint"), roles, artifacts)) == null)
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
