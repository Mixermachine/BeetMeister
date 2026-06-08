package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

data class BeetValvePreviewCommandData(
    @param:Json(name = "pulse_us") val pulseMicros: Int,
)
