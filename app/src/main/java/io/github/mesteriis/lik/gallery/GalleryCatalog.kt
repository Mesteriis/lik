package io.github.mesteriis.lik.gallery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaDateSource
import io.github.mesteriis.lik.catalog.MediaRepository
import io.github.mesteriis.lik.catalog.MediaSource
import io.github.mesteriis.lik.catalog.toGalleryPhoto
import io.github.mesteriis.lik.imports.ImportedPhoto
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.io.IOException
import java.time.ZoneOffset

object GalleryCatalog {
    fun load(context: Context, includeDevicePhotos: Boolean): List<GalleryPhoto> =
        loadResult(context, includeDevicePhotos).photos

    @Synchronized
    fun loadResult(context: Context, includeDevicePhotos: Boolean): GalleryCatalogLoad {
        val store = PhotoLibrary.store(context)
        val repository = MediaRepository(MediaDatabase.get(context))
        val importedSourceError = try {
            repository.reconcileImports(store, System.currentTimeMillis())
            false
        } catch (_: IOException) {
            true
        } catch (_: SecurityException) {
            true
        }
        // Enrichment is separate from the file inventory migration; a bad EXIF block is harmless.
        // A failed inventory has rolled back; keep the previous rows and avoid touching unavailable files.
        if (!importedSourceError) repository.available().filter { it.source == MediaSource.GOOGLE_IMPORT && it.takenAt == null }
            .forEach { record ->
                val photo = fromImported(ImportedPhoto(record.mediaId, store.fileFor(record.mediaId)))
                photo.takenAt?.let { repository.enrichImportedCaptureDate(record.mediaId, it, photo.dateOffsetSeconds) }
            }
        val queried = if (includeDevicePhotos) runCatching {
            requireNotNull(DeviceMediaQuery.read(context)) { "MediaStore image query returned null" }
        } else Result.success(emptyList())
        repository.reconcileDevice(queried.getOrDefault(emptyList()))
        return GalleryCatalogLoad(
            photos = repository.available().map { it.toGalleryPhoto(store::fileFor) },
            deviceSourceError = includeDevicePhotos && queried.isFailure,
            importedSourceError = importedSourceError,
        )
    }

    @SuppressLint("ExifInterface")
    fun fromImported(photo: ImportedPhoto): GalleryPhoto {
        val exif = try { android.media.ExifInterface(photo.file.path) } catch (_: Exception) { null }
        val taken = try { exif?.dateTimeOriginal?.takeIf { it > 0 } } catch (_: Exception) { null }
        val offset = try {
            exif?.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.let { ZoneOffset.of(it).totalSeconds }
        } catch (_: Exception) { null }
        return GalleryPhoto(
            id = photo.id, source = PhotoSource.GOOGLE_IMPORT, file = photo.file,
            bytes = photo.file.length(), takenAt = taken, addedAt = photo.file.lastModified(),
            modifiedAt = photo.file.lastModified(), sourceRevision = photo.file.lastModified(),
            dateSource = if (taken != null) MediaDateSource.EXIF else MediaDateSource.FILE_MODIFIED,
            dateOffsetSeconds = offset,
        )
    }

    fun importedId(id: String): String? = id.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

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
}
