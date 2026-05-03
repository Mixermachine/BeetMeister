package de.aarondietz.beetmeister.beet.model.command

import com.squareup.moshi.Json

internal data class BeetPairCommandData(
    @param:Json(name = "pair") val pairIndex: Int,
)
