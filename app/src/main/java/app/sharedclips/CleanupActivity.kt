package app.sharedclips

import android.app.Activity
import android.app.PendingIntent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import app.sharedclips.action.MediaMover
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.MediaStoreScanner
import app.sharedclips.detect.Verdict
import app.sharedclips.work.AutoMoveWorker
import app.sharedclips.work.ExpiryWorker
import app.sharedclips.work.Notifications
import kotlinx.coroutines.launch

/**
 * Invisible activity opened from a notification: runs one move or delete for the videos the
 * notification announced, then closes. Config changes do not recreate it (see the manifest); if the
 * process is restarted while the system dialog is up, the list is restored from the saved state.
 */
class CleanupActivity : ComponentActivity() {
    private val mover by lazy { MediaMover(this) }
    private val scanner by lazy { MediaStoreScanner(this) }
    private var pending: List<Uri> = emptyList()
    private var requestInFlight = false

    private val moveLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        requestInFlight = false
        lifecycleScope.launch {
            if (result.resultCode == Activity.RESULT_OK) {
                movePending()
            } else {
                ActivityLog.add(this@CleanupActivity, "  Notification move: permission dialog declined")
                finish()
            }
        }
    }

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        requestInFlight = false
        val approved = result.resultCode == Activity.RESULT_OK
        ActivityLog.add(this, "Notification delete: ${if (approved) "trashed ${pending.size}" else "declined"}")
        if (approved) {
            toast("Moved ${pending.size} videos to the trash")
            Notifications.cancelDeletePrompt(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            pending = BundleCompat.getParcelableArrayList(savedInstanceState, KEY_PENDING, Uri::class.java).orEmpty()
            requestInFlight = savedInstanceState.getBoolean(KEY_IN_FLIGHT)
            // The system dialog's result is delivered to the launchers above; otherwise nothing is left to do.
            if (!requestInFlight) finish()
            return
        }
        val announced = intent?.getLongArrayExtra(Notifications.EXTRA_IDS)?.toList()
        lifecycleScope.launch {
            when (intent?.action) {
                ACTION_MOVE_NEW -> prepareMove(announced)
                ACTION_DELETE_EXPIRED -> prepareTrash(announced)
                else -> finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(KEY_PENDING, ArrayList(pending))
        outState.putBoolean(KEY_IN_FLIGHT, requestInFlight)
    }

    private suspend fun prepareMove(announced: List<Long>?) {
        if (announced != null) Notifications.moveTapped(this, announced)
        val nowMs = System.currentTimeMillis()
        // The videos the notification announced; without that list, the same recent window the
        // automatic job uses, so older videos someone keeps in Camera are left alone.
        val found = if (announced != null) {
            scanner.scanCameraFolder(ids = announced)
        } else {
            scanner.scanCameraFolder(addedAfterSec = nowMs / 1000 - AutoMoveWorker.RECENT_WINDOW_SEC)
        }.filter { it.detection.verdict == Verdict.TIKTOK }
        // Never move a video its app is still writing: that is what leaves a copy behind.
        val (ready, saving) = found.filterNot { it.isAbandoned(nowMs) }.partition { it.isSettled(nowMs) }
        pending = ready.map { it.uri }
        ActivityLog.add(
            this,
            "Notification tapped: ${ready.size} saved, ${saving.size} still saving" +
                saving.joinToString("") { "\n  waiting: ${it.describe(nowMs)}" },
        )
        val autoMove = CleanupSettings(this).autoMove
        // Announced again, so the prompt that comes back for them once they are saved alerts too.
        if (saving.isNotEmpty() && autoMove) Notifications.announceAgain(this, saving.map { it.id })
        when {
            saving.isNotEmpty() -> toast(
                "${saving.size} video(s) still saving. " +
                    if (autoMove) "You'll get another notification when they're done." else "Use Move all in the app later."
            )
            ready.isEmpty() -> {
                toast("Nothing left to move")
                Notifications.withdrawMovePromptIfDone(this)
            }
        }
        when {
            pending.isEmpty() -> finish()
            mover.hasAllFilesAccess() -> movePending()
            else -> launch(mover.writeRequest(pending), moveLauncher)
        }
    }

    private suspend fun prepareTrash(announced: List<Long>?) {
        val days = CleanupSettings(this).deleteAfterDays
        pending = when {
            announced != null -> scanner.inTikTokDownloads(announced)
            days > 0 -> scanner.findExpired(ExpiryWorker.cutoffSec(days)).map { it.uri }
            else -> emptyList()
        }
        when {
            pending.isEmpty() -> {
                toast("Nothing left to delete")
                Notifications.cancelDeletePrompt(this)
                finish()
            }
            mover.hasAllFilesAccess() -> {
                val trashed = mover.trashDirectly(pending)
                ActivityLog.add(this, "Notification delete: trashed $trashed")
                toast("Moved $trashed videos to the trash")
                Notifications.cancelDeletePrompt(this)
                finish()
            }
            else -> launch(mover.trashRequest(pending), trashLauncher)
        }
    }

    private suspend fun movePending() {
        val moved = mover.moveOutOfCamera(pending)
        val left = scanner.stillInCamera(pending)
        ActivityLog.add(this, "  Notification move: moved $moved, $left left in Camera")
        toast("Moved $moved videos to TikTok Downloads")
        Notifications.withdrawMovePromptIfDone(this)
        finish()
    }

    private fun launch(request: PendingIntent?, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (request == null) {
            finish()
            return
        }
        requestInFlight = true
        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_MOVE_NEW = "app.sharedclips.MOVE_NEW"
        const val ACTION_DELETE_EXPIRED = "app.sharedclips.DELETE_EXPIRED"
        private const val KEY_PENDING = "pending"
        private const val KEY_IN_FLIGHT = "in_flight"
    }
}
