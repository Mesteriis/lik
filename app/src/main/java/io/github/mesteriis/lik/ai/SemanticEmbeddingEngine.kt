package io.github.mesteriis.lik.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SharedMemory
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaSource
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.io.File
import java.nio.ByteOrder

@android.annotation.SuppressLint("UseKtx")
class SemanticEmbeddingEngine(
    private val context: Context,
    private val catalog: ModelCatalog = ModelCatalog.get(context),
    private val artifacts: ArtifactStore = ArtifactStore(File(context.filesDir, "ai")),
    private val runtime: IsolatedRuntimeClient = IsolatedRuntimeClient(context),
) {
    @Volatile private var compactTokenizer: WordPieceTokenizer? = null
    @Volatile private var siglipTokenizer: SigLipBpeTokenizer? = null

    fun query(profile: ProfileId, text: String): FloatArray {
        require(text.isNotBlank())
        return when (profile) {
            ProfileId.COMPACT -> {
                val tokenizer = compactTokenizer ?: synchronized(this) { compactTokenizer ?: WordPieceTokenizer.load(file("multilingual-text-v1/vocab.txt")).also { compactTokenizer = it } }
                val tokens = tokenizer.encode(text, 128)
                runtime.embedText(file("multilingual-text-v1/model.onnx"), tokens.ids, tokens.mask, "last_hidden_state",
                    file("multilingual-text-v1/projection.safetensors")).getOrThrow()
            }
            ProfileId.BALANCED, ProfileId.EXTENDED -> {
                val tokenizer = siglipTokenizer ?: synchronized(this) { siglipTokenizer ?: SigLipBpeTokenizer.load(file("siglip2-tokenizer-v1/tokenizer.json")).also { siglipTokenizer = it } }
                val ids = tokenizer.encode(text, 64)
                val component = if (profile == ProfileId.BALANCED) "siglip2-base-v1" else "siglip2-large-v1"
                runtime.embedText(file("$component/text.onnx"), ids, null, "pooler_output").getOrThrow()
            }
        }
    }

    fun image(profile: ProfileId, mediaId: String, expectedRevision: Long): FloatArray {
        val row = MediaDatabase.get(context).media().get(mediaId) ?: error("PHOTO_MISSING")
        require(row.contentRevision == expectedRevision && row.availability.name == "AVAILABLE") { "PHOTO_CHANGED" }
        val bitmap = decode(row)
        return try { imageBitmap(profile, bitmap) } finally { bitmap.recycle() }
    }

    fun imageBitmap(profile: ProfileId, bitmap: Bitmap): FloatArray {
        val input = ImageTensor.prepare(bitmap, profile)
        val component = when (profile) { ProfileId.COMPACT -> "clip-image-v1"; ProfileId.BALANCED -> "siglip2-base-v1"; ProfileId.EXTENDED -> "siglip2-large-v1" }
        val output = if (profile == ProfileId.COMPACT) "image_embeds" else "pooler_output"
        return runtime.embedImage(file("$component/image.onnx"), input.memory, input.shape, output).getOrThrow()
    }

    /** Raw classifier logits only. Task 13 owns calibration, override and visibility policy. */
    fun sensitive(mediaId: String, expectedRevision: Long): FloatArray {
        val row = MediaDatabase.get(context).media().get(mediaId) ?: error("PHOTO_MISSING")
        require(row.contentRevision == expectedRevision && row.availability.name == "AVAILABLE") { "PHOTO_CHANGED" }
        val bitmap = decode(row)
        return try {
            val input = ImageTensor.prepareSensitive(bitmap)
            runtime.embedImage(file("sensitive-v1/model.onnx"), input.memory, input.shape, "logits", normalize = false).getOrThrow()
                .also { require(it.size == 2 && it.all(Float::isFinite)) }
        } finally { bitmap.recycle() }
    }

    private fun decode(row: io.github.mesteriis.lik.catalog.MediaRecord): Bitmap {
        val source = if (row.source == MediaSource.DEVICE) ImageDecoder.createSource(context.contentResolver, Uri.parse(row.contentUri))
        else ImageDecoder.createSource(PhotoLibrary.store(context).fileFor(requireNotNull(row.privateFileId)))
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; decoder.isMutableRequired = false }
    }

    private fun file(path: String): File {
        val spec = catalog.trusted.allArtifacts.values.singleOrNull { it.path == path } ?: error("Unknown artifact $path")
        require(artifacts.installed(spec.sha256, spec.size)) { "PROFILE_NOT_INSTALLED" }
        return artifacts.file(spec.sha256)
    }
}

data class SharedImageTensor(val memory: SharedMemory, val shape: IntArray)

@android.annotation.SuppressLint("UseKtx")
object ImageTensor {
    fun prepare(source: Bitmap, profile: ProfileId): SharedImageTensor {
        val size = when (profile) { ProfileId.EXTENDED -> 256; else -> 224 }
        val mean = if (profile == ProfileId.COMPACT) floatArrayOf(.48145466f, .4578275f, .40821073f) else floatArrayOf(.5f, .5f, .5f)
        val std = if (profile == ProfileId.COMPACT) floatArrayOf(.26862954f, .26130258f, .27577711f) else floatArrayOf(.5f, .5f, .5f)
        val values = FloatArray(3 * size * size)
        if (profile == ProfileId.COMPACT) centeredCrop(source, size, values, mean, std) else square(source, size, values, mean, std)
        val memory = SharedMemory.create("lik-image-tensor", values.size * 4)
        val buffer = memory.mapReadWrite().order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(values)
        SharedMemory.unmap(buffer); memory.setProtect(android.system.OsConstants.PROT_READ)
        return SharedImageTensor(memory, intArrayOf(1, 3, size, size))
    }

    fun prepareSensitive(source: Bitmap): SharedImageTensor {
        val size = 384
        val values = FloatArray(3 * size * size)
        centeredCrop(source, size, values, floatArrayOf(.5f, .5f, .5f), floatArrayOf(.5f, .5f, .5f))
        val memory = SharedMemory.create("lik-sensitive-tensor", values.size * 4)
        val buffer = memory.mapReadWrite().order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(values)
        SharedMemory.unmap(buffer); memory.setProtect(android.system.OsConstants.PROT_READ)
        return SharedImageTensor(memory, intArrayOf(1, 3, size, size))
    }

    private fun square(source: Bitmap, size: Int, output: FloatArray, mean: FloatArray, std: FloatArray) {
        for (y in 0 until size) for (x in 0 until size) {
            val sx = (x + .5f) * source.width / size - .5f; val sy = (y + .5f) * source.height / size - .5f
            write(output, size, x, y, bilinear(source, sx, sy), mean, std)
        }
    }

    private fun centeredCrop(source: Bitmap, size: Int, output: FloatArray, mean: FloatArray, std: FloatArray) {
        val scale = size.toFloat() / minOf(source.width, source.height)
        val resizedWidth = kotlin.math.floor(source.width * scale).toInt().coerceAtLeast(size)
        val resizedHeight = kotlin.math.floor(source.height * scale).toInt().coerceAtLeast(size)
        val left = (resizedWidth - size) / 2f; val top = (resizedHeight - size) / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val sx = (x + left + .5f) * source.width / resizedWidth - .5f
            val sy = (y + top + .5f) * source.height / resizedHeight - .5f
            write(output, size, x, y, bicubic(source, sx, sy), mean, std)
        }
    }

    private fun write(output: FloatArray, size: Int, x: Int, y: Int, color: Int, mean: FloatArray, std: FloatArray) {
        val at = y * size + x; val plane = size * size
        output[at] = ((color shr 16 and 255) / 255f - mean[0]) / std[0]
        output[plane + at] = ((color shr 8 and 255) / 255f - mean[1]) / std[1]
        output[2 * plane + at] = ((color and 255) / 255f - mean[2]) / std[2]
    }

    private fun bilinear(bitmap: Bitmap, x: Float, y: Float): Int {
        val x0 = kotlin.math.floor(x).toInt(); val y0 = kotlin.math.floor(y).toInt(); val dx = x - x0; val dy = y - y0
        return interpolate(bitmap, arrayOf(x0 to (1 - dx), x0 + 1 to dx), arrayOf(y0 to (1 - dy), y0 + 1 to dy))
    }

    private fun bicubic(bitmap: Bitmap, x: Float, y: Float): Int {
        val x0 = kotlin.math.floor(x).toInt(); val y0 = kotlin.math.floor(y).toInt()
        val xs = Array(4) { index -> val coordinate = x0 + index - 1; coordinate to cubic(x - coordinate) }
        val ys = Array(4) { index -> val coordinate = y0 + index - 1; coordinate to cubic(y - coordinate) }
        return interpolate(bitmap, xs, ys)
    }

    private fun interpolate(bitmap: Bitmap, xs: Array<Pair<Int, Float>>, ys: Array<Pair<Int, Float>>): Int {
        var r = 0f; var g = 0f; var b = 0f; var weight = 0f
        ys.forEach { (rawY, wy) -> xs.forEach { (rawX, wx) -> val w = wx * wy; val color = bitmap.getPixel(rawX.coerceIn(0, bitmap.width - 1), rawY.coerceIn(0, bitmap.height - 1))
            r += (color shr 16 and 255) * w; g += (color shr 8 and 255) * w; b += (color and 255) * w; weight += w } }
        return (255 shl 24) or ((r / weight).toInt().coerceIn(0, 255) shl 16) or ((g / weight).toInt().coerceIn(0, 255) shl 8) or (b / weight).toInt().coerceIn(0, 255)
    }

    private fun cubic(value: Float): Float { val x = kotlin.math.abs(value); return when { x <= 1 -> 1.5f*x*x*x - 2.5f*x*x + 1; x < 2 -> -.5f*x*x*x + 2.5f*x*x - 4*x + 2; else -> 0f } }
}
