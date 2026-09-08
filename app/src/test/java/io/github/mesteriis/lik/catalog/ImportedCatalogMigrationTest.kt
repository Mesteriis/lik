package io.github.mesteriis.lik.catalog

import io.github.mesteriis.lik.imports.PhotoStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import io.github.mesteriis.lik.gallery.GalleryTimeline
import io.github.mesteriis.lik.gallery.TimelineEntry
import io.github.mesteriis.lik.gallery.TimelineLevel

class ImportedCatalogMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(expected = IOException::class)
    fun invalidInventoryCannotBeMistakenForAnEmptyLibrary() {
        val notDirectory = temporary.newFile()
        ImportedCatalogMigration.migrate(PhotoStore(notDirectory, 100) { }, 1) { }
    }

    @Test fun migrationRetryAfterPartialCommitPreservesIdentityBytesAndExistingMetadata() {
        val directory = temporary.newFolder()
        val first = File(directory, "${"a".repeat(64)}.image").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(directory, "${"b".repeat(64)}.image").writeBytes(byteArrayOf(4, 5))
        File(directory, "import-interrupted.part").writeBytes(byteArrayOf(6))
        File(directory, "unknown.image").writeBytes(byteArrayOf(7))
        val store = PhotoStore(directory, 100) { error("Migration must not decode files") }
        val records = linkedMapOf<String, MediaRecord>()
        var writes = 0
        try {
            ImportedCatalogMigration.migrate(store, 1000) { record ->
                if (++writes == 2) throw IOException("Simulated interruption")
                records.putIfAbsent(record.mediaId, record.copy(displayName = "Снимок"))
            }
            fail("Expected interruption")
        } catch (_: IOException) { }
        ImportedCatalogMigration.migrate(store, 2000) { records.putIfAbsent(it.mediaId, it) }
        ImportedCatalogMigration.migrate(store, 3000) { records.putIfAbsent(it.mediaId, it) }
        assertEquals(setOf("a".repeat(64), "b".repeat(64)), records.keys)
        assertEquals(1, records.values.count { it.displayName == "Снимок" })
        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
        assertEquals(4, directory.listFiles()!!.size)
    }

    @Test fun unknownMetadataStaysUnknownAndCannotInventCaptureDate() {
        val directory = temporary.newFolder()
        val file = File(directory, "${"c".repeat(64)}.image").apply { writeBytes(byteArrayOf(1)) }
        val photo = PhotoStore(directory, 100) { error("No decoding") }.photos().single()
        val record = ImportedCatalogMigration.record(photo, 1000)
        assertNull(record.takenAt)
        assertNull(record.dateOffsetSeconds)
        assertNull(record.mimeType)
        assertNull(record.width)
        assertNull(record.height)
        assertNull(record.displayName)
        assertEquals(file.lastModified(), record.addedAt)
        assertEquals(MediaDateSource.FILE_MODIFIED, record.dateSource)
        assertEquals(photo.id, record.privateFileId)
    }

    @Test fun catalogMappingPreservesInstantsAtDstOverlapAndUnknownDates() {
        val identity = MediaIdentity.imported("d".repeat(64))
        val first = MediaRecord(identity.mediaId, identity.source, identity.sourceKey,
            privateFileId = identity.sourceKey,
            takenAt = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),
            dateSource = MediaDateSource.EXIF, dateOffsetSeconds = 7200, lastSeenAt = 1)
        val second = first.copy(mediaId = "e".repeat(64), sourceKey = "e".repeat(64),
            privateFileId = "e".repeat(64),
            takenAt = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(), dateOffsetSeconds = 3600)
        val unknown = first.copy(mediaId = "f".repeat(64), sourceKey = "f".repeat(64),
            privateFileId = "f".repeat(64), takenAt = null, dateSource = MediaDateSource.UNKNOWN,
            dateOffsetSeconds = null)
        val directory = temporary.newFolder()
        val mapped = listOf(first, second, unknown).map { it.toGalleryPhoto { id -> File(directory, "$id.image") } }
        assertEquals(3_600_000L, mapped[1].takenAt!! - mapped[0].takenAt!!)
        assertEquals(7200, mapped[0].dateOffsetSeconds)
        assertEquals(3600, mapped[1].dateOffsetSeconds)
        assertNull(mapped[2].timelineAt)
        val groups = GalleryTimeline.build(mapped, TimelineLevel.DAYS, ZoneId.of("Europe/Madrid"), Locale.ENGLISH)
        assertEquals(listOf("2026-10-25", "undated"), groups.filterIsInstance<TimelineEntry.Header>().map { it.key })
    }
}
