package de.aarondietz.beetmeister.data.protocol.dto

import com.squareup.moshi.Json

internal data class StateEnvelopeHeaderDto(
    val type: String,
)

internal data class CommandEnvelopeHeaderDto(
    val cmd: String,
    val status: String,
    val reason: String,
)

internal data class CommandRequestEnvelopeDto<T>(
    val cmd: String,
    val data: T,
)

internal data class CommandChunkEnvelopeDto(
    val type: String,
    val id: Long,
    @Json(name = "i") val index: Int,
    @Json(name = "n") val count: Int,
    @Json(name = "b64") val base64Fragment: String,
)
