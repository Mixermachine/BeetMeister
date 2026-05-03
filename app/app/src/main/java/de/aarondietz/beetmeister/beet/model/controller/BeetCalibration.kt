package de.aarondietz.beetmeister.beet.model.controller

import com.squareup.moshi.Json

data class BeetCalibration(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "dry_mv") val dryMillivolts: Int,
    @param:Json(name = "wet_mv") val wetMillivolts: Int,
    val source: String,
    @param:Json(name = "calibrated_at_unix_s") val calibratedAtUnixSeconds: Long,
)
