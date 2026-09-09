package io.github.mesteriis.lik

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrganizationMigrationTest {
    @Test fun versionTwoBackfillsUnicodeInBatchesAndRetriesAtomically() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "organization-migration-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/2.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (i in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
                repeat(270) { index ->
                    sqlite.execSQL("INSERT INTO media(mediaId, source, sourceKey, volumeName, volumeVersion, generationAdded, dateSource, contentRevision, availability, lastSeenAt, displayName) VALUES(?, 'DEVICE', ?, 'primary', 'v1', 1, 'UNKNOWN', 3, 'AVAILABLE', 8, ?)",
                        arrayOf("row-$index", "row-$index", if (index == 1) null else "Е\u0308ЛКА $index"))
                }
                sqlite.execSQL("CREATE TRIGGER interrupt_search BEFORE UPDATE ON media WHEN NEW.mediaId = 'row-99' BEGIN SELECT RAISE(ABORT, 'interrupted search migration'); END")
                sqlite.version = 2
            }
            val failed = Room.databaseBuilder(context, MediaDatabase::class.java, name).addMigrations(MediaDatabase.MIGRATION_2_3, MediaDatabase.MIGRATION_3_4, MediaDatabase.MIGRATION_4_5, MediaDatabase.MIGRATION_5_6, MediaDatabase.MIGRATION_6_7, MediaDatabase.MIGRATION_7_8, MediaDatabase.MIGRATION_8_9, MediaDatabase.MIGRATION_9_10, MediaDatabase.MIGRATION_10_11, MediaDatabase.MIGRATION_11_12, MediaDatabase.MIGRATION_12_13, MediaDatabase.MIGRATION_13_14, MediaDatabase.MIGRATION_14_15).build()
            try { failed.media().availableCount(); fail("Expected rollback") }
            catch (_: android.database.sqlite.SQLiteException) { }
            finally { failed.close() }
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                assertEquals(2, sqlite.version)
                sqlite.rawQuery("PRAGMA table_info(media)", null).use { cursor -> while (cursor.moveToNext()) assertNotEquals("displayNameSearch", cursor.getString(1)) }
                sqlite.execSQL("DROP TRIGGER interrupt_search")
            }
            val migrated = Room.databaseBuilder(context, MediaDatabase::class.java, name).addMigrations(MediaDatabase.MIGRATION_2_3, MediaDatabase.MIGRATION_3_4, MediaDatabase.MIGRATION_4_5, MediaDatabase.MIGRATION_5_6, MediaDatabase.MIGRATION_6_7, MediaDatabase.MIGRATION_7_8, MediaDatabase.MIGRATION_8_9, MediaDatabase.MIGRATION_9_10, MediaDatabase.MIGRATION_10_11, MediaDatabase.MIGRATION_11_12, MediaDatabase.MIGRATION_12_13, MediaDatabase.MIGRATION_13_14, MediaDatabase.MIGRATION_14_15).build()
            try {
                assertEquals(270, migrated.media().availableCount())
                assertEquals("ёлка 99", migrated.media().get("row-99")!!.displayNameSearch)
                assertEquals("", migrated.media().get("row-1")!!.displayNameSearch)
                assertEquals(listOf("row-99"), migrated.organization().search(CatalogSearch(name = "ёлка 99").query(includeProtected=true)).map { it.mediaId })
                migrated.ocrPeople().saveExposure(io.github.mesteriis.lik.ai.AiMediaExposureRecord("row-99",3,io.github.mesteriis.lik.ai.AiExposure.SAFE,1))
                val repo = OrganizationRepository(migrated)
                val album = repo.createAlbum("После обновления")
                repo.organize(setOf("row-99")) { dao, id -> dao.addMember(AlbumMedia(album, id)) }
                assertEquals(1, migrated.organization().albums().single().count)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
