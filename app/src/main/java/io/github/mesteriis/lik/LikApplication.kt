package io.github.mesteriis.lik

import android.app.Application
import io.github.mesteriis.lik.catalog.TrashMaintenance
import io.github.mesteriis.lik.ai.*

class LikApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (android.os.Process.isIsolated()) return
        TrashMaintenance.schedule(this)
        val state = ModelCatalog.get(this).snapshot()
        if (AiFeature.SEARCH in state.enabledFeatures) state.active?.let { AiIndexWorker.enqueue(this, it, manual = false) }
    }
}
