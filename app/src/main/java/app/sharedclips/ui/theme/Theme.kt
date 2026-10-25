package app.sharedclips.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand colors: Sweep Yellow and Ink (see the brand kit's USAGE.md). */
val SweepYellow = Color(0xFFFFC628)
val Ink = Color(0xFF16151A)

private val Light = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = SweepYellow,
    onPrimaryContainer = Ink,
    secondaryContainer = Color(0xFFFFEDB8),
    onSecondaryContainer = Ink,
    background = Color(0xFFFFFCF5),
    surface = Color(0xFFFFFCF5),
    surfaceVariant = Color(0xFFF3EEE3),
    onSurfaceVariant = Color(0xFF55524A),
)

private val Dark = darkColorScheme(
    primary = SweepYellow,
    onPrimary = Ink,
    primaryContainer = SweepYellow,
    onPrimaryContainer = Ink,
    secondaryContainer = Color(0xFF3A3220),
    onSecondaryContainer = Color(0xFFFFE7A3),
    background = Color(0xFF121116),
    surface = Color(0xFF121116),
    surfaceVariant = Color(0xFF26242B),
    onSurfaceVariant = Color(0xFFC9C5BC),
)

/** Fixed brand colors rather than the wallpaper's, so the app looks the same on every phone. */
@Composable
fun SharedClipsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
