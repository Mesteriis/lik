package io.github.mesteriis.lik.catalog

import io.github.mesteriis.lik.imports.PhotoStore
import io.github.mesteriis.lik.imports.StoredPhoto
import java.io.IOException
import java.io.InputStream

/** The shared PhotoStore monitor serializes import, inventory, restore, claims and file unlink.
 * PURGING is a durable, irreversible claim. Commit it before unlink, then remove the row; retry
 * tolerates an already absent file. No filesystem action ever targets a device/content URI. */
class TrashRepository(private val db: MediaDatabase, private val store: PhotoStore) {
    fun trash(ids: Set<String>, now: Long = System.currentTimeMillis()): Int = synchronized(store) {
        db.runInTransaction<Int> { db.media().trash(ids, now) }
    }

    fun restore(id: String): Boolean = synchronized(store) {
        db.runInTransaction<Boolean> {
            val row = db.media().get(id) ?: return@runInTransaction false
            if (row.source != MediaSource.GOOGLE_IMPORT || row.availability != MediaAvailability.TRASHED) return@runInTransaction false
            if (!store.fileFor(requireNotNull(row.privateFileId)).isFile) throw IOException("Private copy unavailable")
            db.media().restoreTrash(id) == 1
        }
    }

    fun importPhoto(input: InputStream): StoredPhoto = synchronized(store) {
        input.use {
            finishPending()
            val result = store.importPhoto(it)
            val restored = restore(result.photo.id)
            MediaRepository(db).reconcileImports(store, System.currentTimeMillis())
            result.copy(restored = restored)
        }
    }

    fun purgeExpired(now: Long = System.currentTimeMillis()) = synchronized(store) {
        db.runInTransaction { db.media().claimExpired(now - RETENTION_MILLIS) }
        finishPending()
    }

    fun purgeNow(ids: Set<String>) = synchronized(store) {
        db.runInTransaction { db.media().claimPurge(ids) }
        finishPending()
    }

    private fun finishPending() {
        while (true) {
            val rows = db.media().pendingPurge()
            if (rows.isEmpty()) return
            for (row in rows) {
                store.deletePhoto(requireNotNull(row.privateFileId))
                db.runInTransaction { db.media().finishPurge(row.mediaId) }
            }
        }
    }

    companion object { const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000 }
}
