package io.github.mesteriis.lik.catalog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MediaRecord::class, VolumeCheckpoint::class, Album::class, AlbumMedia::class, Favorite::class, MediaTag::class,
    io.github.mesteriis.lik.ai.AiIndexGenerationRecord::class, io.github.mesteriis.lik.ai.AiEmbeddingRecord::class,
    io.github.mesteriis.lik.ai.AiSensitiveRunRecord::class], version = 6, exportSchema = true)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun organization(): OrganizationDao
    abstract fun aiIndexes(): io.github.mesteriis.lik.ai.AiIndexDao

    companion object {
        @Volatile private var instance: MediaDatabase? = null

        fun get(context: Context): MediaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MediaDatabase::class.java, "media.db")
                .addMigrations(migration1To2(context), MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build().also { instance = it }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_index_generation (generationId TEXT NOT NULL PRIMARY KEY, profileId TEXT NOT NULL, feature TEXT NOT NULL, pipelineFingerprint TEXT NOT NULL, status TEXT NOT NULL, completed INTEGER NOT NULL, total INTEGER NOT NULL, checkpointMediaId TEXT, error TEXT, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_index_generation_pipelineFingerprint ON ai_index_generation(pipelineFingerprint)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_embedding (generationId TEXT NOT NULL, mediaId TEXT NOT NULL, nativeKey INTEGER NOT NULL, contentRevision INTEGER NOT NULL, accessEpoch INTEGER NOT NULL, vector BLOB NOT NULL, PRIMARY KEY(generationId, mediaId), FOREIGN KEY(generationId) REFERENCES ai_index_generation(generationId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ai_embedding_generationId_nativeKey ON ai_embedding(generationId, nativeKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_embedding_mediaId ON ai_embedding(mediaId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_sensitive_run (mediaId TEXT NOT NULL, contentRevision INTEGER NOT NULL, pipelineFingerprint TEXT NOT NULL, status TEXT NOT NULL, rawOutput BLOB, error TEXT, evaluatedAt INTEGER NOT NULL, PRIMARY KEY(mediaId, contentRevision,pipelineFingerprint))")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN accessGrantEpoch INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE ai_embedding SET accessEpoch = COALESCE((SELECT accessGrantEpoch FROM media WHERE media.mediaId = ai_embedding.mediaId), 1)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN trashedAt INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN displayNameSearch TEXT NOT NULL DEFAULT ''")
                var lastId: String? = null
                while (true) {
                    val after = lastId
                    val batch = mutableListOf<Pair<String, String>>()
                    db.query("SELECT mediaId, displayName FROM media " + (if (after == null) "" else "WHERE mediaId > ? ") + "ORDER BY mediaId LIMIT 256",
                        if (after == null) emptyArray() else arrayOf(after)).use { cursor ->
                        while (cursor.moveToNext()) batch += cursor.getString(0) to searchKey(cursor.getString(1).orEmpty())
                    }
                    if (batch.isEmpty()) break
                    batch.forEach { (id, key) -> db.execSQL("UPDATE media SET displayNameSearch = ? WHERE mediaId = ?", arrayOf(key, id)) }
                    lastId = batch.last().first
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS album (albumId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS album_media (albumId TEXT NOT NULL, mediaId TEXT NOT NULL, PRIMARY KEY(albumId, mediaId), FOREIGN KEY(albumId) REFERENCES album(albumId) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(mediaId) REFERENCES media(mediaId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_album_media_mediaId ON album_media(mediaId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS favorite (mediaId TEXT NOT NULL PRIMARY KEY, FOREIGN KEY(mediaId) REFERENCES media(mediaId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS media_tag (mediaId TEXT NOT NULL, tagKey TEXT NOT NULL, label TEXT NOT NULL, PRIMARY KEY(mediaId, tagKey), FOREIGN KEY(mediaId) REFERENCES media(mediaId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_media_tag_tagKey ON media_tag(tagKey)")
            }
        }

        fun migration1To2(context: Context): Migration {
            val app = context.applicationContext
            return object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE media ADD COLUMN sortAt INTEGER NOT NULL DEFAULT -9223372036854775808")
                    for (column in PERIOD_COLUMNS) db.execSQL("ALTER TABLE media ADD COLUMN $column TEXT NOT NULL DEFAULT 'undated'")
                    db.execSQL("ALTER TABLE media ADD COLUMN exifRevision INTEGER")
                    db.execSQL("ALTER TABLE media ADD COLUMN exifOrientation INTEGER")
                    db.execSQL("ALTER TABLE media ADD COLUMN scanMarker TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE media SET sortAt = COALESCE(takenAt, addedAt, -9223372036854775808)")
                    // Room opens/upgrades inside a transaction, before any DAO or Paging query can run.
                    // Read bounded keyset batches and close each cursor before updating those rows.
                    val zone = libraryZone(app)
                    var lastId: String? = null
                    db.compileStatement("UPDATE media SET dayKey = ?, weekKey = ?, monthKey = ?, yearKey = ? WHERE mediaId = ?").use { update ->
                        while (true) {
                            val after = lastId
                            val batch = mutableListOf<Pair<String, Long>>()
                            db.query("SELECT mediaId, sortAt FROM media WHERE (takenAt IS NOT NULL OR addedAt IS NOT NULL) " +
                                (if (after == null) "" else "AND mediaId > ? ") + "ORDER BY mediaId LIMIT 256",
                                if (after == null) emptyArray() else arrayOf(after)).use { cursor ->
                                while (cursor.moveToNext()) batch += cursor.getString(0) to cursor.getLong(1)
                            }
                            if (batch.isEmpty()) break
                            for ((id, time) in batch) {
                                val date = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
                                update.bindString(1, date.toString())
                                update.bindString(2, date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString())
                                update.bindString(3, java.time.YearMonth.from(date).toString())
                                update.bindString(4, date.year.toString())
                                update.bindString(5, id)
                                update.executeUpdateDelete()
                            }
                            lastId = batch.last().first
                        }
                    }
                    db.execSQL("CREATE INDEX index_media_availability_sortAt_mediaId ON media(availability, sortAt, mediaId)")
                    for (column in PERIOD_COLUMNS) db.execSQL("CREATE INDEX index_media_availability_${column}_sortAt_mediaId ON media(availability, $column, sortAt, mediaId)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS volume_checkpoint (volume TEXT NOT NULL PRIMARY KEY, version TEXT NOT NULL, generation INTEGER NOT NULL, fullAccess INTEGER NOT NULL)")
                }
            }
        }
    }
}
