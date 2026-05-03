package de.aarondietz.beetmeister.beet.model.connection

data class BeetDiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val bondState: Int,
    val lastSeenMillis: Long,
)
