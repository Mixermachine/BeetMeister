package de.aarondietz.beetmeister.model.repository

import de.aarondietz.beetmeister.model.connection.BeetConnectionState
import de.aarondietz.beetmeister.model.connection.BeetDiscoveredDevice
import de.aarondietz.beetmeister.model.controller.BeetCalibration
import de.aarondietz.beetmeister.model.controller.BeetControllerInfo
import de.aarondietz.beetmeister.model.controller.BeetDeviceState
import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.controller.BeetWateringInterval
import de.aarondietz.beetmeister.model.event.BeetHistorySummary
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetSystemHistorySummary
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.stream.BeetEventSyncState
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdateState

data class BeetRepositoryState(
    val connection: BeetConnectionState = BeetConnectionState(),
    val discoveredDevices: List<BeetDiscoveredDevice> = emptyList(),
    val controllerInfo: BeetControllerInfo? = null,
    val maintenanceInfo: BeetMaintenanceInfo? = null,
    val deviceState: BeetDeviceState? = null,
    val valveConfig: BeetValveConfig? = null,
    val wateringInterval: BeetWateringInterval? = null,
    val pairStates: List<BeetPairState> = List(8) { index ->
        BeetPairState(
            pairIndex = index + 1,
            state = "IDLE",
            moisturePercent = 0,
            sensorMillivolts = 0,
            enabled = true,
            sensorValid = true,
            blocked = false,
            blockReason = "NONE",
            remainingSeconds = 0,
            source = "NONE",
        )
    },
    val calibrations: Map<Int, BeetCalibration> = emptyMap(),
    val calibrationsRefreshing: Boolean = false,
    val historySummary: BeetHistorySummary? = null,
    val systemHistorySummary: BeetSystemHistorySummary? = null,
    val recentEvents: List<BeetWateringEvent> = emptyList(),
    val systemEvents: List<BeetSystemEvent> = emptyList(),
    val eventsLoading: Boolean = false,
    val eventSync: BeetEventSyncState = BeetEventSyncState(),
    val valveConfigRefreshing: Boolean = false,
    val wateringIntervalRefreshing: Boolean = false,
    val connectedAtMillis: Long = 0L,
    val connectedAtControllerUptimeSeconds: Long = 0L,
    val lastCommandMessage: String? = null,
    val selectedAddress: String? = null,
    val maintenanceUpdate: BeetMaintenanceUpdateState = BeetMaintenanceUpdateState(),
)
