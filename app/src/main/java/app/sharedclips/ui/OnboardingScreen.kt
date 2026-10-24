package app.sharedclips.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sharedclips.BuildConfig
import app.sharedclips.HomeState
import app.sharedclips.R

/**
 * First-run screens: what the app is for, each access it needs and why (asked one at a time, with
 * the reason shown before Android's own prompt), then automatic mode.
 */
@Composable
fun OnboardingScreen(
    state: HomeState,
    onAllowRead: () -> Unit,
    onAllowNotifications: () -> Unit,
    onAllowManageMedia: () -> Unit,
    onAutoMoveChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (step) {
                    0 -> Welcome()
                    1 -> AccessStep(state, onAllowRead, onAllowNotifications, onAllowManageMedia)
                    else -> AutomaticStep()
                }
            }
            StepDots(step, count = 3)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (step == 0) {
                    TextButton(onClick = onFinish) { Text("Skip") }
                } else {
                    TextButton(onClick = { step-- }) { Text("Back") }
                }
                Spacer(Modifier.weight(1f))
                when (step) {
                    0 -> Button(onClick = { step = 1 }) { Text("Get started") }
                    1 -> Button(onClick = { step = 2 }, enabled = state.canRead) { Text("Continue") }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            onAutoMoveChange(false)
                            onFinish()
                        }) { Text("Not now") }
                        Button(onClick = {
                            onAutoMoveChange(true)
                            onFinish()
                        }) { Text("Turn on") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome() {
    Spacer(Modifier.height(24.dp))
    Box(
        Modifier
            .size(96.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_mark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(72.dp),
        )
    }
    Text(
        "Keep TikTok downloads out of your photo backup",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        "When you save a TikTok, it lands in your Camera folder next to your own photos. Google " +
            "Photos then backs it up, and those videos quietly fill your cloud storage.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "ClipSweep moves them into their own folder, \"TikTok Downloads\", where your backup " +
            "doesn't look. They stay on your phone, and you can have them deleted after a few days.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "Everything happens on your phone. ClipSweep has no account and sends nothing anywhere.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AccessStep(
    state: HomeState,
    onAllowRead: () -> Unit,
    onAllowNotifications: () -> Unit,
    onAllowManageMedia: () -> Unit,
) {
    Text("What ClipSweep needs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        "Each one is asked separately, and you can change them later in Settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AccessItem(
        title = "See your videos",
        why = "To find the TikTok videos in your Camera folder. Required.",
        granted = state.canRead,
        action = "Allow",
        onClick = onAllowRead,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AccessItem(
            title = "Notifications",
            why = "To tell you when a new video arrives, so one tap moves it.",
            granted = state.notificationsOn,
            action = "Allow",
            onClick = onAllowNotifications,
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AccessItem(
            title = "Media management",
            why = "So moves go through right away, without Android asking you to confirm each one.",
            granted = state.canManageMedia,
            action = "Open settings",
            onClick = onAllowManageMedia,
        )
    }
}

@Composable
private fun AccessItem(title: String, why: String, granted: Boolean, action: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Text("Allowed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onClick) { Text(action) }
            }
        }
    }
}

@Composable
private fun AutomaticStep() {
    Text("Automatic mode", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        "ClipSweep watches for new TikTok downloads. A few seconds after one is saved, you get a " +
            "notification, and one tap moves it.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "Tap soon after saving: if your backup runs first, that video is already in the cloud.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (BuildConfig.ALL_FILES_ACCESS) {
        Text(
            "This version can also move them with no tap at all: allow \"All files access\" on the home screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "You can turn this off, or move everything by hand, from the home screen.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepDots(step: Int, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .background(
                        if (i == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    )
            )
        }
    }
}
