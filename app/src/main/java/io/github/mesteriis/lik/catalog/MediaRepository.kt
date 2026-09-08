package io.github.mesteriis.lik.catalog

import io.github.mesteriis.lik.imports.PhotoStore
import java.time.ZoneId

/** Blocking operations belong on gallery/import workers. Metadata writes never own private file mutations. */
class MediaRepository(private val database: MediaDatabase) {
    private val dao get() = database.media()

    fun available(): List<MediaRecord> = dao.available()

    fun reconcileImports(store: PhotoStore, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        // Hold PhotoStore's writer monitor through commit: import/delete cannot invalidate this inventory.
        synchronized(store) {
            database.runInTransaction {
                dao.markSource(MediaSource.GOOGLE_IMPORT, MediaAvailability.MISSING)
                ImportedCatalogMigration.migrate(store, now) { record ->
                    dao.insertIfAbsent(record.withPeriods(zone))
                    var current = requireNotNull(dao.get(record.mediaId))
                    if (current.availability in setOf(MediaAvailability.TRASHED, MediaAvailability.PURGING)) return@migrate
                    if (current.contentRevision != record.contentRevision || current.byteSize != record.byteSize) {
                        current = current.copy(contentRevision = record.contentRevision, modifiedAt = record.modifiedAt,
                            byteSize = record.byteSize, exifRevision = null, exifOrientation = null,
                            dateOffsetSeconds = current.dateOffsetSeconds.takeUnless { current.dateSource == MediaDateSource.EXIF },
                            takenAt = current.takenAt.takeUnless { current.dateSource == MediaDateSource.EXIF },
                            dateSource = if (current.dateSource == MediaDateSource.EXIF) MediaDateSource.FILE_MODIFIED else current.dateSource)
                            .withPeriods(zone)
                        dao.upsert(current)
                    }
                    if (current.dayKey == "undated" && (current.takenAt != null || current.addedAt != null)) {
                        dao.upsert(current.withPeriods(zone))
                    }
                    dao.markSeen(record.mediaId, now)
                }
            }
        }
    }

    fun viewerWindow(id: String): List<MediaRecord> = database.runInTransaction<List<MediaRecord>> {
        val current = dao.get(id)?.takeIf { it.availability == MediaAvailability.AVAILABLE }
            ?: return@runInTransaction emptyList()
        listOfNotNull(dao.previous(id, current.sortAt), current, dao.next(id, current.sortAt))
    }

    fun cacheExif(id: String, revision: Long, takenAt: Long?, offset: Int?, orientation: Int, zone: ZoneId) {
        database.runInTransaction {
            val current = dao.get(id)?.takeIf { it.contentRevision == revision } ?: return@runInTransaction
            dao.upsert(current.copy(exifRevision = revision, exifOrientation = orientation,
                takenAt = takenAt ?: current.takenAt,
                dateSource = if (takenAt == null) current.dateSource else MediaDateSource.EXIF,
                dateOffsetSeconds = offset ?: current.dateOffsetSeconds).withPeriods(zone))
        }
    }

    /** Absent device rows may be hidden by limited permission or a version reset, never infer deletion. */
    fun reconcileDevice(visible: List<MediaRecord>) {
        require(visible.all { it.source == MediaSource.DEVICE })
        database.runInTransaction {
            val previous = visible.associate { it.mediaId to dao.get(it.mediaId) }
            dao.markSource(MediaSource.DEVICE, MediaAvailability.INACCESSIBLE)
            visible.forEach {
                val epoch = previous[it.mediaId]?.let { record ->
                    if (record.availability != MediaAvailability.AVAILABLE) Math.addExact(record.accessGrantEpoch, 1)
                    else record.accessGrantEpoch
                } ?: it.accessGrantEpoch
                dao.upsert(it.copy(availability = MediaAvailability.AVAILABLE, accessGrantEpoch = epoch))
            }
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
