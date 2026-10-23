package app.sharedclips.ui

import android.graphics.Bitmap
import android.os.Build
import android.text.format.Formatter
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.sharedclips.HomeState
import app.sharedclips.data.CleanupMode
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.ScannedVideo
import app.sharedclips.detect.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    state: HomeState,
    onScan: () -> Unit,
    onModeChange: (CleanupMode) -> Unit,
    onCleanUp: () -> Unit,
    onAutoMoveChange: (Boolean) -> Unit,
    onDeleteAfterChange: (Int) -> Unit,
    onRequestManageMedia: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRequestUnrestricted: () -> Unit,
    onRequestKeepWhenUnused: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Shared Clips", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "v${state.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                AutomationSection(
                    state = state,
                    onAutoMoveChange = onAutoMoveChange,
                    onDeleteAfterChange = onDeleteAfterChange,
                    onRequestManageMedia = onRequestManageMedia,
                    onRequestAllFilesAccess = onRequestAllFilesAccess,
                    onRequestUnrestricted = onRequestUnrestricted,
                    onRequestKeepWhenUnused = onRequestKeepWhenUnused,
                    onRequestNotifications = onRequestNotifications,
                    onOpenAppInfo = onOpenAppInfo,
                )
            }

            item { HorizontalDivider() }

            item {
                when {
                    state.scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Scanning your Camera folder")
                    }
                    state.videos.isEmpty() -> Text("No TikTok videos in your Camera folder.")
                    else -> Text(
                        "${state.videos.size} TikTok videos in Camera, " +
                            Formatter.formatShortFileSize(context, state.totalBytes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }

            item { ModePicker(state.mode, onModeChange) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCleanUp, enabled = state.videos.isNotEmpty()) {
                        Text(if (state.mode == CleanupMode.DELETE) "Delete all" else "Move all")
                    }
                    OutlinedButton(onClick = onScan) { Text("Scan again") }
                }
            }

            items(state.videos, key = { it.id }) { VideoRow(it) }

            item { HorizontalDivider() }
            item { ActivityLogSection(state.log, onShareLog, onClearLog) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AutomationSection(
    state: HomeState,
    onAutoMoveChange: (Boolean) -> Unit,
    onDeleteAfterChange: (Int) -> Unit,
    onRequestManageMedia: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRequestUnrestricted: () -> Unit,
    onRequestKeepWhenUnused: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val autoDelete = state.deleteAfterDays > 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatic mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.hasAllFilesAccess) {
                        "Moves new TikTok downloads to TikTok Downloads within seconds"
                    } else {
                        "Notifies you about new TikTok downloads; one tap moves them to TikTok Downloads"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.autoMove, onCheckedChange = onAutoMoveChange)
        }

        Text("Delete from TikTok Downloads after", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CleanupSettings.DELETE_AFTER_OPTIONS.forEach { days ->
                FilterChip(
                    selected = days == state.deleteAfterDays,
                    onClick = { onDeleteAfterChange(days) },
                    label = { Text(if (days == 0) "Never" else if (days == 1) "1 day" else "$days days") },
                )
            }
        }

        if (state.autoMove || autoDelete) {
            AccessHints(
                state, onRequestManageMedia, onRequestAllFilesAccess, onRequestUnrestricted,
                onRequestKeepWhenUnused, onRequestNotifications, onOpenAppInfo,
            )
        }
    }
}

/** What the user still has to allow for automatic mode and auto-delete to work, and why. */
@Composable
private fun AccessHints(
    state: HomeState,
    onRequestManageMedia: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRequestUnrestricted: () -> Unit,
    onRequestKeepWhenUnused: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val autoDelete = state.deleteAfterDays > 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val what = when {
            state.autoMove && autoDelete -> "new videos are moved and old ones deleted"
            state.autoMove -> "new videos are moved"
            else -> "old videos are deleted"
        }
        if (state.hasAllFilesAccess && state.runsUnrestricted) {
            Hint("Fully automatic: $what without a tap.", highlight = true)
        } else if (state.hasAllFilesAccess) {
            Hint("No tap needed: $what by themselves.", highlight = true)
        } else {
            Hint(
                "Android only lets an app move or delete another app's videos in the background with " +
                    "\"All files access\". Without it, you get a notification and one tap does it."
            )
            OutlinedButton(onClick = onRequestAllFilesAccess) { Text("Allow all files access") }
            if (!state.notificationsOn) {
                val missed = when {
                    state.autoMove && autoDelete ->
                        "new videos stay in Camera until you open the app, and old ones are not deleted"
                    state.autoMove -> "new videos stay in Camera until you open the app"
                    else -> "old videos in TikTok Downloads are not deleted"
                }
                Hint("Notifications are off for this app, so $missed.", warning = true)
                OutlinedButton(onClick = onRequestNotifications) { Text("Turn on notifications") }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !state.canManageMedia) {
                Hint("Allow \"Media management\" so that tap goes through without a confirmation popup.")
                OutlinedButton(onClick = onRequestManageMedia) { Text("Allow media management") }
            }
        }
        if (!state.runsUnrestricted) {
            Hint(
                "Android holds back background work for apps you rarely open, so this can be late by " +
                    "hours. Allow unrestricted battery use to have it happen right away."
            )
            OutlinedButton(onClick = onRequestUnrestricted) { Text("Allow background use") }
        }
        if (!state.keptWhenUnused) {
            Hint(
                "Android pauses apps you haven't opened for a few months and takes back their " +
                    "permissions. Turn off \"Pause app activity if unused\" to keep this running."
            )
            OutlinedButton(onClick = onRequestKeepWhenUnused) { Text("Keep running when unused") }
        }
        if (Build.MANUFACTURER.lowercase() in AUTO_LAUNCH_VENDORS) {
            Hint(
                "On ${Build.MANUFACTURER} phones, also turn on auto launch and background activity for " +
                    "Shared Clips (App info > Battery usage). Otherwise this pauses after a restart until " +
                    "you open the app."
            )
            OutlinedButton(onClick = onOpenAppInfo) { Text("Open app info") }
        }
    }
}

/** Phones whose own background killer also stops apps from starting after a reboot. */
private val AUTO_LAUNCH_VENDORS = setOf("oneplus", "oppo", "realme", "xiaomi", "redmi", "poco", "vivo", "huawei", "honor")

@Composable
private fun Hint(text: String, highlight: Boolean = false, warning: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            warning -> MaterialTheme.colorScheme.error
            highlight -> MaterialTheme.colorScheme.primary
            else -> Color.Unspecified
        },
    )
}

@Composable
private fun ModePicker(mode: CleanupMode, onModeChange: (CleanupMode) -> Unit) {
    Column(Modifier.selectableGroup()) {
        ModeOption(
            selected = mode == CleanupMode.MOVE_TO_FOLDER,
            title = "Move to \"TikTok Downloads\" folder",
            subtitle = "Keeps the videos, out of Camera and out of Google Photos backup",
            onClick = { onModeChange(CleanupMode.MOVE_TO_FOLDER) },
        )
        ModeOption(
            selected = mode == CleanupMode.DELETE,
            title = "Delete",
            subtitle = "Moves the videos to the trash, recoverable for 30 days",
            onClick = { onModeChange(CleanupMode.DELETE) },
        )
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoRow(video: ScannedVideo) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, video.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(video.uri, Size(160, 284), null) }.getOrNull()
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 54.dp, height = 96.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            thumb?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(video.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                Formatter.formatShortFileSize(context, video.sizeBytes) +
                    (if (video.detection.verdict == Verdict.UNSURE) " · not sure" else "") +
                    when {
                        video.isSettled() -> ""
                        video.isAbandoned() -> " · empty, save failed"
                        else -> " · still saving"
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                video.detection.reasons.firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActivityLogSection(log: List<String>, onShare: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Activity log", style = MaterialTheme.typography.titleMedium)
        Text(
            "What the app saw and did. Share it if something goes wrong.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShare, enabled = log.isNotEmpty()) { Text("Share log") }
            OutlinedButton(onClick = onClear, enabled = log.isNotEmpty()) { Text("Clear") }
        }
        if (log.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                newestFirst(log.takeLast(LOG_LINES_SHOWN)).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Reverses the order of log entries but not the lines within one: an entry's detail lines are
 * indented after the timestamp and stay under their first line.
 */
private fun newestFirst(lines: List<String>): List<String> {
    val entries = mutableListOf<MutableList<String>>()
    for (line in lines) {
        val isDetail = line.length > STAMP_LENGTH && line[STAMP_LENGTH] == ' '
        if (isDetail && entries.isNotEmpty()) entries.last().add(line) else entries.add(mutableListOf(line))
    }
    return entries.reversed().flatten()
}

private const val LOG_LINES_SHOWN = 40

/** "MM-dd HH:mm:ss " in front of every activity log line. */
private const val STAMP_LENGTH = 15
