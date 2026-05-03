package de.aarondietz.beetmeister.model.connection

data class BeetDiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val bondState: Int,
    val lastSeenMillis: Long,
)
