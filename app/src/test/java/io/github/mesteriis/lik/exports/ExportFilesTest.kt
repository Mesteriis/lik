package io.github.mesteriis.lik.exports

import java.io.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun revokedAccessAfterStagingNeverOpensDestination() {
        val files = ExportFiles(temporary.root)
        var allowed = true
        var opened = false
        val source = object : ByteArrayInputStream(byteArrayOf(3, 5, 8)) {
            override fun close() { allowed = false; super.close() }
        }
        assertThrows(SecurityException::class.java) {
            files.save({ opened = true; ByteArrayOutputStream() }, validate = {
                if (!allowed) throw SecurityException("Photo relocked")
            }) { source }
        }
        assertFalse(opened)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationCreatesNothingAndDoesNotOpenSource() {
        val files = ExportFiles(temporary.root)
        assertFalse(files.save(null) { error("Cancelled destination must not open source") })
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun sourceAndPreparedBytesSurviveDestinationFailuresWithoutTempFiles() {
        val original = temporary.newFile("original").apply { writeBytes(byteArrayOf(3, 5, 8)) }
        val cache = temporary.newFolder("exports")
        val files = ExportFiles(cache)
        for (error in listOf(IOException("no space"), IOException("disconnected"), SecurityException("revoked"))) {
            val destination = object : OutputStream() { override fun write(value: Int) { throw error } }
            assertThrows(error.javaClass) { files.save({ destination }) { original.inputStream() } }
            assertArrayEquals(byteArrayOf(3, 5, 8), original.readBytes())
            assertTrue(cache.listFiles()!!.isEmpty())
        }
        assertThrows(SecurityException::class.java) { files.save({ throw SecurityException("revoked") }) { original.inputStream() } }
        assertTrue(cache.listFiles()!!.isEmpty())
        val output = ByteArrayOutputStream()
        assertTrue(files.save({ output }) { original.inputStream() })
        assertArrayEquals(original.readBytes(), output.toByteArray())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun failedPreparationCleansPartialSnapshotAndSuccessfulCopyIsIndependent() {
        val files = ExportFiles(temporary.root)
        val broken = object : InputStream() { override fun read(): Int = throw IOException("revoked source") }
        assertThrows(IOException::class.java) { files.prepare("image/jpeg") { broken } }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
        val file = files.prepare("image/jpeg") { byteArrayOf(9, 10).inputStream() }
        assertArrayEquals(byteArrayOf(9, 10), file.readBytes())
        assertTrue(file.name.endsWith(".jpg"))
        assertEquals(listOf(file), temporary.root.listFiles()!!.toList())
    }
}
