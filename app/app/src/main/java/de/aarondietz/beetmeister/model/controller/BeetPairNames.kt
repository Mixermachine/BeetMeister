package de.aarondietz.beetmeister.model.controller

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Pair names returned by get_pair_names.
 * Index 0 = pair 1, index 7 = pair 8.
 * Empty strings indicate no name stored.
 */
@JsonClass(generateAdapter = true)
data class BeetPairNames(
    @Json(name = "names") val names: List<String>
)
