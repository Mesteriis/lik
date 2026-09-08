package io.github.mesteriis.lik.catalog

import android.content.Context
import androidx.work.*
import io.github.mesteriis.lik.exports.PhotoExport
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.util.concurrent.TimeUnit

class TrashMaintenance(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        TrashRepository(MediaDatabase.get(applicationContext), PhotoLibrary.store(applicationContext)).purgeExpired()
        PhotoExport(applicationContext).cleanup()
        Result.success()
    } catch (_: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork("trash-start", ExistingWorkPolicy.KEEP, OneTimeWorkRequest.Builder(TrashMaintenance::class.java).build())
            manager.enqueueUniquePeriodicWork("trash-retention", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequest.Builder(TrashMaintenance::class.java, 24, TimeUnit.HOURS).build())
        }
    }
}
