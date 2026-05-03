package de.aarondietz.beetmeister.model.event

import com.squareup.moshi.Json

data class BeetSystemEvent(
    @param:Json(name = "seq") val sequenceNumber: Long,
    @param:Json(name = "event_type") val eventType: String,
    val reason: Int,
    @param:Json(name = "boot_id") val bootId: Long = 0L,
    @param:Json(name = "uptime_s") val uptimeSeconds: Long,
    @param:Json(name = "unix_s") val unixSeconds: Long,
    @param:Json(name = "battery_mv") val batteryMillivolts: Int,
    @param:Json(name = "peer_addr") val peerAddress: String,
    @param:Json(name = "peer_addr_type") val peerAddressType: Int,
    @param:Json(name = "known_peer") val knownPeer: Boolean,
    val detail: Long,
) {
    val timeValid: Boolean
        get() = unixSeconds > 0L
}
