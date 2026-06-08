package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

data class BeetValveConfigCommandData(
    @param:Json(name = "valve_enabled") val valveEnabled: Boolean,
    @param:Json(name = "servo_min_pulse_us") val servoMinPulseMicros: Int,
    @param:Json(name = "servo_max_pulse_us") val servoMaxPulseMicros: Int,
    @param:Json(name = "open_pulse_us") val openPulseMicros: Int,
    @param:Json(name = "shut_pulse_us") val shutPulseMicros: Int,
    @param:Json(name = "move_duration_ms") val moveDurationMillis: Int,
    @param:Json(name = "settle_delay_ms") val settleDelayMillis: Int,
    @param:Json(name = "open_hold_ms") val openHoldMillis: Int,
)
