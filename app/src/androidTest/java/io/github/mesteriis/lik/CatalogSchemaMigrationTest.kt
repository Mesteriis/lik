package io.github.mesteriis.lik

import android.database.sqlite.SQLiteDatabase
import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.MediaDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogSchemaMigrationTest {
    @Test fun versionOneUpgradesWithoutLosingMetadata() = verifyUpgrade(false)

    @Test fun interruptedPeriodBackfillRollsBackSchemaAndRetries() = verifyUpgrade(true)

    @android.annotation.SuppressLint("UseKtx")
    private fun verifyUpgrade(interrupt: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${System.nanoTime()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/1.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val preferences = context.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE)
        val savedZone = preferences.getString("gallery.library.zone", null)
        val previousZone = java.util.TimeZone.getDefault()
        preferences.edit().putString("gallery.library.zone", "Europe/Madrid").commit()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (i in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
                sqlite.execSQL("INSERT INTO media(mediaId, source, sourceKey, volumeName, volumeVersion, generationAdded, dateSource, contentRevision, availability, lastSeenAt, displayName, takenAt) VALUES('old', 'DEVICE', '1', 'primary', 'v1', 1, 'MEDIASTORE_TAKEN', 3, 'INACCESSIBLE', 8, 'Сохранено', ?)",
                    arrayOf(java.time.Instant.parse("2026-12-31T23:30:00Z").toEpochMilli()))
                sqlite.execSQL("INSERT INTO media(mediaId, source, sourceKey, volumeName, volumeVersion, generationAdded, dateSource, contentRevision, availability, lastSeenAt, addedAt) VALUES('imported', 'GOOGLE_IMPORT', 'imported', '', '', 0, 'FILE_MODIFIED', 3, 'AVAILABLE', 8, ?)",
                    arrayOf(java.time.Instant.parse("2026-03-29T22:30:00Z").toEpochMilli()))
                repeat(270) { index ->
                    sqlite.execSQL("INSERT INTO media(mediaId, source, sourceKey, volumeName, volumeVersion, generationAdded, dateSource, contentRevision, availability, lastSeenAt, takenAt) VALUES(?, 'DEVICE', ?, 'primary', 'v1', 1, 'MEDIASTORE_TAKEN', 3, 'MISSING', 8, 10000)",
                        arrayOf("bulk-$index", "bulk-$index"))
                }
                sqlite.execSQL("INSERT INTO media(mediaId, source, sourceKey, volumeName, volumeVersion, generationAdded, dateSource, contentRevision, availability, lastSeenAt) VALUES('unknown', 'GOOGLE_IMPORT', 'unknown', '', '', 0, 'UNKNOWN', 3, 'MISSING', 8)")
                if (interrupt) sqlite.execSQL("CREATE TRIGGER interrupt_period BEFORE UPDATE ON media WHEN NEW.mediaId = 'old' AND NEW.dayKey != 'undated' BEGIN SELECT RAISE(ABORT, 'interrupted backfill'); END")
                sqlite.version = 1
            }
            if (interrupt) {
                val failed = Room.databaseBuilder(context, MediaDatabase::class.java, name).addMigrations(MediaDatabase.migration1To2(context), MediaDatabase.MIGRATION_2_3, MediaDatabase.MIGRATION_3_4, MediaDatabase.MIGRATION_4_5, MediaDatabase.MIGRATION_5_6, MediaDatabase.MIGRATION_6_7, MediaDatabase.MIGRATION_7_8, MediaDatabase.MIGRATION_8_9).build()
                try {
                    failed.media().all()
                    fail("Expected backfill failure")
                } catch (_: android.database.sqlite.SQLiteException) { }
                finally { failed.close() }
                SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                    assertEquals(1, sqlite.version)
                    sqlite.rawQuery("PRAGMA table_info(media)", null).use { columns ->
                        while (columns.moveToNext()) assertNotEquals("dayKey", columns.getString(1))
                    }
                    sqlite.execSQL("DROP TRIGGER interrupt_period")
                }
            }
            val migrated = Room.databaseBuilder(context, MediaDatabase::class.java, name).addMigrations(MediaDatabase.migration1To2(context), MediaDatabase.MIGRATION_2_3, MediaDatabase.MIGRATION_3_4, MediaDatabase.MIGRATION_4_5, MediaDatabase.MIGRATION_5_6, MediaDatabase.MIGRATION_6_7, MediaDatabase.MIGRATION_7_8, MediaDatabase.MIGRATION_8_9).build()
            try {
                val row = migrated.media().get("old")!!
                assertEquals("Сохранено", row.displayName)
                assertEquals("сохранено", row.displayNameSearch)
                assertEquals(java.time.Instant.parse("2026-12-31T23:30:00Z").toEpochMilli(), row.sortAt)
                assertEquals("2027-01-01", row.dayKey)
                assertEquals("2026-12-28", row.weekKey)
                assertEquals("2027-01", row.monthKey)
                assertEquals("2027", row.yearKey)
                val imported = migrated.media().get("imported")!!
                assertEquals("2026-03-30", imported.dayKey)
                assertEquals("2026-03-30", imported.weekKey)
                assertEquals("2026-03", imported.monthKey)
                assertEquals("2026", imported.yearKey)
                assertNull(row.exifRevision)
                assertEquals("1970-01-01", migrated.media().get("bulk-269")!!.dayKey)
                val unknown = migrated.media().get("unknown")!!
                assertEquals("undated", unknown.dayKey)
                assertEquals("undated", unknown.weekKey)
                assertEquals("undated", unknown.monthKey)
                assertEquals("undated", unknown.yearKey)
                assertEquals(273, migrated.media().all().size)
            } finally { migrated.close() }
        } finally {
            context.deleteDatabase(name)
            preferences.edit().putString("gallery.library.zone", savedZone).commit()
            java.util.TimeZone.setDefault(previousZone)
        }
    }
}
