package de.aarondietz.beetmeister.beet.model.command

import com.squareup.moshi.Json

internal data class BeetCommandAckData(
    @param:Json(name = "pair") val pairIndex: Int? = null,
    @param:Json(name = "duration_s") val durationSeconds: Int? = null,
)
