package app.sharedclips.work

import android.content.ContentUris
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

        val cutoff = inputData.getLong(KEY_CUTOFF_SEC, 0).takeIf { it > 0 } ?: cutoffSec(days)
        val scanner = MediaStoreScanner(applicationContext)
        val expired = scanner.findExpired(cutoff)
        if (expired.isEmpty()) {
            Notifications.cancelDeletePrompt(applicationContext)
            return Result.success()
        }

        // Without All files access a background job cannot trash other apps' files; the notification asks.
        val mover = MediaMover(applicationContext)
        val uris = expired.map { it.uri }
        val trashed = if (mover.hasAllFilesAccess()) mover.trashDirectly(uris) else 0
        val log = StringBuilder("Auto-delete: ${uris.size} older than ${Notifications.dayCount(days)}, $trashed trashed")
        val left = scanner.inTikTokDownloads(expired.map { it.id })
        if (left.isNotEmpty()) {
            val result = Notifications.showDeletePrompt(applicationContext, left.map { ContentUris.parseId(it) }, days)
            log.append(", ${left.size} left ${result.log}")
        } else {
            Notifications.cancelDeletePrompt(applicationContext)
        }
        ActivityLog.add(applicationContext, log.toString())
        return Result.success()
    }

    companion object {
        /** Test hook: the cutoff (epoch seconds) to use instead of now minus N days. */
        const val KEY_CUTOFF_SEC = "cutoff_sec"

        fun cutoffSec(days: Int): Long =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - TimeUnit.DAYS.toSeconds(days.toLong())
    }
}
