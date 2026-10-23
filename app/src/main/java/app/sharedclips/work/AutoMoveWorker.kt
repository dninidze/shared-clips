package app.sharedclips.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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
        } catch (e: Throwable) {
            // Never fail: the re-armed watcher below is queued behind this run and would fail with it.
            ActivityLog.add(applicationContext, "Auto-move failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            if (CleanupSettings(applicationContext).autoMove) {
                if (recheck) {
                    // Safety net: restarts the watcher if it was lost; leaves a waiting one alone.
                    CleanupScheduler.watchForNewVideos(applicationContext, ExistingWorkPolicy.KEEP)
                } else {
                    // Content-URI triggers fire once, so the watcher re-arms itself for the next download.
                    CleanupScheduler.watchForNewVideos(applicationContext)
                }
            }
        }
        return Result.success()
    }

    private suspend fun moveNewVideos(recheck: Boolean) {
        val context = applicationContext
        val scanner = MediaStoreScanner(context)
        val nowMs = System.currentTimeMillis()
        // Only confirmed matches move automatically; "not sure" ones wait for the user.
        val (found, unsure) = scanner.scanCameraFolder(addedAfterSec = nowMs / 1000 - RECENT_WINDOW_SEC)
            .partition { it.detection.verdict == Verdict.TIKTOK }
        if (found.isEmpty()) {
            if (unsure.isNotEmpty()) {
                ActivityLog.add(
                    context,
                    "Video change: ${unsure.size} recent video(s) might be TikTok, left for the home screen" +
                        unsure.joinToString("") { "\n  ${it.describe(nowMs)}" },
                )
            }
            return
        }

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
                try {
                    log.append("\n  Moved ${mover.moveOutOfCamera(uris)} directly (All files access)")
                } catch (e: Exception) {
                    // For example MediaProvider restarting; the notification below still offers the move.
                    log.append("\n  Direct move failed: ${e.javaClass.simpleName}: ${e.message}")
                }
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
