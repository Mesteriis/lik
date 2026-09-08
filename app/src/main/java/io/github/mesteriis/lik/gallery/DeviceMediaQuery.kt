package io.github.mesteriis.lik.gallery

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import io.github.mesteriis.lik.catalog.MediaDateSource
import io.github.mesteriis.lik.catalog.MediaIdentity
import io.github.mesteriis.lik.catalog.MediaRecord

/** Full foreground inventory for now; incremental scanning and paging belong to Task 6. */
internal object DeviceMediaQuery {
    fun read(context: Context): List<MediaRecord>? {
        val result = mutableListOf<MediaRecord>()
        val now = System.currentTimeMillis()
        for (volume in MediaStore.getExternalVolumeNames(context).sorted()) {
            val version = MediaStore.getVersion(context, volume)
            val collection = MediaStore.Images.Media.getContentUri(volume)
            val projection = arrayOf(
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.GENERATION_ADDED,
                MediaStore.Images.Media.GENERATION_MODIFIED, MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH,
            )
            val cursor = context.contentResolver.query(collection, projection, null, null, null) ?: return null
            cursor.use {
                while (it.moveToNext()) {
                    val row = requireNotNull(it.number(MediaStore.Images.Media._ID))
                    val identity = MediaIdentity.device(volume, version, row,
                        requireNotNull(it.number(MediaStore.Images.Media.GENERATION_ADDED)))
                    val taken = it.number(MediaStore.Images.Media.DATE_TAKEN)?.takeIf { value -> value > 0 }
                    val added = it.number(MediaStore.Images.Media.DATE_ADDED)?.takeIf { value -> value > 0 }?.times(1000)
                    result += MediaRecord(
                        mediaId = identity.mediaId, source = identity.source, sourceKey = identity.sourceKey,
                        volumeName = identity.volumeName, volumeVersion = identity.volumeVersion,
                        generationAdded = identity.generationAdded,
                        contentUri = ContentUris.withAppendedId(collection, row).toString(),
                        displayName = it.string(MediaStore.Images.Media.DISPLAY_NAME),
                        mimeType = it.string(MediaStore.Images.Media.MIME_TYPE),
                        width = it.number(MediaStore.Images.Media.WIDTH)?.toInt()?.takeIf { value -> value > 0 },
                        height = it.number(MediaStore.Images.Media.HEIGHT)?.toInt()?.takeIf { value -> value > 0 },
                        byteSize = it.number(MediaStore.Images.Media.SIZE)?.takeIf { value -> value >= 0 },
                        takenAt = taken, addedAt = added,
                        modifiedAt = it.number(MediaStore.Images.Media.DATE_MODIFIED)?.takeIf { value -> value > 0 }?.times(1000),
                        dateSource = when {
                            taken != null -> MediaDateSource.MEDIASTORE_TAKEN
                            added != null -> MediaDateSource.MEDIASTORE_ADDED
                            else -> MediaDateSource.UNKNOWN
                        },
                        bucketId = it.string(MediaStore.Images.Media.BUCKET_ID),
                        bucketName = it.string(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
                        relativePath = it.string(MediaStore.Images.Media.RELATIVE_PATH),
                        contentRevision = requireNotNull(it.number(MediaStore.Images.Media.GENERATION_MODIFIED)),
                        lastSeenAt = now,
                    )
                }
            }
            check(MediaStore.getVersion(context, volume) == version) { "MediaStore version changed during inventory" }
        }
        return result
    }

    private fun Cursor.number(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.string(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
}
