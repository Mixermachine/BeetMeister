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
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.ValueGridRow
import de.aarondietz.beetmeister.ui.core.formatting.connectionPhaseLabel

@Composable
internal fun SettingsScreen(
    state: BeetRepositoryState,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
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
                    Text(strings.get(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    ValueGridRow(
                        strings.get(R.string.settings_label_device_id),
                        info?.deviceId ?: strings.get(R.string.placeholder_dash),
                        strings.get(R.string.settings_label_protocol),
                        info?.protocolVersion?.toString() ?: strings.get(R.string.placeholder_dash),
                    )
                    ValueGridRow(
                        strings.get(R.string.settings_label_firmware),
                        info?.firmwareVersion ?: strings.get(R.string.placeholder_dash),
                        strings.get(R.string.settings_label_pairs),
                        info?.pairCount?.toString() ?: strings.get(R.string.placeholder_dash),
                    )
                    ValueGridRow(
                        strings.get(R.string.settings_label_connection),
                        connectionPhaseLabel(state.connection.phase, strings),
                        strings.get(R.string.settings_label_address),
                        state.selectedAddress ?: strings.get(R.string.placeholder_dash),
                    )
                }
            }
        }
        item {
            Button(onClick = onDisconnect) { Text(strings.get(R.string.common_disconnect)) }
        }
    }
}
