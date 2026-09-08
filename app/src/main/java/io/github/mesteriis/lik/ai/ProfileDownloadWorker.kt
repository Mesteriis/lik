package io.github.mesteriis.lik.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.*
import io.github.mesteriis.lik.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.concurrent.TimeUnit

/** Long-running foreground transfer; coroutine cancellation disconnects the blocking HTTP call. */
class ProfileDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val profile = inputData.getString(PROFILE)?.let(ProfileId::fromWire) ?: return Result.failure()
        setForeground(foreground(profile))
        val downloader = ModelDownloader(applicationContext)
        val result: kotlin.Result<Unit> = suspendCancellableCoroutine { continuation ->
            val future: java.util.concurrent.Future<kotlin.Result<Unit>> = downloadExecutor.submit(java.util.concurrent.Callable {
                downloader.install(profile) { progress ->
                    setProgressAsync(workDataOf("completed" to progress.completed, "total" to progress.total, "file" to progress.file))
                }
            })
            continuation.invokeOnCancellation { downloader.cancel(); future.cancel(true) }
            downloadExecutor.execute {
                val value: kotlin.Result<Unit> = try { future.get() }
                catch (error: Throwable) { kotlin.Result.failure<Unit>(error.cause ?: error) }
                if (continuation.isActive) continuation.resume(value)
            }
        }
        return result.fold({ Result.success() }, {
            if (runAttemptCount < 3) Result.retry() else {
                withContext(Dispatchers.IO) { downloader.abandon(profile) }
                Result.failure(workDataOf("error" to it.message))
            }
        })
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val profile = inputData.getString(PROFILE)?.let(ProfileId::fromWire) ?: ProfileId.BALANCED
        return foreground(profile)
    }

    private fun foreground(profile: ProfileId): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.ai_settings_title), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.ai_download_notification))
            .setContentText(applicationContext.getString(when (profile) {
                ProfileId.COMPACT -> R.string.ai_profile_compact
                ProfileId.BALANCED -> R.string.ai_profile_balanced
                ProfileId.EXTENDED -> R.string.ai_profile_extended
            }))
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID + profile.ordinal, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val PROFILE = "profile"
        private const val CHANNEL = "lik-ai-downloads"
        private const val NOTIFICATION_ID = 3010
        private val downloadExecutor = java.util.concurrent.Executors.newCachedThreadPool()
        fun enqueue(context: Context, profile: ProfileId) {
            val control = java.io.File(context.filesDir, "ai/staging/operations/${profile.wire}/control")
            if (control.delete()) control.parentFile?.let(DurableAiFiles::syncDirectory)
            val request = OneTimeWorkRequestBuilder<ProfileDownloadWorker>()
                .setInputData(workDataOf(PROFILE to profile.wire))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("ai-profile-${profile.wire}", ExistingWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context, profile: ProfileId) =
            WorkManager.getInstance(context).cancelUniqueWork("ai-profile-${profile.wire}")
    }
}

class ProfileAbandonWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val profile = inputData.getString(ProfileDownloadWorker.PROFILE)?.let(ProfileId::fromWire) ?: return Result.failure()
        return runCatching {
            ModelDownloader(applicationContext).abandon(profile)
            val catalog = ModelCatalog.get(applicationContext)
            catalog.update { state -> state.copy(
                revision = state.revision + 1,
                pending = state.pending?.takeUnless { it.profile == profile },
                profiles = state.profiles + (profile to if (state.active == profile) {
                    state.profile(profile).copy(phase = ProfilePhase.ACTIVE)
                } else ProfileState()),
            ) }
        }.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        fun enqueue(context: Context, profile: ProfileId) {
            val request = OneTimeWorkRequestBuilder<ProfileAbandonWorker>()
                .setInputData(workDataOf(ProfileDownloadWorker.PROFILE to profile.wire)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "ai-profile-abandon-${profile.wire}", ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}
