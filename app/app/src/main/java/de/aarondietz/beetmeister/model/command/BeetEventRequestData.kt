package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

internal data class BeetEventRequestData(
    @param:Json(name = "seq_no") val sequenceNumber: Long,
)
