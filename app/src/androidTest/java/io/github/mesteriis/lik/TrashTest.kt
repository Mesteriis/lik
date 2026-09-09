package io.github.mesteriis.lik

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mesteriis.lik.catalog.*
import org.junit.Assert.*
import org.junit.Test
import io.github.mesteriis.lik.imports.PhotoStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TrashTest {
    private fun fixture(test: (MediaDatabase, PhotoStore, TrashRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "trash-test-${System.nanoTime()}").apply { mkdirs() }
        val databaseName = "trash-test-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, MediaDatabase::class.java, databaseName).build()
        val store = PhotoStore(directory, 1024) { }
        try { test(db, store, TrashRepository(db, store)) }
        finally { db.close(); context.deleteDatabase(databaseName); directory.deleteRecursively() }
    }

    @Test fun trashHidesEveryNormalQueryRetainsRelationsAndReimportRestores() = fixture { db, store, trash ->
        val bytes = byteArrayOf(1, 2, 3)
        val imported = trash.importPhoto(bytes.inputStream()).photo
        val id = imported.id
        db.ocrPeople().saveExposure(io.github.mesteriis.lik.ai.AiMediaExposureRecord(id,db.media().get(id)!!.contentRevision,io.github.mesteriis.lik.ai.AiExposure.SAFE,1))
        val org = OrganizationRepository(db)
        val album = org.createAlbum("Семья")
        org.organize(setOf(id)) { dao, media -> dao.favorite(Favorite(media)); dao.addMember(AlbumMedia(album, media)) }
        org.tag(setOf(id), "ЛЕТО")
        val original = File(imported.file.parentFile, "device-original").apply { writeBytes(bytes) }
        db.media().upsert(MediaRecord("device", MediaSource.DEVICE, "1", contentUri = original.toURI().toString(), lastSeenAt = 1))
        assertEquals(1, trash.trash(setOf(id, "device"), 1000))
        MediaRepository(db).reconcileImports(store, 1100)
        assertArrayEquals(bytes, imported.file.readBytes())
        assertArrayEquals(bytes, original.readBytes())
        assertEquals(1, db.media().availableCount())
        assertTrue(db.media().page(60, 0).none { it.mediaId == id })
        assertTrue(db.media().exifPending(60).none { it.mediaId == id })
        assertTrue(MediaRepository(db).viewerWindow(id).isEmpty())
        assertTrue(org.dao.search(CatalogSearch(albumId = album, favorites = true, tag = "лето").query()).isEmpty())
        assertEquals(0, org.dao.albums().single().count)
        assertEquals(1, org.dao.tags(id).size)
        assertTrue(trash.importPhoto(bytes.inputStream()).restored)
        assertEquals(id, org.dao.search(CatalogSearch(albumId = album, favorites = true, tag = "лето").query()).single().mediaId)
        assertNull(db.media().get(id)!!.trashedAt)
        assertFalse(trash.importPhoto(bytes.inputStream()).restored)
    }

    @Test fun retentionBoundaryAndInterruptedPurgeRetry() = fixture { db, store, trash ->
        val id = trash.importPhoto(byteArrayOf(4, 5).inputStream()).photo.id
        trash.trash(setOf(id), 1000)
        trash.purgeExpired(2_592_000_999)
        assertTrue(store.fileFor(id).exists())
        // A nonempty directory simulates a filesystem deletion failure without a test-only hook.
        store.fileFor(id).delete(); store.fileFor(id).mkdir(); File(store.fileFor(id), "busy").writeText("busy")
        assertThrows(IOException::class.java) { trash.purgeExpired(2_592_001_000) }
        assertEquals(MediaAvailability.PURGING, db.media().get(id)!!.availability)
        assertFalse(trash.restore(id))
        store.fileFor(id).deleteRecursively()
        trash.purgeExpired(2_592_001_000)
        assertNull(db.media().get(id))
        assertTrue(store.photos().isEmpty())
    }

    @Test fun restoreAndPurgeAreSerializedAndCommittedClaimSurvivesReopen() = fixture { db, store, trash ->
        val id = trash.importPhoto(byteArrayOf(6).inputStream()).photo.id
        trash.trash(setOf(id), 1)
        val executor = Executors.newSingleThreadExecutor()
        val attempted = CountDownLatch(1)
        try {
            synchronized(store) {
                val future = executor.submit<Boolean> { attempted.countDown(); trash.restore(id) }
                assertTrue(attempted.await(2, TimeUnit.SECONDS))
                assertFalse(future.isDone)
                trash.purgeNow(setOf(id))
            }
            executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertNull(db.media().get(id)); assertFalse(store.fileFor(id).exists())
            val other = trash.importPhoto(byteArrayOf(7).inputStream()).photo.id
            trash.trash(setOf(other), 1)
            assertTrue(trash.restore(other))
            trash.purgeExpired(2_592_000_001)
            assertTrue(store.fileFor(other).exists())
            trash.trash(setOf(other), 2)
            db.media().claimPurge(setOf(other)) // crash after commit, before physical unlink
            val name = requireNotNull(db.openHelper.databaseName)
            db.close()
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val reopened = Room.databaseBuilder(context, MediaDatabase::class.java, name).build()
            try {
                TrashRepository(reopened, store).purgeExpired(2)
                assertNull(reopened.media().get(other)); assertFalse(store.fileFor(other).exists())
            } finally { reopened.close() }
        } finally { executor.shutdownNow() }
    }
    @Test fun catalogPersistsTrashTimeForRestartSafeRetention() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        try {
            val columns = mutableSetOf<String>()
            db.openHelper.writableDatabase.query("PRAGMA table_info(media)").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(1)
            }
            assertTrue("Trash must persist its start timestamp", "trashedAt" in columns)
        } finally { db.close() }
    }
}
