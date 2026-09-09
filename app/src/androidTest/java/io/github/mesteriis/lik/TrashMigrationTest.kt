package io.github.mesteriis.lik

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrashMigrationTest {
    @Test fun versionThreeUpgradePreservesExistingOrganizationAndStartsUntrashed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "trash-migration-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/3.json")
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
                for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO media(mediaId, source, sourceKey, volumeName, volumeVersion, generationAdded, dateSource, contentRevision, availability, lastSeenAt) VALUES('import', 'GOOGLE_IMPORT', 'sha', '', '', 0, 'UNKNOWN', 1, 'AVAILABLE', 1)")
                sqlite.execSQL("INSERT INTO album VALUES('album', 'Семья')")
                sqlite.execSQL("INSERT INTO album_media VALUES('album', 'import')")
                sqlite.execSQL("INSERT INTO favorite VALUES('import')")
                sqlite.execSQL("INSERT INTO media_tag VALUES('import', 'лето', 'Лето')")
                sqlite.version = 3
            }
            val db = Room.databaseBuilder(context, MediaDatabase::class.java, name).addMigrations(MediaDatabase.MIGRATION_3_4, MediaDatabase.MIGRATION_4_5, MediaDatabase.MIGRATION_5_6, MediaDatabase.MIGRATION_6_7, MediaDatabase.MIGRATION_7_8, MediaDatabase.MIGRATION_8_9).build()
            try {
                assertNull(db.media().get("import")!!.trashedAt)
                assertEquals("import", db.organization().search(CatalogSearch(albumId = "album", favorites = true, tag = "лето").query()).single().mediaId)
                assertEquals(9, db.openHelper.writableDatabase.version)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
