package de.aarondietz.beetmeister.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.ui.core.component.ValueGridRow

@Composable
internal fun SettingsScreen(
    state: BeetRepositoryState,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = state.controllerInfo
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF2EFE8)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Settings", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    ValueGridRow("Device ID", info?.deviceId ?: "-", "Protocol", info?.protocolVersion?.toString() ?: "-")
                    ValueGridRow("Firmware", info?.firmwareVersion ?: "-", "Pairs", info?.pairCount?.toString() ?: "-")
                    ValueGridRow("Connection", state.connection.phase.name, "Address", state.selectedAddress ?: "-")
                }
            }
        }
        item {
            Button(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}
