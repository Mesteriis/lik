package io.github.mesteriis.lik.catalog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MediaRecord::class, VolumeCheckpoint::class], version = 2, exportSchema = true)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun media(): MediaDao

    companion object {
        @Volatile private var instance: MediaDatabase? = null

        fun get(context: Context): MediaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MediaDatabase::class.java, "media.db")
                .addMigrations(migration1To2(context))
                .build().also { instance = it }
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
