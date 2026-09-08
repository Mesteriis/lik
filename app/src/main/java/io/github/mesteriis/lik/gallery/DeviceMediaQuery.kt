package io.github.mesteriis.lik.gallery

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import android.os.Bundle
import android.os.CancellationSignal
import android.content.ContentResolver
import io.github.mesteriis.lik.catalog.MediaInventory
import io.github.mesteriis.lik.catalog.VolumeState
import io.github.mesteriis.lik.catalog.MediaDateSource
import io.github.mesteriis.lik.catalog.MediaIdentity
import io.github.mesteriis.lik.catalog.MediaRecord

/** Cursor windows and application batches remain bounded; no catalog-sized Kotlin inventory. */
internal class DeviceMediaQuery(private val context: Context) : MediaInventory {
    override fun volumes() = MediaStore.getExternalVolumeNames(context)
    override fun state(volume: String) = VolumeState(MediaStore.getVersion(context, volume), MediaStore.getGeneration(context, volume),
        context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED)

    override fun changed(volume: String, state: VolumeState, after: Long, signal: CancellationSignal, emit: (List<MediaRecord>) -> Unit) {
        val result = ArrayList<MediaRecord>(128)
        val now = System.currentTimeMillis()
            val version = state.version
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
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "generation_modified > ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(after.toString()))
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "_id ASC")
            }
            val cursor = requireNotNull(context.contentResolver.query(collection, projection, args, signal)) { "MediaStore returned null cursor" }
            cursor.use {
                while (it.moveToNext()) {
                    signal.throwIfCanceled()
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
                    if (result.size == 128) { emit(result.toList()); result.clear() }
                }
            }
        if (result.isNotEmpty()) emit(result.toList())
    }

    override fun visibleIds(volume: String, state: VolumeState, signal: CancellationSignal, emit: (List<String>) -> Unit) {
        val ids = ArrayList<String>(128)
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val cursor = requireNotNull(context.contentResolver.query(collection, arrayOf("_id", "generation_added"), Bundle(), signal))
        cursor.use {
            while (it.moveToNext()) {
                signal.throwIfCanceled()
                ids += MediaIdentity.device(volume, state.version, it.getLong(0), it.getLong(1)).mediaId
                if (ids.size == 128) { emit(ids.toList()); ids.clear() }
            }
        }
        if (ids.isNotEmpty()) emit(ids.toList())
    }

    private fun Cursor.number(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.string(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
}
