package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

data class BeetValveConfigCommandData(
    @param:Json(name = "valve_enabled") val valveEnabled: Boolean,
    @param:Json(name = "open_angle_deg") val openAngleDegrees: Int,
    @param:Json(name = "close_angle_deg") val closeAngleDegrees: Int,
    @param:Json(name = "move_duration_ms") val moveDurationMillis: Int,
    @param:Json(name = "settle_delay_ms") val settleDelayMillis: Int,
    @param:Json(name = "open_hold_ms") val openHoldMillis: Int,
)
