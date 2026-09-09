package io.github.mesteriis.lik

import android.app.Application
import io.github.mesteriis.lik.catalog.TrashMaintenance
import io.github.mesteriis.lik.ai.*

class LikApplication : Application() {
    private val similarityEligibilityObserver=object:androidx.room.InvalidationTracker.Observer("media","ai_media_exposure"){
        override fun onInvalidated(tables:Set<String>){io.github.mesteriis.lik.similarity.SimilarityWorker.enqueue(this@LikApplication)}
    }
    override fun onCreate() {
        super.onCreate()
        if (android.os.Process.isIsolated()) return
        TrashMaintenance.schedule(this)
        io.github.mesteriis.lik.catalog.MediaDatabase.get(this).invalidationTracker.addObserver(similarityEligibilityObserver)
        io.github.mesteriis.lik.similarity.SimilarityWorker.schedule(this)
        Thread({
            ModelMaintenance.recover(this)
            val state = ModelCatalog.get(this).snapshot()
            state.pending?.profile?.takeIf { state.profile(it).phase == ProfilePhase.SELF_TESTING }?.let {
                ProfileDownloadWorker.enqueueValidation(this, it)
                return@Thread
            }
            val requested = state.pending?.enabled ?: state.enabledFeatures
            val profile = state.pending?.profile ?: state.active
            if (AiFeature.SEARCH in requested) profile?.let { AiIndexWorker.enqueue(this, it, manual = false) }
            if (requested.intersect(setOf(AiFeature.OCR, AiFeature.PEOPLE)).isNotEmpty())
                profile?.let { OcrPeopleIndexWorker.enqueue(this, it, manual = false) }
        }, "lik-ai-recovery").start()
    }
}
