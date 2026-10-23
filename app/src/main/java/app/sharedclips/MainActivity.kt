package app.sharedclips

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.CleanupMode
import app.sharedclips.ui.HomeScreen
import app.sharedclips.ui.theme.SharedClipsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccess()
        viewModel.refreshLog()
        // Pick up moves made in the background while the app was closed.
        if (ContextCompat.checkSelfPermission(this, readPermission()) == PackageManager.PERMISSION_GRANTED) {
            viewModel.scan()
        }
    }

    private fun readPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun shareLog() {
        val header = "Shared Clips ${viewModel.state.value.versionName} on ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val text = (listOf(header) + ActivityLog.read(this)).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share activity log"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val readPermission = readPermission()

        setContent {
            SharedClipsTheme {
                val state = viewModel.state.collectAsStateWithLifecycle().value

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> if (granted) viewModel.scan() }

                val moveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { if (it.resultCode == Activity.RESULT_OK) viewModel.onMoveApproved() }

                val trashLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { if (it.resultCode == Activity.RESULT_OK) viewModel.onTrashApproved() }

                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }

                LaunchedEffect(Unit) { permissionLauncher.launch(readPermission) }

                HomeScreen(
                    state = state,
                    onScan = { permissionLauncher.launch(readPermission) },
                    onModeChange = viewModel::setMode,
                    onAutoMoveChange = { enabled ->
                        viewModel.setAutoMove(enabled)
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onDeleteAfterChange = { days ->
                        viewModel.setDeleteAfterDays(days)
                        if (days > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onShareLog = ::shareLog,
                    onClearLog = viewModel::clearLog,
                    onRequestManageMedia = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName"))
                            )
                        }
                    },
                    onCleanUp = {
                        when (state.mode) {
                            CleanupMode.MOVE_TO_FOLDER -> viewModel.moveRequest()?.let {
                                moveLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
                            }
                            CleanupMode.DELETE -> viewModel.trashRequest()?.let {
                                trashLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
                            }
                        }
                    },
                )
            }
        }
    }
}
