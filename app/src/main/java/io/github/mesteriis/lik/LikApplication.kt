package io.github.mesteriis.lik

import android.app.Application
import io.github.mesteriis.lik.catalog.TrashMaintenance
import io.github.mesteriis.lik.ai.*

class LikApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (android.os.Process.isIsolated()) return
        TrashMaintenance.schedule(this)
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
        }, "lik-ai-recovery").start()
    }
}
