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
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN sortAt INTEGER NOT NULL DEFAULT -9223372036854775808")
                for (column in PERIOD_COLUMNS) db.execSQL("ALTER TABLE media ADD COLUMN $column TEXT NOT NULL DEFAULT 'undated'")
                db.execSQL("ALTER TABLE media ADD COLUMN exifRevision INTEGER")
                db.execSQL("ALTER TABLE media ADD COLUMN exifOrientation INTEGER")
                db.execSQL("ALTER TABLE media ADD COLUMN scanMarker TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE media SET sortAt = COALESCE(takenAt, addedAt, -9223372036854775808)")
                db.execSQL("CREATE INDEX index_media_availability_sortAt_mediaId ON media(availability, sortAt, mediaId)")
                for (column in PERIOD_COLUMNS) db.execSQL("CREATE INDEX index_media_availability_${column}_sortAt_mediaId ON media(availability, $column, sortAt, mediaId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS volume_checkpoint (volume TEXT NOT NULL PRIMARY KEY, version TEXT NOT NULL, generation INTEGER NOT NULL, fullAccess INTEGER NOT NULL)")
            }
        }
    }
}
