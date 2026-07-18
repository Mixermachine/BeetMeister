package de.aarondietz.beetmeister.ui.feature.connection

/**
 * Stable Compose test tags for [ConnectionGate] and the per-device [DeviceCard].
 *
 * The scan / connect / device-list flow is the first surface the
 * FreshInstallE2ETest interacts with. The robot in Phase 2 uses these
 * tags to drive:
 *  - [ScanButton]: tap to begin a scan
 *  - [DeviceCard]: per-device ElevatedCard
 *  - [DeviceConnectButton]: the per-device Connect button inside the card
 *  - [StatusText]: the connection-status text that surfaces reason codes
 *
 * [PermissionsButton] and [EnableBluetoothButton] cover the gating buttons
 * shown before the device list is reachable.
 */
internal object ConnectionGateTestTags {
    const val Container = "connection_gate_container"
    const val StatusText = "connection_gate_status"
    const val PermissionsButton = "connection_gate_permissions_button"
    const val EnableBluetoothButton = "connection_gate_enable_bluetooth_button"
    const val ScanButton = "connection_gate_scan_button"
    const val RetryButton = "connection_gate_retry_button"
    const val DeviceList = "connection_gate_device_list"
    const val DeviceCard = "connection_gate_device_card"
    const val DeviceName = "connection_gate_device_name"
    const val DeviceAddress = "connection_gate_device_address"
    const val DeviceConnectButton = "connection_gate_device_connect_button"
}
