package de.aarondietz.beetmeister.model.controller

import com.squareup.moshi.Json

data class BeetWateringInterval(
    @param:Json(name = "watering_interval_s") val seconds: Int,
)
