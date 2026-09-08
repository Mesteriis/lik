package io.github.mesteriis.lik

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.MediaDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogSchemaMigrationTest {
    @Test fun versionOneUpgradesWithoutLosingMetadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${System.nanoTime()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/1.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
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
                sqlite.execSQL("INSERT INTO media(mediaId, source, sourceKey, volumeName, volumeVersion, generationAdded, dateSource, contentRevision, availability, lastSeenAt, displayName, takenAt) VALUES('old', 'DEVICE', '1', 'primary', 'v1', 1, 'MEDIASTORE_TAKEN', 3, 'INACCESSIBLE', 8, 'Сохранено', 10000)")
                sqlite.version = 1
            }
            val migrated = Room.databaseBuilder(context, MediaDatabase::class.java, name).addMigrations(MediaDatabase.MIGRATION_1_2).build()
            try {
                val row = migrated.media().get("old")!!
                assertEquals("Сохранено", row.displayName)
                assertEquals(10000L, row.sortAt)
                assertNull(row.exifRevision)
                assertEquals(1, migrated.media().all().size)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
