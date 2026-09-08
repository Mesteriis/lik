package io.github.mesteriis.lik.imports

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class ImportedPhoto(val id: String, val file: File)
data class StoredPhoto(val photo: ImportedPhoto, val added: Boolean)
enum class PhotoStoreError { EMPTY, TOO_LARGE, INVALID_IMAGE, STORAGE, INTERRUPTED, READ_FAILED }
class PhotoStoreException(val reason: PhotoStoreError, cause: Throwable? = null) :
    IOException(reason.name, cause)

/** One writer per directory. Only completed, validated files belong to the library. */
class PhotoStore(
    private val directory: File,
    private val maxBytes: Long,
    private val validate: (File) -> Unit,
) {
    fun cleanupInterruptedImports() {
        directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    @Synchronized
    fun photos(): List<ImportedPhoto> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.image")) }
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.nameWithoutExtension })
        .map { ImportedPhoto(it.nameWithoutExtension, it) }

    @Synchronized
    fun deletePhoto(id: String): Boolean {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "Invalid photo id" }
        val photo = File(directory, "$id.image")
        if (!photo.exists()) return false
        if (!photo.isFile || !photo.delete()) throw IOException("Cannot delete image")
        return true
    }

    @Synchronized
    fun importPhoto(input: InputStream): StoredPhoto {
        val source = input
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw PhotoStoreException(PhotoStoreError.STORAGE)
            val temporary = try {
                File.createTempFile("import-", ".part", directory)
            } catch (error: IOException) {
                throw PhotoStoreException(PhotoStoreError.STORAGE, error)
            }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                try { temporary.outputStream() } catch (error: IOException) {
                    throw PhotoStoreException(PhotoStoreError.STORAGE, error)
                }.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw PhotoStoreException(PhotoStoreError.INTERRUPTED)
                        val count = try { source.read(buffer) } catch (error: IOException) {
                            throw PhotoStoreException(PhotoStoreError.READ_FAILED, error)
                        }
                        if (count == -1) break
                        size += count
                        if (size > maxBytes) throw PhotoStoreException(PhotoStoreError.TOO_LARGE)
                        try { output.write(buffer, 0, count) } catch (error: IOException) {
                            throw PhotoStoreException(PhotoStoreError.STORAGE, error)
                        }
                        digest.update(buffer, 0, count)
                    }
                    if (size == 0L) throw PhotoStoreException(PhotoStoreError.EMPTY)
                    try { output.fd.sync() } catch (error: IOException) {
                        throw PhotoStoreException(PhotoStoreError.STORAGE, error)
                    }
                }
                if (Thread.currentThread().isInterrupted) throw PhotoStoreException(PhotoStoreError.INTERRUPTED)
                try { validate(temporary) } catch (error: IOException) {
                    throw PhotoStoreException(PhotoStoreError.INVALID_IMAGE, error)
                } catch (error: IllegalArgumentException) {
                    throw PhotoStoreException(PhotoStoreError.INVALID_IMAGE, error)
                }
                val id = digest.digest().joinToString("") { "%02x".format(it) }
                val destination = File(directory, "$id.image")
                if (destination.isFile) return StoredPhoto(ImportedPhoto(id, destination), false)
                if (!temporary.renameTo(destination)) throw PhotoStoreException(PhotoStoreError.STORAGE)
                return StoredPhoto(ImportedPhoto(id, destination), true)
            } finally {
                temporary.delete()
            }
        } finally {
            try { source.close() } catch (_: IOException) { }
        }
    }
}
