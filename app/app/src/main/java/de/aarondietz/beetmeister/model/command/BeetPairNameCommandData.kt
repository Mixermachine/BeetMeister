package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

internal data class BeetPairNameCommandData(
    @Json(name = "pair") val pairIndex: Int,
    @Json(name = "name") val name: String,
)
