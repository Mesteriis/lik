package io.github.mesteriis.lik

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.imports.PhotoStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class MediaCatalogTest {
    private lateinit var database: MediaDatabase
    private lateinit var repository: MediaRepository
    private lateinit var directory: File

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        repository = MediaRepository(database)
        directory = File(context.cacheDir, "catalog-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun cleanup() { database.close(); directory.deleteRecursively() }

    @Test fun sameIdentityUpdatesMetadataButReusedRowAndCrossSourceStaySeparate() {
        val original = device("v1").copy(displayName = "Снимок.jpg", takenAt = 1000)
        repository.reconcileDevice(listOf(original))
        repository.reconcileDevice(listOf(original.copy(width = 400, contentRevision = 7)))
        assertEquals(1, database.media().all().size)
        assertEquals(400, database.media().get(original.mediaId)!!.width)
        val next = device("v2").copy(displayName = "Снимок.jpg", takenAt = 1000)
        repository.reconcileDevice(listOf(next))
        val sha = "a".repeat(64)
        database.media().insertIfAbsent(MediaRecord(sha, MediaSource.GOOGLE_IMPORT, sha,
            privateFileId = sha, displayName = "Снимок.jpg", takenAt = 1000, lastSeenAt = 1))
        assertEquals(3, database.media().all().size)
        assertEquals(MediaAvailability.INACCESSIBLE, database.media().get(original.mediaId)!!.availability)
        assertEquals(setOf(next.mediaId, sha), repository.available().map { it.mediaId }.toSet())
    }

    @Test fun lossOfVisibilityKeepsMetadataAndLastSeenUntilObjectReturns() {
        val original = device("v1").copy(displayName = "Original", lastSeenAt = 42)
        repository.reconcileDevice(listOf(original))
        repository.reconcileDevice(emptyList())
        val hidden = database.media().get(original.mediaId)!!
        assertEquals("Original", hidden.displayName)
        assertEquals(42L, hidden.lastSeenAt)
        assertEquals(MediaAvailability.INACCESSIBLE, hidden.availability)
        repository.reconcileDevice(listOf(original.copy(lastSeenAt = 100)))
        assertEquals(1, repository.available().size)
        assertEquals(100L, repository.available().single().lastSeenAt)
    }

    @Test fun migrationSurvivesRetryAndDeletionWithoutMovingOrDecodingFiles() {
        val sha = "b".repeat(64)
        val file = File(directory, "$sha.image").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        File(directory, "interrupted.part").writeText("partial")
        val store = PhotoStore(directory, 100) { error("Must not decode") }
        repository.reconcileImports(store, 10)
        database.media().upsert(database.media().get(sha)!!.copy(displayName = "Preserved", takenAt = 5))
        repository.reconcileImports(store, 20)
        assertEquals(1, database.media().all().size)
        assertEquals("Preserved", repository.available().single().displayName)
        assertEquals(5L, repository.available().single().takenAt)
        assertEquals(20L, repository.available().single().lastSeenAt)
        assertArrayEquals(byteArrayOf(9, 8, 7), file.readBytes())
        store.deletePhoto(sha)
        repository.reconcileImports(store, 30)
        assertTrue(repository.available().isEmpty())
        assertEquals(MediaAvailability.MISSING, database.media().get(sha)!!.availability)
        file.writeBytes(byteArrayOf(9, 8, 7))
        repository.reconcileImports(store, 40)
        assertEquals("Preserved", repository.available().single().displayName)
    }

    @Test fun failedMigrationTransactionLeavesPreviousCatalogUntouchedAndRetryCompletes() {
        val sha = "c".repeat(64)
        File(directory, "$sha.image").writeBytes(byteArrayOf(1))
        val store = PhotoStore(directory, 100) { error("Must not decode") }
        // A real SQLite write failure after the migration has started, not a mocked DAO.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_insert BEFORE INSERT ON media BEGIN SELECT RAISE(ABORT, 'interrupted'); END",
        )
        try {
            repository.reconcileImports(store, 10)
            fail("Expected database failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(database.media().all().isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_insert")
        repository.reconcileImports(store, 20)
        assertEquals(sha, repository.available().single().mediaId)
        assertTrue(File(directory, "$sha.image").exists())
    }

    @Test fun persistentDatabaseRetainsUnknownsAndOffsetAcrossReopen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "catalog-persistence-${System.nanoTime()}.db"
        try {
            val first = Room.databaseBuilder(context, MediaDatabase::class.java, name).build()
            try {
                first.media().upsert(device("v1").copy(takenAt = 1792891800000,
                    dateSource = MediaDateSource.EXIF, dateOffsetSeconds = 3600))
            } finally { first.close() }
            val reopened = Room.databaseBuilder(context, MediaDatabase::class.java, name).build()
            try {
                val restored = reopened.media().all().single()
                assertEquals(1792891800000L, restored.takenAt)
                assertEquals(3600, restored.dateOffsetSeconds)
                assertNull(restored.width)
                assertNull(restored.addedAt)
                assertNull(restored.mimeType)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun device(version: String): MediaRecord {
        val id = MediaIdentity.device("external_primary", version, 1, 5)
        return MediaRecord(id.mediaId, id.source, id.sourceKey, id.volumeName, id.volumeVersion,
            id.generationAdded, contentUri = "content://media/external_primary/images/media/1", lastSeenAt = 1)
    }
}
