package app.sharedclips

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.sharedclips.action.MediaMover
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.MediaStoreScanner
import app.sharedclips.detect.Verdict
import app.sharedclips.work.ExpiryWorker
import kotlinx.coroutines.launch

/** Invisible activity opened from a notification: runs one move or delete request, then closes. */
class CleanupActivity : ComponentActivity() {
    private val mover by lazy { MediaMover(this) }
    private val scanner by lazy { MediaStoreScanner(this) }
    private var pending: List<android.net.Uri> = emptyList()

    private val moveLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        lifecycleScope.launch {
            if (result.resultCode == Activity.RESULT_OK) {
                val moved = mover.moveOutOfCamera(pending)
                val left = scanner.stillInCamera(pending)
                ActivityLog.add(this@CleanupActivity, "  Notification move: moved $moved, $left left in Camera")
                toast("Moved $moved videos to TikTok Downloads")
            } else {
                ActivityLog.add(this@CleanupActivity, "  Notification move: permission dialog declined")
            }
            finish()
        }
    }

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val approved = result.resultCode == Activity.RESULT_OK
        ActivityLog.add(this, "  Notification delete: ${if (approved) "trashed ${pending.size}" else "declined"}")
        if (approved) toast("Moved ${pending.size} videos to the trash")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            when (intent?.action) {
                ACTION_MOVE_NEW -> {
                    val found = scanner.scanCameraFolder().filter { it.detection.verdict == Verdict.TIKTOK }
                    // Never move a video its app is still writing: that is what leaves a copy behind.
                    val (ready, saving) = found.partition { it.isSettled() }
                    pending = ready.map { it.uri }
                    ActivityLog.add(
                        this@CleanupActivity,
                        "Notification tapped: ${ready.size} saved, ${saving.size} still saving" +
                            saving.joinToString("") { "\n  waiting: ${it.describe()}" },
                    )
                    if (saving.isNotEmpty()) toast("${saving.size} video(s) still saving, they'll move when done")
                    launch(mover.writeRequest(pending), moveLauncher)
                }
                ACTION_DELETE_EXPIRED -> {
                    val days = CleanupSettings(this@CleanupActivity).deleteAfterDays
                    pending = if (days > 0) {
                        scanner.findExpired(ExpiryWorker.cutoffSec(days)).map { it.uri }
                    } else {
                        emptyList()
                    }
                    launch(mover.trashRequest(pending), trashLauncher)
                }
                else -> finish()
            }
        }
    }

    private fun launch(
        request: android.app.PendingIntent?,
        launcher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>,
    ) {
        if (pending.isEmpty() || request == null) {
            finish()
            return
        }
        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_MOVE_NEW = "app.sharedclips.MOVE_NEW"
        const val ACTION_DELETE_EXPIRED = "app.sharedclips.DELETE_EXPIRED"
    }
}
