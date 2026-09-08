package io.github.mesteriis.lik

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mesteriis.lik.catalog.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class CatalogExifTest {
    @Test fun deviceUriCachesAbsentExifButMissingUriDoesNotCacheAccessFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "lik-exif-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LikTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        var removed = false
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val record = MediaRecord("device:fixture", MediaSource.DEVICE, "fixture", contentUri = uri.toString(),
                contentRevision = 1, lastSeenAt = 1)
            db.media().upsert(record)
            assertTrue(CatalogExif.enrich(context, db, record, ZoneId.of("UTC")))
            assertEquals(1L, db.media().get(record.mediaId)!!.exifRevision)
            assertNull(db.media().get(record.mediaId)!!.takenAt)
            context.contentResolver.delete(uri, null, null)
            removed = true
            val changed = db.media().get(record.mediaId)!!.copy(contentRevision = 2)
            db.media().upsert(changed)
            assertFalse(CatalogExif.enrich(context, db, changed, ZoneId.of("UTC")))
            assertEquals(1L, db.media().get(record.mediaId)!!.exifRevision)
            assertEquals(MediaAvailability.AVAILABLE, db.media().get(record.mediaId)!!.availability)
        } finally { if (!removed) context.contentResolver.delete(uri, null, null); db.close() }
    }
}
