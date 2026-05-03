package de.aarondietz.beetmeister.ui.core.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable

internal enum class TopLevelScreen(val label: String, val icon: @Composable () -> Unit) {
    Overview("Overview", { Icon(Icons.Default.Grass, contentDescription = null) }),
    Calibration("Calibration", { Icon(Icons.Default.Tune, contentDescription = null) }),
    Events("Events", { Icon(Icons.Default.Event, contentDescription = null) }),
    Settings("Settings", { Icon(Icons.Default.Settings, contentDescription = null) }),
}
