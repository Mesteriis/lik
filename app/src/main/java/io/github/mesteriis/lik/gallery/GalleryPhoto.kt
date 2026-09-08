package io.github.mesteriis.lik.gallery

import android.net.Uri
import io.github.mesteriis.lik.catalog.MediaDateSource
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
    val sourceRevision: Long = 0,
    val displayName: String? = null,
    val modifiedAt: Long? = null,
    val dateSource: MediaDateSource = MediaDateSource.UNKNOWN,
    val dateOffsetSeconds: Int? = null,
    val bucketId: String? = null,
    val bucketName: String? = null,
    val relativePath: String? = null,
    val exifOrientation: Int? = null,
) {
    val canDeleteCopy: Boolean get() = source == PhotoSource.GOOGLE_IMPORT && file != null
    val timelineAt: Long? get() = takenAt?.takeIf { it > 0 } ?: addedAt.takeIf { it > 0 }
}

data class GalleryCatalogLoad(
    val photos: List<GalleryPhoto>,
    val deviceSourceError: Boolean = false,
    val importedSourceError: Boolean = false,
)

internal fun deviceQueryResult(
    includeDevicePhotos: Boolean,
    query: () -> List<GalleryPhoto>?,
): Result<List<GalleryPhoto>> = when {
    !includeDevicePhotos -> Result.success(emptyList())
    else -> runCatching { requireNotNull(query()) { "MediaStore image query returned null" } }
}
