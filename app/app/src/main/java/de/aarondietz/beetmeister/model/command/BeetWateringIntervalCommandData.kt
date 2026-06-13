package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

data class BeetWateringIntervalCommandData(
    @param:Json(name = "watering_interval_s") val seconds: Int,
)
