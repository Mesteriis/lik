package io.github.mesteriis.lik.imports

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.mesteriis.lik.gallery.GalleryCatalog
import io.github.mesteriis.lik.gallery.GalleryPhoto
import java.io.IOException
import java.util.concurrent.Executors

enum class LibraryOperation { NONE, IMPORT, DELETE }
enum class ImportFailureKind { SOURCE_UNAVAILABLE, INVALID_IMAGE, TOO_LARGE, STORAGE }
private class ImportRejected(val kind: ImportFailureKind) : Exception()

data class ImportState(
    val photos: List<GalleryPhoto> = emptyList(), val busy: Boolean = false,
    val operation: LibraryOperation = LibraryOperation.NONE,
    val total: Int = 0, val processed: Int = 0,
    val added: Int = 0, val duplicates: Int = 0, val failed: Int = 0,
    val failureKinds: Set<ImportFailureKind> = emptySet(),
    val deleted: Int = 0, val deleteFailed: Int = 0,
    val deletedIds: Set<String> = emptySet(),
    val deleteFailedIds: Set<String> = emptySet(),
)

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val updates = MutableLiveData(ImportState())
    val state: LiveData<ImportState> = updates
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    fun refresh(includeDevicePhotos: Boolean = false) {
        if (updates.value?.busy == true) return
        worker.execute {
            val photos = GalleryCatalog.load(getApplication(), includeDevicePhotos)
            main.post { if (!closed) updates.value = requireNotNull(updates.value).copy(photos = photos) }
        }
    }

    fun importPhotos(uris: List<Uri>): Boolean {
        if (uris.isEmpty() || uris.size > ImportInput.MAX_PHOTOS || updates.value?.busy == true) return false
        val before = requireNotNull(updates.value)
        updates.value = ImportState(photos = before.photos, busy = true, operation = LibraryOperation.IMPORT, total = uris.size)
        worker.execute {
            var current = ImportState(photos = before.photos, busy = true, operation = LibraryOperation.IMPORT, total = uris.size)
            try {
                val store = PhotoLibrary.store(getApplication())
                val resolver = getApplication<Application>().contentResolver
                for (uri in uris) {
                    if (closed || Thread.currentThread().isInterrupted) break
                    current = try {
                        if (!isGooglePhotosUri(uri)) throw ImportRejected(ImportFailureKind.SOURCE_UNAVAILABLE)
                        val stream = try {
                            resolver.openInputStream(uri)
                        } catch (_: IOException) {
                            throw ImportRejected(ImportFailureKind.SOURCE_UNAVAILABLE)
                        } catch (_: SecurityException) {
                            throw ImportRejected(ImportFailureKind.SOURCE_UNAVAILABLE)
                        } ?: throw ImportRejected(ImportFailureKind.SOURCE_UNAVAILABLE)
                        val result = try {
                            store.importPhoto(stream)
                        } catch (error: PhotoStoreException) {
                            throw ImportRejected(error.reason.toImportFailure())
                        }
                        val photos = if (result.added) {
                            (listOf(GalleryCatalog.fromImported(result.photo)) + current.photos).distinctBy { it.id }
                        } else current.photos
                        current.copy(
                            photos = photos,
                            added = current.added + if (result.added) 1 else 0,
                            duplicates = current.duplicates + if (result.added) 0 else 1,
                        )
                    } catch (error: ImportRejected) {
                        current.copy(failed = current.failed + 1, failureKinds = current.failureKinds + error.kind)
                    } catch (_: IllegalArgumentException) {
                        current.copy(failed = current.failed + 1,
                            failureKinds = current.failureKinds + ImportFailureKind.INVALID_IMAGE)
                    }
                    current = current.copy(processed = current.processed + 1)
                    publish(current)
                }
                current = current.copy(photos = GalleryCatalog.load(getApplication(), hasPhotoPermission()))
            } catch (_: IOException) {
                current = current.copy(failed = current.failed + (current.total - current.processed), processed = current.total,
                    failureKinds = current.failureKinds + ImportFailureKind.STORAGE)
            } catch (_: SecurityException) {
                current = current.copy(failed = current.failed + (current.total - current.processed), processed = current.total,
                    failureKinds = current.failureKinds + ImportFailureKind.STORAGE)
            } catch (_: IllegalArgumentException) {
                current = current.copy(failed = current.failed + (current.total - current.processed), processed = current.total,
                    failureKinds = current.failureKinds + ImportFailureKind.STORAGE)
            }
            publish(current.copy(busy = false, operation = LibraryOperation.NONE))
        }
        return true
    }

    fun deletePhotos(ids: Set<String>): Boolean {
        if (ids.isEmpty() || updates.value?.busy == true) return false
        val before = requireNotNull(updates.value)
        updates.value = ImportState(
            photos = before.photos,
            busy = true,
            operation = LibraryOperation.DELETE,
            total = ids.size,
        )
        worker.execute {
            var deleted = 0
            val deletedIds = linkedSetOf<String>()
            val failed = linkedSetOf<String>()
            val store = PhotoLibrary.store(getApplication())
            ids.forEachIndexed { index, id ->
                try {
                    val importedId = GalleryCatalog.importedId(id)
                    if (importedId != null && store.deletePhoto(importedId)) {
                        deleted++
                        deletedIds += id
                    }
                } catch (_: IOException) {
                    failed += id
                } catch (_: IllegalArgumentException) {
                    failed += id
                }
                publish(ImportState(
                    photos = before.photos.filterNot { it.id in deletedIds },
                    busy = true,
                    operation = LibraryOperation.DELETE,
                    total = ids.size,
                    processed = index + 1,
                    deleted = deleted,
                    deletedIds = deletedIds.toSet(),
                    deleteFailed = failed.size,
                    deleteFailedIds = failed.toSet(),
                ))
            }
            publish(ImportState(
                photos = GalleryCatalog.load(getApplication(), hasPhotoPermission()),
                deleted = deleted,
                deletedIds = deletedIds,
                deleteFailed = failed.size,
                deleteFailedIds = failed,
            ))
        }
        return true
    }

    private fun publish(state: ImportState) { main.post { if (!closed) updates.value = state } }
    private fun isGooglePhotosUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val context = getApplication<Application>()
        val authority = uri.authority ?: return false
        val owner = context.packageManager.resolveContentProvider(
            authority,
            PackageManager.ComponentInfoFlags.of(0),
        )?.packageName
        if (owner == GOOGLE_PHOTOS_PACKAGE) return true
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return debuggable && authority == TEST_PROVIDER_AUTHORITY
    }
    private fun hasPhotoPermission(): Boolean {
        val context = getApplication<Application>()
        return context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    override fun onCleared() { closed = true; worker.shutdownNow() }

    private fun PhotoStoreError.toImportFailure(): ImportFailureKind = when (this) {
        PhotoStoreError.EMPTY, PhotoStoreError.INVALID_IMAGE -> ImportFailureKind.INVALID_IMAGE
        PhotoStoreError.TOO_LARGE -> ImportFailureKind.TOO_LARGE
        PhotoStoreError.STORAGE -> ImportFailureKind.STORAGE
        PhotoStoreError.INTERRUPTED, PhotoStoreError.READ_FAILED -> ImportFailureKind.SOURCE_UNAVAILABLE
    }

    companion object {
        private const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"
        private const val TEST_PROVIDER_AUTHORITY = "io.github.mesteriis.lik.test.photos"
    }
}
