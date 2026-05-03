package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

internal data class BeetCalibrationCommandData(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "dry_mv") val dryMillivolts: Int,
    @param:Json(name = "wet_mv") val wetMillivolts: Int,
)
