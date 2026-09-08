package io.github.mesteriis.lik

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mesteriis.lik.catalog.*
import org.junit.Assert.*
import org.junit.Test

class OrganizationTest {
    @Test fun relationshipsSurviveRevocationAndReopenAndNamesDoNotDefineIdentity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "organization-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, MediaDatabase::class.java, name).build()
        try {
            val repo = OrganizationRepository(db)
            val local = MediaRecord("device:a", MediaSource.DEVICE, "a", contentUri = "content://media/a", displayName = "ЁЛКА.jpg", lastSeenAt = 1)
            val imported = MediaRecord("import", MediaSource.GOOGLE_IMPORT, "import", privateFileId = "a".repeat(64), displayName = "ЁЛКА.jpg", takenAt = 100, lastSeenAt = 1)
            db.media().upsert(local); db.media().upsert(imported)
            val album = repo.createAlbum("Лето")
            val duplicate = repo.createAlbum("Лето")
            assertNotEquals(album, duplicate)
            repeat(2) {
                repo.organize(setOf(local.mediaId, imported.mediaId)) { dao, id -> dao.addMember(AlbumMedia(album, id)); dao.favorite(Favorite(id)) }
                repo.tag(setOf(local.mediaId, imported.mediaId), "СЕМЬЯ")
                repo.tag(setOf(local.mediaId), "семья")
            }
            assertEquals(1, repo.dao.tags(local.mediaId).size)
            assertEquals(2, repo.dao.albums().first { it.albumId == album }.count)
            assertEquals(0, repo.dao.albums().first { it.albumId == duplicate }.count)
            assertEquals(setOf(local.mediaId, imported.mediaId), repo.dao.search(CatalogSearch(name = "ёлка", tag = "семья", albumId = album, favorites = true).query()).map { it.mediaId }.toSet())
            assertEquals(listOf(imported.mediaId), repo.dao.search(CatalogSearch(from = 100, until = 101).query()).map { it.mediaId })
            assertTrue(repo.dao.search(CatalogSearch(from = 101).query()).isEmpty())
            assertEquals(listOf(local.mediaId), repo.dao.search(CatalogSearch(source = MediaSource.DEVICE).query()).map { it.mediaId })
            db.media().markSource(MediaSource.DEVICE, MediaAvailability.INACCESSIBLE)
            assertEquals(setOf(imported.mediaId), repo.eligible(setOf(local.mediaId, imported.mediaId), MediaOperation.ORGANIZE))
            assertEquals(1, repo.dao.search(CatalogSearch(albumId = album).query()).size)
            db.close()
            db = Room.databaseBuilder(context, MediaDatabase::class.java, name).build()
            assertEquals(1, db.organization().tags(local.mediaId).size)
            db.media().markSeen(local.mediaId, 2)
            assertEquals(2, db.organization().search(CatalogSearch(albumId = album, favorites = true).query()).size)
            db.organization().removeMember(album, local.mediaId)
            db.organization().unfavorite(local.mediaId)
            OrganizationRepository(db).tag(setOf(local.mediaId), "семья", false)
            assertEquals(1, db.organization().search(CatalogSearch(albumId = album).query()).size)
            assertTrue(db.organization().tags(local.mediaId).isEmpty())
            db.organization().renameAlbum(album, "Осень")
            assertEquals("Осень", db.organization().albums().first { it.albumId == album }.name)
            db.organization().deleteAlbum(album)
            assertEquals(2, db.media().all().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun foldersKeepVolumeAndPathIdentityAndSearchTreatsSqlWildcardsLiterally() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        try {
            listOf("primary", "sdcard").forEach { volume -> db.media().upsert(MediaRecord(volume, MediaSource.DEVICE, "1", volumeName = volume,
                bucketId = "1", bucketName = "Camera", relativePath = "DCIM/Camera/", displayName = "100%_СНИМОК.jpg", lastSeenAt = 1)) }
            assertEquals(2, db.organization().folders().size)
            db.organization().folders().forEach { assertEquals(1, db.organization().search(CatalogSearch(folder = it).query()).size) }
            assertEquals(2, db.organization().search(CatalogSearch(name = "%_снимок").query()).size)
            assertTrue(db.organization().search(CatalogSearch(name = "' OR 1=1 --").query()).isEmpty())
            val row = db.media().get("primary")!!
            db.media().upsert(row.copy(displayName = "ИЗМЕНЕНО"))
            assertEquals(1, db.organization().search(CatalogSearch(name = "изменено").query()).size)
        } finally { db.close() }
    }

    @Test fun catalogStoresOrganizationIndependentlyFromMediaAvailability() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        try {
            val tables = mutableSetOf<String>()
            database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue("Organization must have persistent relationship tables: $tables",
                tables.containsAll(setOf("album", "album_media", "favorite", "media_tag")))
        } finally { database.close() }
    }
}
