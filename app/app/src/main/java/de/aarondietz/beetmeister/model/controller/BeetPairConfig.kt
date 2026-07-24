package de.aarondietz.beetmeister.model.controller

import com.squareup.moshi.Json

enum class TargetMoistureLevel(val wireValue: String) {
    @Json(name = "dry") DRY("dry"),
    @Json(name = "medium") MEDIUM("medium"),
    @Json(name = "moist") MOIST("moist");

    companion object {
        fun fromWireValue(value: String): TargetMoistureLevel {
            return entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: MEDIUM
        }
    }
}

data class BeetPairConfig(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "target_level") val targetLevel: TargetMoistureLevel = TargetMoistureLevel.MEDIUM,
    @param:Json(name = "duration_multiplier") val durationMultiplier: Int = 100,
) {
    val multiplierFloat: Float
        get() = (durationMultiplier / 100f).coerceIn(0.2f, 2.0f)
}
