package io.github.mesteriis.lik.ai

import android.annotation.SuppressLint
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
    private var createdSessions = 0
    private data class ResidentSession(val session: ai.onnxruntime.OrtSession, val modelBytes: Long)
    private val sessions = LinkedHashMap<String, ResidentSession>(8, .75f, true)
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what !in setOf(MSG_VALIDATE, MSG_EMBED_IMAGE, MSG_EMBED_TEXT, MSG_RUN_FLOAT, MSG_RUN_SMOKE, MSG_STATS, MSG_EVICT)) return@Handler false
        val requestCode = message.what
        val reply = message.replyTo
        val payload = Bundle(message.data)
        val descriptors = payload.getParcelableArrayList(FDS, ParcelFileDescriptor::class.java).orEmpty()
        executor.execute {
            val result = runCatching { when (requestCode) {
                MSG_VALIDATE -> { validate(descriptors); null }
                MSG_EMBED_IMAGE -> embedImage(payload)
                MSG_EMBED_TEXT -> embedText(payload)
                MSG_RUN_FLOAT -> runFloat(payload)
                MSG_RUN_SMOKE -> runSmoke(payload)
                MSG_EVICT -> { payload.getStringArrayList(EVICT).orEmpty().forEach { sessions.remove(it)?.session?.close() }; null }
                else -> floatArrayOf(sessions.size.toFloat(), createdSessions.toFloat())
            } }
            result.exceptionOrNull()?.let { android.util.Log.e("LikAiRuntime", "Inference request failed", it) }
            descriptors.forEach { runCatching { it.close() } }
            val response = Message.obtain(null, MSG_RESULT).apply {
                data = Bundle().apply { putBoolean(OK, result.isSuccess); putString(ERROR, result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" })
                    result.getOrNull()?.let { putFloatArray(VECTOR, it) }
                    putStringArrayList(SESSIONS, ArrayList(sessions.keys)) }
            }
            runCatching { reply.send(response) }
        }
        true
    })

    override fun onBind(intent: Intent) = incoming.binder
    override fun onDestroy() { executor.shutdownNow(); sessions.values.forEach { it.session.close() }; sessions.clear(); super.onDestroy() }

    private fun session(data: Bundle, descriptor: ParcelFileDescriptor): ai.onnxruntime.OrtSession {
        val key = requireNotNull(data.getString(MODEL_ID))
        sessions[key]?.let { return it.session }
        val modelBytes = descriptor.statSize.takeIf { it > 0 } ?: Long.MAX_VALUE
        // Evict before opening another graph. ORT maps or allocates weights while createSession runs,
        // so insertion-time eviction can briefly hold two multi-GB SigLIP graphs and be killed.
        while (sessions.isNotEmpty() &&
            (sessions.size >= MAX_RESIDENT_SESSIONS || residentBytes() + modelBytes > MAX_RESIDENT_MODEL_BYTES)) {
            val eldest = sessions.entries.iterator().next()
            sessions.remove(eldest.key)
            eldest.value.session.close()
        }
        val created = ai.onnxruntime.OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            createSession(descriptor, options)
        }
        createdSessions++
        sessions[key] = ResidentSession(created, modelBytes)
        return created
    }

    private fun residentBytes(): Long = sessions.values.fold(0L) { total, entry ->
        if (Long.MAX_VALUE - total < entry.modelBytes) Long.MAX_VALUE else total + entry.modelBytes
    }

    private fun createSession(descriptor: ParcelFileDescriptor, options: ai.onnxruntime.OrtSession.SessionOptions): ai.onnxruntime.OrtSession =
        java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
            val model = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            ai.onnxruntime.OrtEnvironment.getEnvironment().createSession(model, options)
        }

    private fun validate(descriptors: List<ParcelFileDescriptor>) {
        // Validation may immediately open a model larger than the resident-cache budget. Release
        // cached serving sessions first so self-testing a new profile never overlaps their weights.
        sessions.values.forEach { it.session.close() }
        sessions.clear()
        descriptors.forEach { descriptor ->
            ai.onnxruntime.OrtSession.SessionOptions().use { options ->
                createSession(descriptor, options).use { session ->
                    require(session.inputNames.isNotEmpty() && session.outputNames.isNotEmpty())
                }
            }
        }
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

    private fun runFloat(data: Bundle): FloatArray {
        val descriptor = data.getParcelable(MODEL, ParcelFileDescriptor::class.java)!!
        val memory = data.getParcelable(MEMORY, SharedMemory::class.java)!!
        val shape = data.getIntArray(SHAPE)!!.map(Int::toLong).toLongArray()
        val mapped = memory.mapReadOnly().order(ByteOrder.nativeOrder())
        return try {
            val environment = ai.onnxruntime.OrtEnvironment.getEnvironment()
            session(data, descriptor).let { session ->
                ai.onnxruntime.OnnxTensor.createTensor(environment, mapped.asFloatBuffer(), shape).use { tensor ->
                    session.run(mapOf(requireNotNull(data.getString(INPUT)) to tensor)).use { output ->
                        val name = data.getString(OUTPUT) ?: session.outputNames.first()
                        val values = (output.get(name).get() as ai.onnxruntime.OnnxTensor).floatBuffer.toArray()
                        require(values.isNotEmpty() && values.all(Float::isFinite))
                        values
                    }
                }
            }
        } finally { SharedMemory.unmap(mapped); memory.close(); descriptor.close() }
    }

    private fun runSmoke(data: Bundle): FloatArray {
        val descriptor = data.getParcelable(MODEL, ParcelFileDescriptor::class.java)!!
        val names = data.getStringArrayList(INPUT_NAMES).orEmpty()
        val types = data.getStringArrayList(INPUT_TYPES).orEmpty()
        val shapes = data.getStringArrayList(INPUT_SHAPES).orEmpty()
        val fills = data.getDoubleArray(INPUT_FILLS) ?: DoubleArray(0)
        require(names.isNotEmpty() && names.size == types.size && names.size == shapes.size && names.size == fills.size)
        val environment = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val tensors = linkedMapOf<String, ai.onnxruntime.OnnxTensor>()
        return try {
            names.indices.forEach { index ->
                val shape = shapes[index].split(',').map(String::toLong).toLongArray()
                val count = shape.fold(1L, Math::multiplyExact).also { require(it in 1..Int.MAX_VALUE) }.toInt()
                tensors[names[index]] = when (types[index]) {
                    "float32" -> ai.onnxruntime.OnnxTensor.createTensor(environment,
                        java.nio.FloatBuffer.wrap(FloatArray(count) { fills[index].toFloat() }), shape)
                    "int64" -> ai.onnxruntime.OnnxTensor.createTensor(environment,
                        LongBuffer.wrap(LongArray(count) { fills[index].toLong() }), shape)
                    else -> error("UNSUPPORTED_SMOKE_INPUT:${types[index]}")
                }
            }
            session(data, descriptor).let { session ->
                session.run(tensors).use { output ->
                    (output.get(requireNotNull(data.getString(OUTPUT))).get() as ai.onnxruntime.OnnxTensor)
                        .floatBuffer.toArray().also { require(it.isNotEmpty() && it.all(Float::isFinite)) }
                }
            }
        } finally { tensors.values.forEach { it.close() }; descriptor.close() }
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
        const val MSG_RUN_FLOAT = 5
        const val MSG_STATS = 6
        const val MSG_EVICT = 7
        const val MSG_RUN_SMOKE = 8
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
        const val INPUT = "input"
        const val INPUT_NAMES = "input-names"
        const val INPUT_TYPES = "input-types"
        const val INPUT_SHAPES = "input-shapes"
        const val INPUT_FILLS = "input-fills"
        const val SESSIONS = "sessions"
        const val EVICT = "evict"
        private const val MAX_RESIDENT_SESSIONS = 8
        private const val MAX_RESIDENT_MODEL_BYTES = 1024L * 1024L * 1024L
    }
}


data class RuntimeStats(val liveSessions: Int, val createdSessions: Int, val connectionGeneration: Long)

/**
 * Process-wide persistent binding to the isolated runtime. Keeping this binding for the app-process
 * lifetime lets the size-budgeted session cache reuse graphs across an indexing batch and queries.
 * Binder death invalidates every lease; the next request binds a fresh isolated process.
 */
class IsolatedRuntimeClient(context: Context, private val leases: RuntimeLeases = processLeases) {
    private val transport = transport(context.applicationContext, leases)

    fun validate(models: List<File>, timeoutSeconds: Long = 180): Result<Unit> {
        val descriptors = ArrayList(models.map { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) })
        return try {
            requestBundle(InferenceRuntimeService.MSG_VALIDATE, models.toSet(), Bundle().apply {
                putParcelableArrayList(InferenceRuntimeService.FDS, descriptors)
            }, timeoutSeconds).map { Unit }
        } finally { descriptors.forEach { runCatching { it.close() } } }
    }

    fun embedImage(model: File, memory: SharedMemory, shape: IntArray, output: String, normalize: Boolean = true): Result<FloatArray> =
        requestVector(InferenceRuntimeService.MSG_EMBED_IMAGE, setOf(model), Bundle().apply {
            putParcelable(InferenceRuntimeService.MODEL, ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY))
            putString(InferenceRuntimeService.MODEL_ID, artifactDigest(model))
            putParcelable(InferenceRuntimeService.MEMORY, memory)
            putIntArray(InferenceRuntimeService.SHAPE, shape)
            putString(InferenceRuntimeService.OUTPUT, output)
            putBoolean(InferenceRuntimeService.NORMALIZE, normalize)
        }).also { memory.close() }

    fun embedText(model: File, ids: LongArray, mask: LongArray?, output: String, projection: File? = null): Result<FloatArray> =
        requestVector(InferenceRuntimeService.MSG_EMBED_TEXT, setOfNotNull(model, projection), Bundle().apply {
            putParcelable(InferenceRuntimeService.MODEL, ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY))
            putString(InferenceRuntimeService.MODEL_ID, artifactDigest(model))
            putLongArray(InferenceRuntimeService.IDS, ids)
            mask?.let { putLongArray(InferenceRuntimeService.MASK, it) }
            putString(InferenceRuntimeService.OUTPUT, output)
            projection?.let { putParcelable(InferenceRuntimeService.PROJECTION, ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)) }
        })

    fun runFloat(model: File, inputName: String, shape: IntArray, values: FloatArray, outputName: String? = null): Result<FloatArray> {
        require(shape.fold(1L) { product, value -> Math.multiplyExact(product, value.toLong()) } == values.size.toLong())
        val memory = SharedMemory.create("lik-smoke-tensor", values.size * 4)
        val mapped = memory.mapReadWrite().order(ByteOrder.nativeOrder())
        mapped.asFloatBuffer().put(values)
        SharedMemory.unmap(mapped)
        memory.setProtect(android.system.OsConstants.PROT_READ)
        return requestVector(InferenceRuntimeService.MSG_RUN_FLOAT, setOf(model), Bundle().apply {
            putParcelable(InferenceRuntimeService.MODEL, ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY))
            putString(InferenceRuntimeService.MODEL_ID, artifactDigest(model))
            putParcelable(InferenceRuntimeService.MEMORY, memory)
            putIntArray(InferenceRuntimeService.SHAPE, shape)
            putString(InferenceRuntimeService.INPUT, inputName)
            outputName?.let { putString(InferenceRuntimeService.OUTPUT, it) }
        }).also { memory.close() }
    }

    fun runSmoke(model: File, graph: GraphSmokeSpec): Result<FloatArray> =
        requestVector(InferenceRuntimeService.MSG_RUN_SMOKE, setOf(model), Bundle().apply {
            putParcelable(InferenceRuntimeService.MODEL, ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY))
            putString(InferenceRuntimeService.MODEL_ID, artifactDigest(model))
            putStringArrayList(InferenceRuntimeService.INPUT_NAMES, ArrayList(graph.inputs.map { it.name }))
            putStringArrayList(InferenceRuntimeService.INPUT_TYPES, ArrayList(graph.inputs.map { it.type }))
            putStringArrayList(InferenceRuntimeService.INPUT_SHAPES, ArrayList(graph.inputs.map { input -> input.shape.joinToString(",") }))
            putDoubleArray(InferenceRuntimeService.INPUT_FILLS, graph.inputs.map { it.fill }.toDoubleArray())
            putString(InferenceRuntimeService.OUTPUT, graph.outputName)
        })

    fun stats(): Result<RuntimeStats> = requestVector(InferenceRuntimeService.MSG_STATS, emptySet(), Bundle()).map {
        RuntimeStats(it[0].toInt(), it[1].toInt(), transport.connectionGeneration())
    }

    fun evict(digests: Set<String>): Result<Unit> {
        if (digests.isEmpty()) return Result.success(Unit)
        return requestBundle(InferenceRuntimeService.MSG_EVICT, emptySet(), Bundle().apply {
            putStringArrayList(InferenceRuntimeService.EVICT, ArrayList(digests))
        }, 180).map { Unit }
    }

    /** Test-only process-death equivalent: invalidates leases and proves the next call rebinds. */
    fun disconnectForTests() = transport.disconnectForTests()

    private fun requestVector(code: Int, files: Set<File>, payload: Bundle): Result<FloatArray> =
        try { requestBundle(code, files, payload, 180).mapCatching { requireNotNull(it.getFloatArray(InferenceRuntimeService.VECTOR)) } }
        finally { payload.closeDescriptors() }

    private fun requestBundle(code: Int, files: Set<File>, payload: Bundle, timeoutSeconds: Long): Result<Bundle> {
        val lease = leases.acquire(files.map(::artifactDigest).toSet())
        return try {
            transport.request(code, payload, timeoutSeconds).also {
                if (!leases.valid(lease) && it.isSuccess) return Result.failure(IllegalStateException("RUNTIME_DIED"))
            }
        } finally { leases.release(lease) }
    }

    private fun Bundle.closeDescriptors() {
        listOf(InferenceRuntimeService.MODEL, InferenceRuntimeService.PROJECTION).forEach { key ->
            getParcelable(key, ParcelFileDescriptor::class.java)?.let { runCatching { it.close() } }
        }
    }

    private fun artifactDigest(file: File): String =
        file.name.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: ArtifactStore.sha256(file)

    companion object {
        private val processLeases = RuntimeLeases()
        // PersistentRuntimeTransport receives applicationContext only and intentionally owns the
        // process-lifetime service binding used by indexing and search.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var sharedTransport: PersistentRuntimeTransport? = null
        private fun transport(context: Context, leases: RuntimeLeases): PersistentRuntimeTransport =
            sharedTransport ?: synchronized(this) {
                sharedTransport ?: PersistentRuntimeTransport(context, leases).also { sharedTransport = it }
            }
        fun liveArtifactDigests(): Set<String> = processLeases.liveDigests() + sharedTransport?.residentDigests().orEmpty()
    }
}

private class PersistentRuntimeTransport(
    private val context: Context,
    private val leases: RuntimeLeases,
) {
    private val lock = java.lang.Object()
    @Volatile private var messenger: Messenger? = null
    @Volatile private var generation = 0L
    @Volatile private var residents: Set<String> = emptySet()
    private var binding = false
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(lock) {
                messenger = Messenger(binder)
                binding = false
                bound = true
                generation++
                lock.notifyAll()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = lost()
        override fun onBindingDied(name: ComponentName) = lost()
        override fun onNullBinding(name: ComponentName) = lost()
    }

    fun request(code: Int, payload: Bundle, timeoutSeconds: Long): Result<Bundle> {
        val remote = ensureConnected(timeoutSeconds).getOrElse { return Result.failure(it) }
        val startGeneration = generation
        val latch = CountDownLatch(1)
        var response: Bundle? = null
        val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == InferenceRuntimeService.MSG_RESULT) { response = Bundle(message.data); latch.countDown() }
            true
        })
        return runCatching {
            try { remote.send(Message.obtain(null, code).apply { replyTo = callback; data = payload }) }
            catch (dead: RemoteException) { lost(); throw dead }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (generation != startGeneration) error("RUNTIME_DIED")
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (System.nanoTime() >= deadline) error("RUNTIME_TIMEOUT")
            }
            val result = requireNotNull(response)
            residents = result.getStringArrayList(InferenceRuntimeService.SESSIONS).orEmpty().toSet()
            if (!result.getBoolean(InferenceRuntimeService.OK)) error(result.getString(InferenceRuntimeService.ERROR) ?: "RUNTIME_ERROR")
            result
        }
    }

    fun connectionGeneration(): Long = generation
    fun residentDigests(): Set<String> = residents

    fun disconnectForTests() {
        synchronized(lock) {
            if (bound || binding) runCatching { context.unbindService(connection) }
            messenger = null
            residents = emptySet()
            bound = false
            binding = false
            generation++
            leases.runtimeDied()
            lock.notifyAll()
        }
    }

    private fun ensureConnected(timeoutSeconds: Long): Result<Messenger> = runCatching {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        synchronized(lock) {
            messenger?.let { return@runCatching it }
            if (!binding) {
                binding = true
                if (!context.bindService(Intent(context, InferenceRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                    binding = false
                    error("RUNTIME_BIND_FAILED")
                }
            }
            while (messenger == null) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) error("RUNTIME_BIND_TIMEOUT")
                TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            }
            requireNotNull(messenger)
        }
    }

    private fun lost() {
        val unbind = synchronized(lock) {
            val wasBound = bound || binding
            messenger = null
            residents = emptySet()
            binding = false
            bound = false
            generation++
            leases.runtimeDied()
            lock.notifyAll()
            wasBound
        }
        if (unbind) runCatching { context.unbindService(connection) }
    }
}
