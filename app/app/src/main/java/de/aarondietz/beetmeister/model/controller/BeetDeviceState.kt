package de.aarondietz.beetmeister.model.controller

import com.squareup.moshi.Json

data class BeetDeviceState(
    @param:Json(name = "battery_state") val batteryState: String,
    @param:Json(name = "battery_mv") val batteryMillivolts: Int,
    @param:Json(name = "time_valid") val timeValid: Boolean,
    @param:Json(name = "boot_id") val bootId: Long = 0L,
    @param:Json(name = "next_check_in_s") val nextCheckInSeconds: Int,
    @param:Json(name = "active_pumps") val activePumps: Int,
    @param:Json(name = "wifi_connected") val wifiConnected: Boolean,
    @param:Json(name = "mqtt_connected") val mqttConnected: Boolean,
    @param:Json(name = "uptime_s") val uptimeSeconds: Long = 0L,
) {
    val batteryPercentApprox: Int
        get() {
            val clamped = batteryMillivolts.coerceIn(3100, 3600)
            return ((clamped - 3100) * 100) / 500
        }
}
