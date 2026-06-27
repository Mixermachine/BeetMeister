package de.aarondietz.beetmeister.model.update

import com.squareup.moshi.Json

data class BeetMaintenanceStatus(
    @param:Json(name = "state") val state: String,
    @param:Json(name = "session_id") val sessionId: Int? = null,
    @param:Json(name = "next_offset") val nextOffset: Int,
    @param:Json(name = "bytes_received") val bytesReceived: Int,
    @param:Json(name = "total_bytes") val totalBytes: Int,
    @param:Json(name = "failure_reason") val failureReason: String? = null,
)
