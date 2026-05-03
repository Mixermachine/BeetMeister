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
)

@Composable
fun BeetMeisterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}
