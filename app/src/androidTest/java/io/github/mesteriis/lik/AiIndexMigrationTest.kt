package io.github.mesteriis.lik

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.ai.AiIndexGenerationRecord
import io.github.mesteriis.lik.ai.GenerationStatus
import io.github.mesteriis.lik.catalog.MediaDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AiIndexMigrationTest {
    @Test fun versionFourUpgradePreservesOrganizationAndCreatesAiGenerationStore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "ai-index-migration-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets
            .open("io.github.mesteriis.lik.catalog.MediaDatabase/4.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: JSONArray()
                    for (i in 0 until indices.length()) {
                        sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO album VALUES('kept', 'Семья')")
                sqlite.version = 4
            }
            val db = Room.databaseBuilder(context, MediaDatabase::class.java, name)
                .addMigrations(MediaDatabase.MIGRATION_4_5, MediaDatabase.MIGRATION_5_6, MediaDatabase.MIGRATION_6_7, MediaDatabase.MIGRATION_7_8, MediaDatabase.MIGRATION_8_9).build()
            try {
                assertEquals("Семья", db.organization().albums().single().name)
                val generation = AiIndexGenerationRecord("g", "compact-v1", "SEARCH", "pipeline",
                    GenerationStatus.PREPARING, 0, 3, null, null, 1)
                db.aiIndexes().saveGeneration(generation)
                assertNotNull(db.aiIndexes().generation("g"))
                assertEquals(9, db.openHelper.writableDatabase.version)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionFiveUpgradeUsesStableGrantEpochForExistingEmbeddings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val name = "access-epoch-migration-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets
            .open("io.github.mesteriis.lik.catalog.MediaDatabase/5.json").bufferedReader().use { it.readText() })
            .getJSONObject("database")
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: JSONArray()
                    for (i in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO media(mediaId,source,sourceKey,volumeName,volumeVersion,generationAdded,dateSource,contentRevision,availability,lastSeenAt) VALUES('m','DEVICE','1','primary','v',1,'UNKNOWN',7,'AVAILABLE',999)")
                sqlite.execSQL("INSERT INTO ai_index_generation VALUES('g','compact-v1','SEARCH','p','PREPARING',1,1,'m',NULL,1)")
                sqlite.execSQL("INSERT INTO ai_embedding VALUES('g','m',1,7,999,X'00000000')")
                sqlite.version = 5
            }
            val db = Room.databaseBuilder(context, MediaDatabase::class.java, name).addMigrations(MediaDatabase.MIGRATION_5_6, MediaDatabase.MIGRATION_6_7, MediaDatabase.MIGRATION_7_8, MediaDatabase.MIGRATION_8_9).build()
            try {
                assertEquals(1L, db.media().get("m")!!.accessGrantEpoch)
                assertEquals(1L, db.aiIndexes().embedding("g", "m")!!.accessEpoch)
                assertEquals(1, db.aiIndexes().currentEmbeddingCount("g"))
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
