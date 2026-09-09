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

    @Query("SELECT m.* FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (:includeProtected=1 OR x.exposure='SAFE') ORDER BY m.sortAt DESC,m.mediaId DESC")
    fun visible(includeProtected:Boolean):List<MediaRecord>

    @Query("UPDATE media SET availability = :availability WHERE source = :source AND availability NOT IN ('TRASHED', 'PURGING')")
    fun markSource(source: MediaSource, availability: MediaAvailability)

    @Query("UPDATE media SET availability = 'MISSING' WHERE source = 'GOOGLE_IMPORT' AND availability NOT IN ('TRASHED', 'PURGING') AND (scanMarker IS NULL OR scanMarker != :stamp)")
    fun reconcileImports(stamp: String)

    @Query("UPDATE media SET accessGrantEpoch = CASE WHEN availability != 'AVAILABLE' THEN accessGrantEpoch + 1 ELSE accessGrantEpoch END, availability = 'AVAILABLE', lastSeenAt = :now, scanMarker = :stamp WHERE mediaId = :id AND source = 'GOOGLE_IMPORT' AND availability NOT IN ('TRASHED', 'PURGING')")
    fun markImportSeen(id: String, now: Long, stamp: String)

    @Query("UPDATE media SET accessGrantEpoch = CASE WHEN availability != 'AVAILABLE' THEN accessGrantEpoch + 1 ELSE accessGrantEpoch END, availability = 'AVAILABLE', lastSeenAt = :now WHERE mediaId = :id AND availability NOT IN ('TRASHED', 'PURGING')")
    fun markSeen(id: String, now: Long)

    @Query("SELECT * FROM media WHERE source = 'GOOGLE_IMPORT' AND availability = 'TRASHED' ORDER BY trashedAt DESC, mediaId LIMIT :limit OFFSET :offset")
    fun trashPage(limit: Int = 60, offset: Int = 0): List<MediaRecord>
    @Query("SELECT m.* FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.source='GOOGLE_IMPORT' AND m.availability='TRASHED' AND (:includeProtected=1 OR x.exposure='SAFE') ORDER BY m.trashedAt DESC,m.mediaId LIMIT :limit OFFSET :offset")
    fun visibleTrashPage(includeProtected:Boolean,limit:Int=60,offset:Int=0):List<MediaRecord>

    @Query("UPDATE media SET availability = 'TRASHED', trashedAt = :now WHERE mediaId IN (:ids) AND source = 'GOOGLE_IMPORT' AND availability = 'AVAILABLE' AND privateFileId IS NOT NULL")
    fun trash(ids: Set<String>, now: Long): Int

    @Query("UPDATE media SET availability = 'AVAILABLE', trashedAt = NULL WHERE mediaId = :id AND source = 'GOOGLE_IMPORT' AND availability = 'TRASHED'")
    fun restoreTrash(id: String): Int

    @Query("UPDATE media SET availability = 'PURGING' WHERE source = 'GOOGLE_IMPORT' AND availability = 'TRASHED' AND trashedAt <= :cutoff")
    fun claimExpired(cutoff: Long)

    @Query("UPDATE media SET availability = 'PURGING' WHERE mediaId IN (:ids) AND source = 'GOOGLE_IMPORT' AND availability = 'TRASHED'")
    fun claimPurge(ids: Set<String>)

    @Query("SELECT * FROM media WHERE source = 'GOOGLE_IMPORT' AND availability = 'PURGING' LIMIT 60")
    fun pendingPurge(): List<MediaRecord>

    @Query("DELETE FROM media WHERE mediaId = :id AND source = 'GOOGLE_IMPORT' AND availability = 'PURGING'")
    fun finishPurge(id: String)

    @Query("SELECT * FROM volume_checkpoint WHERE volume = :volume")
    fun checkpoint(volume: String): VolumeCheckpoint?

    @Upsert fun saveCheckpoint(checkpoint: VolumeCheckpoint)

    @Query("UPDATE media SET availability = CASE WHEN volumeVersion = :version THEN :absent ELSE 'INACCESSIBLE' END WHERE source = 'DEVICE' AND volumeName = :volume AND scanMarker != :stamp")
    fun reconcileVolume(volume: String, version: String, stamp: String, absent: MediaAvailability)

    @Query("UPDATE media SET accessGrantEpoch = CASE WHEN availability != 'AVAILABLE' THEN accessGrantEpoch + 1 ELSE accessGrantEpoch END, availability = 'AVAILABLE', lastSeenAt = :now, scanMarker = :stamp WHERE mediaId = :id")
    fun markScanSeen(id: String, stamp: String, now: Long)

    @Query("UPDATE media SET availability = 'INACCESSIBLE' WHERE source = 'DEVICE' AND volumeName NOT IN (:volumes)")
    fun detachedVolumes(volumes: List<String>)

    @Query("SELECT COUNT(*) FROM media WHERE availability = 'AVAILABLE'")
    fun availableCount(): Int

    @Query("SELECT * FROM media WHERE availability = 'AVAILABLE' ORDER BY sortAt DESC, mediaId DESC LIMIT :limit OFFSET :offset")
    fun page(limit: Int, offset: Int): List<MediaRecord>

    @Query("SELECT * FROM media WHERE availability = 'AVAILABLE' ORDER BY sortAt DESC, mediaId DESC")
    fun feed(): PagingSource<Int, MediaRecord>

    @Query("SELECT m.* FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (:includeProtected=1 OR x.exposure='SAFE') ORDER BY m.sortAt DESC,m.mediaId DESC")
    fun visibleFeed(includeProtected:Boolean):PagingSource<Int,MediaRecord>

    @Query("SELECT m.* FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (:includeProtected=1 OR x.exposure='SAFE') AND (m.sortAt<:at OR (m.sortAt=:at AND m.mediaId<:id)) ORDER BY m.sortAt DESC,m.mediaId DESC LIMIT 1")
    fun visibleNext(id:String,at:Long,includeProtected:Boolean):MediaRecord?

    @Query("SELECT m.* FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (:includeProtected=1 OR x.exposure='SAFE') AND (m.sortAt>:at OR (m.sortAt=:at AND m.mediaId>:id)) ORDER BY m.sortAt ASC,m.mediaId ASC LIMIT 1")
    fun visiblePrevious(id:String,at:Long,includeProtected:Boolean):MediaRecord?

    @Query("SELECT COUNT(*) FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (:includeProtected=1 OR x.exposure='SAFE') AND (m.sortAt>:at OR (m.sortAt=:at AND m.mediaId>:id))")
    fun visibleRank(id:String,at:Long,includeProtected:Boolean):Int

    @Query("SELECT COUNT(*) FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (:includeProtected=1 OR x.exposure='SAFE') AND m.dayKey=:day")
    fun visibleDayCount(day:String,includeProtected:Boolean):Int

    @Query("SELECT COUNT(*) FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (:includeProtected=1 OR x.exposure='SAFE') AND m.dayKey=:day AND (m.sortAt>:at OR (m.sortAt=:at AND m.mediaId>:id))")
    fun visibleDayPosition(id:String,at:Long,day:String,includeProtected:Boolean):Int

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

    fun visibleCovers(column:String,key:String,includeProtected:Boolean):List<MediaRecord>{
        require(column in PERIOD_COLUMNS)
        return mediaRows(SimpleSQLiteQuery("SELECT m.* FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND (?=1 OR x.exposure='SAFE') AND m.$column=? ORDER BY m.sortAt DESC,m.mediaId DESC LIMIT 3",arrayOf<Any>(if(includeProtected)1 else 0,key)))
    }

    fun visiblePeriodRank(column:String,at:Long,includeProtected:Boolean):Int{
        require(column in PERIOD_COLUMNS)
        val visible=if(includeProtected)"1=1" else "x.exposure='SAFE'"
        return integer(SimpleSQLiteQuery("SELECT COUNT(*) FROM (SELECT MAX(m.sortAt) newest FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND $visible GROUP BY m.$column HAVING newest>(SELECT MAX(a.sortAt) FROM media a LEFT JOIN ai_media_exposure ax ON ax.mediaId=a.mediaId AND ax.contentRevision=a.contentRevision WHERE a.availability='AVAILABLE' AND ${if(includeProtected)"1=1" else "ax.exposure='SAFE'"} AND a.$column=(SELECT b.$column FROM media b LEFT JOIN ai_media_exposure bx ON bx.mediaId=b.mediaId AND bx.contentRevision=b.contentRevision WHERE b.availability='AVAILABLE' AND ${if(includeProtected)"1=1" else "bx.exposure='SAFE'"} AND b.sortAt<=? ORDER BY b.sortAt DESC,b.mediaId DESC LIMIT 1)))",arrayOf(at)))
    }
}

val PERIOD_COLUMNS = setOf("dayKey", "weekKey", "monthKey", "yearKey")
fun periodSql(column: String): String {
    require(column in PERIOD_COLUMNS)
    return "SELECT $column AS periodKey, COUNT(*) AS count, MAX(sortAt) AS newestAt FROM media WHERE availability = 'AVAILABLE' GROUP BY $column ORDER BY newestAt DESC, periodKey DESC"
}

fun visiblePeriodSql(column:String,includeProtected:Boolean):String{
    require(column in PERIOD_COLUMNS)
    val visible=if(includeProtected)"1=1" else "x.exposure='SAFE'"
    return "SELECT m.$column AS periodKey,COUNT(*) AS count,MAX(m.sortAt) AS newestAt FROM media m LEFT JOIN ai_media_exposure x ON x.mediaId=m.mediaId AND x.contentRevision=m.contentRevision WHERE m.availability='AVAILABLE' AND $visible GROUP BY m.$column ORDER BY newestAt DESC,periodKey DESC"
}
