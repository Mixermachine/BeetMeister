package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

data class BeetPairCombinedCommandData(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "followers") val followersMask: Int,
)
