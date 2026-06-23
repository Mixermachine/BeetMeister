package de.aarondietz.beetmeister.model.controller

import com.squareup.moshi.Json

data class BeetPairWiring(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "moisture_gpio") val moistureGpio: Int,
    @param:Json(name = "relay_gpio") val relayGpio: Int,
)
