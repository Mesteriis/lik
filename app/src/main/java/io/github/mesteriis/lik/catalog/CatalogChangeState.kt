package io.github.mesteriis.lik.catalog

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase

/** A transactionally updated token for privacy and catalog presentation, including hidden/trash rows.
 * Similarity retains its separate SAFE-only domain and budget revision. */
@Entity(tableName="catalog_change_state")
data class CatalogChangeState(@PrimaryKey val stateId:String="default",val revision:Long=0)

object CatalogChanges {
    fun revision(database:MediaDatabase):Long = database.openHelper.readableDatabase.query(
        "SELECT COALESCE((SELECT revision FROM catalog_change_state WHERE stateId='default'),0)"
    ).use { it.moveToFirst(); it.getLong(0) }

    internal fun install(db:SupportSQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO catalog_change_state(stateId,revision) VALUES('default',0)")
        val tables=mapOf(
            "media" to listOf("mediaId","source","sourceKey","contentUri","privateFileId","contentRevision","accessGrantEpoch","availability","trashedAt","displayName","sortAt","bucketId","bucketName","relativePath"),
            "ai_media_exposure" to listOf("mediaId","contentRevision","exposure"),
            "sensitive_manual" to listOf("mediaId","contentRevision","decision"),
            "sensitive_automatic" to listOf("mediaId","contentRevision","accessEpoch","decision"),
            "person_cannot_link" to listOf("leftAnchorId","rightAnchorId"),
        )
        val body="UPDATE catalog_change_state SET revision=revision+1 WHERE stateId='default';"
        tables.forEach { (table,columns) ->
            listOf("INSERT","DELETE").forEach { action -> db.execSQL("CREATE TRIGGER IF NOT EXISTS catalog_${table}_${action.lowercase()} AFTER $action ON $table BEGIN $body END") }
            val changed=columns.joinToString(" OR "){"OLD.$it IS NOT NEW.$it"}
            db.execSQL("CREATE TRIGGER IF NOT EXISTS catalog_${table}_update AFTER UPDATE OF ${columns.joinToString(",")} ON $table WHEN $changed BEGIN $body END")
        }
    }
}
