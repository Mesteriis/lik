package io.github.mesteriis.lik.gallery

import android.content.ContentUris
import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import io.github.mesteriis.lik.imports.ImportedPhoto
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.io.File

enum class PhotoSource { DEVICE, GOOGLE_IMPORT }

data class GalleryPhoto(
    val id: String,
    val source: PhotoSource,
    val uri: Uri? = null,
    val file: File? = null,
    val mimeType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0,
    val takenAt: Long? = null,
    val addedAt: Long = 0,
) {
    val canDeleteCopy: Boolean get() = source == PhotoSource.GOOGLE_IMPORT && file != null
    val timelineAt: Long? get() = takenAt?.takeIf { it > 0 } ?: addedAt.takeIf { it > 0 }
}

object GalleryCatalog {
    private const val DEVICE_PREFIX = "device:"

    fun load(context: Context, includeDevicePhotos: Boolean): List<GalleryPhoto> {
        val imported = PhotoLibrary.store(context).photos().map(::fromImported)
        val device = if (includeDevicePhotos) try {
            queryDevice(context)
        } catch (_: SecurityException) {
            emptyList()
        } else emptyList()
        return (device + imported).sortedWith(
            compareByDescending<GalleryPhoto> { it.timelineAt ?: Long.MIN_VALUE }.thenByDescending { it.id },
        )
    }

    @SuppressLint("ExifInterface")
    fun fromImported(photo: ImportedPhoto) = GalleryPhoto(
        id = photo.id,
        source = PhotoSource.GOOGLE_IMPORT,
        file = photo.file,
        bytes = photo.file.length(),
        takenAt = try {
            android.media.ExifInterface(photo.file.path).dateTimeOriginal.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        },
        addedAt = photo.file.lastModified(),
    )

    fun importedId(id: String): String? = id.takeUnless { it.startsWith(DEVICE_PREFIX) }

    fun decode(context: Context, photo: GalleryPhoto, edge: Int): Bitmap {
        photo.file?.let { return PhotoLibrary.decode(it, edge) }
        val source = ImageDecoder.createSource(context.contentResolver, requireNotNull(photo.uri))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(1.0, edge.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
        }
    }

    private fun queryDevice(context: Context): List<GalleryPhoto> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        return context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            buildList {
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idColumn)
                    add(GalleryPhoto(
                        id = DEVICE_PREFIX + mediaId,
                        source = PhotoSource.DEVICE,
                        uri = ContentUris.withAppendedId(collection, mediaId),
                        mimeType = cursor.getString(mimeColumn).orEmpty(),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        bytes = cursor.getLong(sizeColumn),
                        takenAt = cursor.getLong(takenColumn).takeIf { !cursor.isNull(takenColumn) && it > 0 },
                        addedAt = cursor.getLong(dateColumn) * 1000,
                    ))
                }
            }
        }.orEmpty()
    }
}
