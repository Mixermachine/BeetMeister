package de.aarondietz.beetmeister.ui.core.preview

import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.connection.BeetConnectionState
import de.aarondietz.beetmeister.model.connection.BeetDiscoveredDevice
import de.aarondietz.beetmeister.model.controller.BeetCalibration
import de.aarondietz.beetmeister.model.controller.BeetControllerInfo
import de.aarondietz.beetmeister.model.controller.BeetDeviceState
import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.controller.BeetPairWiring
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.controller.BeetWateringInterval
import de.aarondietz.beetmeister.model.event.BeetHistorySummary
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetSystemHistorySummary
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.model.stream.BeetEventSyncPhase
import de.aarondietz.beetmeister.model.stream.BeetEventSyncState
import de.aarondietz.beetmeister.model.update.BeetFirmwareMetadata
import de.aarondietz.beetmeister.model.update.BeetFirmwarePackageSummary
import de.aarondietz.beetmeister.model.update.BeetFirmwareSource
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdatePhase
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdateState

/**
 * Sample-state factory for Compose previews.
 *
 * All values are realistic, not "test" / 0 placeholders. Previews are static;
 * nothing here mutates the returned state.
 */
internal object PreviewData {

    // ---------- Controller / device info ----------

    fun controllerInfo(
        deviceId: String = "beet-3a4b",
        protocolVersion: Int = 2,
        firmwareVersion: String = "v2.1.3",
        pairCount: Int = 8,
    ): BeetControllerInfo = BeetControllerInfo(
        deviceId = deviceId,
        protocolVersion = protocolVersion,
        firmwareVersion = firmwareVersion,
        pairCount = pairCount,
    )

    fun maintenanceInfo(
        productId: String = "beetmeister",
        hardwareRev: String = "rev_a",
        firmwareVersion: String = "v2.1.2",
        buildLabel: String = "stable-2026-05-18",
        maintenanceProtocolVersion: Int = 1,
        runtimeProtocolVersion: Int = 2,
        updateCapable: Boolean = true,
        imageKind: String = "bundled",
    ): BeetMaintenanceInfo = BeetMaintenanceInfo(
        productId = productId,
        hardwareRev = hardwareRev,
        firmwareVersion = firmwareVersion,
        buildLabel = buildLabel,
        maintenanceProtocolVersion = maintenanceProtocolVersion,
        runtimeProtocolVersion = runtimeProtocolVersion,
        updateCapable = updateCapable,
        imageKind = imageKind,
    )

    fun deviceState(
        batteryMillivolts: Int = 3410,
        batteryState: String = "IDLE_LOW_POWER",
        timeValid: Boolean = true,
        bootId: Long = 1748213456L,
        nextCheckInSeconds: Int = 600,
        activePumps: Int = 0,
        wifiConnected: Boolean = true,
        mqttConnected: Boolean = true,
        uptimeSeconds: Long = 18_240L,
        valveEnabled: Boolean = true,
        valveState: String = "CLOSED",
    ): BeetDeviceState = BeetDeviceState(
        batteryState = batteryState,
        batteryMillivolts = batteryMillivolts,
        timeValid = timeValid,
        bootId = bootId,
        nextCheckInSeconds = nextCheckInSeconds,
        activePumps = activePumps,
        wifiConnected = wifiConnected,
        mqttConnected = mqttConnected,
        uptimeSeconds = uptimeSeconds,
        valveEnabled = valveEnabled,
        valveState = valveState,
    )

    fun valveConfig(
        valveEnabled: Boolean = true,
        servoMinPulseMicros: Int = 1000,
        servoMaxPulseMicros: Int = 2000,
        openPulseMicros: Int = 1900,
        shutPulseMicros: Int = 1100,
        moveDurationMillis: Int = 1500,
        settleDelayMillis: Int = 250,
        openHoldMillis: Int = 1200,
    ): BeetValveConfig = BeetValveConfig(
        valveEnabled = valveEnabled,
        servoMinPulseMicros = servoMinPulseMicros,
        servoMaxPulseMicros = servoMaxPulseMicros,
        openPulseMicros = openPulseMicros,
        shutPulseMicros = shutPulseMicros,
        moveDurationMillis = moveDurationMillis,
        settleDelayMillis = settleDelayMillis,
        openHoldMillis = openHoldMillis,
    )

    fun wateringInterval(seconds: Int = 3600): BeetWateringInterval =
        BeetWateringInterval(seconds = seconds)

    // ---------- Discovered devices ----------

    fun discoveredDevice(
        name: String,
        address: String,
        rssi: Int = -58,
        bondState: Int = 10,
        lastSeenMillis: Long = 1_700_000_000_000L,
    ): BeetDiscoveredDevice = BeetDiscoveredDevice(
        name = name,
        address = address,
        rssi = rssi,
        bondState = bondState,
        lastSeenMillis = lastSeenMillis,
    )

    fun discoveredDeviceList(): List<BeetDiscoveredDevice> = listOf(
        discoveredDevice(name = "BeetMeister-A3", address = "AA:BB:CC:11:22:33", rssi = -52, bondState = 12),
        discoveredDevice(name = "BeetMeister-7F", address = "AA:BB:CC:44:55:66", rssi = -68, bondState = 10),
        discoveredDevice(name = "BeetMeister-12", address = "AA:BB:CC:77:88:99", rssi = -73, bondState = 11),
    )

    // ---------- Pair state factories ----------

    fun pairStateIdle(
        pairIndex: Int,
        enabled: Boolean = true,
        moisturePercent: Int = 62,
        sensorMillivolts: Int = 1480,
    ): BeetPairState = BeetPairState(
        pairIndex = pairIndex,
        state = "IDLE",
        moisturePercent = moisturePercent,
        sensorMillivolts = sensorMillivolts,
        enabled = enabled,
        sensorValid = true,
        blocked = false,
        blockReason = "NONE",
        remainingSeconds = 0,
        source = "AUTOMATIC",
    )

    fun pairStateWatering(
        pairIndex: Int,
        moisturePercent: Int = 38,
        sensorMillivolts: Int = 1920,
        remainingSeconds: Int = 47,
    ): BeetPairState = BeetPairState(
        pairIndex = pairIndex,
        state = "WATERING",
        moisturePercent = moisturePercent,
        sensorMillivolts = sensorMillivolts,
        enabled = true,
        sensorValid = true,
        blocked = false,
        blockReason = "NONE",
        remainingSeconds = remainingSeconds,
        source = "MANUAL",
    )

    fun pairStateFault(
        pairIndex: Int,
        blockReason: String = "SENSOR_READING_INVALID",
    ): BeetPairState = BeetPairState(
        pairIndex = pairIndex,
        state = "FAULT",
        moisturePercent = 0,
        sensorMillivolts = 0,
        enabled = true,
        sensorValid = false,
        blocked = true,
        blockReason = blockReason,
        remainingSeconds = 0,
        source = "AUTOMATIC",
    )

    fun pairStateBlocked(
        pairIndex: Int,
        blockReason: String = "MOISTURE_RESPONSE_TEST_FAILED",
    ): BeetPairState = BeetPairState(
        pairIndex = pairIndex,
        state = "BLOCKED",
        moisturePercent = 84,
        sensorMillivolts = 980,
        enabled = true,
        sensorValid = true,
        blocked = true,
        blockReason = blockReason,
        remainingSeconds = 0,
        source = "AUTOMATIC",
    )

    fun pairStateDisabled(pairIndex: Int): BeetPairState = BeetPairState(
        pairIndex = pairIndex,
        state = "DISABLED",
        moisturePercent = 0,
        sensorMillivolts = 0,
        enabled = false,
        sensorValid = true,
        blocked = false,
        blockReason = "NONE",
        remainingSeconds = 0,
        source = "NONE",
    )

    /** A mixed bag of pair states for the "all loaded" overview preview. */
    fun mixedPairStates(): List<BeetPairState> = listOf(
        pairStateIdle(1, moisturePercent = 58, sensorMillivolts = 1520),
        pairStateWatering(2, moisturePercent = 42, sensorMillivolts = 1280, remainingSeconds = 87),
        pairStateFault(3, blockReason = "SENSOR_READING_INVALID"),
        pairStateBlocked(4, blockReason = "MOISTURE_RESPONSE_TEST_FAILED"),
        pairStateDisabled(5),
        pairStateIdle(6, moisturePercent = 71, sensorMillivolts = 1110),
        pairStateIdle(7, moisturePercent = 33, sensorMillivolts = 2050),
        pairStateFault(8, blockReason = "LOW_BATTERY_ABORT"),
    )

    fun allIdlePairStates(): List<BeetPairState> = (1..8).map { pairStateIdle(it) }

    fun allFaultedPairStates(): List<BeetPairState> = (1..8).map {
        pairStateFault(it, blockReason = "SENSOR_READING_INVALID")
    }

    fun fewPairStates(): List<BeetPairState> = listOf(
        pairStateIdle(1, moisturePercent = 64, sensorMillivolts = 1400),
        pairStateWatering(2, moisturePercent = 30, sensorMillivolts = 2100, remainingSeconds = 14),
    )

    fun pairNames(): Map<Int, String> = mapOf(
        1 to "Front Garden",
        2 to "Greenhouse A",
        4 to "Herb Bed",
        7 to "Back Lawn",
    )

    fun pairWiring(pairIndex: Int): BeetPairWiring = BeetPairWiring(
        pairIndex = pairIndex,
        moistureGpio = 32 + (pairIndex - 1) * 2,
        relayGpio = 33 + (pairIndex - 1) * 2,
    )

    fun pairWiringMap(): Map<Int, BeetPairWiring> = (1..8).associateWith(::pairWiring)

    // ---------- Calibrations ----------

    fun calibration(pairIndex: Int): BeetCalibration = BeetCalibration(
        pairIndex = pairIndex,
        dryMillivolts = 2700,
        wetMillivolts = 900,
        source = "USER",
        calibratedAtUnixSeconds = 1_748_000_000L,
    )

    fun calibrationMapAll(): Map<Int, BeetCalibration> = (1..8).associateWith(::calibration)

    fun calibrationMapPartial(): Map<Int, BeetCalibration> = (1..3).associateWith(::calibration)

    // ---------- Connection state helpers ----------

    fun connectionState(
        phase: BeetConnectionPhase,
        detail: String? = null,
    ): BeetConnectionState = BeetConnectionState(phase = phase, detail = detail)

    // ---------- Maintenance / firmware update ----------

    fun firmwareMetadata(
        productId: String = "beetmeister",
        hardwareRev: String = "rev_a",
        firmwareVersion: String = "v2.1.3",
        buildLabel: String = "stable-2026-05-22",
        maintenanceProtocolVersion: Int = 1,
        runtimeProtocolVersion: Int = 2,
        imageKind: String = "bundled",
    ): BeetFirmwareMetadata = BeetFirmwareMetadata(
        productId = productId,
        hardwareRev = hardwareRev,
        firmwareVersion = firmwareVersion,
        buildLabel = buildLabel,
        maintenanceProtocolVersion = maintenanceProtocolVersion,
        runtimeProtocolVersion = runtimeProtocolVersion,
        imageKind = imageKind,
        compatibleHardwareRevs = listOf("rev_a", "rev_b"),
    )

    fun firmwarePackageSummary(
        source: BeetFirmwareSource = BeetFirmwareSource.Bundled,
        firmwareVersion: String = "v2.1.3",
        buildLabel: String = "stable-2026-05-22",
        imageKind: String = "bundled",
        imageSize: Int = 524_288,
        isDowngrade: Boolean = false,
        runtimeProtocolWarning: Boolean = false,
    ): BeetFirmwarePackageSummary = BeetFirmwarePackageSummary(
        source = source,
        sourceLabel = if (source == BeetFirmwareSource.Bundled) "bundled" else "user-uploaded.bin",
        assetId = if (source == BeetFirmwareSource.Bundled) "bundled-stable" else "user-asset-1",
        metadata = firmwareMetadata(
            firmwareVersion = firmwareVersion,
            buildLabel = buildLabel,
            imageKind = imageKind,
        ),
        imageSize = imageSize,
        sha256Hex = "9f8a7b6c5d4e3f2a1908b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6",
        isDowngrade = isDowngrade,
        runtimeProtocolWarning = runtimeProtocolWarning,
    )

    fun maintenanceUpdateIdle(): BeetMaintenanceUpdateState = BeetMaintenanceUpdateState(
        bundledFirmware = firmwarePackageSummary(),
        phase = BeetMaintenanceUpdatePhase.Idle,
    )

    fun maintenanceUpdateSelected(): BeetMaintenanceUpdateState = BeetMaintenanceUpdateState(
        bundledFirmware = firmwarePackageSummary(),
        selectedFirmware = firmwarePackageSummary(firmwareVersion = "v2.1.3", isDowngrade = true),
        phase = BeetMaintenanceUpdatePhase.Ready,
        statusDetail = "Ready to install selected firmware image.",
    )

    fun maintenanceUpdateActive(
        bytesTransferred: Int = 360_000,
        totalBytes: Int = 524_288,
        elapsedSeconds: Int = 42,
        estimatedRemainingSeconds: Int? = 18,
        phase: BeetMaintenanceUpdatePhase = BeetMaintenanceUpdatePhase.Uploading,
    ): BeetMaintenanceUpdateState = BeetMaintenanceUpdateState(
        bundledFirmware = firmwarePackageSummary(),
        selectedFirmware = firmwarePackageSummary(),
        phase = phase,
        bytesTransferred = bytesTransferred,
        totalBytes = totalBytes,
        elapsedSeconds = elapsedSeconds,
        estimatedRemainingSeconds = estimatedRemainingSeconds,
        statusDetail = "Uploading firmware image...",
    )

    fun maintenanceUpdateCompleted(): BeetMaintenanceUpdateState = BeetMaintenanceUpdateState(
        bundledFirmware = firmwarePackageSummary(),
        selectedFirmware = firmwarePackageSummary(),
        phase = BeetMaintenanceUpdatePhase.Completed,
        bytesTransferred = 524_288,
        totalBytes = 524_288,
        elapsedSeconds = 64,
        statusDetail = "Update completed successfully.",
    )

    fun maintenanceUpdateFailed(): BeetMaintenanceUpdateState = BeetMaintenanceUpdateState(
        bundledFirmware = firmwarePackageSummary(),
        selectedFirmware = firmwarePackageSummary(),
        phase = BeetMaintenanceUpdatePhase.Failed,
        bytesTransferred = 120_000,
        totalBytes = 524_288,
        elapsedSeconds = 12,
        statusDetail = "Upload failed; controller did not acknowledge chunk 14.",
        errorDetail = "controller-abort(14)",
    )

    // ---------- Event sync state ----------

    fun eventSyncIdle(): BeetEventSyncState = BeetEventSyncState(
        active = false,
        downloaded = 0,
        total = 0,
        phase = BeetEventSyncPhase.Idle,
    )

    fun eventSyncActive(downloaded: Int = 64, total: Int = 240): BeetEventSyncState =
        BeetEventSyncState(
            active = true,
            downloaded = downloaded,
            total = total,
            phase = BeetEventSyncPhase.CatchingUp,
        )

    // ---------- Events ----------

    fun wateringEvent(
        pairIndex: Int,
        sequenceNumber: Long = 1L,
        triggerSource: Int = 1,
        actualDurationSeconds: Int = 18,
        moistureBeforePercent: Int = 32,
        moistureAfterPercent: Int = 71,
        sensorBeforeMillivolts: Int = 2120,
        sensorAfterMillivolts: Int = 1100,
        startedAtUnixSeconds: Long = 1_748_100_000L,
        endedAtUnixSeconds: Long = 1_748_100_018L,
    ): BeetWateringEvent = BeetWateringEvent(
        sequenceNumber = sequenceNumber,
        pairIndex = pairIndex,
        bootId = 1748213456L,
        triggerSource = triggerSource,
        startedAtUnixSeconds = startedAtUnixSeconds,
        endedAtUnixSeconds = endedAtUnixSeconds,
        moistureBeforePercent = moistureBeforePercent,
        moistureAfterPercent = moistureAfterPercent,
        sensorBeforeMillivolts = sensorBeforeMillivolts,
        sensorAfterMillivolts = sensorAfterMillivolts,
        requestedDurationSeconds = 20,
        actualDurationSeconds = actualDurationSeconds,
        stopReason = 0,
        blockReason = 0,
        batteryStartMillivolts = 3410,
        batteryEndMillivolts = 3380,
    )

    fun populatedWateringEvents(): List<BeetWateringEvent> = listOf(
        wateringEvent(1, sequenceNumber = 5L, actualDurationSeconds = 22),
        wateringEvent(2, sequenceNumber = 4L, actualDurationSeconds = 15),
        wateringEvent(0, sequenceNumber = 3L, actualDurationSeconds = 0, startedAtUnixSeconds = 0L, endedAtUnixSeconds = 0L),
        wateringEvent(3, sequenceNumber = 2L, actualDurationSeconds = 30),
        wateringEvent(4, sequenceNumber = 1L, actualDurationSeconds = 12),
    )

    fun systemEvent(
        sequenceNumber: Long,
        eventType: String,
        unixSeconds: Long = 1_748_100_000L,
        uptimeSeconds: Long = 18_240L,
        batteryMillivolts: Int = 3410,
        peerAddress: String = "AA:BB:CC:11:22:33",
    ): BeetSystemEvent = BeetSystemEvent(
        sequenceNumber = sequenceNumber,
        eventType = eventType,
        reason = 0,
        bootId = 1748213456L,
        uptimeSeconds = uptimeSeconds,
        unixSeconds = unixSeconds,
        batteryMillivolts = batteryMillivolts,
        peerAddress = peerAddress,
        peerAddressType = 0,
        knownPeer = true,
        detail = 0L,
    )

    fun populatedSystemEvents(): List<BeetSystemEvent> = listOf(
        systemEvent(1L, "BLE_CONNECT", unixSeconds = 1_748_100_500L),
        systemEvent(2L, "MQTT_CONNECT", unixSeconds = 1_748_100_510L, peerAddress = ""),
        systemEvent(3L, "STARTUP", unixSeconds = 1_748_100_000L),
        systemEvent(4L, "SLEEP", unixSeconds = 1_748_100_600L),
    )

    fun historySummary(): BeetHistorySummary = BeetHistorySummary(
        latestSequenceNumber = 5L,
        eventCount = 5,
        pairTotalsSeconds = listOf(120, 80, 0, 240, 30, 0, 0, 0),
    )

    fun systemHistorySummary(): BeetSystemHistorySummary = BeetSystemHistorySummary(
        latestSequenceNumber = 4L,
        eventCount = 4,
    )

    // ---------- Full BeetRepositoryState presets ----------

    fun connectedState(): BeetRepositoryState = BeetRepositoryState(
        connection = connectionState(BeetConnectionPhase.Connected, detail = null),
        controllerInfo = controllerInfo(),
        deviceState = deviceState(),
        valveConfig = valveConfig(),
        wateringInterval = wateringInterval(),
        maxActivePumps = 2,
        pairStates = mixedPairStates(),
        calibrations = calibrationMapAll(),
        pairNames = pairNames(),
        pairWirings = pairWiringMap(),
        recentEvents = populatedWateringEvents(),
        systemEvents = populatedSystemEvents(),
        eventSync = eventSyncIdle(),
        maintenanceUpdate = maintenanceUpdateIdle(),
        historySummary = historySummary(),
        systemHistorySummary = systemHistorySummary(),
        connectedAtMillis = 1_748_110_000_000L,
        connectedAtControllerUptimeSeconds = 18_200L,
    )

    fun disconnectedState(): BeetRepositoryState = BeetRepositoryState(
        connection = connectionState(
            phase = BeetConnectionPhase.Disconnected,
            detail = "Lost link to controller while syncing.",
        ),
        maintenanceInfo = maintenanceInfo(),
        deviceState = null,
        pairStates = emptyList(),
    )
}
