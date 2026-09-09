package io.github.mesteriis.lik

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.similarity.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SimilarityMigrationTest {
    @Test fun versionThirteenUpgradePreservesExactDigestsAndInvalidatesVisualReadiness(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext;val name="similarity-v13-${System.nanoTime()}.db";val path=context.getDatabasePath(name).apply{parentFile!!.mkdirs()}
        val schema=JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/13.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        try{
            SQLiteDatabase.openOrCreateDatabase(path,null).use{sqlite->
                val entities=schema.getJSONArray("entities");for(index in 0 until entities.length()){val entity=entities.getJSONObject(index);val table=entity.getString("tableName");sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table));val indices=entity.optJSONArray("indices")?:JSONArray();for(i in 0 until indices.length())sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",table))}
                val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO content_fingerprint VALUES('a',1,1,'same',2,X'0000000000000000',1,1),('b',1,1,'same',2,X'0100000000000000',1,1)")
                sqlite.execSQL("INSERT INTO similarity_relation VALUES('a','b',1,1,1,1,'VISUAL',2,1,1)")
                sqlite.execSQL("INSERT INTO similarity_scan VALUES('a',1,1,2,'01',1,1)");sqlite.version=13
            }
            val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addMigrations(MediaDatabase.MIGRATION_13_14).build();try{
                val sql=db.openHelper.writableDatabase;fun count(table:String)=sql.query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();it.getInt(0)}
                assertEquals(14,sql.version);assertEquals(2,count("content_fingerprint"));assertEquals(0,count("similarity_relation"));assertEquals(0,count("similarity_scan"));assertEquals(0,db.similarity().fingerprint("a")!!.relationsRevision)
            }finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }
    @Test fun versionTenUpgradePreservesCatalogAndCreatesEmptyRegenerableSimilarityState(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext;val name="similarity-migration-${System.nanoTime()}.db";val path=context.getDatabasePath(name).apply{parentFile!!.mkdirs()}
        val schema=JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/10.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        try{
            SQLiteDatabase.openOrCreateDatabase(path,null).use{sqlite->
                val entities=schema.getJSONArray("entities");for(index in 0 until entities.length()){val entity=entities.getJSONObject(index);val table=entity.getString("tableName");sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table));val indices=entity.optJSONArray("indices")?:JSONArray();for(i in 0 until indices.length())sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",table))}
                val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sqlite.execSQL(setup.getString(i));sqlite.execSQL("INSERT INTO album VALUES('kept','Семья')");sqlite.version=10
            }
            val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addMigrations(MediaDatabase.MIGRATION_10_11, MediaDatabase.MIGRATION_11_12, MediaDatabase.MIGRATION_12_13, MediaDatabase.MIGRATION_13_14).build()
            try{assertEquals("Семья",db.organization().albums().single().name);assertTrue(db.similarity().visibleRelations(10,0).isEmpty());db.similarity().saveCheckpoint(SimilarityCheckpoint(checkpointMediaId=null,completed=0,total=0,status=SimilarityWorkStatus.COMPLETE,updatedAt=1));assertNotNull(db.similarity().checkpoint());assertEquals(14,db.openHelper.writableDatabase.version)}finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }

    @Test fun versionElevenUpgradeInvalidatesIncompleteBandsAndEdgesAndAddsDurableScan(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext;val name="similarity-v11-${System.nanoTime()}.db";val path=context.getDatabasePath(name).apply{parentFile!!.mkdirs()}
        val schema=JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/11.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        try{
            SQLiteDatabase.openOrCreateDatabase(path,null).use{sqlite->
                val entities=schema.getJSONArray("entities");for(index in 0 until entities.length()){val entity=entities.getJSONObject(index);val table=entity.getString("tableName");sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table));val indices=entity.optJSONArray("indices")?:JSONArray();for(i in 0 until indices.length())sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",table))}
                val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO content_fingerprint VALUES('a',1,2,'sha',1,X'0000000000000000',3,1)")
                sqlite.execSQL("INSERT INTO fingerprint_band VALUES('a',1,0,0)")
                sqlite.execSQL("INSERT INTO similarity_relation VALUES('a','b',1,1,2,2,'VISUAL',1,1,3)")
                sqlite.execSQL("INSERT INTO similarity_checkpoint VALUES('default','a',1,1,'COMPLETE',3,NULL)");sqlite.version=11
            }
            val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addMigrations(MediaDatabase.MIGRATION_11_12, MediaDatabase.MIGRATION_12_13, MediaDatabase.MIGRATION_13_14).build()
            try{
                val sql=db.openHelper.writableDatabase
                fun count(table:String)=sql.query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();it.getInt(0)}
                assertEquals(0,count("fingerprint_band"));assertEquals(0,count("similarity_relation"));assertEquals(0,db.similarity().progress().completed)
                db.similarity().saveScan(SimilarityScanRecord("a",1,2,PerceptualFingerprintV2.VERSION,"",0,4));assertNotNull(db.similarity().scan("a"));assertEquals(14,sql.version)
                val revision=db.similarity().libraryRevision();val trigger=MediaRecord("trigger",MediaSource.DEVICE,"trigger",lastSeenAt=1);db.media().upsert(trigger);db.ocrPeople().saveExposure(AiMediaExposureRecord("trigger",0,AiExposure.SAFE,1));assertEquals(revision+2,db.similarity().libraryRevision())
            }finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }
}
