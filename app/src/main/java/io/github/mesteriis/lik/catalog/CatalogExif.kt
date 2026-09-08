package io.github.mesteriis.lik.catalog

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.net.toUri
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.io.IOException
import java.time.ZoneId

/** Lazy metadata for either source. I/O/permission failures are not negative EXIF evidence. */
object CatalogExif {
    @SuppressLint("ExifInterface")
    fun enrich(context: Context, database: MediaDatabase, record: MediaRecord, zone: ZoneId): Boolean {
        if (record.exifRevision == record.contentRevision) return true
        val exif = try {
            if (record.privateFileId != null) android.media.ExifInterface(PhotoLibrary.store(context).fileFor(record.privateFileId).path)
            else record.contentUri?.let { uri ->
                context.contentResolver.openInputStream(uri.toUri())?.use { android.media.ExifInterface(it) }
            }
        } catch (_: IOException) { null } catch (_: SecurityException) { null }
            ?: return false
        val capture = ExifCaptureDate.parse(exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL), zone)
        MediaRepository(database).cacheExif(record.mediaId, record.contentRevision, capture?.instant, capture?.offset,
            exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL), zone)
        return true
    }
}
