package de.aarondietz.beetmeister.ui.core.component

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun PairEnabledToggleButton(pairEnabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onToggle, modifier = modifier) {
        Text(if (pairEnabled) "Disable pair" else "Enable pair")
    }
}

@Composable
internal fun PairErrorClearButton(canClearError: Boolean, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClear, enabled = canClearError, modifier = modifier) {
        Text("Clear error")
    }
}
