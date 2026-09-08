package io.github.mesteriis.lik

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.TrashRepository
import io.github.mesteriis.lik.exports.PhotoExport
import io.github.mesteriis.lik.imports.PhotoLibrary
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class ExportDestinationPreservationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val authority = "io.github.mesteriis.lik.test.documents"

    private fun grant(vararg uris: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        fun missingGrant() = uris.any {
            context.checkUriPermission(it, android.os.Process.myPid(), android.os.Process.myUid(), flags) != PackageManager.PERMISSION_GRANTED
        }
        if (!missingGrant()) return
        instrumentation.context.startActivity(Intent().apply {
            setClassName(instrumentation.context, FixtureGrantActivity::class.java.name)
            putParcelableArrayListExtra("uris", ArrayList(uris.toList()))
            putExtra("grantFlags", flags)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        val deadline = System.nanoTime() + 5_000_000_000
        while (missingGrant()) {
            check(System.nanoTime() < deadline) { "Document grant did not arrive" }; Thread.sleep(20)
        }
    }

    private fun destination(mode: String): Uri {
        val parent = DocumentsContract.buildDocumentUri(authority, "root")
        val existing = DocumentsContract.buildDocumentUri(authority, "existing-$mode")
        grant(parent, existing)
        // Read before create to prove this returned URI represents pre-existing content.
        context.contentResolver.openInputStream(existing)!!.use { assertArrayEquals(sentinel(mode), it.readBytes()) }
        return requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent, "image/png", "existing-$mode"))
            .also { assertEquals(existing, it) }
    }

    private fun sentinel(mode: String) = "PREEXISTING SENTINEL $mode".toByteArray(Charsets.UTF_8)
    private fun assertPreserved(mode: String, sourceFailure: Boolean) {
        val uri = destination(mode)
        val directory = File(context.cacheDir, "prepared_exports")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val store = PhotoLibrary.store(context)
        val bitmap = android.graphics.Bitmap.createBitmap(9, 7, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val trash = TrashRepository(MediaDatabase.get(context), store)
        val item = trash.importPhoto(bytes.inputStream()).photo
        try {
            if (sourceFailure) assertTrue(item.file.delete())
            assertThrows(if (mode == "security") SecurityException::class.java else IOException::class.java) {
                PhotoExport(context).save(item.id, uri)
            }
            assertEquals("App export temps must still be cleaned", before, directory.listFiles().orEmpty().map { it.name }.toSet())
            // DocumentsProvider revokes grants on deletion; re-grant to inspect existence itself.
            grant(uri)
            context.contentResolver.query(uri, null, null, null, null)!!.use {
                assertEquals("Export failure must not delete the existing destination", 1, it.count)
            }
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(sentinel(mode), it.readBytes()) }
            if (!sourceFailure) assertArrayEquals(bytes, item.file.readBytes())
        } finally { trash.trash(setOf(item.id)); trash.purgeNow(setOf(item.id)) }
    }

    @Test fun sourceReadFailureNeverDeletesExistingProviderDocument() = assertPreserved("source", true)
    @Test fun destinationNoSpaceNeverDeletesExistingProviderDocument() = assertPreserved("write", false)
    @Test fun destinationSecurityFailureNeverDeletesExistingProviderDocument() = assertPreserved("security", false)
}
