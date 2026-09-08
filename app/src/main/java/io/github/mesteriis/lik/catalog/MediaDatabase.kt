package io.github.mesteriis.lik.catalog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MediaRecord::class], version = 1, exportSchema = true)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun media(): MediaDao

    companion object {
        @Volatile private var instance: MediaDatabase? = null

        fun get(context: Context): MediaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MediaDatabase::class.java, "media.db")
                .build().also { instance = it }
        }
    }
}
