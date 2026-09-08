package io.github.mesteriis.lik.catalog

import io.github.mesteriis.lik.imports.ImportedPhoto
import io.github.mesteriis.lik.imports.PhotoStore

/** File migration is repeatable insert-if-absent, not a destructive Room schema migration. */
object ImportedCatalogMigration {
    fun record(photo: ImportedPhoto, now: Long): MediaRecord {
        val identity = MediaIdentity.imported(photo.id)
        val modified = photo.file.lastModified().takeIf { it > 0 }
        return MediaRecord(
            mediaId = identity.mediaId, source = identity.source, sourceKey = identity.sourceKey,
            privateFileId = photo.id, byteSize = photo.file.length(), addedAt = modified, modifiedAt = modified,
            dateSource = if (modified == null) MediaDateSource.UNKNOWN else MediaDateSource.FILE_MODIFIED,
            contentRevision = modified ?: 0, lastSeenAt = now,
        )
    }

    fun migrate(store: PhotoStore, now: Long, insertIfAbsent: (MediaRecord) -> Unit) {
        store.photos().forEach { insertIfAbsent(record(it, now)) }
    }
}
