package de.aarondietz.beetmeister.ui.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Moss,
    onPrimary = Wheat,
    secondary = Fern,
    onSecondary = Wheat,
    tertiary = Clay,
    background = Wheat,
    onBackground = Bark,
    surface = Color(0xFFFFFCF6),
    onSurface = Bark,
    surfaceVariant = Mist,
    onSurfaceVariant = Stone,
    surfaceContainer = Color(0xFFF5EFE4),
    surfaceContainerHigh = Color(0xFFEFE9DC),
    surfaceContainerHighest = Color(0xFFE8E2D4),
    surfaceContainerLow = Color(0xFFFAF5EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    outline = Stone,
    outlineVariant = Color(0xFFCBD5C0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = StatusErrorContainer,
    onErrorContainer = StatusErrorOnContainer,
)

@Composable
fun BeetMeisterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}
