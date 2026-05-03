package de.aarondietz.beetmeister.ui.connection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.beet.model.connection.BeetDiscoveredDevice
import de.aarondietz.beetmeister.ui.formatting.bondStateLabel

@Composable
internal fun DeviceCard(device: BeetDiscoveredDevice, selected: Boolean, onConnect: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) Color(0xFFE8F1E3) else Color(0xFFFDFBF6),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(device.name, fontWeight = FontWeight.SemiBold)
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.BluetoothSearching, contentDescription = null, tint = Color(0xFF59734E))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "RSSI ${device.rssi} dBm  |  ${bondStateLabel(device.bondState)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5D6658),
            )
            if (selected) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Connecting...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF46663E),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onConnect) {
                Text(if (selected) "Connecting" else "Connect")
            }
        }
    }
}
