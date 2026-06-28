package de.aarondietz.beetmeister.model.controller

import com.squareup.moshi.Json

data class BeetMaxActivePumps(
    @param:Json(name = "max_active_pumps") val maxActivePumps: Int,
)
