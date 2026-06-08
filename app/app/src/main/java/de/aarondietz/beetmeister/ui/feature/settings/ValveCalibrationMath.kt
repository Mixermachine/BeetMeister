package de.aarondietz.beetmeister.ui.feature.settings

import kotlin.math.roundToInt

internal const val ValvePulseMinimumMicros = 500
internal const val ValvePulseMaximumMicros = 2500

internal fun isValidValvePulseRange(minPulseMicros: Int, maxPulseMicros: Int): Boolean =
    minPulseMicros in ValvePulseMinimumMicros..ValvePulseMaximumMicros &&
        maxPulseMicros in ValvePulseMinimumMicros..ValvePulseMaximumMicros &&
        minPulseMicros < maxPulseMicros

internal fun clampValvePulse(pulseMicros: Int, minPulseMicros: Int, maxPulseMicros: Int): Int =
    pulseMicros.coerceIn(minPulseMicros, maxPulseMicros)

internal fun valvePulseToPercent(
    pulseMicros: Int,
    minPulseMicros: Int,
    maxPulseMicros: Int,
): Float {
    if (!isValidValvePulseRange(minPulseMicros, maxPulseMicros)) {
        return 0f
    }
    val clamped = clampValvePulse(pulseMicros, minPulseMicros, maxPulseMicros)
    return ((clamped - minPulseMicros).toFloat() / (maxPulseMicros - minPulseMicros).toFloat()) * 100f
}

internal fun valvePercentToPulse(
    percent: Float,
    minPulseMicros: Int,
    maxPulseMicros: Int,
): Int {
    if (!isValidValvePulseRange(minPulseMicros, maxPulseMicros)) {
        return minPulseMicros
    }
    val clampedPercent = percent.coerceIn(0f, 100f)
    val span = maxPulseMicros - minPulseMicros
    return minPulseMicros + ((span * (clampedPercent / 100f)).roundToInt())
}
