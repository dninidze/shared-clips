package app.sharedclips.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sharedclips.action.MediaMover
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.MediaStoreScanner
import java.util.concurrent.TimeUnit

/** Daily: trashes videos in TikTok Downloads saved more than N days ago. */
class ExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val days = CleanupSettings(applicationContext).deleteAfterDays
        if (days <= 0) return Result.success()

        val scanner = MediaStoreScanner(applicationContext)
        val uris = scanner.findExpired(cutoffSec(days)).map { it.uri }
        if (uris.isEmpty()) return Result.success()

        val mover = MediaMover(applicationContext)
        val request = mover.trashRequest(uris)
        if (request != null) mover.sendSilently(request)
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
