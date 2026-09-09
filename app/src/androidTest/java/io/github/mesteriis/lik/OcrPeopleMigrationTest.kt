package io.github.mesteriis.lik

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.ai.PersonIdentityRecord
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
            val db=Room.databaseBuilder(context,MediaDatabase::class.java,name).addMigrations(MediaDatabase.MIGRATION_6_7).build()
            try{assertEquals(7,db.openHelper.writableDatabase.version);db.ocrPeople().savePerson(PersonIdentityRecord("p","Ирина",1));assertEquals("Ирина",db.ocrPeople().person("p")!!.name);val tables=mutableSetOf<String>();db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use{c->while(c.moveToNext())tables+=c.getString(0)};assertTrue(tables.containsAll(setOf("ai_ocr_result","ai_feature_media_run","ai_face_detection","person_identity","person_face_decision","person_merge","person_cannot_link")))}finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }
}
