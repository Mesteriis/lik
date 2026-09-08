package io.github.mesteriis.lik.aigate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class AiGateHealth(val running: Boolean, val port: Int, val version: String?, val modelCount: Int?)
data class AiGateReply(val requestId: Long, val text: String, val model: String?)

@SuppressLint("UseKtx")
class AiGateSettings(context: Context) {
    private val values = context.getSharedPreferences("aigate-v1", Context.MODE_PRIVATE)
    var enabled: Boolean get() = values.getBoolean("enabled", false); set(value) { values.edit().putBoolean("enabled", value).apply() }
    var port: Int get() = values.getInt("port", 8889); set(value) { require(value in 1..65535); values.edit().putInt("port", value).apply() }
}

class AiGateClient(
    private val endpoint: AiGateEndpoint,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {
    init { require(connectTimeoutMs > 0 && readTimeoutMs > 0) }
    private val cancelled = AtomicBoolean()
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { cancelled.set(true); connection?.disconnect() }

    fun health(): AiGateHealth {
        val json = request("GET", "/health")
        require(json.optString("service") == "aigate") { "Unexpected loopback service" }
        return AiGateHealth(json.optBoolean("running"), json.optInt("port", endpoint.port),
            json.optString("version").takeIf(String::isNotBlank), json.optInt("model_count").takeIf { json.has("model_count") })
    }

    fun models(): List<AiGateModel> {
        val array = request("GET", "/v1/models").getJSONArray("data")
        return List(array.length()) { array.getJSONObject(it) }.map {
            AiGateModel(it.getString("id"), it.optString("owned_by").takeIf(String::isNotBlank), visionKnown = false)
        }
    }

    fun chat(
        token: PhotoSendToken,
        consent: PhotoSendConsent,
        mediaId: String,
        revision: Long,
        requestId: Long,
        prompt: String,
        jpeg: ByteArray,
    ): AiGateReply {
        require(consent.consume(token, mediaId, revision)) { "PHOTO_SEND_NOT_AUTHORIZED" }
        require(prompt.isNotBlank() && jpeg.isNotEmpty() && jpeg.size <= MAX_IMAGE_BYTES)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt.trim()))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url",
                "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        val body = JSONObject().put("model", "auto").put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", content))).toString().toByteArray()
        val json = request("POST", "/v1/chat/completions", body)
        val choice = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return AiGateReply(requestId, choice.getString("content").take(MAX_RESPONSE_CHARS), json.optString("model").takeIf(String::isNotBlank))
    }

    private fun request(method: String, path: String, body: ByteArray? = null): JSONObject {
        cancelled.set(false)
        val url = endpoint.url(path)
        require(url.host == "127.0.0.1" && url.scheme == "http")
        val current = (URL(url.toString()).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; requestMethod = method
            connectTimeout = connectTimeoutMs; readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setFixedLengthStreamingMode(body.size); setRequestProperty("Content-Type", "application/json") }
        }
        connection = current
        return try {
            if (body != null) current.outputStream.use { it.write(body) }
            if (cancelled.get()) throw java.util.concurrent.CancellationException()
            val code = current.responseCode
            require(code !in 300..399) { "AiGate redirects are disabled" }
            val source = if (code in 200..299) current.inputStream else current.errorStream
            val bytes = source?.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { if (cancelled.get()) throw java.util.concurrent.CancellationException(); val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "AiGate response is too large" }; output.write(buffer, 0, count) }
                output.toByteArray()
            } ?: byteArrayOf()
            require(code in 200..299) { "AiGate HTTP $code: ${bytes.toString(Charsets.UTF_8).take(200)}" }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection = null; current.disconnect() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_RESPONSE_CHARS = 200_000
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

        fun discover(): Pair<AiGateEndpoint, AiGateHealth>? = AiGateEndpoint.discoveryPorts().firstNotNullOfOrNull { port ->
            runCatching { AiGateEndpoint(port).let { it to AiGateClient(it).health() } }.getOrNull()?.takeIf { it.second.running }
        }
    }
}

@SuppressLint("UseKtx")
object AiGateImage {
    fun encode(source: Bitmap, maxSide: Int = 1600): ByteArray {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true) else source
        return try {
            var quality = 90
            var bytes: ByteArray
            do { val output = ByteArrayOutputStream(); check(resized.compress(Bitmap.CompressFormat.JPEG, quality, output)); bytes = output.toByteArray(); quality -= 10 }
            while (bytes.size > AiGateClient.MAX_IMAGE_BYTES && quality >= 50)
            require(bytes.size <= AiGateClient.MAX_IMAGE_BYTES) { "PHOTO_TOO_LARGE" }
            bytes
        } finally { if (resized !== source) resized.recycle() }
    }

    fun encode(context: Context, uri: Uri?, orientation: Int?, maxSide: Int = 1600): ByteArray {
        require(uri != null)
        val decoded = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: error("PHOTO_UNAVAILABLE")
        val rotated = orientationMatrix(orientation).takeIf { !it.isIdentity }?.let { matrix ->
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        } ?: decoded
        val scale = minOf(1f, maxSide.toFloat() / maxOf(rotated.width, rotated.height))
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true)
            .also { if (it !== rotated) rotated.recycle() } else rotated
        return try { encode(resized, maxSide) } finally { resized.recycle() }
    }

    private fun orientationMatrix(orientation: Int?) = Matrix().apply { when (orientation) {
        2 -> postScale(-1f, 1f); 3 -> postRotate(180f); 4 -> postScale(1f, -1f)
        5 -> { postRotate(90f); postScale(-1f, 1f) }; 6 -> postRotate(90f)
        7 -> { postRotate(270f); postScale(-1f, 1f) }; 8 -> postRotate(270f)
    } }
}
