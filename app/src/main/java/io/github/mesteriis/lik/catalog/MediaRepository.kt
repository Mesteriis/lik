package io.github.mesteriis.lik.catalog

import io.github.mesteriis.lik.imports.PhotoStore

/** Blocking operations belong on gallery/import workers. Metadata writes never own private file mutations. */
class MediaRepository(private val database: MediaDatabase) {
    private val dao get() = database.media()

    fun available(): List<MediaRecord> = dao.available()

    fun reconcileImports(store: PhotoStore, now: Long) {
        // Hold PhotoStore's writer monitor through commit: import/delete cannot invalidate this inventory.
        synchronized(store) {
            database.runInTransaction {
                dao.markSource(MediaSource.GOOGLE_IMPORT, MediaAvailability.MISSING)
                ImportedCatalogMigration.migrate(store, now) { record ->
                    dao.insertIfAbsent(record)
                    dao.markSeen(record.mediaId, now)
                }
            }
        }
    }

    /** Absent device rows may be hidden by limited permission or a version reset, never infer deletion. */
    fun reconcileDevice(visible: List<MediaRecord>) {
        require(visible.all { it.source == MediaSource.DEVICE })
        database.runInTransaction {
            dao.markSource(MediaSource.DEVICE, MediaAvailability.INACCESSIBLE)
            visible.forEach { dao.upsert(it.copy(availability = MediaAvailability.AVAILABLE)) }
        }
    }

    /** Best-effort enrichment is outside migration and cannot overwrite unrelated persisted metadata. */
    fun enrichImportedCaptureDate(id: String, takenAt: Long, offsetSeconds: Int?) {
        database.runInTransaction {
            val current = dao.get(id) ?: return@runInTransaction
            if (current.source == MediaSource.GOOGLE_IMPORT && current.takenAt == null && takenAt > 0) {
                dao.upsert(current.copy(takenAt = takenAt, dateSource = MediaDateSource.EXIF,
                    dateOffsetSeconds = offsetSeconds))
            }
        }
    }
}
