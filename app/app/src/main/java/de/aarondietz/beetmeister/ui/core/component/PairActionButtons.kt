package de.aarondietz.beetmeister.ui.core.component

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver

@Composable
internal fun PairEnabledToggleButton(pairEnabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val strings = rememberBeetStringResolver()
    Button(onClick = onToggle, modifier = modifier) {
        Text(strings.get(if (pairEnabled) R.string.action_disable_pair else R.string.action_enable_pair))
    }
}

@Composable
internal fun PairErrorClearButton(canClearError: Boolean, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val strings = rememberBeetStringResolver()
    Button(onClick = onClear, enabled = canClearError, modifier = modifier) {
        Text(strings.get(R.string.action_clear_error))
    }
}
