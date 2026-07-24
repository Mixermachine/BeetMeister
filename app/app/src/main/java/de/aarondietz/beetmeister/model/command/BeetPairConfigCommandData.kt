package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json
import de.aarondietz.beetmeister.model.controller.TargetMoistureLevel

data class BeetPairConfigCommandData(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "target_level") val targetLevel: TargetMoistureLevel,
    @param:Json(name = "duration_multiplier") val durationMultiplier: Int,
)
