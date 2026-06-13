package de.aarondietz.beetmeister.model.command

import de.aarondietz.beetmeister.model.controller.BeetCalibration
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.controller.BeetWateringInterval
import de.aarondietz.beetmeister.model.event.BeetHistorySummary
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetSystemHistorySummary
import de.aarondietz.beetmeister.model.event.BeetWateringEvent

data class BeetCommandResult(
    val command: String,
    val pairIndex: Int?,
    val status: String,
    val reason: String,
    val acceptedDurationSeconds: Int? = null,
    val calibration: BeetCalibration? = null,
    val historySummary: BeetHistorySummary? = null,
    val event: BeetWateringEvent? = null,
    val systemHistorySummary: BeetSystemHistorySummary? = null,
    val systemEvent: BeetSystemEvent? = null,
    val valveConfig: BeetValveConfig? = null,
    val wateringInterval: BeetWateringInterval? = null,
)
