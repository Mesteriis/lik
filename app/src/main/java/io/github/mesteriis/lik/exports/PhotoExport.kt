package io.github.mesteriis.lik.exports

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.io.File
import java.io.IOException
import java.io.InputStream

class ExportProvider : FileProvider()

data class SaveCopyRequest(val id: String, val revision: Long, val accessEpoch: Long, val protected: Boolean)

class PhotoExport(private val context: Context) {
    private val directory get() = File(context.cacheDir, "prepared_exports")
    private val files get() = ExportFiles(directory)
    private val store get() = PhotoLibrary.store(context)

    private fun record(id: String, operation: MediaOperation,snapshot:io.github.mesteriis.lik.privacy.RevealSnapshot): MediaRecord =
        MediaDatabase.get(context).media().get(id)?.takeIf { it.allows(operation)&&io.github.mesteriis.lik.privacy.SensitiveMediaRepository(context).mayAccess(it.mediaId,it.contentRevision,snapshot) }
            ?: throw IOException("Photo unavailable")

    private fun open(row: MediaRecord): InputStream = when (row.source) {
        MediaSource.GOOGLE_IMPORT -> store.fileFor(requireNotNull(row.privateFileId)).inputStream()
        MediaSource.DEVICE -> context.contentResolver.openInputStream(requireNotNull(row.contentUri).toUri())
            ?: throw IOException("Source unavailable")
    }

    private fun mime(row: MediaRecord): String = row.mimeType ?: when (row.source) {
        MediaSource.DEVICE -> context.contentResolver.getType(requireNotNull(row.contentUri).toUri())
        MediaSource.GOOGLE_IMPORT -> android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(store.fileFor(requireNotNull(row.privateFileId)).path, this)
        }.outMimeType
    } ?: "image/*"

    fun share(ids: Set<String>): Intent = synchronized(store) {
        require(ids.isNotEmpty())
        val privacy=io.github.mesteriis.lik.privacy.SensitiveMediaSession.current.snapshot()
        val prepared = mutableListOf<File>()
        try {
            val rows = ids.map { record(it, MediaOperation.SHARE,privacy) }
            val types = rows.map(::mime)
            val uris = rows.mapIndexed { index, row ->
                val file = files.prepare(types[index]) { open(row) }.also(prepared::add)
                FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
            }
            rows.forEach{if(!io.github.mesteriis.lik.privacy.SensitiveMediaRepository(context).mayAccess(it.mediaId,it.contentRevision,privacy))throw IOException("Photo relocked")}
            shareIntent(uris, types.distinct().singleOrNull() ?: "image/*")
        } catch (error: Exception) { prepared.forEach(File::delete); throw error }
    }

    fun save(id: String, destination: Uri?, request: SaveCopyRequest? = null,
             privacy: io.github.mesteriis.lik.privacy.RevealSnapshot = io.github.mesteriis.lik.privacy.SensitiveMediaSession.current.snapshot()): Boolean {
        if (destination == null) return false
        // A malicious/replaced result must not redirect a write into Lik's own shared cache.
        require(destination.scheme == "content" && destination.authority != "${context.packageName}.exports")
        return synchronized(store) {
            val row = record(id, MediaOperation.EXPORT,privacy)
            if(request!=null && (request.id!=id || request.revision!=row.contentRevision || request.accessEpoch!=row.accessGrantEpoch ||
                (request.protected && (!privacy.revealed || !io.github.mesteriis.lik.privacy.SensitiveMediaSession.current.accepts(privacy.epoch)))))
                throw IOException("Save request changed or relocked")
            // CREATE_DOCUMENT does not prove this URI is new or owned by Lik. Never delete or
            // truncate it as failure cleanup. ExportFiles removes only our prepared snapshot.
            files.save({ context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("Destination unavailable") }, validate = {
                if(!io.github.mesteriis.lik.privacy.SensitiveMediaRepository(context).mayAccess(row.mediaId,row.contentRevision,privacy))throw IOException("Photo relocked")
            }) {
                open(row)
            }
        }
    }

    fun destinationIntent(id: String): Intent = prepareSave(id).second

    fun prepareSave(id: String): Pair<SaveCopyRequest, Intent> {
        val row = record(id, MediaOperation.EXPORT,io.github.mesteriis.lik.privacy.SensitiveMediaSession.current.snapshot())
        val request = SaveCopyRequest(id,row.contentRevision,row.accessGrantEpoch,
            io.github.mesteriis.lik.privacy.SensitiveMediaRepository(context).decision(id)!=io.github.mesteriis.lik.privacy.SensitiveDecision.SAFE)
        return request to Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime(row)
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "image"
            putExtra(Intent.EXTRA_TITLE, row.displayName ?: "Lik-${id.takeLast(8)}.$extension")
        }
    }

    fun cleanup(now: Long = System.currentTimeMillis()) = synchronized(ExportFiles.monitor) {
        directory.listFiles()?.filter { now - it.lastModified() >= 24L * 60 * 60 * 1000 }?.forEach { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!file.delete() && file.exists()) throw IOException("Cannot remove expired export")
        }
    }

    companion object {
        fun shareIntent(uris: List<Uri>, mime: String): Intent {
            require(uris.isNotEmpty())
            return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData = ClipData.newRawUri("", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        }
    }
}
