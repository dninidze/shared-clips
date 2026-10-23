package app.sharedclips

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sharedclips.data.CleanupMode
import app.sharedclips.ui.HomeScreen
import app.sharedclips.ui.theme.SharedClipsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

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

                LaunchedEffect(Unit) { permissionLauncher.launch(readPermission) }

                HomeScreen(
                    state = state,
                    onScan = { permissionLauncher.launch(readPermission) },
                    onModeChange = viewModel::setMode,
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
