package io.github.mesteriis.lik.exports

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Prepared snapshots isolate outgoing grants from the private library and source providers. */
class ExportFiles(private val directory: File) {
    fun prepare(mime: String, source: () -> InputStream): File = synchronized(monitor) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot prepare export")
        val stem = UUID.randomUUID().toString()
        val part = File(directory, "$stem.part")
        val extension = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/heic" -> "heic"; "image/avif" -> "avif"; "image/gif" -> "gif"; else -> "image" }
        val target = File(directory, "$stem.$extension")
        try {
            source().use { input -> part.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            if (!part.renameTo(target)) throw IOException("Cannot publish export")
            target
        } finally { part.delete() }
    }

    /** Null destination is cancellation. Every failure closes streams and removes the snapshot. */
    fun save(destination: (() -> OutputStream)?, validate: () -> Unit = {}, source: () -> InputStream): Boolean {
        if (destination == null) return false
        val prepared = prepare("image/*", source)
        try {
            validate()
            destination().use { output ->
                prepared.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        validate()
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
            }
            return true
        } finally { prepared.delete() }
    }

    companion object { internal val monitor = Any() }
}
