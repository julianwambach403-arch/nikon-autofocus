package de.nikonautofocus.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF4FC3F7)
val AccentDim = Color(0xFF1F6F92)
val SharpGreen = Color(0xFF34D399)
val BlurRed = Color(0xFFF87171)
val WarnAmber = Color(0xFFFBBF24)
val Ink = Color(0xFF101317)
val Panel = Color(0xFF181C22)
val PanelHigh = Color(0xFF222831)
val OnInk = Color(0xFFE6EAF0)
val Muted = Color(0xFF98A2B3)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04212E),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFE1F5FE),
    secondary = SharpGreen,
    onSecondary = Color(0xFF05291D),
    error = BlurRed,
    onError = Color(0xFF2B0A0A),
    background = Ink,
    onBackground = OnInk,
    surface = Panel,
    onSurface = OnInk,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Muted,
    outline = Color(0xFF39414D)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00668B),
    secondary = Color(0xFF00794C),
    error = Color(0xFFB3261E),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEFF3)
)

/**
 * The app sits next to a camera on a tripod, usually in a dim room, and the LiveView image
 * has to be judged by eye. It therefore uses the dark palette unconditionally; pass
 * darkTheme = isSystemInDarkTheme() if you would rather follow the system setting.
 */
@Composable
fun NikonAutoFocusTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
