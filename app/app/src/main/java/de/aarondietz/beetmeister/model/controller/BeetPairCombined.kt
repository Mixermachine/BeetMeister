package de.aarondietz.beetmeister.model.controller

import com.squareup.moshi.Json

data class BeetPairCombined(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "followers") val followersMask: Int,
)
