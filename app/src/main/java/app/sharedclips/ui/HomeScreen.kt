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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
            item { Text("Shared Clips", style = MaterialTheme.typography.headlineMedium) }

            item {
                AutomationSection(
                    autoMove = state.autoMove,
                    deleteAfterDays = state.deleteAfterDays,
                    canManageMedia = state.canManageMedia,
                    onAutoMoveChange = onAutoMoveChange,
                    onDeleteAfterChange = onDeleteAfterChange,
                    onRequestManageMedia = onRequestManageMedia,
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
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AutomationSection(
    autoMove: Boolean,
    deleteAfterDays: Int,
    canManageMedia: Boolean,
    onAutoMoveChange: (Boolean) -> Unit,
    onDeleteAfterChange: (Int) -> Unit,
    onRequestManageMedia: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatic mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Moves new TikTok downloads to TikTok Downloads within seconds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoMove, onCheckedChange = onAutoMoveChange)
        }

        Text("Delete from TikTok Downloads after", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CleanupSettings.DELETE_AFTER_OPTIONS.forEach { days ->
                FilterChip(
                    selected = days == deleteAfterDays,
                    onClick = { onDeleteAfterChange(days) },
                    label = { Text(if (days == 0) "Never" else if (days == 1) "1 day" else "$days days") },
                )
            }
        }

        if ((autoMove || deleteAfterDays > 0) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canManageMedia) {
            Text(
                "Allow \"Media management\" so this runs without asking each time. " +
                    "Without it, you get a notification to tap instead.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onRequestManageMedia) { Text("Allow media management") }
        }
    }
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
                    if (video.detection.verdict == Verdict.UNSURE) " · not sure" else "",
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
