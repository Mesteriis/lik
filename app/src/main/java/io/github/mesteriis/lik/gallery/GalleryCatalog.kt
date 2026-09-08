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
import java.time.ZoneId
import io.github.mesteriis.lik.catalog.ExifCaptureDate
import android.os.CancellationSignal
import android.os.OperationCanceledException
import io.github.mesteriis.lik.catalog.MediaScanner
import io.github.mesteriis.lik.catalog.MediaAvailability
import io.github.mesteriis.lik.catalog.libraryZone
import io.github.mesteriis.lik.catalog.CatalogExif

object GalleryCatalog {
    fun load(context: Context, includeDevicePhotos: Boolean): List<GalleryPhoto> =
        loadResult(context, includeDevicePhotos).photos

    @Synchronized
    fun loadResult(context: Context, includeDevicePhotos: Boolean, signal: CancellationSignal = CancellationSignal()): GalleryCatalogLoad {
        val store = PhotoLibrary.store(context)
        val database = MediaDatabase.get(context)
        val repository = MediaRepository(database)
        val zone = libraryZone(context)
        signal.throwIfCanceled()
        val importedSourceError = try {
            io.github.mesteriis.lik.catalog.TrashRepository(database, store).purgeExpired()
            repository.reconcileImports(store, System.currentTimeMillis(), zone)
            false
        } catch (_: IOException) {
            true
        } catch (_: SecurityException) {
            true
        }
        // Enrichment is separate from the file inventory migration; a bad EXIF block is harmless.
        // A failed inventory has rolled back; keep the previous rows and avoid touching unavailable files.
        if (!importedSourceError) database.media().exifPending(60)
            .forEach { record ->
                signal.throwIfCanceled()
                CatalogExif.enrich(context, database, record, zone)
            }
        val queried = runCatching {
            if (includeDevicePhotos) {
                val full = context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
                MediaScanner(database, zone).scan(DeviceMediaQuery(context), full, signal)
            } else database.media().markSource(MediaSource.DEVICE, MediaAvailability.INACCESSIBLE)
        }
        queried.exceptionOrNull()?.let {
            if (it is OperationCanceledException) throw it
            android.util.Log.w("LikCatalog", "Device scan could not commit", it)
            database.media().markSource(MediaSource.DEVICE, MediaAvailability.INACCESSIBLE)
        }
        return GalleryCatalogLoad(
            photos = database.media().page(60, 0).map { it.toGalleryPhoto(store::fileFor) },
            deviceSourceError = includeDevicePhotos && queried.isFailure,
            importedSourceError = importedSourceError,
        )
    }

    @SuppressLint("ExifInterface")
    fun fromImported(photo: ImportedPhoto, zone: ZoneId = ZoneId.systemDefault()): GalleryPhoto {
        val exif = try { android.media.ExifInterface(photo.file.path) } catch (_: Exception) { null }
        val capture = ExifCaptureDate.parse(exif?.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL),
            exif?.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL), zone)
        val taken = capture?.instant
        val offset = capture?.offset
        return GalleryPhoto(
            id = photo.id, source = PhotoSource.GOOGLE_IMPORT, file = photo.file,
            bytes = photo.file.length(), takenAt = taken, addedAt = photo.file.lastModified(),
            modifiedAt = photo.file.lastModified(), sourceRevision = photo.file.lastModified(),
            dateSource = if (taken != null) MediaDateSource.EXIF else MediaDateSource.FILE_MODIFIED,
            dateOffsetSeconds = offset,
            exifOrientation = exif?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) ?: 1,
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
