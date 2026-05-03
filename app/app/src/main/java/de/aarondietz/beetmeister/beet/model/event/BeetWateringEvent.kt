package de.aarondietz.beetmeister.beet.model.event

import com.squareup.moshi.Json

data class BeetWateringEvent(
    @param:Json(name = "seq") val sequenceNumber: Long,
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "boot_id") val bootId: Long = 0L,
    @param:Json(name = "src") val triggerSource: Int,
    @param:Json(name = "start") val startedAtUnixSeconds: Long,
    @param:Json(name = "end") val endedAtUnixSeconds: Long,
    @param:Json(name = "mb") val moistureBeforePercent: Int,
    @param:Json(name = "ma") val moistureAfterPercent: Int,
    @param:Json(name = "sb") val sensorBeforeMillivolts: Int,
    @param:Json(name = "sa") val sensorAfterMillivolts: Int,
    @param:Json(name = "req") val requestedDurationSeconds: Int,
    @param:Json(name = "act") val actualDurationSeconds: Int,
    @param:Json(name = "stop") val stopReason: Int,
    @param:Json(name = "block") val blockReason: Int,
    @param:Json(name = "bs") val batteryStartMillivolts: Int,
    @param:Json(name = "be") val batteryEndMillivolts: Int,
    @param:Json(name = "su") val startedUptimeSeconds: Long = 0L,
    @param:Json(name = "eu") val endedUptimeSeconds: Long = 0L,
) {
    val timeValid: Boolean
        get() = startedAtUnixSeconds > 0L && endedAtUnixSeconds > 0L

    val isControllerSleepEvent: Boolean
        get() = pairIndex == 0
}
