package io.github.mesteriis.lik.catalog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MediaRecord::class, CatalogChangeState::class, VolumeCheckpoint::class, Album::class, AlbumMedia::class, Favorite::class, MediaTag::class,
    io.github.mesteriis.lik.ai.AiIndexGenerationRecord::class, io.github.mesteriis.lik.ai.AiEmbeddingRecord::class,
    io.github.mesteriis.lik.ai.AiSensitiveRunRecord::class, io.github.mesteriis.lik.ai.AiMediaExposureRecord::class,
    io.github.mesteriis.lik.ai.AiOcrResultRecord::class, io.github.mesteriis.lik.ai.AiFeatureMediaRunRecord::class,
    io.github.mesteriis.lik.ai.AiFaceDetectionRecord::class,
    io.github.mesteriis.lik.ai.PersonIdentityRecord::class, io.github.mesteriis.lik.ai.PersonFaceDecisionRecord::class,
    io.github.mesteriis.lik.ai.PersonMergeRecord::class, io.github.mesteriis.lik.ai.PersonCannotLinkRecord::class,
    io.github.mesteriis.lik.ai.PersonSplitRecord::class, io.github.mesteriis.lik.ai.PersonCannotLinkOwnerRecord::class,
    io.github.mesteriis.lik.similarity.ContentFingerprintRecord::class, io.github.mesteriis.lik.similarity.FingerprintFailureRecord::class,
    io.github.mesteriis.lik.similarity.FingerprintBandRecord::class,
    io.github.mesteriis.lik.similarity.SimilarityRelationRecord::class,
    io.github.mesteriis.lik.similarity.SimilarityScanRecord::class,
    io.github.mesteriis.lik.similarity.SimilarityCheckpoint::class,
    io.github.mesteriis.lik.similarity.SimilarityLibraryState::class,
    io.github.mesteriis.lik.privacy.SensitiveAutomaticRecord::class,
    io.github.mesteriis.lik.privacy.SensitiveManualRecord::class,
    io.github.mesteriis.lik.privacy.SensitiveClassifierRunRecord::class], version = 16, exportSchema = true)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun organization(): OrganizationDao
    abstract fun aiIndexes(): io.github.mesteriis.lik.ai.AiIndexDao
    abstract fun ocrPeople(): io.github.mesteriis.lik.ai.OcrPeopleDao
    abstract fun similarity(): io.github.mesteriis.lik.similarity.SimilarityDao
    abstract fun sensitiveMedia(): io.github.mesteriis.lik.privacy.SensitiveMediaDao

    companion object {
        @Volatile private var instance: MediaDatabase? = null

        fun get(context: Context): MediaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MediaDatabase::class.java, "media.db")
                .addMigrations(migration1To2(context), MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                .addCallback(SIMILARITY_CALLBACK)
                .build().also { instance = it }
        }

        internal val SIMILARITY_CALLBACK=object:RoomDatabase.Callback(){
            override fun onCreate(db:SupportSQLiteDatabase){ensureSimilarityLibraryState(db);CatalogChanges.install(db)}
            override fun onOpen(db:SupportSQLiteDatabase){ensureSimilarityLibraryState(db);CatalogChanges.install(db)}
        }

        val MIGRATION_15_16=object:Migration(15,16){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_change_state (stateId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_face_detection_generationId_detectionId ON ai_face_detection(generationId,detectionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_face_detection_generationId_anchorId ON ai_face_detection(generationId,anchorId)")
                CatalogChanges.install(db)
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sensitive_automatic (mediaId TEXT NOT NULL, contentRevision INTEGER NOT NULL, accessEpoch INTEGER NOT NULL, pipelineFingerprint TEXT NOT NULL, decision TEXT NOT NULL, rawOutput BLOB NOT NULL, decidedAt INTEGER NOT NULL, PRIMARY KEY(mediaId,contentRevision,accessEpoch))")
                db.execSQL("CREATE TABLE IF NOT EXISTS sensitive_manual (mediaId TEXT NOT NULL PRIMARY KEY, contentRevision INTEGER NOT NULL, decision TEXT NOT NULL, decidedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sensitive_classifier_run (mediaId TEXT NOT NULL, contentRevision INTEGER NOT NULL, accessEpoch INTEGER NOT NULL, pipelineFingerprint TEXT NOT NULL, outcome TEXT NOT NULL, rawOutput BLOB, error TEXT, evaluatedAt INTEGER NOT NULL, PRIMARY KEY(mediaId,contentRevision,accessEpoch,pipelineFingerprint))")
                // The earlier exposure table lacked model and access-epoch provenance. Its SAFE
                // values cannot cross the new visibility boundary without a trusted decision.
                db.execSQL("UPDATE ai_media_exposure SET exposure='QUARANTINED'")
            }
        }

        private fun ensureSimilarityLibraryState(db:SupportSQLiteDatabase){
            db.execSQL("INSERT OR IGNORE INTO similarity_library_state(stateId,revision) VALUES('default',0)")
            val body="UPDATE similarity_library_state SET revision=revision+1 WHERE stateId='default'; UPDATE similarity_checkpoint SET checkpointMediaId=CASE WHEN status='PAUSED' THEN checkpointMediaId ELSE NULL END,completed=CASE WHEN status='PAUSED' THEN completed ELSE 0 END,total=CASE WHEN status='PAUSED' THEN total ELSE 0 END,status=CASE WHEN status='PAUSED' THEN 'PAUSED' ELSE 'IDLE' END,updatedAt=CAST(strftime('%s','now') AS INTEGER)*1000,error=CASE WHEN status='PAUSED' THEN error ELSE NULL END,libraryRevision=(SELECT revision FROM similarity_library_state WHERE stateId='default') WHERE checkpointId='default';"
            listOf("media","exposure").forEach{label->
                listOf("insert","update","delete").forEach{db.execSQL("DROP TRIGGER IF EXISTS similarity_${label}_$it")}
            }
            val rules=io.github.mesteriis.lik.similarity.SimilarityDomainRevision
            db.execSQL("CREATE TRIGGER similarity_media_insert AFTER INSERT ON media WHEN ${rules.mediaInsertWhen} BEGIN $body END")
            db.execSQL("CREATE TRIGGER similarity_media_delete AFTER DELETE ON media WHEN ${rules.mediaDeleteWhen} BEGIN $body END")
            db.execSQL("CREATE TRIGGER similarity_media_update AFTER UPDATE OF ${rules.MEDIA_UPDATE_COLUMNS.joinToString(",")} ON media WHEN ${rules.mediaUpdateWhen} BEGIN $body END")
            db.execSQL("CREATE TRIGGER similarity_exposure_insert AFTER INSERT ON ai_media_exposure WHEN ${rules.exposureInsertWhen} BEGIN $body END")
            db.execSQL("CREATE TRIGGER similarity_exposure_delete AFTER DELETE ON ai_media_exposure WHEN ${rules.exposureDeleteWhen} BEGIN $body END")
            db.execSQL("CREATE TRIGGER similarity_exposure_update AFTER UPDATE OF ${rules.EXPOSURE_UPDATE_COLUMNS.joinToString(",")} ON ai_media_exposure WHEN ${rules.exposureUpdateWhen} BEGIN $body END")
        }

        val MIGRATION_12_13=object:Migration(12,13){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS similarity_library_state (stateId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO similarity_library_state(stateId,revision) VALUES('default',0)")
                db.execSQL("ALTER TABLE similarity_checkpoint ADD COLUMN libraryRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE similarity_checkpoint ADD COLUMN tranche INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE similarity_checkpoint ADD COLUMN comparisons INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE similarity_checkpoint ADD COLUMN continuations INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE similarity_checkpoint SET status='IDLE',checkpointMediaId=NULL,completed=0,total=0,error=NULL")
                ensureSimilarityLibraryState(db)
            }
        }

        val MIGRATION_13_14=object:Migration(13,14){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE content_fingerprint ADD COLUMN relationsRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE similarity_scan ADD COLUMN libraryRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE similarity_relation ADD COLUMN libraryRevision INTEGER NOT NULL DEFAULT 0")
                // Visual edges and cursors are regenerable. SHA rows remain available to exact groups.
                db.execSQL("DELETE FROM similarity_relation")
                db.execSQL("DELETE FROM similarity_scan")
                db.execSQL("UPDATE content_fingerprint SET relationsReady=0,relationsRevision=0")
                db.execSQL("UPDATE similarity_checkpoint SET status='IDLE',completed=0,total=0,checkpointMediaId=NULL,error=NULL WHERE status!='PAUSED'")
                ensureSimilarityLibraryState(db)
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS similarity_scan (mediaId TEXT NOT NULL PRIMARY KEY, contentRevision INTEGER NOT NULL, accessEpoch INTEGER NOT NULL, fingerprintVersion INTEGER NOT NULL, afterMediaId TEXT NOT NULL, examined INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                // Version 2 uses a provably complete 16-nibble multi-index. Old hints and edges are regenerable.
                db.execSQL("DELETE FROM fingerprint_band")
                db.execSQL("DELETE FROM similarity_relation")
                db.execSQL("UPDATE content_fingerprint SET relationsReady=0")
                db.execSQL("UPDATE similarity_checkpoint SET status='IDLE', completed=0, total=0, checkpointMediaId=NULL, error=NULL")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS content_fingerprint (mediaId TEXT NOT NULL PRIMARY KEY, contentRevision INTEGER NOT NULL, accessEpoch INTEGER NOT NULL, sha256 TEXT NOT NULL, perceptualVersion INTEGER NOT NULL, perceptualBits BLOB NOT NULL, computedAt INTEGER NOT NULL, relationsReady INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_content_fingerprint_sha256 ON content_fingerprint(sha256)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_content_fingerprint_perceptualVersion ON content_fingerprint(perceptualVersion)")
                db.execSQL("CREATE TABLE IF NOT EXISTS fingerprint_failure (mediaId TEXT NOT NULL, contentRevision INTEGER NOT NULL, accessEpoch INTEGER NOT NULL, perceptualVersion INTEGER NOT NULL, error TEXT NOT NULL, failedAt INTEGER NOT NULL, PRIMARY KEY(mediaId,contentRevision,accessEpoch,perceptualVersion))")
                db.execSQL("CREATE TABLE IF NOT EXISTS fingerprint_band (mediaId TEXT NOT NULL, perceptualVersion INTEGER NOT NULL, bandIndex INTEGER NOT NULL, bandValue INTEGER NOT NULL, PRIMARY KEY(mediaId,perceptualVersion,bandIndex))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_fingerprint_band_perceptualVersion_bandIndex_bandValue ON fingerprint_band(perceptualVersion,bandIndex,bandValue)")
                db.execSQL("CREATE TABLE IF NOT EXISTS similarity_relation (leftMediaId TEXT NOT NULL, rightMediaId TEXT NOT NULL, leftRevision INTEGER NOT NULL, rightRevision INTEGER NOT NULL, leftAccessEpoch INTEGER NOT NULL, rightAccessEpoch INTEGER NOT NULL, kind TEXT NOT NULL, fingerprintVersion INTEGER NOT NULL, distance INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(leftMediaId,rightMediaId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_similarity_relation_rightMediaId ON similarity_relation(rightMediaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_similarity_relation_kind ON similarity_relation(kind)")
                db.execSQL("CREATE TABLE IF NOT EXISTS similarity_checkpoint (checkpointId TEXT NOT NULL PRIMARY KEY, checkpointMediaId TEXT, completed INTEGER NOT NULL, total INTEGER NOT NULL, status TEXT NOT NULL, updatedAt INTEGER NOT NULL, error TEXT)")
            }
        }

        /** Materializes legacy computed `auto:*` identities before reclustering can rename their anchor. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val legacy = linkedSetOf<String>()
                listOf(
                    "SELECT personId FROM person_identity WHERE personId LIKE 'auto:%'",
                    "SELECT fromPersonId FROM person_merge WHERE fromPersonId LIKE 'auto:%'",
                    "SELECT intoPersonId FROM person_merge WHERE intoPersonId LIKE 'auto:%'",
                    "SELECT personId FROM person_face_decision WHERE personId LIKE 'auto:%'",
                    "SELECT splitPersonId FROM person_split WHERE splitPersonId LIKE 'auto:%'",
                    "SELECT fromPersonId FROM person_split WHERE fromPersonId LIKE 'auto:%'",
                ).forEach { sql -> db.query(sql).use { cursor -> while (cursor.moveToNext()) legacy += cursor.getString(0) } }
                if (legacy.isEmpty()) return
                val mapping = legacy.sorted().associateWith(io.github.mesteriis.lik.ai.LegacyPersonIdentity::stableId)
                mapping.forEach { (old, stable) ->
                    db.execSQL("INSERT OR IGNORE INTO person_identity(personId,name,createdAt) SELECT ?,name,createdAt FROM person_identity WHERE personId=?", arrayOf(stable, old))
                    db.execSQL("INSERT OR IGNORE INTO person_identity(personId,name,createdAt) VALUES(?,NULL,0)", arrayOf(stable))
                    db.execSQL("INSERT OR IGNORE INTO person_face_decision(anchorId,decision,personId,updatedAt) SELECT anchorId,'ASSIGN',?,0 FROM ai_face_detection WHERE computedClusterId=?", arrayOf(stable, old))
                    db.execSQL("UPDATE person_face_decision SET personId=? WHERE personId=?", arrayOf(stable, old))
                    db.execSQL("UPDATE person_split SET splitPersonId=? WHERE splitPersonId=?", arrayOf(stable, old))
                    db.execSQL("UPDATE person_split SET fromPersonId=? WHERE fromPersonId=?", arrayOf(stable, old))
                    db.execSQL("UPDATE person_cannot_link SET splitPersonId=? WHERE splitPersonId=?", arrayOf(stable, old))
                    db.execSQL("UPDATE person_cannot_link_owner SET ownerId=? WHERE ownerId=?", arrayOf("split:$stable", "split:$old"))
                }
                val merges = mutableListOf<Triple<String,String,Long>>()
                db.query("SELECT fromPersonId,intoPersonId,updatedAt FROM person_merge ORDER BY fromPersonId").use { cursor ->
                    while (cursor.moveToNext()) merges += Triple(mapping[cursor.getString(0)] ?: cursor.getString(0), mapping[cursor.getString(1)] ?: cursor.getString(1), cursor.getLong(2))
                }
                db.execSQL("DELETE FROM person_merge")
                merges.filter { it.first != it.second }.sortedWith(compareBy<Triple<String,String,Long>> { it.first }.thenByDescending { value -> value.third }).forEach { (from, into, at) ->
                    db.execSQL("INSERT OR IGNORE INTO person_merge(fromPersonId,intoPersonId,updatedAt) VALUES(?,?,?)", arrayOf<Any>(from, into, at))
                }
                mapping.keys.forEach { db.execSQL("DELETE FROM person_identity WHERE personId=?", arrayOf(it)) }
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS person_cannot_link_owner (leftAnchorId TEXT NOT NULL, rightAnchorId TEXT NOT NULL, ownerId TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(leftAnchorId,rightAnchorId,ownerId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_person_cannot_link_owner_ownerId ON person_cannot_link_owner(ownerId)")
                db.execSQL("INSERT OR IGNORE INTO person_cannot_link_owner(leftAnchorId,rightAnchorId,ownerId,updatedAt) SELECT leftAnchorId,rightAnchorId,CASE WHEN splitPersonId IS NULL THEN 'manual' ELSE 'split:' || splitPersonId END,updatedAt FROM person_cannot_link")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE person_cannot_link ADD COLUMN splitPersonId TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS person_split (splitPersonId TEXT NOT NULL PRIMARY KEY, fromPersonId TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_media_exposure (mediaId TEXT NOT NULL, contentRevision INTEGER NOT NULL, exposure TEXT NOT NULL, decidedAt INTEGER NOT NULL, PRIMARY KEY(mediaId,contentRevision))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_media_exposure_exposure ON ai_media_exposure(exposure)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_ocr_result (generationId TEXT NOT NULL, mediaId TEXT NOT NULL, contentRevision INTEGER NOT NULL, accessEpoch INTEGER NOT NULL, pipelineFingerprint TEXT NOT NULL, displayText TEXT NOT NULL, searchText TEXT NOT NULL, regionsJson TEXT NOT NULL, confidence REAL NOT NULL, PRIMARY KEY(generationId,mediaId), FOREIGN KEY(generationId) REFERENCES ai_index_generation(generationId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_ocr_result_mediaId ON ai_ocr_result(mediaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_ocr_result_searchText ON ai_ocr_result(searchText)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_feature_media_run (generationId TEXT NOT NULL, mediaId TEXT NOT NULL, feature TEXT NOT NULL, contentRevision INTEGER NOT NULL, accessEpoch INTEGER NOT NULL, error TEXT, PRIMARY KEY(generationId,mediaId), FOREIGN KEY(generationId) REFERENCES ai_index_generation(generationId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_feature_media_run_mediaId ON ai_feature_media_run(mediaId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_face_detection (detectionId TEXT NOT NULL PRIMARY KEY, generationId TEXT NOT NULL, mediaId TEXT NOT NULL, contentRevision INTEGER NOT NULL, accessEpoch INTEGER NOT NULL, pipelineFingerprint TEXT NOT NULL, anchorId TEXT NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, landmarks BLOB NOT NULL, embedding BLOB NOT NULL, confidence REAL NOT NULL, computedClusterId TEXT NOT NULL, FOREIGN KEY(generationId) REFERENCES ai_index_generation(generationId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_face_detection_generationId ON ai_face_detection(generationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_face_detection_mediaId ON ai_face_detection(mediaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_face_detection_anchorId ON ai_face_detection(anchorId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS person_identity (personId TEXT NOT NULL PRIMARY KEY, name TEXT, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS person_face_decision (anchorId TEXT NOT NULL PRIMARY KEY, decision TEXT NOT NULL, personId TEXT, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_person_face_decision_personId ON person_face_decision(personId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS person_merge (fromPersonId TEXT NOT NULL PRIMARY KEY, intoPersonId TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS person_cannot_link (leftAnchorId TEXT NOT NULL, rightAnchorId TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(leftAnchorId,rightAnchorId))")
            }
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
