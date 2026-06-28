package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

data class BeetMaxActivePumpsCommandData(
    @param:Json(name = "max") val maxActivePumps: Int,
)
