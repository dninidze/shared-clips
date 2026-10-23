package app.sharedclips.work

import android.content.ContentUris
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import app.sharedclips.action.MediaMover
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.AppAccess
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
        val recent = scanner.scanCameraFolder(addedAfterSec = nowMs / 1000 - RECENT_WINDOW_SEC)
        // Only confirmed matches move automatically; "not sure" ones wait for the user.
        val (tiktok, unsure) = recent.partition { it.detection.verdict == Verdict.TIKTOK }
        // An entry TikTok never wrote to (a failed save) is neither waited for nor moved.
        val (abandoned, found) = tiktok.partition { it.isAbandoned(nowMs) }
        val (ready, saving) = found.partition { it.isSettled(nowMs) }

        val log = StringBuilder(if (recheck) "Re-check" else "Video change")
        // What this run saw, without ages and sizes: a run that sees the same as the last one is not
        // logged again, so frequent unrelated video changes do not push the history out of the log.
        val seen = StringBuilder()
        if (found.isNotEmpty()) {
            log.append(": ${found.size} TikTok video(s) in Camera, ${ready.size} saved, ${saving.size} still saving")
        } else if (unsure.isNotEmpty() || abandoned.isNotEmpty()) {
            log.append(": no confirmed TikTok video to move")
        }
        if (AppAccess.isThrottled(context)) {
            log.append(" (Android is holding this app back: standby bucket ${AppAccess.standbyBucket(context)})")
        }
        for (video in found + abandoned) {
            log.append("\n  ").append(video.describe(nowMs))
            seen.append(video.id).append(if (video in ready) ":ready " else if (video in saving) ":saving " else ":empty ")
            // TikTok only looks for its earlier copy in Camera, so after a move it saves a new one there.
            if (scanner.inTikTokDownloads(video.displayName)) log.append(" | saved again, already in TikTok Downloads")
        }
        for (video in unsure) {
            log.append("\n  might be TikTok, left for the home screen: ").append(video.describe(nowMs))
            seen.append(video.id).append(":unsure ")
        }

        if (saving.isNotEmpty()) {
            val waitMs = saving.maxOf { it.msUntilSettled(nowMs) } + RECHECK_MARGIN_MS
            CleanupScheduler.recheckLater(context, waitMs, fromRecheck = recheck)
            log.append("\n  Checking again in ${waitMs / 1000}s")
        } else if (!recheck) {
            CleanupScheduler.catchUpSoon(context)
        }

        var acted = false
        if (ready.isNotEmpty()) {
            val uris = ready.map { it.uri }
            val mover = MediaMover(context)
            if (mover.hasAllFilesAccess()) {
                try {
                    val moved = mover.moveOutOfCamera(uris)
                    log.append("\n  Moved $moved directly (All files access)")
                    acted = moved > 0
                } catch (e: Exception) {
                    // For example MediaProvider restarting; the notification below still offers the move.
                    log.append("\n  Direct move failed: ${e.javaClass.simpleName}: ${e.message}")
                    acted = true
                }
            } else {
                log.append("\n  Needs a tap: All files access is off")
            }
            val left = scanner.leftInCamera(uris)
            if (left.isNotEmpty()) {
                val result = Notifications.showMovePrompt(context, left.map { ContentUris.parseId(it) })
                log.append("\n  ${left.size} left in Camera ${result.log}")
                acted = acted || result == Notifications.Result.POSTED
            } else {
                Notifications.cancelMovePrompt(context)
            }
        } else if (saving.isEmpty()) {
            // Nothing recent left to announce: an open prompt is out of date.
            Notifications.cancelMovePrompt(context)
        }

        val signature = if (seen.isEmpty()) "" else "${if (recheck) "R" else "V"} $seen"
        Log.i(TAG, "Auto-move run: ${if (recheck) "re-check" else "video change"}, saw ${seen.ifEmpty { "nothing" }}")
        val isNew = LastLog(context).changed(signature)
        if ((isNew && seen.isNotEmpty()) || acted) ActivityLog.add(context, log.toString())
    }

    /** Remembers what the previous logged run saw. */
    private class LastLog(context: Context) {
        private val prefs = context.getSharedPreferences("auto_move", Context.MODE_PRIVATE)

        fun changed(signature: String): Boolean {
            if (prefs.getString(KEY, null) == signature) return false
            prefs.edit().putString(KEY, signature).apply()
            return true
        }

        companion object {
            const val KEY = "last_logged"
        }
    }

    companion object {
        const val KEY_RECHECK = "recheck"

        /** Only videos added this recently are considered; older ones are left to the home screen. */
        const val RECENT_WINDOW_SEC = 24 * 60 * 60L
        private const val RECHECK_MARGIN_MS = 2_000L
        private const val TAG = "SharedClips"
    }
}
