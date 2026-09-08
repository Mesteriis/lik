package io.github.mesteriis.lik.gallery

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.isVisible
import io.github.mesteriis.lik.R
import io.github.mesteriis.lik.ui.applySystemBarInsets
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class PhotoViewerActivity : ComponentActivity() {
    private lateinit var model: PhotoViewerViewModel
    private lateinit var image: ZoomImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)
        findViewById<View>(R.id.viewer_root).applySystemBarInsets()
        model = ViewModelProvider(this)[PhotoViewerViewModel::class.java]
        image = findViewById(R.id.viewer_image)
        image.onNavigate = model::move
        findViewById<View>(R.id.viewer_back).setOnClickListener { finish() }
        findViewById<View>(R.id.viewer_previous).setOnClickListener { model.move(-1) }
        findViewById<View>(R.id.viewer_next).setOnClickListener { model.move(1) }
        findViewById<View>(R.id.viewer_info).setOnClickListener {
            findViewById<View>(R.id.photo_details).apply {
                isVisible = !isVisible
            }
        }
        findViewById<View>(R.id.viewer_delete).setOnClickListener { confirmDelete() }
        model.state.observe(this, ::render)
        model.start(intent.getStringExtra(EXTRA_PHOTO_ID))
    }

    private fun render(state: ViewerState) {
        state.cursor?.current?.id?.let { id ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PHOTO_ID, id))
        }
        findViewById<ProgressBar>(R.id.viewer_progress).visibility = if (state.loading || state.deleting) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.viewer_error).visibility = if (state.error) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.viewer_previous).isEnabled = state.cursor?.hasPrevious == true && !state.loading
        findViewById<Button>(R.id.viewer_next).isEnabled = state.cursor?.hasNext == true && !state.loading
        findViewById<Button>(R.id.viewer_delete).apply {
            visibility = if (state.cursor?.current?.canDeleteCopy == true) View.VISIBLE else View.GONE
            isEnabled = state.cursor?.current?.canDeleteCopy == true && !state.deleting
        }
        state.bitmap?.let {
            if (image.tag != state.cursor?.current?.id) {
                image.tag = state.cursor?.current?.id
                image.setImageBitmap(it)
            }
        }
        findViewById<TextView>(R.id.photo_details).text = state.details?.let(::formatDetails).orEmpty()
        if (!state.loading && !state.deleting && state.cursor?.photos?.isEmpty() == true) {
            setResult(RESULT_PHOTO_DELETED)
            finish()
        }
    }

    private fun formatDetails(details: PhotoDetails): String {
        val format = details.mimeType.substringAfter('/').uppercase(Locale.getDefault()).ifEmpty {
            getString(R.string.viewer_unknown)
        }
        val size = getString(R.string.photo_size_bytes, details.bytes / 1_048_576.0)
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(details.addedAt))
        return getString(R.string.photo_details_format, format, details.width, details.height, size, date)
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.delete_photos_title, 1, 1))
            .setMessage(R.string.delete_photos_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> model.deleteCurrent() }
            .show()
    }

    companion object {
        const val EXTRA_PHOTO_ID = "io.github.mesteriis.lik.extra.PHOTO_ID"
        const val EXTRA_RESULT_PHOTO_ID = "io.github.mesteriis.lik.extra.RESULT_PHOTO_ID"
        const val RESULT_PHOTO_DELETED = 2
    }
}
