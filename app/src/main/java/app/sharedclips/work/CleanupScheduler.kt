package app.sharedclips.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.sharedclips.data.CleanupSettings
import java.time.Duration
import java.util.concurrent.TimeUnit

object CleanupScheduler {
    private const val WATCH = "watch-new-videos"
    private const val EXPIRY = "expire-old-videos"
    private const val RECHECK = "recheck-new-videos"

    /** Applies the current settings: starts or stops the watcher and the daily expiry job. */
    fun apply(context: Context) {
        val settings = CleanupSettings(context)
        val wm = WorkManager.getInstance(context)
        if (settings.autoMove) {
            watchForNewVideos(context, ExistingWorkPolicy.KEEP)
        } else {
            wm.cancelUniqueWork(WATCH)
            wm.cancelUniqueWork(RECHECK)
        }
        if (settings.deleteAfterDays > 0) {
            wm.enqueueUniquePeriodicWork(
                EXPIRY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ExpiryWorker>(1, TimeUnit.DAYS).build(),
            )
        } else {
            wm.cancelUniqueWork(EXPIRY)
        }
    }

    /**
     * One-shot job that runs a few seconds after any video is added or changed. The worker re-arms
     * itself with APPEND_OR_REPLACE so it does not cancel its own run.
     */
    fun watchForNewVideos(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(2))
            .setTriggerContentMaxDelay(Duration.ofSeconds(10))
            .build()
        val request = OneTimeWorkRequestBuilder<AutoMoveWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WATCH, policy, request)
    }

    /**
     * Looks again after [delayMs], once videos that were still being saved should be finished.
     * From the watcher, REPLACE pushes the re-check back after each new change, so it runs once the
     * saving app has gone quiet. From a running re-check, APPEND_OR_REPLACE queues the next one
     * without cancelling the current run.
     */
    fun recheckLater(context: Context, delayMs: Long, fromRecheck: Boolean) {
        val request = OneTimeWorkRequestBuilder<AutoMoveWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(AutoMoveWorker.KEY_RECHECK to true))
            .build()
        val policy = if (fromRecheck) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(RECHECK, policy, request)
    }
}
