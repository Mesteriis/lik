package io.github.mesteriis.lik

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.catalog.MediaDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveMigrationTest{
    @Test fun migrationKeepsLegacyExposureButNeverPromotesItIntoTrustedDecision(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val name="sensitive-migration-${System.nanoTime()}.db";val path=context.getDatabasePath(name).apply{parentFile!!.mkdirs()}
        val schema=JSONObject(instrumentation.context.assets.open("io.github.mesteriis.lik.catalog.MediaDatabase/14.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        try{
            SQLiteDatabase.openOrCreateDatabase(path,null).use{sqlite->
                val entities=schema.getJSONArray("entities")
                for(index in 0 until entities.length()){
                    val entity=entities.getJSONObject(index);val table=entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                    val indices=entity.optJSONArray("indices")?:JSONArray()
                    for(i in 0 until indices.length())sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",table))
                }
                val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO ai_media_exposure(mediaId,contentRevision,exposure,decidedAt) VALUES('old',0,'SAFE',1)")
                sqlite.version=14
            }
            val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addMigrations(MediaDatabase.MIGRATION_14_15, MediaDatabase.MIGRATION_15_16).build()
            try{
                val sql=db.openHelper.writableDatabase
                fun count(table:String)=sql.query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();it.getInt(0)}
                assertEquals(16,sql.version);assertEquals(0,count("sensitive_automatic"));assertEquals(0,count("sensitive_manual"));assertEquals(1,count("ai_media_exposure"))
                assertEquals("QUARANTINED",sql.query("SELECT exposure FROM ai_media_exposure WHERE mediaId='old'").use{it.moveToFirst();it.getString(0)})
            }finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }
}
