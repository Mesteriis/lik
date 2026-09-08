package io.github.mesteriis.lik.catalog

import android.net.Uri
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import io.github.mesteriis.lik.gallery.GalleryPhoto
import io.github.mesteriis.lik.gallery.PhotoSource
import java.io.File

enum class MediaAvailability { AVAILABLE, INACCESSIBLE, MISSING, TRASHED, PURGING }
enum class MediaDateSource { UNKNOWN, MEDIASTORE_TAKEN, MEDIASTORE_ADDED, EXIF, FILE_MODIFIED }

/** Times are UTC epoch milliseconds; offsets are preserved evidence, never guessed from the current zone. */
@Entity(tableName = "media", indices = [
    Index(value = ["source", "sourceKey", "volumeName", "volumeVersion", "generationAdded"], unique = true),
    Index(value = ["source", "availability"]),
    Index(value = ["takenAt", "addedAt", "mediaId"]),
    Index(value = ["availability", "sortAt", "mediaId"]),
    Index(value = ["availability", "dayKey", "sortAt", "mediaId"]),
    Index(value = ["availability", "weekKey", "sortAt", "mediaId"]),
    Index(value = ["availability", "monthKey", "sortAt", "mediaId"]),
    Index(value = ["availability", "yearKey", "sortAt", "mediaId"]),
])
data class MediaRecord(
    @PrimaryKey val mediaId: String,
    val source: MediaSource,
    val sourceKey: String,
    val volumeName: String = "",
    val volumeVersion: String = "",
    val generationAdded: Long = 0,
    val contentUri: String? = null,
    val privateFileId: String? = null,
    val displayName: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val byteSize: Long? = null,
    val takenAt: Long? = null,
    val addedAt: Long? = null,
    val modifiedAt: Long? = null,
    val dateSource: MediaDateSource = MediaDateSource.UNKNOWN,
    val dateOffsetSeconds: Int? = null,
    val bucketId: String? = null,
    val bucketName: String? = null,
    val relativePath: String? = null,
    val contentRevision: Long = 0,
    val availability: MediaAvailability = MediaAvailability.AVAILABLE,
    val lastSeenAt: Long,
    @ColumnInfo(defaultValue = "-9223372036854775808") val sortAt: Long = takenAt ?: addedAt ?: Long.MIN_VALUE,
    @ColumnInfo(defaultValue = "'undated'") val dayKey: String = "undated",
    @ColumnInfo(defaultValue = "'undated'") val weekKey: String = "undated",
    @ColumnInfo(defaultValue = "'undated'") val monthKey: String = "undated",
    @ColumnInfo(defaultValue = "'undated'") val yearKey: String = "undated",
    val exifRevision: Long? = null,
    val exifOrientation: Int? = null,
    @ColumnInfo(defaultValue = "''") val scanMarker: String = "",
    val trashedAt: Long? = null,
) {
    // A body property deliberately recomputes on copy(displayName = ...), including scanner updates.
    @ColumnInfo(defaultValue = "''") var displayNameSearch: String = searchKey(displayName.orEmpty())
}

fun MediaRecord.toGalleryPhoto(privateFile: (String) -> File): GalleryPhoto = GalleryPhoto(
    id = mediaId,
    source = if (source == MediaSource.DEVICE) PhotoSource.DEVICE else PhotoSource.GOOGLE_IMPORT,
    uri = contentUri?.let(Uri::parse),
    file = privateFileId?.let(privateFile),
    mimeType = mimeType.orEmpty(), width = width ?: 0, height = height ?: 0, bytes = byteSize ?: 0,
    takenAt = takenAt, addedAt = addedAt ?: 0, sourceRevision = contentRevision,
    displayName = displayName, modifiedAt = modifiedAt, dateSource = dateSource,
    dateOffsetSeconds = dateOffsetSeconds, bucketId = bucketId, bucketName = bucketName,
    relativePath = relativePath,
    exifOrientation = exifOrientation,
)
