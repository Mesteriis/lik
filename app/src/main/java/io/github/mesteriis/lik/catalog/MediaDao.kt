package io.github.mesteriis.lik.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(record: MediaRecord): Long

    @Upsert
    fun upsert(record: MediaRecord)

    @Query("SELECT * FROM media WHERE mediaId = :id")
    fun get(id: String): MediaRecord?

    @Query("SELECT * FROM media ORDER BY mediaId")
    fun all(): List<MediaRecord>

    @Query("SELECT * FROM media WHERE source = :source")
    fun bySource(source: MediaSource): List<MediaRecord>

    @Query("SELECT * FROM media WHERE availability = 'AVAILABLE' ORDER BY COALESCE(takenAt, addedAt, -9223372036854775808) DESC, mediaId DESC")
    fun available(): List<MediaRecord>

    @Query("UPDATE media SET availability = :availability WHERE source = :source")
    fun markSource(source: MediaSource, availability: MediaAvailability)

    @Query("UPDATE media SET availability = 'AVAILABLE', lastSeenAt = :now WHERE mediaId = :id")
    fun markSeen(id: String, now: Long)
}
