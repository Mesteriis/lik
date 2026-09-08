package io.github.mesteriis.lik

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Model acceptance probe only. This does not enable ML in the gallery. */
class ModelArtifactSmokeTest {
    @Test
    fun apkContainsPresetMetadataWithoutInstalledModels() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val availability = JSONObject(assets.open("models/availability.json").bufferedReader().use { it.readText() })
        assertEquals(false, availability.getBoolean("available"))
        assertEquals(false, availability.getBoolean("bundledPayloads"))
        assertEquals("balanced-v1", availability.getString("selectedProfile"))
        val catalog = JSONObject(assets.open("models/catalog-v1.json").bufferedReader().use { it.readText() })
        assertEquals(3, catalog.getJSONArray("profiles").length())
        assertTrue(assets.list("models").orEmpty().none { it.endsWith(".onnx") || it.startsWith("tokenizer") })
    }

    @Test
    fun externallyProvisionedModelsMatchTheirManifestAndRunOnAndroidCpu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = context.assets
        val provisioned = InstrumentationRegistry.getArguments().getString("externalModels") == "true"
        if (InstrumentationRegistry.getArguments().getString("requireModels") == "true") {
            assertTrue("CPU acceptance requires explicit external QA provisioning", provisioned)
        }
        assumeTrue("Model CPU probe requires external QA files; APK has metadata only", provisioned)
        val externalRoot = File(context.filesDir, "model-probe")
        val catalog = JSONObject(assets.open("models/catalog-v1.json").bufferedReader().use { it.readText() })
        val components = catalog.getJSONArray("components")
        val requestedComponent = InstrumentationRegistry.getArguments().getString("modelComponent")
        val failures = mutableListOf<String>()
        var expectedModels = 0
        val environment = OrtEnvironment.getEnvironment()
        var tested = 0
        for (componentIndex in 0 until components.length()) {
            val component = components.getJSONObject(componentIndex)
            if (requestedComponent != null && component.getString("id") != requestedComponent) continue
            val files = component.getJSONArray("artifacts")
            for (fileIndex in 0 until files.length()) {
                val artifact = files.getJSONObject(fileIndex)
                val path = artifact.getString("path")
                if (!path.endsWith(".onnx")) continue
                expectedModels++
                val model = File(externalRoot, path)
                val hash = MessageDigest.getInstance("SHA-256")
                var size = 0L
                model.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        hash.update(buffer, 0, read)
                        size += read
                    }
                }
                assertEquals(path, artifact.getLong("size"), size)
                assertEquals(path, artifact.getString("sha256"), hash.digest().joinToString("") { "%02x".format(it) })
                val contract = artifact.getJSONObject("onnx")
                val referenceContract = contract.getJSONObject("smokeReference")
                val referencePath = path.substringBeforeLast('/') + "/" + referenceContract.getString("file")
                val referenceBytes = File(externalRoot, referencePath).readBytes()
                assertEquals(referencePath, referenceContract.getLong("size"), referenceBytes.size.toLong())
                assertEquals(referencePath, referenceContract.getString("sha256"),
                    MessageDigest.getInstance("SHA-256").digest(referenceBytes).joinToString("") { "%02x".format(it) })
                val reference = ByteBuffer.wrap(referenceBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val sampling = AtomicBoolean(true)
                val peakPssKiB = AtomicLong(0)
                val sampler = Thread {
                    while (sampling.get()) {
                        peakPssKiB.accumulateAndGet(Debug.getPss().toLong(), ::maxOf)
                        Thread.sleep(100)
                    }
                }.apply { isDaemon = true; start() }
                val started = SystemClock.elapsedRealtime()
                try {
                    OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(2)
                        options.setInterOpNumThreads(1)
                        if (InstrumentationRegistry.getArguments().getString("disableOptimizations") == "true") {
                            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                        }
                        environment.createSession(model.absolutePath, options).use { session ->
                            val inputs = linkedMapOf<String, OnnxTensor>()
                            try {
                                val descriptors = contract.getJSONArray("inputs")
                                for (inputIndex in 0 until descriptors.length()) {
                                    val descriptor = descriptors.getJSONObject(inputIndex)
                                    val dimensions = descriptor.getJSONArray("smokeShape")
                                    val shape = LongArray(dimensions.length()) { dimensions.getLong(it) }
                                    val count = shape.fold(1L, Math::multiplyExact).toInt()
                                    val fill = descriptor.getInt("smokeFill")
                                    inputs[descriptor.getString("name")] = when (descriptor.getString("type")) {
                                        "float32" -> OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(count) { fill.toFloat() }), shape)
                                        "int64" -> OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(count) { fill.toLong() }), shape)
                                        else -> error("Unsupported manifest tensor type")
                                    }
                                }
                                session.run(inputs).use { result ->
                                    val outputs = contract.getJSONArray("outputs")
                                    assertEquals(path, outputs.length(), result.size())
                                    for (outputIndex in 0 until outputs.length()) {
                                        val descriptor = outputs.getJSONObject(outputIndex)
                                        val tensor = result.get(descriptor.getString("name")).get() as OnnxTensor
                                        val expected = descriptor.getJSONArray("smokeShape")
                                        assertEquals(path, expected.length(), tensor.info.shape.size)
                                        tensor.info.shape.forEachIndexed { index, dimension ->
                                            assertEquals(path, expected.getLong(index), dimension)
                                        }
                                        val floats = tensor.floatBuffer
                                        assertTrue(path, floats.remaining() > 0)
                                        var dot = 0.0
                                        var actualSquared = 0.0
                                        var expectedSquared = 0.0
                                        var maximumError = 0.0
                                        var allClose = true
                                        while (floats.hasRemaining()) {
                                            val actual = floats.get()
                                            val expectedValue = reference.get()
                                            assertTrue("Non-finite output: $path", actual.isFinite())
                                            allClose = allClose && abs(actual - expectedValue) <=
                                                referenceContract.getDouble("atol") + referenceContract.getDouble("rtol") * abs(expectedValue)
                                            maximumError = maxOf(maximumError, abs(actual.toDouble() - expectedValue))
                                            dot += actual.toDouble() * expectedValue
                                            actualSquared += actual.toDouble() * actual
                                            expectedSquared += expectedValue.toDouble() * expectedValue
                                        }
                                        val cosine = dot / sqrt(actualSquared * expectedSquared)
                                        val outputName = descriptor.getString("name")
                                        Log.i("LikModelProbe", "$path output=$outputName cosine=$cosine maximumAbsoluteError=$maximumError allClose=$allClose")
                                        if (referenceContract.getString("comparison") == "cosine") {
                                            if (cosine < referenceContract.getDouble("minimumCosine") || !cosine.isFinite()) {
                                                failures += "$path output=$outputName cosine=$cosine"
                                            }
                                        } else if (!allClose) {
                                            failures += "$path output=$outputName maximumAbsoluteError=$maximumError"
                                        }
                                    }
                                }
                            } finally {
                                inputs.values.forEach { it.close() }
                            }
                        }
                    }
                    assertEquals("Entire CPU reference consumed: $path", 0, reference.remaining())
                } finally {
                    sampling.set(false)
                    sampler.join(1_000)
                    Log.i("LikModelProbe", "$path load+inferenceMs=${SystemClock.elapsedRealtime() - started} sampledPeakPssKiB=${peakPssKiB.get()} samplePeriodMs=100 backend=ORT-CPU")
                }
                tested++
            }
        }
        assertTrue("At least one requested model must exist", expectedModels > 0)
        assertEquals("All requested model graphs must run", expectedModels, tested)
        if (requestedComponent == null) assertEquals("All profiles", 12, tested)
        assertTrue("Android/host CPU parity failures: ${failures.joinToString()}", failures.isEmpty())
    }
}
