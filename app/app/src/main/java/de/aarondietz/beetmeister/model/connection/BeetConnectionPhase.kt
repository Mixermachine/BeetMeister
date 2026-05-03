package de.aarondietz.beetmeister.model.connection

enum class BeetConnectionPhase {
    PermissionsRequired,
    BluetoothDisabled,
    Idle,
    Scanning,
    Bonding,
    Connecting,
    DiscoveringServices,
    Syncing,
    Connected,
    Disconnected,
    Error,
}
