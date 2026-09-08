package io.github.mesteriis.lik.gallery

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaRepository
import io.github.mesteriis.lik.catalog.toGalleryPhoto
import io.github.mesteriis.lik.catalog.libraryZone
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class PhotoDetails(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val addedAt: Long,
)

data class ViewerState(
    val cursor: PhotoCursor? = null,
    val bitmap: Bitmap? = null,
    val details: PhotoDetails? = null,
    val loading: Boolean = false,
    val deleting: Boolean = false,
    val error: Boolean = false,
)

// Lik only runs on API 37, where the platform android.media.ExifInterface fixes the old-version issues
// behind Android Lint's compatibility warning.
@SuppressLint("ExifInterface")
class PhotoViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val updates = MutableLiveData(ViewerState())
    val state: LiveData<ViewerState> = updates
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val contentRevision = AtomicInteger()
    @Volatile private var requestedCursor: PhotoCursor? = null
    @Volatile private var closed = false
    private val dao get() = MediaDatabase.get(getApplication()).media()
    private val repository get() = MediaRepository(MediaDatabase.get(getApplication()))
    private val navigationPending = AtomicInteger()
    private val scanSignal = android.os.CancellationSignal()

    private fun window(id: String?): PhotoCursor {
        id?.let(dao::get)?.takeIf { it.availability == io.github.mesteriis.lik.catalog.MediaAvailability.AVAILABLE && it.exifRevision != it.contentRevision }?.let { record ->
            io.github.mesteriis.lik.catalog.CatalogExif.enrich(getApplication(), MediaDatabase.get(getApplication()), record, libraryZone(getApplication()))
        }
        return PhotoCursor(id?.let { repository.viewerWindow(it).map { record ->
            record.toGalleryPhoto(PhotoLibrary.store(getApplication())::fileFor)
        } }.orEmpty(), id)
    }

    fun start(photoId: String?) {
        if (requestedCursor != null || updates.value?.loading == true) return
        val request = generation.incrementAndGet()
        val revision = contentRevision.get()
        publish(request, revision, ViewerState(loading = true))
        worker.execute {
            val context = getApplication<Application>()
            val hasAccess = context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            try { GalleryCatalog.loadResult(context, hasAccess, scanSignal) }
            catch (_: android.os.OperationCanceledException) { return@execute }
            val cursor = window(photoId)
            requestedCursor = cursor
            load(cursor, request = request, revision = revision)
        }
    }

    fun move(delta: Int) {
        if (updates.value?.deleting == true || delta == 0) return
        val current = requestedCursor ?: updates.value?.cursor ?: return
        if (navigationPending.get() == 0 && ((delta < 0 && !current.hasPrevious) || (delta > 0 && !current.hasNext))) return
        navigationPending.incrementAndGet()
        val request = generation.incrementAndGet()
        val revision = contentRevision.get()
        publish(request, revision, ViewerState(cursor = current, loading = true))
        worker.execute {
            try {
                val id = requestedCursor?.current?.id ?: return@execute
                val record = dao.get(id) ?: return@execute
                val target = if (delta == 1) dao.next(id, record.sortAt) else if (delta == -1) dao.previous(id, record.sortAt)
                    else dao.page(1, (dao.rank(id, record.sortAt).toLong() + delta).coerceIn(0, (dao.availableCount() - 1).coerceAtLeast(0).toLong()).toInt()).firstOrNull()
                val cursor = window(target?.mediaId ?: id)
                requestedCursor = cursor
                if (isCurrent(request, revision)) load(cursor, request = request, revision = revision)
            } finally { navigationPending.decrementAndGet() }
        }
    }

    fun deleteCurrent() {
        val state = updates.value ?: return
        val current = state.cursor?.current ?: return
        if (state.loading || state.deleting || state.error) return
        val request = generation.incrementAndGet()
        val revision = contentRevision.incrementAndGet()
        publish(request, revision, state.copy(deleting = true))
        worker.execute {
            try {
                val importedId = GalleryCatalog.importedId(current.id)
                    ?: return@execute publish(request, revision, state.copy(deleting = false))
                io.github.mesteriis.lik.catalog.TrashRepository(MediaDatabase.get(getApplication()), PhotoLibrary.store(getApplication()))
                    .trash(setOf(importedId))
                val old = dao.get(current.id)
                repository.reconcileImports(PhotoLibrary.store(getApplication()), System.currentTimeMillis(), libraryZone(getApplication()))
                val adjacent = old?.let { dao.next(it.mediaId, it.sortAt) ?: dao.previous(it.mediaId, it.sortAt) }
                val cursor = window(adjacent?.mediaId)
                requestedCursor = cursor
                load(cursor, request = request, revision = revision)
            } catch (_: IOException) {
                publish(request, revision, state.copy(deleting = false, error = true))
            }
        }
    }

    private fun load(
        cursor: PhotoCursor,
        deleting: Boolean = false,
        request: Int,
        revision: Int,
    ) {
        val photo = cursor.current
        if (photo == null) {
            publish(request, revision, ViewerState(cursor = cursor, deleting = deleting, error = true))
            return
        }
        val bitmap = try { GalleryCatalog.decode(getApplication(), photo, MAX_BITMAP_EDGE) } catch (_: Exception) { null }
        if (!isCurrent(request, revision)) {
            bitmap?.recycle()
            return
        }
        if (bitmap == null) {
            publish(request, revision, ViewerState(cursor = cursor, error = true, deleting = deleting))
            return
        }
        val file = photo.file
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (file != null) BitmapFactory.decodeFile(file.path, options)
        val orientation = photo.exifOrientation ?: android.media.ExifInterface.ORIENTATION_NORMAL
        val rawWidth = photo.width.takeIf { it > 0 } ?: options.outWidth.takeIf { it > 0 } ?: bitmap.width
        val rawHeight = photo.height.takeIf { it > 0 } ?: options.outHeight.takeIf { it > 0 } ?: bitmap.height
        val swapsAxes = orientation in setOf(
            android.media.ExifInterface.ORIENTATION_TRANSPOSE,
            android.media.ExifInterface.ORIENTATION_ROTATE_90,
            android.media.ExifInterface.ORIENTATION_TRANSVERSE,
            android.media.ExifInterface.ORIENTATION_ROTATE_270,
        )
        val (width, height) = if (swapsAxes) rawHeight to rawWidth else rawWidth to rawHeight
        val details = PhotoDetails(
            mimeType = photo.mimeType.ifEmpty { options.outMimeType.orEmpty() },
            width = width,
            height = height,
            bytes = photo.bytes.takeIf { it > 0 } ?: file?.length() ?: 0,
            addedAt = photo.addedAt.takeIf { it > 0 } ?: file?.lastModified() ?: 0,
        )
        if (!isCurrent(request, revision)) {
            bitmap.recycle()
            return
        }
        publish(request, revision, ViewerState(cursor = cursor, bitmap = bitmap, details = details, deleting = deleting))
    }

    private fun isCurrent(request: Int, revision: Int) =
        !closed && generation.get() == request && contentRevision.get() == revision

    private fun publish(request: Int, revision: Int, state: ViewerState) {
        main.post { if (isCurrent(request, revision)) updates.value = state }
    }

    override fun onCleared() {
        closed = true
        generation.incrementAndGet()
        contentRevision.incrementAndGet()
        scanSignal.cancel()
        worker.shutdownNow()
    }

    companion object { private const val MAX_BITMAP_EDGE = 4096 }
}
