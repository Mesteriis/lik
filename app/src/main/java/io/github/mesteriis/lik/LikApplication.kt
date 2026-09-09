package io.github.mesteriis.lik

import android.app.Application
import io.github.mesteriis.lik.catalog.TrashMaintenance
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.privacy.*

class LikApplication : Application() {
    private var startedActivities=0
    @Volatile private var revealedMediaDomain:String?=null
    private var privacySubscription:AutoCloseable?=null
    private var aiCatalogScheduler:AiCatalogScheduler?=null
    private val similarityEligibilityObserver=object:androidx.room.InvalidationTracker.Observer("media","ai_media_exposure"){
        override fun onInvalidated(tables:Set<String>){io.github.mesteriis.lik.similarity.SimilarityWorker.enqueue(this@LikApplication)}
    }
    override fun onCreate() {
        super.onCreate()
        if (android.os.Process.isIsolated()) return
        SensitiveMediaSession.current.relock(RevealRelockReason.PROCESS_START)
        registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks{
            override fun onActivityStarted(activity:android.app.Activity){startedActivities++}
            override fun onActivityStopped(activity:android.app.Activity){
                startedActivities=(startedActivities-1).coerceAtLeast(0)
                android.os.Handler(mainLooper).post{
                    if(startedActivities==0)SensitiveMediaSession.current.relock(RevealRelockReason.BACKGROUND)
                }
            }
            override fun onActivityCreated(a:android.app.Activity,b:android.os.Bundle?)=Unit
            override fun onActivityResumed(a:android.app.Activity)=Unit
            override fun onActivityPaused(a:android.app.Activity)=Unit
            override fun onActivitySaveInstanceState(a:android.app.Activity,b:android.os.Bundle)=Unit
            override fun onActivityDestroyed(a:android.app.Activity)=Unit
        })
        registerReceiver(object:android.content.BroadcastReceiver(){
            override fun onReceive(context:android.content.Context,intent:android.content.Intent){
                SensitiveMediaSession.current.relock(if(intent.action==android.content.Intent.ACTION_SCREEN_OFF)RevealRelockReason.SCREEN_LOCK else RevealRelockReason.USER_SWITCH)
            }
        },android.content.IntentFilter().apply{addAction(android.content.Intent.ACTION_SCREEN_OFF);addAction(android.content.Intent.ACTION_USER_BACKGROUND)},RECEIVER_NOT_EXPORTED)
        TrashMaintenance.schedule(this)
        val mediaDatabase=io.github.mesteriis.lik.catalog.MediaDatabase.get(this)
        aiCatalogScheduler=AiCatalogScheduler(this,mediaDatabase)
        mediaDatabase.invalidationTracker.addObserver(similarityEligibilityObserver)
        mediaDatabase.invalidationTracker.addObserver(object:androidx.room.InvalidationTracker.Observer("media"){
            override fun onInvalidated(tables:Set<String>){
                val expected=revealedMediaDomain?:return
                if(mediaDomain(mediaDatabase)!=expected)SensitiveMediaSession.current.relock(RevealRelockReason.CONTENT_CHANGED)
            }
        })
        privacySubscription=SensitiveMediaSession.current.observe{snapshot->
            revealedMediaDomain=if(snapshot.revealed)mediaDomain(mediaDatabase)else null
        }
        io.github.mesteriis.lik.similarity.SimilarityWorker.schedule(this)
        io.github.mesteriis.lik.privacy.SensitiveClassifierWorker.enqueue(this)
        Thread({
            ModelMaintenance.recover(this)
            val state = ModelCatalog.get(this).snapshot()
            state.pending?.profile?.takeIf { state.profile(it).phase == ProfilePhase.SELF_TESTING }?.let {
                ProfileDownloadWorker.enqueueValidation(this, it)
                return@Thread
            }
            aiCatalogScheduler?.changed()
        }, "lik-ai-recovery").start()
    }

    override fun onTrimMemory(level:Int){
        if(level>=TRIM_MEMORY_UI_HIDDEN)SensitiveMediaSession.current.relock(RevealRelockReason.BACKGROUND)
        super.onTrimMemory(level)
    }

    private fun mediaDomain(database:io.github.mesteriis.lik.catalog.MediaDatabase):String{
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        database.openHelper.readableDatabase.query("SELECT mediaId,contentRevision,accessGrantEpoch,availability,COALESCE(trashedAt,-1) FROM media ORDER BY mediaId").use{cursor->
            while(cursor.moveToNext())for(column in 0 until cursor.columnCount){digest.update(cursor.getString(column).toByteArray());digest.update(0)}
        }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
}
