package io.github.mesteriis.lik.catalog

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Preserve user spelling; only comparison keys use NFC and locale-independent Unicode lowercase. */
fun searchKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)

@Entity(tableName = "album")
data class Album(@PrimaryKey val albumId: String, val name: String)

@Entity(tableName = "album_media", primaryKeys = ["albumId", "mediaId"],
    foreignKeys = [ForeignKey(entity = Album::class, parentColumns = ["albumId"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaRecord::class, parentColumns = ["mediaId"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mediaId")])
data class AlbumMedia(val albumId: String, val mediaId: String)

@Entity(tableName = "favorite", foreignKeys = [ForeignKey(entity = MediaRecord::class, parentColumns = ["mediaId"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE)])
data class Favorite(@PrimaryKey val mediaId: String)

@Entity(tableName = "media_tag", primaryKeys = ["mediaId", "tagKey"],
    foreignKeys = [ForeignKey(entity = MediaRecord::class, parentColumns = ["mediaId"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tagKey")])
data class MediaTag(val mediaId: String, val tagKey: String, val label: String)

data class AlbumSummary(val albumId: String, val name: String, val count: Int)
data class DeviceFolder(val volumeName: String, val bucketId: String, val relativePath: String, val name: String?, val count: Int)

enum class MediaOperation { ORGANIZE, SHARE, EXPORT, DELETE_COPY }
fun MediaRecord.allows(operation: MediaOperation): Boolean = availability == MediaAvailability.AVAILABLE && when (operation) {
    MediaOperation.ORGANIZE -> true
    MediaOperation.SHARE, MediaOperation.EXPORT -> if (source == MediaSource.DEVICE) contentUri != null else privateFileId != null
    MediaOperation.DELETE_COPY -> source == MediaSource.GOOGLE_IMPORT && privateFileId != null
}

data class CatalogSearch(
    val name: String = "", val from: Long? = null, val until: Long? = null,
    val source: MediaSource? = null, val tag: String = "", val albumId: String? = null,
    val favorites: Boolean = false, val folder: DeviceFolder? = null,
) {
    init { require(from == null || until == null || from < until) }

    /** Date interval is [from, until); undated rows only match an unbounded date filter. */
    fun query(limit: Int = 60, offset: Int = 0): SupportSQLiteQuery {
        require(limit in 1..120 && offset >= 0)
        val clauses = mutableListOf("m.availability = 'AVAILABLE'")
        val args = mutableListOf<Any>()
        fun condition(sql: String, value: Any) { clauses += sql; args += value }
        if (name.isNotEmpty()) condition("instr(m.displayNameSearch, ?) > 0", searchKey(name))
        from?.let { condition("COALESCE(m.takenAt, m.addedAt) >= ?", it) }
        until?.let { condition("COALESCE(m.takenAt, m.addedAt) < ?", it) }
        source?.let { condition("m.source = ?", it.name) }
        if (tag.isNotBlank()) condition("EXISTS (SELECT 1 FROM media_tag t WHERE t.mediaId = m.mediaId AND t.tagKey = ?)", searchKey(tag.trim()))
        albumId?.let { condition("EXISTS (SELECT 1 FROM album_media a WHERE a.mediaId = m.mediaId AND a.albumId = ?)", it) }
        if (favorites) clauses += "EXISTS (SELECT 1 FROM favorite f WHERE f.mediaId = m.mediaId)"
        folder?.let {
            clauses += "m.source = 'DEVICE'"
            condition("m.volumeName = ?", it.volumeName)
            condition("COALESCE(m.bucketId, '') = ?", it.bucketId)
            condition("COALESCE(m.relativePath, '') = ?", it.relativePath)
        }
        args += limit; args += offset
        return SimpleSQLiteQuery("SELECT m.* FROM media m WHERE ${clauses.joinToString(" AND ")} ORDER BY m.sortAt DESC, m.mediaId DESC LIMIT ? OFFSET ?", args.toTypedArray())
    }
}

@Dao
interface OrganizationDao {
    @Insert fun insertAlbum(album: Album)
    @Query("UPDATE album SET name = :name WHERE albumId = :id") fun renameAlbum(id: String, name: String)
    @Query("DELETE FROM album WHERE albumId = :id") fun deleteAlbum(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun addMember(member: AlbumMedia)
    @Query("DELETE FROM album_media WHERE albumId = :album AND mediaId = :media") fun removeMember(album: String, media: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun favorite(value: Favorite)
    @Query("DELETE FROM favorite WHERE mediaId = :id") fun unfavorite(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun addTag(value: MediaTag)
    @Query("DELETE FROM media_tag WHERE mediaId = :id AND tagKey = :key") fun removeTag(id: String, key: String)
    @Query("SELECT * FROM media_tag WHERE mediaId = :id ORDER BY tagKey") fun tags(id: String): List<MediaTag>
    @Query("SELECT a.albumId, a.name, COUNT(m.mediaId) AS count FROM album a LEFT JOIN album_media am ON am.albumId = a.albumId LEFT JOIN media m ON m.mediaId = am.mediaId AND m.availability = 'AVAILABLE' GROUP BY a.albumId ORDER BY a.name, a.albumId")
    fun albums(): List<AlbumSummary>
    @Query("SELECT volumeName, COALESCE(bucketId, '') AS bucketId, COALESCE(relativePath, '') AS relativePath, MAX(bucketName) AS name, COUNT(*) AS count FROM media WHERE source = 'DEVICE' AND availability = 'AVAILABLE' GROUP BY volumeName, COALESCE(bucketId, ''), COALESCE(relativePath, '') ORDER BY name, volumeName, relativePath, bucketId")
    fun folders(): List<DeviceFolder>
    @RawQuery(observedEntities = [MediaRecord::class, AlbumMedia::class, Favorite::class, MediaTag::class])
    fun search(query: SupportSQLiteQuery): List<MediaRecord>
}

/** Every action rechecks current catalog access inside its transaction, including mixed-source selections. */
class OrganizationRepository(private val database: MediaDatabase) {
    val dao get() = database.organization()
    fun createAlbum(name: String): String {
        require(name.isNotBlank())
        return UUID.randomUUID().toString().also { dao.insertAlbum(Album(it, name.trim())) }
    }
    fun eligible(ids: Set<String>, operation: MediaOperation): Set<String> = ids.mapNotNull { id ->
        database.media().get(id)?.takeIf { it.allows(operation) }?.mediaId
    }.toSet()
    fun organize(ids: Set<String>, action: (OrganizationDao, String) -> Unit): Int = database.runInTransaction<Int> {
        val eligible = eligible(ids, MediaOperation.ORGANIZE)
        eligible.forEach { action(dao, it) }
        eligible.size
    }
    fun tag(ids: Set<String>, label: String, add: Boolean = true): Int {
        require(label.isNotBlank())
        val trimmed = label.trim()
        return organize(ids) { dao, id ->
            if (add) dao.addTag(MediaTag(id, searchKey(trimmed), trimmed)) else dao.removeTag(id, searchKey(trimmed))
        }
    }
}
