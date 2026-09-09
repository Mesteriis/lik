package io.github.mesteriis.lik.privacy

import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.gallery.GalleryPhoto

/** Indexed point read at image delivery, including cached and previously SAFE images. */
object SensitiveImagePublication {
    fun accepts(database: MediaDatabase, photo: GalleryPhoto, revealEpoch: Long?): Boolean {
        return database.openHelper.readableDatabase.query("""
            SELECT m.contentRevision,m.accessGrantEpoch,m.availability,m.trashedAt,
              COALESCE(CASE WHEN s.contentRevision=m.contentRevision THEN s.decision END,
                CASE WHEN a.contentRevision=m.contentRevision AND a.accessEpoch=m.accessGrantEpoch THEN a.decision END,'QUARANTINED')
            FROM media m LEFT JOIN sensitive_manual s ON s.mediaId=m.mediaId
            LEFT JOIN sensitive_automatic a ON a.mediaId=m.mediaId AND a.contentRevision=m.contentRevision AND a.accessEpoch=m.accessGrantEpoch
            WHERE m.mediaId=? LIMIT 1
        """.trimIndent(),arrayOf(photo.id)).use { row ->
            row.moveToFirst() && row.getLong(0)==photo.sourceRevision && row.getLong(1)==photo.accessGrantEpoch &&
                row.getString(2)=="AVAILABLE" && row.isNull(3) &&
                (row.getString(4)=="SAFE" || revealEpoch?.let(SensitiveMediaSession.current::accepts)==true)
        }
    }
}
