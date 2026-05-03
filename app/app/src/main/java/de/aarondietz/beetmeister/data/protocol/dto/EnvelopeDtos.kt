package de.aarondietz.beetmeister.data.protocol.dto

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
