package io.github.mesteriis.lik.aigate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
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
    fun observeEnabled(listener: (Boolean) -> Unit): AutoCloseable {
        val observer = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enabled") listener(enabled)
        }
        values.registerOnSharedPreferenceChangeListener(observer)
        return AutoCloseable { values.unregisterOnSharedPreferenceChangeListener(observer) }
    }
}

class AiGateClient(
    private val endpoint: AiGateEndpoint,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val overallTimeoutMs: Int = OVERALL_TIMEOUT_MS,
) {
    init { require(connectTimeoutMs > 0 && readTimeoutMs > 0 && overallTimeoutMs > 0) }
    private val cancelled = AtomicBoolean()
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { cancelled.set(true); connection?.disconnect() }

    fun health(): AiGateHealth {
        val json = request("GET", "/health")
        require(json.optString("service") == "aigate") { "Unexpected loopback service" }
        val countKey = when { json.has("models_count") -> "models_count"; json.has("model_count") -> "model_count"; else -> null }
        return AiGateHealth(json.optBoolean("running"), json.optInt("port", endpoint.port),
            json.optString("version").takeIf(String::isNotBlank), countKey?.let(json::getInt))
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
        if (cancelled.get()) throw java.util.concurrent.CancellationException()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(overallTimeoutMs.toLong())
        fun remaining(): Int {
            val value = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
            if (value <= 0 || cancelled.get()) throw if (cancelled.get()) java.util.concurrent.CancellationException()
                else java.net.SocketTimeoutException("AiGate overall deadline exceeded")
            return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val url = endpoint.url(path)
        require(url.host == "127.0.0.1" && url.scheme == "http")
        val current = (URL(url.toString()).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; requestMethod = method
            connectTimeout = minOf(connectTimeoutMs, remaining()); readTimeout = minOf(readTimeoutMs, remaining())
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setFixedLengthStreamingMode(body.size); setRequestProperty("Content-Type", "application/json") }
        }
        connection = current
        val timedOut = AtomicBoolean()
        val watchdog = deadlines.schedule({ timedOut.set(true); current.disconnect() }, overallTimeoutMs.toLong(),
            java.util.concurrent.TimeUnit.MILLISECONDS)
        return try {
            if (body != null) current.outputStream.use { output ->
                var offset = 0
                while (offset < body.size) {
                    remaining()
                    val count = minOf(WRITE_CHUNK_BYTES, body.size - offset)
                    output.write(body, offset, count); offset += count
                }
                output.flush(); remaining()
            }
            current.readTimeout = minOf(readTimeoutMs, remaining())
            if (cancelled.get()) throw java.util.concurrent.CancellationException()
            val code = current.responseCode
            require(code !in 300..399) { "AiGate redirects are disabled" }
            val source = if (code in 200..299) current.inputStream else current.errorStream
            val bytes = source?.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { current.readTimeout = minOf(readTimeoutMs, remaining()); val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "AiGate response is too large" }; output.write(buffer, 0, count) }
                output.toByteArray()
            } ?: byteArrayOf()
            require(code in 200..299) { "AiGate HTTP $code: ${bytes.toString(Charsets.UTF_8).take(200)}" }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (error: Throwable) {
            if (timedOut.get()) throw java.net.SocketTimeoutException("AiGate overall deadline exceeded").apply { initCause(error) }
            throw error
        } finally { watchdog.cancel(false); connection = null; current.disconnect() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val OVERALL_TIMEOUT_MS = 50_000
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_RESPONSE_CHARS = 200_000
        private const val WRITE_CHUNK_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "lik-aigate-deadlines").apply { isDaemon = true }
        }

        fun discover(onClient: (AiGateClient?) -> Unit = {}): Pair<AiGateEndpoint, AiGateHealth>? =
            AiGateEndpoint.discoveryPorts().firstNotNullOfOrNull { port ->
                val endpoint = AiGateEndpoint(port); val client = AiGateClient(endpoint); onClient(client)
                try { runCatching { endpoint to client.health().also { health ->
                    require(health.running) { "AIGATE_NOT_RUNNING" }; client.models()
                } }.getOrNull() } finally { onClient(null) }
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

    @Suppress("UNUSED_PARAMETER")
    fun encode(context: Context, uri: Uri?, orientation: Int?, maxSide: Int = 1600): ByteArray {
        require(uri != null)
        return decode(ImageDecoder.createSource(context.contentResolver, uri), maxSide)
    }

    fun encode(file: File, maxSide: Int = 1600): ByteArray = decode(ImageDecoder.createSource(file), maxSide)

    private fun decode(source: ImageDecoder.Source, maxSide: Int): ByteArray {
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            decoder.setTargetSampleSize((longest / (maxSide * 2)).coerceAtLeast(1))
        }
        return try { encode(decoded, maxSide) } finally { decoded.recycle() }
    }
}
