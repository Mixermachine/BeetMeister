package de.aarondietz.beetmeister.ui.core.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import de.aarondietz.beetmeister.R

internal enum class TopLevelScreen(@StringRes val labelRes: Int, val icon: @Composable () -> Unit) {
    Overview(R.string.nav_overview, { Icon(Icons.Default.Grass, contentDescription = null) }),
    Calibration(R.string.nav_calibration, { Icon(Icons.Default.Tune, contentDescription = null) }),
    Events(R.string.nav_events, { Icon(Icons.Default.Event, contentDescription = null) }),
    Settings(R.string.nav_settings, { Icon(Icons.Default.Settings, contentDescription = null) }),
}
