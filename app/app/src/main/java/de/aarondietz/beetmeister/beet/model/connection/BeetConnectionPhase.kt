package de.aarondietz.beetmeister.beet.model.connection

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
