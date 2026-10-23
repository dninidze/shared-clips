package app.sharedclips.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sharedclips.action.MediaMover
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.MediaStoreScanner
import app.sharedclips.detect.Verdict

/**
 * Runs whenever MediaStore reports a video change. Moves confirmed TikTok videos out of Camera
 * silently when "Media management" allows it, otherwise posts a one-tap notification.
 */
class AutoMoveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = CleanupSettings(applicationContext)
        if (!settings.autoMove) return Result.success()
        try {
            val scanner = MediaStoreScanner(applicationContext)
            // Only confirmed matches move automatically; "not sure" ones wait for the user.
            val uris = scanner.scanCameraFolder()
                .filter { it.detection.verdict == Verdict.TIKTOK }
                .map { it.uri }
            if (uris.isNotEmpty()) {
                val mover = MediaMover(applicationContext)
                val request = mover.writeRequest(uris)
                if (request != null && mover.sendSilently(request)) mover.moveOutOfCamera(uris)
                val left = scanner.stillInCamera(uris)
                if (left > 0) Notifications.showMovePrompt(applicationContext, left)
            }
        } finally {
            // Content-URI triggers fire once, so watch again for the next download.
            CleanupScheduler.watchForNewVideos(applicationContext)
        }
        return Result.success()
    }
}
