package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

internal data class BeetStreamEventsCommandData(
    @param:Json(name = "kind") val kind: String,
    @param:Json(name = "from_seq") val fromSeq: Long,
    @param:Json(name = "max_events") val maxEvents: Long? = null,
)
