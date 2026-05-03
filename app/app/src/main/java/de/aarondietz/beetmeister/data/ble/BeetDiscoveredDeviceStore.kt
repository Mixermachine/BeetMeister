package de.aarondietz.beetmeister.data.ble

import android.bluetooth.le.ScanResult
import de.aarondietz.beetmeister.model.connection.BeetDiscoveredDevice

internal class BeetDiscoveredDeviceStore {
    private val devices = linkedMapOf<String, BeetDiscoveredDevice>()

    fun clear() {
        devices.clear()
    }

    fun upsert(result: ScanResult) {
        val device = result.device
        val name = device.name ?: result.scanRecord?.deviceName ?: "BeetMeister"
        devices[device.address] = BeetDiscoveredDevice(
            name = name,
            address = device.address,
            rssi = result.rssi,
            bondState = device.bondState,
            lastSeenMillis = System.currentTimeMillis(),
        )
    }

    fun pruneStaleDevices(staleTimeoutMillis: Long) {
        val cutoff = System.currentTimeMillis() - staleTimeoutMillis
        devices.entries.removeAll { (_, device) -> device.lastSeenMillis < cutoff }
    }

    fun snapshot(): List<BeetDiscoveredDevice> =
        devices.values.sortedWith(
            compareByDescending<BeetDiscoveredDevice> { item -> item.lastSeenMillis }
                .thenByDescending { item -> item.rssi },
        )
}
