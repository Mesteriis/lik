package io.github.mesteriis.lik

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.ai.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

class IncrementalCatalogTest {
    private lateinit var db: MediaDatabase
    private lateinit var scanner: MediaScanner
    private val source = Inventory()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MediaDatabase::class.java).build()
        scanner = MediaScanner(db, ZoneId.of("Europe/Madrid"))
    }
    @After fun close() = db.close()

    @Test fun insertsUpdatesAndExplicitFullInventoryRemovalPreserveMetadata() {
        source.rows = listOf(row(1), row(2))
        scanner.scan(source, true, CancellationSignal())
        source.generation = 2
        source.rows = listOf(row(2).copy(displayName = "Updated", contentRevision = 2))
        scanner.scan(source, true, CancellationSignal())
        assertEquals("Updated", db.media().get(row(2).mediaId)!!.displayName)
        assertEquals(MediaAvailability.MISSING, db.media().get(row(1).mediaId)!!.availability)
        assertEquals("Photo 1", db.media().get(row(1).mediaId)!!.displayName)
        assertEquals(listOf(0L, 1L), source.watermarks)
    }

    @Test fun lastSeenRemainsAnEpochTimestampRatherThanAScanToken() {
        source.rows = listOf(row(1))
        val before = System.currentTimeMillis()
        scanner.scan(source, true, CancellationSignal())
        assertTrue(db.media().get(row(1).mediaId)!!.lastSeenAt in before..System.currentTimeMillis())
    }

    @Test fun noOpScanKeepsEmbeddingAccessEpochWhileRegrantAdvancesIt() {
        source.rows = listOf(row(1))
        scanner.scan(source, true, CancellationSignal())
        val media = db.media().get(row(1).mediaId)!!
        val generation = AiIndexGenerationRecord("stable", "compact-v1", "SEARCH", "pipeline",
            GenerationStatus.PREPARING, 1, 1, media.mediaId, null, 1)
        db.aiIndexes().saveGeneration(generation)
        db.aiIndexes().saveEmbedding(AiEmbeddingRecord("stable", media.mediaId, 1,
            media.contentRevision, media.accessGrantEpoch, floatArrayOf(1f).toBytes()))

        scanner.scan(source, true, CancellationSignal())
        val unchanged = db.media().get(media.mediaId)!!
        assertEquals(media.accessGrantEpoch, unchanged.accessGrantEpoch)
        assertEquals(1, db.aiIndexes().currentEmbeddingCount("stable"))

        source.rows = emptyList()
        scanner.scan(source, false, CancellationSignal())
        assertEquals(MediaAvailability.INACCESSIBLE, db.media().get(media.mediaId)!!.availability)
        source.rows = listOf(row(1))
        scanner.scan(source, false, CancellationSignal())
        val regranted = db.media().get(media.mediaId)!!
        assertEquals(media.accessGrantEpoch + 1, regranted.accessGrantEpoch)
        assertEquals(0, db.aiIndexes().currentEmbeddingCount("stable"))
    }

    @Test fun limitedSelectionAndRevocationNeverBecomeDeletion() {
        source.rows = listOf(row(1), row(2))
        scanner.scan(source, true, CancellationSignal())
        source.rows = listOf(row(2))
        scanner.scan(source, false, CancellationSignal())
        assertEquals(MediaAvailability.INACCESSIBLE, db.media().get(row(1).mediaId)!!.availability)
        source.denied = true
        try { scanner.scan(source, true, CancellationSignal()); fail("Expected revoked source") } catch (_: SecurityException) { }
        assertEquals(MediaAvailability.AVAILABLE, db.media().get(row(2).mediaId)!!.availability)
    }

    @Test fun unchangedLimitedRefreshPreservesRevisionMatchedExif() {
        source.rows = listOf(row(1))
        scanner.scan(source, true, CancellationSignal())
        MediaRepository(db).cacheExif(row(1).mediaId, 1, 10000, 3600, 6, ZoneId.of("UTC"))
        scanner.scan(source, false, CancellationSignal())
        val refreshed = db.media().get(row(1).mediaId)!!
        assertEquals(10000L, refreshed.takenAt)
        assertEquals(MediaDateSource.EXIF, refreshed.dateSource)
        assertEquals(3600, refreshed.dateOffsetSeconds)
        assertEquals(6, refreshed.exifOrientation)
    }

    @Test fun versionResetAndReusedRowHaveNewIdentities() {
        source.rows = listOf(row(1))
        scanner.scan(source, true, CancellationSignal())
        source.version = "v2"
        source.rows = listOf(row(1, "v2", 2))
        scanner.scan(source, true, CancellationSignal())
        assertEquals(MediaAvailability.INACCESSIBLE, db.media().get(row(1).mediaId)!!.availability)
        assertEquals(2, db.media().all().size)
        assertEquals(listOf(0L, 0L), source.watermarks)
    }

    @Test fun cancellationOrChangingGenerationRollBackCheckpointAndRemoval() {
        source.rows = listOf(row(1))
        scanner.scan(source, true, CancellationSignal())
        source.generation = 2
        source.rows = listOf(row(2).copy(contentRevision = 2))
        val cancellation = CancellationSignal()
        source.afterBatch = { cancellation.cancel() }
        try { scanner.scan(source, true, cancellation); fail("Expected cancellation") } catch (_: OperationCanceledException) { }
        assertEquals(listOf(row(1).mediaId), db.media().available().map { it.mediaId })
        source.afterBatch = { source.generation++ }
        try { scanner.scan(source, true, CancellationSignal()); fail("Expected unstable inventory") } catch (_: IllegalStateException) { }
        assertNull(db.media().get(row(2).mediaId))
        assertEquals(1L, db.media().checkpoint("external_primary")!!.generation)
    }

    @Test fun oneProviderGenerationChangeRetriesFromTheUncommittedCheckpoint() {
        source.rows = listOf(row(1))
        source.afterBatch = { source.generation++; source.afterBatch = {} }
        scanner.scan(source, true, CancellationSignal())
        assertEquals(listOf(row(1).mediaId), db.media().available().map { it.mediaId })
        assertEquals(2L, db.media().checkpoint("external_primary")!!.generation)
        assertEquals(listOf(0L, 0L), source.watermarks)
    }

    @Test fun permissionNarrowingDuringScanCannotProveRemoval() {
        source.rows = listOf(row(1), row(2))
        scanner.scan(source, true, CancellationSignal())
        source.generation = 2
        source.rows = listOf(row(2).copy(contentRevision = 2))
        source.afterBatch = { source.full = false }
        try { scanner.scan(source, true, CancellationSignal()); fail("Visibility changed during inventory") } catch (_: IllegalStateException) { }
        assertEquals(MediaAvailability.AVAILABLE, db.media().get(row(1).mediaId)!!.availability)
    }

    @Test fun metadataScalePagesAggregatesAndNeighborQueriesStayBounded() {
        for (size in listOf(2_000, 20_000, 100_000)) {
            db.clearAllTables()
            db.runInTransaction {
                repeat(size) { index -> db.media().upsert(row(index.toLong()).withPeriods(ZoneId.of("Europe/Madrid"))) }
            }
            assertEquals(size, db.media().availableCount())
            assertEquals(60, db.media().page(60, size / 2).size)
            val loaded = kotlinx.coroutines.runBlocking {
                db.media().feed().load(androidx.paging.PagingSource.LoadParams.Refresh(size / 2, 60, false))
            }
            assertEquals(60, (loaded as androidx.paging.PagingSource.LoadResult.Page).data.size)
            val middle = db.media().page(1, size / 2).single()
            val window = MediaRepository(db).viewerWindow(middle.mediaId)
            assertEquals(3, window.size)
            assertEquals(middle.mediaId, window[1].mediaId)
            val summaries = db.media().periods("dayKey", 10, 0)
            assertEquals(size, summaries.sumOf { it.count })
            assertTrue(db.media().covers("dayKey", summaries.first().periodKey).size <= 3)
        }
    }

    @Test fun overviewAnchorInsideAPeriodStartsAtThatPeriod() {
        val marchNew = java.time.Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
        val marchOld = java.time.Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()
        val february = java.time.Instant.parse("2026-02-01T12:00:00Z").toEpochMilli()
        listOf(marchNew, marchOld, february).forEachIndexed { i, date ->
            db.media().upsert(row(i.toLong()).copy(takenAt = date).withPeriods(ZoneId.of("UTC")))
        }
        assertEquals(0, db.media().periodRank("monthKey", marchOld))
        assertEquals(1, db.media().periodRank("monthKey", february))
    }

    @Test fun negativeExifCacheIsRevisionSpecificAndUsesStoredZone() {
        val sha = "a".repeat(64)
        val record = MediaRecord(sha, MediaSource.GOOGLE_IMPORT, sha, takenAt = null, addedAt = 1792891800000,
            contentRevision = 1, lastSeenAt = 1)
        db.media().upsert(record)
        val repository = MediaRepository(db)
        repository.cacheExif(record.mediaId, 1, null, null, 1, ZoneId.of("Europe/Madrid"))
        assertTrue(db.media().exifPending(10).isEmpty())
        db.media().upsert(db.media().get(record.mediaId)!!.copy(contentRevision = 2))
        assertEquals(record.mediaId, db.media().exifPending(10).single().mediaId)
        repository.cacheExif(record.mediaId, 1, 1000, null, 1, ZoneId.of("UTC"))
        assertNull(db.media().get(record.mediaId)!!.takenAt)
    }

    @Test fun mutatedPrivateFileInvalidatesCachedExif() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.cacheDir, "revision-${System.nanoTime()}").apply { mkdirs() }
        try {
            val sha = "b".repeat(64)
            val file = java.io.File(directory, "$sha.image").apply { writeBytes(byteArrayOf(1)); setLastModified(1000) }
            val store = io.github.mesteriis.lik.imports.PhotoStore(directory, 100) { error("No decode") }
            val repository = MediaRepository(db)
            repository.reconcileImports(store, 1)
            repository.cacheExif(sha, 1000, 10000, null, 1, ZoneId.of("UTC"))
            file.writeBytes(byteArrayOf(2, 3))
            assertTrue(file.setLastModified(2000))
            repository.reconcileImports(store, 2)
            val changed = db.media().get(sha)!!
            assertEquals(2000L, changed.contentRevision)
            assertNull(changed.exifRevision)
            assertNull(changed.takenAt)
            assertEquals(1000L, changed.addedAt)
        } finally { directory.deleteRecursively() }
    }

    private fun row(id: Long, version: String = "v1", birth: Long = 1): MediaRecord {
        val identity = MediaIdentity.device("external_primary", version, id, birth)
        return MediaRecord(identity.mediaId, identity.source, identity.sourceKey, identity.volumeName,
            identity.volumeVersion, identity.generationAdded, displayName = "Photo $id", takenAt = 1_700_000_000_000,
            contentRevision = 1, lastSeenAt = 1)
    }

    private class Inventory : MediaInventory {
        var rows = emptyList<MediaRecord>()
        var generation = 1L
        var version = "v1"
        var denied = false
        var full = true
        var afterBatch: () -> Unit = {}
        val watermarks = mutableListOf<Long>()
        override fun volumes() = setOf("external_primary")
        override fun state(volume: String) = VolumeState(version, generation, full)
        override fun changed(volume: String, state: VolumeState, after: Long, signal: CancellationSignal, emit: (List<MediaRecord>) -> Unit) {
            if (denied) throw SecurityException("Revoked")
            watermarks += after
            rows.filter { it.contentRevision > after }.chunked(128).forEach { emit(it); afterBatch() }
        }
        override fun visibleIds(volume: String, state: VolumeState, signal: CancellationSignal, emit: (List<String>) -> Unit) {
            rows.map { it.mediaId }.chunked(128).forEach(emit)
        }
    }
}
