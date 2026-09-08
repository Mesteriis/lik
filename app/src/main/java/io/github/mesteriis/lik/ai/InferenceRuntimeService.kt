package io.github.mesteriis.lik.ai

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.ByteOrder
import java.nio.LongBuffer
import kotlin.math.sqrt

/** Private isolated process. Its single executor is the process-level one-heavy-task gate. */
class InferenceRuntimeService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val sessions = object : LinkedHashMap<String, ai.onnxruntime.OrtSession>(3, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ai.onnxruntime.OrtSession>?): Boolean {
            if (size <= 2) return false
            eldest?.value?.close(); return true
        }
    }
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what !in setOf(MSG_VALIDATE, MSG_EMBED_IMAGE, MSG_EMBED_TEXT)) return@Handler false
        val requestCode = message.what
        val reply = message.replyTo
        val payload = Bundle(message.data)
        val descriptors = payload.getParcelableArrayList(FDS, ParcelFileDescriptor::class.java).orEmpty()
        executor.execute {
            val result = runCatching { when (requestCode) {
                MSG_VALIDATE -> { validate(descriptors); null }
                MSG_EMBED_IMAGE -> embedImage(payload)
                else -> embedText(payload)
            } }
            result.exceptionOrNull()?.let { android.util.Log.e("LikAiRuntime", "Inference request failed", it) }
            descriptors.forEach { runCatching { it.close() } }
            val response = Message.obtain(null, MSG_RESULT).apply {
                data = Bundle().apply { putBoolean(OK, result.isSuccess); putString(ERROR, result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" })
                    result.getOrNull()?.let { putFloatArray(VECTOR, it) } }
            }
            runCatching { reply.send(response) }
        }
        true
    })

    override fun onBind(intent: Intent) = incoming.binder
    override fun onDestroy() { executor.shutdownNow(); sessions.values.forEach { it.close() }; sessions.clear(); super.onDestroy() }

    private fun session(data: Bundle, descriptor: ParcelFileDescriptor): ai.onnxruntime.OrtSession {
        val key = requireNotNull(data.getString(MODEL_ID))
        return sessions[key] ?: createSession(descriptor, ai.onnxruntime.OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2); setInterOpNumThreads(1)
        }).also { sessions[key] = it }
    }

    private fun createSession(descriptor: ParcelFileDescriptor, options: ai.onnxruntime.OrtSession.SessionOptions): ai.onnxruntime.OrtSession =
        java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
            val model = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            ai.onnxruntime.OrtEnvironment.getEnvironment().createSession(model, options)
        }

    private fun validate(descriptors: List<ParcelFileDescriptor>) {
        descriptors.forEach { descriptor -> createSession(descriptor, ai.onnxruntime.OrtSession.SessionOptions()).use { session ->
            require(session.inputNames.isNotEmpty() && session.outputNames.isNotEmpty())
        } }
    }

    private fun embedImage(data: Bundle): FloatArray {
        val descriptor = data.getParcelable(MODEL, ParcelFileDescriptor::class.java)!!
        val memory = data.getParcelable(MEMORY, SharedMemory::class.java)!!
        val shape = data.getIntArray(SHAPE)!!.map(Int::toLong).toLongArray()
        val mapped = memory.mapReadOnly().order(ByteOrder.nativeOrder())
        return try {
            val environment = ai.onnxruntime.OrtEnvironment.getEnvironment()
            session(data, descriptor).let { session ->
                ai.onnxruntime.OnnxTensor.createTensor(environment, mapped.asFloatBuffer(), shape).use { tensor ->
                    session.run(mapOf("pixel_values" to tensor)).use { output ->
                        val raw = (output.get(data.getString(OUTPUT)!!).get() as ai.onnxruntime.OnnxTensor).floatBuffer.toArray()
                        if (data.getBoolean(NORMALIZE, true)) normalize(raw) else raw
                    }
                }
            }
        } finally { SharedMemory.unmap(mapped); memory.close(); descriptor.close() }
    }

    private fun embedText(data: Bundle): FloatArray {
        val descriptor = data.getParcelable(MODEL, ParcelFileDescriptor::class.java)!!
        val ids = data.getLongArray(IDS)!!
        val mask = data.getLongArray(MASK)
        val outputName = data.getString(OUTPUT)!!
        val environment = ai.onnxruntime.OrtEnvironment.getEnvironment()
        return try {
            session(data, descriptor).let { session ->
                ai.onnxruntime.OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { idTensor ->
                    val inputs = mutableMapOf<String, ai.onnxruntime.OnnxTensor>("input_ids" to idTensor)
                    val maskTensor = mask?.let { ai.onnxruntime.OnnxTensor.createTensor(environment, LongBuffer.wrap(it), longArrayOf(1, it.size.toLong())) }
                    maskTensor.use {
                        if (it != null) inputs["attention_mask"] = it
                        session.run(inputs).use { output ->
                            val raw = (output.get(outputName).get() as ai.onnxruntime.OnnxTensor).floatBuffer.toArray()
                            if (mask == null) normalize(raw) else {
                                val hidden = raw.size / ids.size
                                val pooled = FloatArray(hidden)
                                var count = 0f
                                mask.forEachIndexed { token, include -> if (include != 0L) { count++
                                    for (column in 0 until hidden) pooled[column] += raw[token * hidden + column] } }
                                pooled.indices.forEach { pooled[it] /= count }
                                val projection = data.getParcelable(PROJECTION, ParcelFileDescriptor::class.java)!!
                                projection.use { normalize(project(pooled, it)) }
                            }
                        }
                    }
                }
            }
        } finally { descriptor.close() }
    }

    private fun project(vector: FloatArray, descriptor: ParcelFileDescriptor): FloatArray {
        java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
            val prefix = java.nio.ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN); channel.read(prefix); prefix.flip()
            val header = prefix.long
            require(header in 2..(1024 * 1024))
            val weights = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 8 + header, channel.size() - 8 - header).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            require(weights.remaining() == 512 * vector.size)
            return FloatArray(512) { row -> var value = 0f; for (column in vector.indices) value += weights[row * vector.size + column] * vector[column]; value }
        }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        require(vector.isNotEmpty() && vector.all(Float::isFinite))
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat(); require(norm > 0)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun java.nio.FloatBuffer.toArray() = FloatArray(remaining()).also(::get)

    companion object {
        const val MSG_VALIDATE = 1
        const val MSG_RESULT = 2
        const val MSG_EMBED_IMAGE = 3
        const val MSG_EMBED_TEXT = 4
        const val FDS = "fds"
        const val OK = "ok"
        const val ERROR = "error"
        const val VECTOR = "vector"
        const val MODEL = "model"
        const val MODEL_ID = "model-id"
        const val MEMORY = "memory"
        const val SHAPE = "shape"
        const val IDS = "ids"
        const val MASK = "mask"
        const val OUTPUT = "output"
        const val PROJECTION = "projection"
        const val NORMALIZE = "normalize"
    }
}

class IsolatedRuntimeClient(private val context: Context, private val leases: RuntimeLeases = processLeases) {
    fun validate(models: List<File>, timeoutSeconds: Long = 180): Result<Unit> {
        val digests = models.map(::artifactDigest).toSet()
        val lease = leases.acquire(digests)
        val latch = CountDownLatch(1)
        var outcome: Result<Unit> = Result.failure(IllegalStateException("RUNTIME_NO_REPLY"))
        val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == InferenceRuntimeService.MSG_RESULT) {
                outcome = if (message.data.getBoolean(InferenceRuntimeService.OK)) Result.success(Unit)
                else Result.failure(IllegalStateException(message.data.getString(InferenceRuntimeService.ERROR) ?: "RUNTIME_ERROR"))
                latch.countDown()
            }
            true
        })
        val descriptors = ArrayList(models.map { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) })
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val request = Message.obtain(null, InferenceRuntimeService.MSG_VALIDATE).apply {
                    replyTo = callback
                    data = Bundle().apply { putParcelableArrayList(InferenceRuntimeService.FDS, descriptors) }
                }
                runCatching { Messenger(binder).send(request) }.onFailure { outcome = Result.failure(it); latch.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) { leases.runtimeDied(); latch.countDown() }
            override fun onBindingDied(name: ComponentName) { leases.runtimeDied(); latch.countDown() }
            override fun onNullBinding(name: ComponentName) { latch.countDown() }
        }
        return try {
            bound = context.bindService(Intent(context, InferenceRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) Result.failure(IllegalStateException("RUNTIME_BIND_FAILED"))
            else if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) Result.failure(IllegalStateException("RUNTIME_TIMEOUT")) else outcome
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            descriptors.forEach { runCatching { it.close() } }
            leases.release(lease)
        }
    }

    fun embedImage(model: File, memory: SharedMemory, shape: IntArray, output: String, normalize: Boolean = true): Result<FloatArray> =
        request(MSG_EMBED_IMAGE, setOf(model), Bundle().apply {
            putParcelable(MODEL, ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY))
            putString(MODEL_ID, model.name); putParcelable(MEMORY, memory); putIntArray(SHAPE, shape); putString(OUTPUT, output); putBoolean(NORMALIZE, normalize)
        }).also { memory.close() }

    fun embedText(model: File, ids: LongArray, mask: LongArray?, output: String, projection: File? = null): Result<FloatArray> =
        request(MSG_EMBED_TEXT, setOfNotNull(model, projection), Bundle().apply {
            putParcelable(MODEL, ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY))
            putString(MODEL_ID, model.name); putLongArray(IDS, ids); mask?.let { putLongArray(MASK, it) }; putString(OUTPUT, output)
            projection?.let { putParcelable(PROJECTION, ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)) }
        })

    private fun request(code: Int, files: Set<File>, payload: Bundle): Result<FloatArray> {
        val lease = leases.acquire(files.map(::artifactDigest).toSet())
        val latch = CountDownLatch(1); var outcome = Result.failure<FloatArray>(IllegalStateException("RUNTIME_NO_REPLY"))
        val callback = Messenger(Handler(Looper.getMainLooper()) { message -> if (message.what == MSG_RESULT) {
            outcome = if (message.data.getBoolean(OK)) Result.success(message.data.getFloatArray(VECTOR)!!)
            else Result.failure(IllegalStateException(message.data.getString(ERROR) ?: "RUNTIME_ERROR")); latch.countDown() }; true })
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { runCatching { Messenger(binder).send(Message.obtain(null, code).apply { replyTo = callback; data = payload }) }.onFailure { outcome = Result.failure(it); latch.countDown() } }
            override fun onServiceDisconnected(name: ComponentName) { leases.runtimeDied(); latch.countDown() }
            override fun onBindingDied(name: ComponentName) { leases.runtimeDied(); latch.countDown() }
            override fun onNullBinding(name: ComponentName) { latch.countDown() }
        }
        return try { bound = context.bindService(Intent(context, InferenceRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) Result.failure(IllegalStateException("RUNTIME_BIND_FAILED"))
            else if (!latch.await(180, TimeUnit.SECONDS)) Result.failure(IllegalStateException("RUNTIME_TIMEOUT")) else outcome
        } finally { if (bound) runCatching { context.unbindService(connection) }; payload.closeDescriptors(); leases.release(lease) }
    }

    private fun Bundle.closeDescriptors() {
        listOf(MODEL, PROJECTION).forEach { key ->
            getParcelable(key, ParcelFileDescriptor::class.java)?.let { descriptor ->
                runCatching { descriptor.close() }
            }
        }
    }
    private fun artifactDigest(file: File): String = file.name.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: ArtifactStore.sha256(file)

    companion object {
        private val processLeases = RuntimeLeases()
        fun liveArtifactDigests(): Set<String> = processLeases.liveDigests()
        private const val MSG_EMBED_IMAGE = InferenceRuntimeService.MSG_EMBED_IMAGE
        private const val MSG_EMBED_TEXT = InferenceRuntimeService.MSG_EMBED_TEXT
        private const val MSG_RESULT = InferenceRuntimeService.MSG_RESULT
        private const val MODEL = InferenceRuntimeService.MODEL
        private const val MODEL_ID = InferenceRuntimeService.MODEL_ID
        private const val MEMORY = InferenceRuntimeService.MEMORY
        private const val SHAPE = InferenceRuntimeService.SHAPE
        private const val IDS = InferenceRuntimeService.IDS
        private const val MASK = InferenceRuntimeService.MASK
        private const val OUTPUT = InferenceRuntimeService.OUTPUT
        private const val PROJECTION = InferenceRuntimeService.PROJECTION
        private const val NORMALIZE = InferenceRuntimeService.NORMALIZE
        private const val OK = InferenceRuntimeService.OK
        private const val ERROR = InferenceRuntimeService.ERROR
        private const val VECTOR = InferenceRuntimeService.VECTOR
    }
}
