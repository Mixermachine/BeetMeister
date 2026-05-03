package de.aarondietz.beetmeister.beet.model.command

import de.aarondietz.beetmeister.beet.model.controller.BeetCalibration
import de.aarondietz.beetmeister.beet.model.event.BeetHistorySummary
import de.aarondietz.beetmeister.beet.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.beet.model.event.BeetSystemHistorySummary
import de.aarondietz.beetmeister.beet.model.event.BeetWateringEvent

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
)
