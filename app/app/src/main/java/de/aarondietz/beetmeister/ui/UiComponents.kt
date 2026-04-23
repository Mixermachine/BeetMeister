package de.aarondietz.beetmeister.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import de.aarondietz.beetmeister.beet.BeetRepositoryState

@Composable
internal fun Header(state: BeetRepositoryState) {
    val info = state.controllerInfo
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = info?.deviceId ?: "Connected controller",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Live BLE session",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AssistChip(onClick = {}, label = { Text(state.connection.phase.name) })
    }
}

@Composable
internal fun ValueGridRow(leftLabel: String, leftValue: String, rightLabel: String, rightValue: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ValueCell(label = leftLabel, value = leftValue, modifier = Modifier.weight(1f))
        ValueCell(label = rightLabel, value = rightValue, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ValueCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun PairEnabledToggleButton(pairEnabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onToggle, modifier = modifier) {
        Text(if (pairEnabled) "Disable pair" else "Enable pair")
    }
}
