package io.github.mesteriis.lik.ai

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class ProfileDownloadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val profile = inputData.getString(PROFILE)?.let(ProfileId::fromWire) ?: return Result.failure()
        val result = ModelDownloader(applicationContext).install(profile) { progress ->
            setProgressAsync(workDataOf("completed" to progress.completed, "total" to progress.total, "file" to progress.file))
        }
        return result.fold({ Result.success() }, { if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to it.message)) })
    }

    companion object {
        const val PROFILE = "profile"
        fun enqueue(context: Context, profile: ProfileId) {
            java.io.File(context.filesDir, "ai/staging/${profile.wire}/control").delete()
            val request = OneTimeWorkRequestBuilder<ProfileDownloadWorker>()
                .setInputData(workDataOf(PROFILE to profile.wire))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("ai-profile-${profile.wire}", ExistingWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context, profile: ProfileId) = WorkManager.getInstance(context).cancelUniqueWork("ai-profile-${profile.wire}")
    }
}
