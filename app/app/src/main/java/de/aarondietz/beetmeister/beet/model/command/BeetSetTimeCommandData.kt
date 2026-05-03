package de.aarondietz.beetmeister.beet.model.command

import com.squareup.moshi.Json

internal data class BeetSetTimeCommandData(
    @param:Json(name = "unix_s") val unixSeconds: Long,
)
