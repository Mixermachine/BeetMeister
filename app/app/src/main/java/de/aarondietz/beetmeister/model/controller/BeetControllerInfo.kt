package de.aarondietz.beetmeister.model.controller

import com.squareup.moshi.Json

data class BeetControllerInfo(
    @param:Json(name = "device_id") val deviceId: String,
    @param:Json(name = "protocol_version") val protocolVersion: Int,
    @param:Json(name = "firmware_version") val firmwareVersion: String,
    @param:Json(name = "pair_count") val pairCount: Int,
)
