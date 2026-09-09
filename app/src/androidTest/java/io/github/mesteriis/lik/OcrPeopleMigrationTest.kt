package io.github.mesteriis.lik

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.catalog.MediaDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OcrPeopleMigrationTest {
    @Test fun versionSixUpgradeCreatesGeneratedAndManualStoresWithoutChangingExistingRows(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext;val name="ocr-people-migration-${System.nanoTime()}.db";val path=context.getDatabasePath(name).apply{parentFile!!.mkdirs()}
        val schema=JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/6.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        try{
            SQLiteDatabase.openOrCreateDatabase(path,null).use{sqlite->val entities=schema.getJSONArray("entities");for(i in 0 until entities.length()){val entity=entities.getJSONObject(i);val table=entity.getString("tableName");sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table));val indices=entity.optJSONArray("indices")?:JSONArray();for(j in 0 until indices.length())sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))};val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sqlite.execSQL(setup.getString(i));sqlite.version=6}
            val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addMigrations(MediaDatabase.MIGRATION_6_7,MediaDatabase.MIGRATION_7_8,MediaDatabase.MIGRATION_8_9, MediaDatabase.MIGRATION_9_10).build()
            try{assertEquals(10,db.openHelper.writableDatabase.version);db.ocrPeople().savePerson(PersonIdentityRecord("p","Ирина",1));assertEquals("Ирина",db.ocrPeople().person("p")!!.name);val tables=mutableSetOf<String>();db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use{c->while(c.moveToNext())tables+=c.getString(0)};assertTrue(tables.containsAll(setOf("ai_ocr_result","ai_feature_media_run","ai_face_detection","person_identity","person_face_decision","person_merge","person_cannot_link","person_cannot_link_owner","person_split")))}finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }

    @Test fun versionEightUpgradePreservesCannotLinkProvenanceAsIndependentOwners(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext;val name="cannot-owner-migration-${System.nanoTime()}.db";val path=context.getDatabasePath(name).apply{parentFile!!.mkdirs()}
        val schema=JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/8.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        try{
            SQLiteDatabase.openOrCreateDatabase(path,null).use{sqlite->val entities=schema.getJSONArray("entities");for(i in 0 until entities.length()){val entity=entities.getJSONObject(i);val table=entity.getString("tableName");sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table));val indices=entity.optJSONArray("indices")?:JSONArray();for(j in 0 until indices.length())sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))};val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sqlite.execSQL(setup.getString(i));sqlite.execSQL("INSERT INTO person_cannot_link(leftAnchorId,rightAnchorId,updatedAt,splitPersonId) VALUES('a','b',1,'person')");sqlite.version=8}
            val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addMigrations(MediaDatabase.MIGRATION_8_9, MediaDatabase.MIGRATION_9_10).build()
            try{assertEquals(10,db.openHelper.writableDatabase.version);assertEquals(1,db.ocrPeople().cannotLinkOwnerCount("a","b"));assertEquals("split:person",db.ocrPeople().cannotLinkOwners("split:person").single().ownerId)}finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }

    @Test fun versionNineUpgradeMaterializesNamedAndMergedAutoIdentitiesAndSurvivesRecluster(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext;val name="person-v10-${System.nanoTime()}.db";val path=context.getDatabasePath(name).apply{parentFile!!.mkdirs()}
        val schema=JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/9.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        try{
            SQLiteDatabase.openOrCreateDatabase(path,null).use{sqlite->
                val entities=schema.getJSONArray("entities");for(i in 0 until entities.length()){val entity=entities.getJSONObject(i);val table=entity.getString("tableName");sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table));val indices=entity.optJSONArray("indices")?:JSONArray();for(j in 0 until indices.length())sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))};val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO media(mediaId,source,sourceKey,volumeName,volumeVersion,generationAdded,dateSource,contentRevision,availability,lastSeenAt,accessGrantEpoch) VALUES('m','DEVICE','1','primary','v',1,'UNKNOWN',0,'AVAILABLE',1,1)")
                sqlite.execSQL("INSERT INTO ai_media_exposure(mediaId,contentRevision,exposure,decidedAt) VALUES('m',0,'SAFE',1)")
                sqlite.execSQL("INSERT INTO ai_index_generation(generationId,profileId,feature,pipelineFingerprint,status,completed,total,createdAt) VALUES('g1','balanced-v1','PEOPLE','face','COMPLETE',1,1,1)")
                fun face(id:String,anchor:String,cluster:String){sqlite.execSQL("INSERT INTO ai_face_detection(detectionId,generationId,mediaId,contentRevision,accessEpoch,pipelineFingerprint,anchorId,`left`,`top`,`right`,`bottom`,landmarks,embedding,confidence,computedClusterId) VALUES(?,'g1','m',0,1,'face',?,.1,.1,.4,.4,X'00',X'00',.99,?)",arrayOf(id,anchor,cluster))}
                face("d-a","a-old","auto:a-old");face("d-b","b-stable","auto:a-old");face("d-c","c-old","auto:c-old")
                sqlite.execSQL("INSERT INTO person_identity(personId,name,createdAt) VALUES('auto:a-old','Анна',11),('auto:c-old','Слияние',12),('manual','Ручная',13)")
                sqlite.execSQL("INSERT INTO person_merge(fromPersonId,intoPersonId,updatedAt) VALUES('auto:c-old','auto:a-old',20)")
                sqlite.execSQL("INSERT INTO person_face_decision(anchorId,decision,personId,updatedAt) VALUES('a-old','EXCLUDE',NULL,30),('c-old','ASSIGN','manual',31)")
                sqlite.version=9
            }
            val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addMigrations(MediaDatabase.MIGRATION_9_10).build()
            try{
                assertEquals(10,db.openHelper.writableDatabase.version)
                val stableA=LegacyPersonIdentity.stableId("auto:a-old");val stableC=LegacyPersonIdentity.stableId("auto:c-old")
                assertEquals("Анна",db.ocrPeople().person(stableA)?.name);assertEquals("Слияние",db.ocrPeople().person(stableC)?.name)
                assertTrue(db.ocrPeople().people().none{it.personId.startsWith("auto:")})
                assertEquals(stableA,db.ocrPeople().decisions().single{it.anchorId=="b-stable"}.personId)
                assertEquals(ManualFaceDecision.EXCLUDE,db.ocrPeople().decisions().single{it.anchorId=="a-old"}.decision)
                assertEquals("manual",db.ocrPeople().decisions().single{it.anchorId=="c-old"}.personId)
                assertEquals(stableA,db.ocrPeople().merges().single{it.fromPersonId==stableC}.intoPersonId)
                db.aiIndexes().saveGeneration(AiIndexGenerationRecord("g2","balanced-v1","PEOPLE","face",GenerationStatus.COMPLETE,1,1,"m",null,2))
                val bytes=ByteArray(8);listOf("0-new","b-stable").forEachIndexed{i,anchor->db.ocrPeople().saveFace(AiFaceDetectionRecord("n$i","g2","m",0,1,"face",anchor,.1f,.1f,.4f,.4f,bytes,bytes,.99f,"auto:0-new"))}
                assertEquals(stableA,PeopleRepository(db).groups("g2").single().personId)
            }finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }
}
