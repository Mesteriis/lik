package io.github.mesteriis.lik.imports

import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhotoStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(limit: Long = 1024, validate: (File) -> Unit = {}) =
        PhotoStore(temporary.root, limit, validate)

    @Test fun preservesBytesAcrossReopeningAndDeduplicatesContent() {
        val bytes = byteArrayOf(1, 3, 5, 7)
        val first = store().importPhoto(bytes.inputStream())
        val second = store().importPhoto(bytes.inputStream())
        assertTrue(first.added)
        assertFalse(second.added)
        assertEquals(first.photo.id, second.photo.id)
        assertEquals(1, store().photos().size)
        assertArrayEquals(bytes, store().photos().single().file.readBytes())
    }

    @Test fun interruptedReadNeverPublishesPartialPhoto() {
        val input = object : InputStream() {
            var remaining = 20
            override fun read(): Int = if (remaining-- > 0) 1 else throw IOException("interrupted")
        }
        assertThrows(IOException::class.java) { store().importPhoto(input) }
        assertTrue(store().photos().isEmpty())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun emptyOversizedAndInvalidImagesLeaveNoCopies() {
        assertEquals(PhotoStoreError.EMPTY, assertThrows(PhotoStoreException::class.java) {
            store().importPhoto(byteArrayOf().inputStream())
        }.reason)
        assertEquals(PhotoStoreError.TOO_LARGE, assertThrows(PhotoStoreException::class.java) {
            store(3).importPhoto(byteArrayOf(1, 2, 3, 4).inputStream())
        }.reason)
        assertEquals(PhotoStoreError.INVALID_IMAGE, assertThrows(PhotoStoreException::class.java) {
            store(validate = { throw IOException("not an image") }).importPhoto(byteArrayOf(1).inputStream())
        }.reason)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun readAndStorageFailuresHaveDistinctReasons() {
        val unreadable = object : InputStream() {
            override fun read(): Int = throw IOException("provider stopped")
        }
        assertEquals(PhotoStoreError.READ_FAILED, assertThrows(PhotoStoreException::class.java) {
            store().importPhoto(unreadable)
        }.reason)

        val occupiedPath = temporary.newFile("not-a-directory")
        val unavailable = PhotoStore(occupiedPath, 1024) { }
        assertEquals(PhotoStoreError.STORAGE, assertThrows(PhotoStoreException::class.java) {
            unavailable.importPhoto(byteArrayOf(1).inputStream())
        }.reason)
    }

    @Test fun cleanupPreservesCommittedPhotosAndRemovesInterruptedTemporaryFile() {
        val saved = store().importPhoto(byteArrayOf(9).inputStream()).photo
        val partial = File(temporary.root, "interrupted.part").apply { writeText("partial") }
        store().cleanupInterruptedImports()
        assertFalse(partial.exists())
        assertTrue(saved.file.exists())
        assertEquals(saved.id, store().photos().single().id)
    }

    @Test fun equalTimestampsAreOrderedByIdAndDuplicateDoesNotChangeTimestamp() {
        val first = store().importPhoto(byteArrayOf(1).inputStream()).photo
        val second = store().importPhoto(byteArrayOf(2).inputStream()).photo
        val timestamp = 1_700_000_000_000L
        assertTrue(first.file.setLastModified(timestamp))
        assertTrue(second.file.setLastModified(timestamp))

        val expectedIds = listOf(first.id, second.id).sorted()
        assertEquals(expectedIds, store().photos().map { it.id })

        store().importPhoto(byteArrayOf(1).inputStream())
        assertEquals(timestamp, first.file.lastModified())
    }

    @Test fun deletingCopyPreservesInputAndAllowsReimport() {
        val source = temporary.newFile("source.jpg").apply {
            writeBytes(byteArrayOf(1, 3, 5, 7))
        }
        val library = store()
        val saved = library.importPhoto(source.inputStream()).photo

        assertTrue(library.deletePhoto(saved.id))
        assertTrue(source.exists())
        assertTrue(library.photos().isEmpty())
        assertTrue(library.importPhoto(source.inputStream()).added)
    }

    @Test fun deletingMissingPhotoIsIdempotentAndInvalidIdIsRejected() {
        val missing = "0".repeat(64)
        assertFalse(store().deletePhoto(missing))
        assertThrows(IllegalArgumentException::class.java) { store().deletePhoto("../outside") }
    }
}
