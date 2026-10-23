package app.sharedclips.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sharedclips.action.MediaMover
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.MediaStoreScanner
import app.sharedclips.detect.Verdict
import kotlinx.coroutines.CancellationException

/**
 * Runs whenever MediaStore reports a video change, and again once a video still being saved should
 * have finished. Moves confirmed TikTok videos that have finished saving out of Camera: directly when
 * the user granted "All files access", otherwise through a one-tap notification (a background job
 * cannot get a MediaStore write request granted).
 */
class AutoMoveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val recheck = inputData.getBoolean(KEY_RECHECK, false)
        try {
            if (CleanupSettings(applicationContext).autoMove) moveNewVideos(recheck)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActivityLog.add(applicationContext, "Auto-move failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // Content-URI triggers fire once, so the watcher re-arms itself for the next download.
            if (!recheck && CleanupSettings(applicationContext).autoMove) {
                CleanupScheduler.watchForNewVideos(applicationContext)
            }
        }
        return Result.success()
    }

    private suspend fun moveNewVideos(recheck: Boolean) {
        val context = applicationContext
        val scanner = MediaStoreScanner(context)
        val nowMs = System.currentTimeMillis()
        // Only confirmed matches move automatically; "not sure" ones wait for the user.
        val found = scanner.scanCameraFolder(addedAfterSec = nowMs / 1000 - RECENT_WINDOW_SEC)
            .filter { it.detection.verdict == Verdict.TIKTOK }
        if (found.isEmpty()) return

        val (ready, saving) = found.partition { it.isSettled(nowMs) }
        val log = StringBuilder(if (recheck) "Re-check" else "Video change")
            .append(": ${found.size} TikTok video(s) in Camera, ${ready.size} saved, ${saving.size} still saving")
        found.forEach { video ->
            log.append("\n  ").append(video.describe(nowMs))
            // TikTok only looks for its earlier copy in Camera, so after a move it saves a new one there.
            if (scanner.inTikTokDownloads(video.displayName)) log.append(" | saved again, already in TikTok Downloads")
        }

        if (saving.isNotEmpty()) {
            val waitMs = saving.maxOf { it.msUntilSettled(nowMs) } + RECHECK_MARGIN_MS
            CleanupScheduler.recheckLater(context, waitMs, fromRecheck = recheck)
            log.append("\n  Checking again in ${waitMs / 1000}s")
        }

        if (ready.isNotEmpty()) {
            val uris = ready.map { it.uri }
            val mover = MediaMover(context)
            if (mover.hasAllFilesAccess()) {
                val moved = mover.moveOutOfCamera(uris)
                log.append("\n  Moved $moved directly (All files access)")
            } else {
                log.append("\n  Needs a tap: All files access is off")
            }
            val left = scanner.stillInCamera(uris)
            if (left > 0) {
                Notifications.showMovePrompt(context, left)
                log.append("\n  $left left in Camera -> notification")
            }
        }
        ActivityLog.add(context, log.toString())
    }

    companion object {
        const val KEY_RECHECK = "recheck"

        /** Only videos added this recently are considered; older ones are left to the home screen. */
        private const val RECENT_WINDOW_SEC = 24 * 60 * 60L
        private const val RECHECK_MARGIN_MS = 2_000L
    }
}
