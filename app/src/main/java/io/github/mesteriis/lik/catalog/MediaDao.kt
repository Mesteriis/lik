package io.github.mesteriis.lik.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.paging.PagingSource

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

    @Query("SELECT * FROM volume_checkpoint WHERE volume = :volume")
    fun checkpoint(volume: String): VolumeCheckpoint?

    @Upsert fun saveCheckpoint(checkpoint: VolumeCheckpoint)

    @Query("UPDATE media SET availability = CASE WHEN volumeVersion = :version THEN :absent ELSE 'INACCESSIBLE' END WHERE source = 'DEVICE' AND volumeName = :volume AND scanMarker != :stamp")
    fun reconcileVolume(volume: String, version: String, stamp: String, absent: MediaAvailability)

    @Query("UPDATE media SET availability = 'AVAILABLE', lastSeenAt = :now, scanMarker = :stamp WHERE mediaId = :id")
    fun markScanSeen(id: String, stamp: String, now: Long)

    @Query("UPDATE media SET availability = 'INACCESSIBLE' WHERE source = 'DEVICE' AND volumeName NOT IN (:volumes)")
    fun detachedVolumes(volumes: List<String>)

    @Query("SELECT COUNT(*) FROM media WHERE availability = 'AVAILABLE'")
    fun availableCount(): Int

    @Query("SELECT * FROM media WHERE availability = 'AVAILABLE' ORDER BY sortAt DESC, mediaId DESC LIMIT :limit OFFSET :offset")
    fun page(limit: Int, offset: Int): List<MediaRecord>

    @Query("SELECT * FROM media WHERE availability = 'AVAILABLE' ORDER BY sortAt DESC, mediaId DESC")
    fun feed(): PagingSource<Int, MediaRecord>

    @Query("SELECT * FROM media WHERE availability = 'AVAILABLE' AND (sortAt < :at OR (sortAt = :at AND mediaId < :id)) ORDER BY sortAt DESC, mediaId DESC LIMIT 1")
    fun next(id: String, at: Long): MediaRecord?

    @Query("SELECT * FROM media WHERE availability = 'AVAILABLE' AND (sortAt > :at OR (sortAt = :at AND mediaId > :id)) ORDER BY sortAt ASC, mediaId ASC LIMIT 1")
    fun previous(id: String, at: Long): MediaRecord?

    @Query("SELECT COUNT(*) FROM media WHERE availability = 'AVAILABLE' AND (sortAt > :at OR (sortAt = :at AND mediaId > :id))")
    fun rank(id: String, at: Long): Int

    @Query("SELECT COUNT(*) FROM media WHERE availability = 'AVAILABLE' AND dayKey = :day")
    fun dayCount(day: String): Int

    @Query("SELECT COUNT(*) FROM media WHERE availability = 'AVAILABLE' AND dayKey = :day AND (sortAt > :at OR (sortAt = :at AND mediaId > :id))")
    fun dayPosition(id: String, at: Long, day: String): Int

    @RawQuery fun integer(query: SupportSQLiteQuery): Int

    fun periodRank(column: String, at: Long): Int {
        require(column in PERIOD_COLUMNS)
        return integer(SimpleSQLiteQuery("SELECT COUNT(*) FROM (SELECT MAX(sortAt) newest FROM media WHERE availability = 'AVAILABLE' GROUP BY $column HAVING newest > (SELECT MAX(sortAt) FROM media WHERE availability = 'AVAILABLE' AND $column = (SELECT $column FROM media WHERE availability = 'AVAILABLE' AND sortAt <= ? ORDER BY sortAt DESC LIMIT 1)))", arrayOf(at)))
    }

    @Query("SELECT * FROM media WHERE availability = 'AVAILABLE' AND source = 'GOOGLE_IMPORT' AND (exifRevision IS NULL OR exifRevision != contentRevision) LIMIT :limit")
    fun exifPending(limit: Int): List<MediaRecord>

    @RawQuery(observedEntities = [MediaRecord::class])
    fun periodFeed(query: SupportSQLiteQuery): PagingSource<Int, PeriodSummary>

    @RawQuery fun periodRows(query: SupportSQLiteQuery): List<PeriodSummary>
    @RawQuery fun mediaRows(query: SupportSQLiteQuery): List<MediaRecord>

    fun periods(column: String, limit: Int, offset: Int): List<PeriodSummary> =
        periodRows(SimpleSQLiteQuery(periodSql(column) + " LIMIT ? OFFSET ?", arrayOf(limit, offset)))

    fun covers(column: String, key: String): List<MediaRecord> {
        require(column in PERIOD_COLUMNS)
        return mediaRows(SimpleSQLiteQuery("SELECT * FROM media WHERE availability = 'AVAILABLE' AND $column = ? ORDER BY sortAt DESC, mediaId DESC LIMIT 3", arrayOf(key)))
    }
}

val PERIOD_COLUMNS = setOf("dayKey", "weekKey", "monthKey", "yearKey")
fun periodSql(column: String): String {
    require(column in PERIOD_COLUMNS)
    return "SELECT $column AS periodKey, COUNT(*) AS count, MAX(sortAt) AS newestAt FROM media WHERE availability = 'AVAILABLE' GROUP BY $column ORDER BY newestAt DESC, periodKey DESC"
}
