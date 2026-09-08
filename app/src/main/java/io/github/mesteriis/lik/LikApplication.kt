package io.github.mesteriis.lik

import android.app.Application
import io.github.mesteriis.lik.catalog.TrashMaintenance

class LikApplication : Application() {
    override fun onCreate() { super.onCreate(); TrashMaintenance.schedule(this) }
}
