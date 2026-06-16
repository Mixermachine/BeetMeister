package de.aarondietz.beetmeister.model.controller

import com.squareup.moshi.Json

data class BeetMaintenanceInfo(
    @param:Json(name = "product_id") val productId: String,
    @param:Json(name = "hardware_rev") val hardwareRev: String,
    @param:Json(name = "firmware_version") val firmwareVersion: String,
    @param:Json(name = "build_label") val buildLabel: String,
    @param:Json(name = "maintenance_protocol_version") val maintenanceProtocolVersion: Int,
    @param:Json(name = "runtime_protocol_version") val runtimeProtocolVersion: Int,
    @param:Json(name = "update_capable") val updateCapable: Boolean,
    @param:Json(name = "image_kind") val imageKind: String,
)
