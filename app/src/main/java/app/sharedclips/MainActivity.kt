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
            viewModel.scan(clearMessage = false)
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

                // Media location access is asked together with read access: without it, "Media
                // management" still shows a confirmation dialog for every move.
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results -> if (results[readPermission] == true) viewModel.scan() }
                val requestRead = {
                    permissionLauncher.launch(arrayOf(readPermission, Manifest.permission.ACCESS_MEDIA_LOCATION))
                }

                val moveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { if (it.resultCode == Activity.RESULT_OK) viewModel.onMoveApproved() }

                val trashLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { if (it.resultCode == Activity.RESULT_OK) viewModel.onTrashApproved() }

                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }

                LaunchedEffect(Unit) { requestRead() }

                HomeScreen(
                    state = state,
                    onScan = requestRead,
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
                    onRequestAllFilesAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                )
                            )
                        }
                    },
                    onCleanUp = {
                        viewModel.cleanUp { request ->
                            val delete = viewModel.state.value.mode == CleanupMode.DELETE
                            val launcher = if (delete) trashLauncher else moveLauncher
                            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        }
                    },
                )
            }
        }
    }
}
