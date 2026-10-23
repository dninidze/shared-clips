package app.sharedclips.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import app.sharedclips.action.MediaMover
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.MediaStoreScanner
import java.util.concurrent.TimeUnit

/** Daily: trashes videos in TikTok Downloads saved more than N days ago. */
class ExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = CleanupSettings(applicationContext)
        // Safety net for automatic mode: restarts the new-video watcher if it was ever lost.
        if (settings.autoMove) CleanupScheduler.watchForNewVideos(applicationContext, ExistingWorkPolicy.KEEP)
        val days = settings.deleteAfterDays
        if (days <= 0) return Result.success()

        val scanner = MediaStoreScanner(applicationContext)
        val uris = scanner.findExpired(cutoffSec(days)).map { it.uri }
        if (uris.isEmpty()) return Result.success()

        // Without All files access a background job cannot trash other apps' files; the notification asks.
        val mover = MediaMover(applicationContext)
        if (mover.hasAllFilesAccess()) mover.trashDirectly(uris)
        val left = scanner.countExisting(uris)
        if (left > 0) Notifications.showDeletePrompt(applicationContext, left, days)
        ActivityLog.add(
            applicationContext,
            "Auto-delete: ${uris.size} older than $days days, ${uris.size - left} trashed" +
                if (left > 0) ", $left left -> notification" else "",
        )
        return Result.success()
    }

    companion object {
        fun cutoffSec(days: Int): Long =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - TimeUnit.DAYS.toSeconds(days.toLong())
    }
}
