package app.sharedclips

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.AppAccess
import app.sharedclips.ui.HomeScreen
import app.sharedclips.ui.theme.SharedClipsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    /** Refreshes the list and the log while the screen is open, e.g. when a video arrives or the worker moves one. */
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = viewModel.onMediaChanged()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccess()
        viewModel.refreshLog()
        viewModel.dropStaleMessage()
        // Pick up moves made in the background while the app was closed.
        if (canRead()) {
            viewModel.scan(clearMessage = false)
            contentResolver.registerContentObserver(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), true, mediaObserver,
            )
        }
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(mediaObserver)
    }

    private fun canRead() = ContextCompat.checkSelfPermission(this, readPermission()) == PackageManager.PERMISSION_GRANTED

    private fun readPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun shareLog() {
        val header = "Shared Clips ${viewModel.state.value.versionName} on ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" + AppAccess.summary(this)
        val text = (listOf(header) + ActivityLog.read(this)).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share activity log"))
    }

    private fun appUri() = Uri.parse("package:$packageName")

    /** Opens the first settings screen this phone has; vendors remove or rename some of them. */
    private fun openFirst(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No screen for ${intent.action}", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to open ${intent.action}", e)
            }
        }
    }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri())

    private fun openAllFilesAccess() = openFirst(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri()),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        appDetails(),
    )

    private fun openManageMedia() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openFirst(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, appUri()), appDetails())
        }
    }

    /** Asks for "Unrestricted" battery use, so Android runs the watcher without delays. */
    private fun openBatterySettings() = openFirst(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(),
    )

    /** The app's "Pause app activity if unused" switch. */
    private fun openUnusedAppSettings() = openFirst(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, appUri()), appDetails())

    private fun openNotificationSettings() = openFirst(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        appDetails(),
    )

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
                ) { viewModel.refreshAccess() }

                // Once denied twice, Android stops showing the prompt; then only the settings screen helps.
                val fixNotificationsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (!granted || !AppAccess.canNotify(this)) openNotificationSettings()
                    viewModel.refreshAccess()
                }

                val askForNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                LaunchedEffect(Unit) { requestRead() }

                // The system request is launched from here, with this composition's launchers, so a
                // request prepared while the screen was being recreated still goes to a live one.
                LaunchedEffect(state.request) {
                    val request = state.request ?: return@LaunchedEffect
                    val launcher = if (request.delete) trashLauncher else moveLauncher
                    try {
                        launcher.launch(IntentSenderRequest.Builder(request.intent.intentSender).build())
                    } finally {
                        viewModel.requestLaunched()
                    }
                }

                HomeScreen(
                    state = state,
                    onScan = requestRead,
                    onModeChange = viewModel::setMode,
                    onAutoMoveChange = { enabled ->
                        viewModel.setAutoMove(enabled)
                        if (enabled) askForNotifications()
                    },
                    onDeleteAfterChange = { days ->
                        viewModel.setDeleteAfterDays(days)
                        if (days > 0) askForNotifications()
                    },
                    onShareLog = ::shareLog,
                    onClearLog = viewModel::clearLog,
                    onRequestManageMedia = ::openManageMedia,
                    onRequestAllFilesAccess = ::openAllFilesAccess,
                    onRequestUnrestricted = ::openBatterySettings,
                    onRequestKeepWhenUnused = ::openUnusedAppSettings,
                    onRequestNotifications = {
                        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        if (needsPermission) {
                            fixNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings()
                        }
                    },
                    onOpenAppInfo = { openFirst(appDetails()) },
                    onCleanUp = viewModel::cleanUp,
                )
            }
        }
    }

    private companion object {
        const val TAG = "SharedClips"
    }
}
