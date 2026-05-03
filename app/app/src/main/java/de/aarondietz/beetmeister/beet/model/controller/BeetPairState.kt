package de.aarondietz.beetmeister.beet.model.controller

import com.squareup.moshi.Json

data class BeetPairState(
    @param:Json(name = "pair") val pairIndex: Int,
    val state: String,
    @param:Json(name = "moisture_pct") val moisturePercent: Int,
    @param:Json(name = "sensor_mv") val sensorMillivolts: Int,
    val enabled: Boolean,
    @param:Json(name = "sensor_valid") val sensorValid: Boolean,
    val blocked: Boolean,
    @param:Json(name = "block_reason") val blockReason: String,
    @param:Json(name = "remaining_s") val remainingSeconds: Int,
    val source: String,
)
