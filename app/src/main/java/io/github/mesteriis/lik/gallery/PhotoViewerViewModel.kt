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
    @Volatile private var closed = false

    fun start(photoId: String?) {
        if (updates.value?.cursor != null || updates.value?.loading == true) return
        worker.execute {
            val context = getApplication<Application>()
            val hasAccess = context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            val cursor = PhotoCursor(GalleryCatalog.load(context, hasAccess), photoId)
            load(cursor)
        }
    }

    fun move(delta: Int) {
        val cursor = updates.value?.cursor?.move(delta) ?: return
        worker.execute { load(cursor) }
    }

    fun deleteCurrent() {
        val state = updates.value ?: return
        val current = state.cursor?.current ?: return
        if (state.deleting) return
        publish(state.copy(deleting = true))
        worker.execute {
            try {
                val importedId = GalleryCatalog.importedId(current.id) ?: return@execute publish(state.copy(deleting = false))
                PhotoLibrary.store(getApplication()).deletePhoto(importedId)
                load(state.cursor.without(current.id), deleting = false)
            } catch (_: IOException) {
                publish(state.copy(deleting = false, error = true))
            }
        }
    }

    private fun load(cursor: PhotoCursor, deleting: Boolean = false) {
        val request = generation.incrementAndGet()
        val photo = cursor.current
        if (photo == null) {
            publish(ViewerState(cursor = cursor, deleting = deleting, error = true))
            return
        }
        publish(ViewerState(cursor = cursor, loading = true, deleting = deleting))
        val bitmap = try { GalleryCatalog.decode(getApplication(), photo, MAX_BITMAP_EDGE) } catch (_: Exception) { null }
        if (closed || generation.get() != request) {
            bitmap?.recycle()
            return
        }
        if (bitmap == null) {
            publish(ViewerState(cursor = cursor, error = true, deleting = deleting))
            return
        }
        val file = photo.file
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (file != null) BitmapFactory.decodeFile(file.path, options)
        val orientation = if (file != null) try {
            android.media.ExifInterface(file.path).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: IOException) {
            android.media.ExifInterface.ORIENTATION_NORMAL
        } else android.media.ExifInterface.ORIENTATION_NORMAL
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
        publish(ViewerState(cursor = cursor, bitmap = bitmap, details = details, deleting = deleting))
    }

    private fun publish(state: ViewerState) {
        main.post { if (!closed) updates.value = state }
    }

    override fun onCleared() {
        closed = true
        generation.incrementAndGet()
        worker.shutdownNow()
    }

    companion object { private const val MAX_BITMAP_EDGE = 4096 }
}
