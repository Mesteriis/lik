package io.github.mesteriis.lik

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.mesteriis.lik.exports.PhotoExport
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.catalog.*
import java.io.File

class PhotoExportTest {
    @Test fun sharingGrantsOnlySelectedPreparedUrisAndNeverPrivateFiles() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "prepared_exports").apply { mkdirs() }
        val selected = File(directory, "selected.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        val other = File(directory, "other.jpg").apply { writeBytes(byteArrayOf(3)) }
        try {
            val first = FileProvider.getUriForFile(context, "${context.packageName}.exports", selected)
            val second = FileProvider.getUriForFile(context, "${context.packageName}.exports", other)
            val one = PhotoExport.shareIntent(listOf(first), "image/jpeg")
            assertEquals(Intent.ACTION_SEND, one.action)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, one.flags)
            assertEquals(first, one.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            assertEquals(1, one.clipData!!.itemCount)
            assertEquals(first, one.clipData!!.getItemAt(0).uri)
            val multiple = PhotoExport.shareIntent(listOf(first, second), "image/*")
            assertEquals(Intent.ACTION_SEND_MULTIPLE, multiple.action)
            assertEquals(listOf(first, second), multiple.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java))
            assertEquals(2, multiple.clipData!!.itemCount)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, multiple.flags)
            for (file in listOf(File(context.filesDir, "imported_photos/private.image"), context.getDatabasePath("media.db"), File(context.cacheDir, "unprepared.jpg"))) {
                assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, "${context.packageName}.exports", file) }
            }
            val testUid = context.packageManager.getApplicationInfo("${context.packageName}.test", 0).uid
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkUriPermission(second, -1, testUid, Intent.FLAG_GRANT_READ_URI_PERMISSION))
            val received = java.util.concurrent.CountDownLatch(1)
            var result: android.os.Bundle? = null
            val receiver = object : android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) { result = resultData; received.countDown() }
            }
            context.startActivity(one.setComponent(android.content.ComponentName("${context.packageName}.test", "io.github.mesteriis.lik.ExportReceiverActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("other", second.toString()).putExtra("receiver", receiver))
            assertTrue(received.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(result!!.getBoolean("selectedReadable"))
            assertFalse(result!!.getBoolean("selectedWritable"))
            assertFalse(result!!.getBoolean("otherReadable"))
        } finally { selected.delete(); other.delete() }
    }

    @Test fun actualSnapshotPreservesPngMimeAndSourceAfterTrashAndCancellation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = PhotoLibrary.store(context)
        val bitmap = android.graphics.Bitmap.createBitmap(3, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val bytes = output.toByteArray()
        val trash = TrashRepository(MediaDatabase.get(context), store)
        val item = trash.importPhoto(bytes.inputStream()).photo
        MediaDatabase.get(context).ocrPeople().saveExposure(io.github.mesteriis.lik.ai.AiMediaExposureRecord(item.id,MediaDatabase.get(context).media().get(item.id)!!.contentRevision,io.github.mesteriis.lik.ai.AiExposure.SAFE,1))
        MediaDatabase.get(context).sensitiveMedia().saveManual(io.github.mesteriis.lik.privacy.SensitiveManualRecord(item.id,MediaDatabase.get(context).media().get(item.id)!!.contentRevision,io.github.mesteriis.lik.privacy.SensitiveDecision.SAFE,1))
        try {
            val export = PhotoExport(context)
            val destination = export.destinationIntent(item.id)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, destination.action)
            assertTrue(destination.hasCategory(Intent.CATEGORY_OPENABLE))
            assertEquals("image/png", destination.type)
            val send = export.share(setOf(item.id))
            assertEquals("image/png", send.type)
            val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
            trash.trash(setOf(item.id))
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
            assertFalse(export.save(item.id, null))
            assertThrows(java.io.IOException::class.java) { export.share(setOf(item.id)) }
            assertArrayEquals(bytes, item.file.readBytes())
        } finally { trash.trash(setOf(item.id)); trash.purgeNow(setOf(item.id)) }
    }
    @Test fun preparedExportsHaveANonExportedGrantProvider() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.exports", 0)
        assertNotNull("Prepared copies need a narrow URI provider", provider)
        assertFalse(provider!!.exported)
        assertTrue(provider.grantUriPermissions)
    }
}
