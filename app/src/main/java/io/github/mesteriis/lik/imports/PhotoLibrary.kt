package io.github.mesteriis.lik.imports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File

object PhotoLibrary {
    @Volatile private var instance: PhotoStore? = null

    fun store(context: Context): PhotoStore = instance ?: synchronized(this) {
        instance ?: PhotoStore(File(context.filesDir, "imported_photos"), 200L * 1024 * 1024) {
            decode(it, 256).recycle()
        }.also { it.cleanupInterruptedImports(); instance = it }
    }

    fun decode(file: File, edge: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val scale = minOf(1.0, edge.toDouble() / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setOnPartialImageListener { false }
    }
}
