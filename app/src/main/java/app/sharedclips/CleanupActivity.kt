package app.sharedclips

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.sharedclips.action.MediaMover
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
                toast("Moved $moved videos to TikTok Downloads")
            }
            finish()
        }
    }

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) toast("Moved ${pending.size} videos to the trash")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            when (intent?.action) {
                ACTION_MOVE_NEW -> {
                    pending = scanner.scanCameraFolder()
                        .filter { it.detection.verdict == Verdict.TIKTOK }
                        .map { it.uri }
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
