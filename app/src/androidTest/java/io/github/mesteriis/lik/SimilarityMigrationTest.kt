package io.github.mesteriis.lik

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.similarity.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SimilarityMigrationTest {
    @Test fun versionTenUpgradePreservesCatalogAndCreatesEmptyRegenerableSimilarityState(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext;val name="similarity-migration-${System.nanoTime()}.db";val path=context.getDatabasePath(name).apply{parentFile!!.mkdirs()}
        val schema=JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/10.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        try{
            SQLiteDatabase.openOrCreateDatabase(path,null).use{sqlite->
                val entities=schema.getJSONArray("entities");for(index in 0 until entities.length()){val entity=entities.getJSONObject(index);val table=entity.getString("tableName");sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table));val indices=entity.optJSONArray("indices")?:JSONArray();for(i in 0 until indices.length())sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",table))}
                val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sqlite.execSQL(setup.getString(i));sqlite.execSQL("INSERT INTO album VALUES('kept','Семья')");sqlite.version=10
            }
            val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addMigrations(MediaDatabase.MIGRATION_10_11, MediaDatabase.MIGRATION_11_12).build()
            try{assertEquals("Семья",db.organization().albums().single().name);assertTrue(db.similarity().visibleRelations(10,0).isEmpty());db.similarity().saveCheckpoint(SimilarityCheckpoint(checkpointMediaId=null,completed=0,total=0,status=SimilarityWorkStatus.COMPLETE,updatedAt=1));assertNotNull(db.similarity().checkpoint());assertEquals(12,db.openHelper.writableDatabase.version)}finally{db.close()}
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
            val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addMigrations(MediaDatabase.MIGRATION_11_12).build()
            try{
                val sql=db.openHelper.writableDatabase
                fun count(table:String)=sql.query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();it.getInt(0)}
                assertEquals(0,count("fingerprint_band"));assertEquals(0,count("similarity_relation"));assertEquals(0,db.similarity().progress().completed)
                db.similarity().saveScan(SimilarityScanRecord("a",1,2,PerceptualFingerprintV2.VERSION,"",0,4));assertNotNull(db.similarity().scan("a"));assertEquals(12,sql.version)
            }finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }
}
