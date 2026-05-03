package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

internal data class BeetManualStartCommandData(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "duration_s") val durationSeconds: Int? = null,
)
