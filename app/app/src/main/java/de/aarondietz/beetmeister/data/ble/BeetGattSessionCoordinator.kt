package de.aarondietz.beetmeister.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.util.Log
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.data.local.BeetEventCache
import de.aarondietz.beetmeister.data.local.mergeRetainedSystemEvents
import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import de.aarondietz.beetmeister.data.repository.BeetBacklogFetchResult
import de.aarondietz.beetmeister.data.repository.BeetBacklogFetchStatus
import de.aarondietz.beetmeister.data.repository.BeetBacklogSyncConfig
import de.aarondietz.beetmeister.data.repository.BeetBacklogSyncInput
import de.aarondietz.beetmeister.data.repository.BeetBacklogSyncRunner
import de.aarondietz.beetmeister.data.repository.BeetRepositoryCallbacks
import de.aarondietz.beetmeister.data.repository.commandMessageForResult
import de.aarondietz.beetmeister.model.command.BeetCommandResult
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.stream.BeetEventSyncPhase
import de.aarondietz.beetmeister.model.stream.BeetEventSyncState
import de.aarondietz.beetmeister.model.stream.BeetStateMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import kotlin.math.max

internal class BeetGattSessionCoordinator(
    private val host: BeetRepositoryCallbacks,
) {
    private val strings get() = host.strings
    private val commandMutex = Mutex()
    private val eventCache = BeetEventCache(host.appContext.getSharedPreferences("beetmeister_event_cache", android.content.Context.MODE_PRIVATE))
    private var connectionTimeoutJob: Job? = null
    private var controllerInfoRetryJob: Job? = null
    private var eventSyncJob: Job? = null
    @Volatile
    private var syncPauseRequested = false
    private val pendingMoistureTests = mutableMapOf<Int, Boolean>()
    private val commandChunkAssembler = BeetCommandResultChunkAssembler()

    fun close() {
        Log.d(TAG, "close()")
        disconnectGatt(clearSelection = false, reason = "repository close")
        cancelControllerInfoRetry("repository close")
        eventSyncJob?.cancel()
        eventSyncJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    fun clearCommandMessage() {
        host.clearCommandMessage()
    }

    fun refreshCalibrations() {
        host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            host.updateState { state -> state.copy(calibrationsRefreshing = true) }
            try {
                withSyncPausedForCommand {
                    for (pairIndex in 1..8) {
                        runCatching { sendCommand(BeetJsonCodec.getCalibration(pairIndex)) }
                            .onFailure { host.setCommandMessage(strings.get(R.string.runtime_calibration_refresh_failed, pairIndex)) }
                    }
                }
            } finally {
                host.updateState { state -> state.copy(calibrationsRefreshing = false) }
            }
        }
    }

    fun refreshHistorySummary() {
        startBackgroundEventSync(force = true)
    }

    fun loadRecentEvents(limit: Int = 50) {
        startBackgroundEventSync(force = true, limit = limit)
    }

    fun manualStart(pairIndex: Int, durationSeconds: Int?) {
        host.scope.launch {
            if (durationSeconds != null && durationSeconds !in 1..MAX_MANUAL_DURATION_SECONDS) {
                host.setCommandMessage(strings.get(R.string.runtime_manual_duration_invalid))
                return@launch
            }
            sendUserCommand(BeetJsonCodec.manualStart(pairIndex, durationSeconds))
        }
    }

    fun manualStop(pairIndex: Int) {
        host.scope.launch { sendUserCommand(BeetJsonCodec.manualStop(pairIndex)) }
    }

    fun moistureTestStart(pairIndex: Int) {
        host.scope.launch {
            pendingMoistureTests[pairIndex] = false
            runCatching {
                withSyncPausedForCommand { sendCommand(BeetJsonCodec.moistureTestStart(pairIndex)) }
            }
                .onSuccess { result ->
                    if (result.status != "accepted") {
                        pendingMoistureTests.remove(pairIndex)
                    }
                    host.setCommandMessage(messageForResult(result))
                }
                .onFailure { error ->
                    pendingMoistureTests.remove(pairIndex)
                    host.setCommandMessage(error.message ?: strings.get(R.string.runtime_command_failed))
                }
        }
    }

    fun clearPairError(pairIndex: Int) {
        host.scope.launch { sendUserCommand(BeetJsonCodec.resetBlock(pairIndex)) }
    }

    fun resetBlock(pairIndex: Int) = clearPairError(pairIndex)

    fun disablePair(pairIndex: Int) {
        host.scope.launch { sendUserCommand(BeetJsonCodec.disablePair(pairIndex)) }
    }

    fun enablePair(pairIndex: Int) {
        host.scope.launch { sendUserCommand(BeetJsonCodec.enablePair(pairIndex)) }
    }

    fun saveCalibration(pairIndex: Int, dryMillivolts: Int, wetMillivolts: Int) {
        host.scope.launch {
            if (dryMillivolts <= wetMillivolts || dryMillivolts == 0 || wetMillivolts == 0) {
                host.setCommandMessage(strings.get(R.string.runtime_calibration_invalid_order))
                return@launch
            }
            sendUserCommand(BeetJsonCodec.storeCalibration(pairIndex, dryMillivolts, wetMillivolts))
            refreshCalibrations()
        }
    }

    fun refreshValveConfig() {
        host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            host.updateState { state -> state.copy(valveConfigRefreshing = true) }
            try {
                runCatching { withSyncPausedForCommand { sendCommand(BeetJsonCodec.getValveConfig()) } }
            } finally {
                host.updateState { state -> state.copy(valveConfigRefreshing = false) }
            }
        }
    }

    fun saveValveConfig(config: BeetValveConfig) {
        host.scope.launch {
            if (
                config.servoMinPulseMicros !in 500..2500 ||
                config.servoMaxPulseMicros !in 500..2500 ||
                config.servoMinPulseMicros >= config.servoMaxPulseMicros ||
                config.openPulseMicros !in config.servoMinPulseMicros..config.servoMaxPulseMicros ||
                config.shutPulseMicros !in config.servoMinPulseMicros..config.servoMaxPulseMicros ||
                config.moveDurationMillis !in 100..5000 ||
                config.settleDelayMillis !in 0..5000 ||
                config.openHoldMillis !in 0..10000
            ) {
                host.setCommandMessage(strings.get(R.string.runtime_valve_config_invalid))
                return@launch
            }
            sendUserCommand(BeetJsonCodec.storeValveConfig(config))
        }
    }

    fun previewValvePosition(pulseMicros: Int) {
        host.scope.launch {
            runCatching { withSyncPausedForCommand { sendCommand(BeetJsonCodec.previewValvePosition(pulseMicros)) } }
                .onFailure { error -> host.setCommandMessage(error.message ?: strings.get(R.string.runtime_command_failed)) }
        }
    }

    fun openValve() {
        host.scope.launch { sendUserCommand(BeetJsonCodec.openValve()) }
    }

    fun closeValve() {
        host.scope.launch { sendUserCommand(BeetJsonCodec.closeValve()) }
    }

    fun openGatt(device: BluetoothDevice) {
        Log.d(TAG, "openGatt(address=${device.address}, bondState=${device.bondState})")
        disconnectGatt(clearSelection = false, reason = "openGatt reset existing session")
        resetSyncState()
        host.updateConnection(BeetConnectionPhase.Connecting, strings.get(R.string.runtime_connecting_to_controller, device.address))
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = host.scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                Log.w(TAG, "Connection timeout fired while phase=${host.state.value.connection.phase}")
                disconnectGatt(clearSelection = false, reason = "connection timeout")
                host.clearSession()
                host.requestStartScan(detail = strings.get(R.string.runtime_connection_timed_out))
            }
        }
        @Suppress("MissingPermission")
        host.session.currentGatt = device.connectGatt(host.appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect(clearSelection: Boolean, reason: String) {
        Log.d(TAG, "disconnect(clearSelection=$clearSelection, reason=$reason)")
        disconnectGatt(clearSelection, reason)
    }

    private suspend fun sendUserCommand(payload: String) {
        runCatching { withSyncPausedForCommand { sendCommand(payload) } }
            .onSuccess { result -> host.setCommandMessage(messageForResult(result)) }
            .onFailure { error -> host.setCommandMessage(error.message ?: strings.get(R.string.runtime_command_failed)) }
    }

    private suspend fun <T> withSyncPausedForCommand(block: suspend () -> T): T {
        syncPauseRequested = true
        host.updateState { state ->
            if (state.eventSync.active) {
                state.copy(eventSync = state.eventSync.copy(phase = BeetEventSyncPhase.PausedForCommand))
            } else {
                state
            }
        }
        return try {
            block()
        } finally {
            syncPauseRequested = false
        }
    }

    private suspend fun awaitSyncResumeIfNeeded() {
        while (syncPauseRequested && host.state.value.connection.phase == BeetConnectionPhase.Connected) {
            delay(SYNC_PAUSE_POLL_MS)
        }
    }

    private suspend fun sendSyncCommand(payload: String): BeetCommandResult {
        awaitSyncResumeIfNeeded()
        return sendCommand(payload)
    }

    private suspend fun sendCommand(payload: String): BeetCommandResult {
        return commandMutex.withLock {
            val gatt = host.session.currentGatt ?: error(strings.get(R.string.runtime_no_connected_controller))
            val controlPoint = host.session.controlPointCharacteristic ?: error(strings.get(R.string.runtime_control_point_unavailable))
            val deferred = CompletableDeferred<BeetCommandResult>()
            host.session.pendingCommand = deferred

            controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            controlPoint.value = payload.toByteArray(StandardCharsets.UTF_8)
            @Suppress("MissingPermission")
            val writeStarted = gatt.writeCharacteristic(controlPoint)
            if (!writeStarted) {
                host.session.pendingCommand = null
                error(strings.get(R.string.runtime_ble_send_failed))
            }

            try {
                withTimeout(COMMAND_TIMEOUT_MS) { deferred.await() }
            } catch (timeout: TimeoutCancellationException) {
                commandChunkAssembler.reset()
                throw timeout
            } finally {
                host.session.pendingCommand = null
            }
        }
    }

    private fun startBackgroundEventSync(force: Boolean = false, limit: Int = MAX_BACKGROUND_EVENT_DOWNLOAD) {
        if (!force && eventSyncJob?.isActive == true) {
            return
        }
        eventSyncJob?.cancel()
        eventSyncJob = host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            if (!synchronizeControllerTimeIfNeeded()) {
                val deviceState = host.state.value.deviceState
                Log.w(
                    TAG,
                    "startBackgroundEventSync aborted because controller time is unavailable " +
                        "bootId=${deviceState?.bootId} timeValid=${deviceState?.timeValid} syncedTimeBootId=${host.session.syncedTimeBootId}",
                )
                host.updateState {
                    it.copy(
                        calibrationsRefreshing = false,
                        eventsLoading = false,
                        eventSync = BeetEventSyncState(
                            active = false,
                            downloaded = 0,
                            total = 0,
                            phase = BeetEventSyncPhase.PausedForCommand,
                        ),
                        valveConfigRefreshing = false,
                    )
                }
                return@launch
            }
            val deviceId = host.state.value.controllerInfo?.deviceId ?: return@launch
            val cachedWatering = eventCache.loadWateringEvents(deviceId)
            val cachedSystem = eventCache.loadSystemEvents(deviceId)
            host.updateState {
                it.copy(
                    recentEvents = mergeWateringEvents(it.recentEvents, cachedWatering),
                    systemEvents = mergeSystemEvents(it.systemEvents, cachedSystem),
                    eventsLoading = true,
                    eventSync = BeetEventSyncState(active = true, phase = BeetEventSyncPhase.CatchingUp),
                )
            }

            val wateringSummary = runCatching { sendSyncCommand(BeetJsonCodec.getHistorySummary()) }.getOrNull()?.historySummary
            val systemSummary = runCatching { sendSyncCommand(BeetJsonCodec.getSystemHistorySummary()) }.getOrNull()?.systemHistorySummary
            host.updateState {
                it.copy(
                    historySummary = wateringSummary ?: it.historySummary,
                    systemHistorySummary = systemSummary ?: it.systemHistorySummary,
                )
            }

            val runner = BeetBacklogSyncRunner(
                config = BeetBacklogSyncConfig(
                    retentionSeconds = EVENT_RETENTION_SECONDS,
                    initialBatchSize = INITIAL_SYNC_BATCH_SIZE,
                    maxBatchSize = MAX_SYNC_BATCH_SIZE,
                    batchGrowthStep = SYNC_BATCH_GROWTH_STEP,
                    burstDelayMs = SYNC_BURST_DELAY_MS,
                    pausePollDelayMs = SYNC_PAUSE_POLL_MS,
                    congestionDelayMs = SYNC_CONGESTION_DELAY_MS,
                    transientFailurePerSequenceLimit = SYNC_TRANSIENT_FAILURE_LIMIT,
                ),
                nowUnixSeconds = { System.currentTimeMillis() / 1000L },
                sleep = { delay(it) },
            )

            runner.run(
                input = BeetBacklogSyncInput(
                    wateringSummary = wateringSummary,
                    systemSummary = systemSummary,
                    existingWateringSequences = host.state.value.recentEvents.map { event -> event.sequenceNumber }.toSet(),
                    existingSystemSequences = host.state.value.systemEvents.map { event -> event.sequenceNumber }.toSet(),
                    limit = limit,
                ),
                isConnected = { host.state.value.connection.phase == BeetConnectionPhase.Connected },
                isPauseRequested = { syncPauseRequested },
                onProgress = { progress ->
                    host.updateState {
                        it.copy(
                            eventSync = it.eventSync.copy(
                                active = progress.active,
                                downloaded = progress.downloaded,
                                total = progress.total,
                                phase = progress.phase,
                            ),
                        )
                    }
                },
                onWateringEvent = { event -> ingestWateringEvent(deviceId, event) },
                onSystemEvent = { event -> ingestSystemEvent(deviceId, event) },
                fetchWateringEvent = { sequence -> fetchWateringEventForSync(sequence) },
                fetchSystemEvent = { sequence -> fetchSystemEventForSync(sequence) },
            )

            host.updateState { state ->
                state.copy(
                    calibrationsRefreshing = false,
                    eventsLoading = false,
                    eventSync = BeetEventSyncState(),
                    valveConfigRefreshing = false,
                )
            }
        }
    }

    private suspend fun fetchWateringEventForSync(sequence: Long): BeetBacklogFetchResult<BeetWateringEvent> {
        val result = runCatching { sendSyncCommand(BeetJsonCodec.getEvent(sequence)) }.getOrNull()
            ?: run {
                Log.w(TAG, "fetchWateringEventForSync seq=$sequence command failed before a result was returned")
                return BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
            }
        val reason = result.reason.lowercase()
        return when {
            result.status == "accepted" && result.event != null ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Accepted, event = result.event)
            reason == "busy" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Busy)
            reason == "rate_limited" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.RateLimited)
            reason == "event_not_found" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.NotFound)
            else ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
        }
    }

    private suspend fun fetchSystemEventForSync(sequence: Long): BeetBacklogFetchResult<BeetSystemEvent> {
        val result = runCatching { sendSyncCommand(BeetJsonCodec.getSystemEvent(sequence)) }.getOrNull()
            ?: run {
                Log.w(TAG, "fetchSystemEventForSync seq=$sequence command failed before a result was returned")
                return BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
            }
        val reason = result.reason.lowercase()
        return when {
            result.status == "accepted" && result.systemEvent != null ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Accepted, event = result.systemEvent)
            reason == "busy" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Busy)
            reason == "rate_limited" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.RateLimited)
            reason == "event_not_found" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.NotFound)
            else ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
        }
    }

    private fun mergeWateringEvents(current: List<BeetWateringEvent>, incoming: List<BeetWateringEvent>): List<BeetWateringEvent> =
        (current + incoming)
            .filter {
                it.bootId > 0L &&
                    (!it.timeValid || it.endedAtUnixSeconds >= ((System.currentTimeMillis() / 1000L) - EVENT_RETENTION_SECONDS))
            }
            .associateBy { it.sequenceNumber }
            .values
            .sortedByDescending { it.sequenceNumber }

    private fun mergeSystemEvents(current: List<BeetSystemEvent>, incoming: List<BeetSystemEvent>): List<BeetSystemEvent> =
        mergeRetainedSystemEvents(
            current = current,
            incoming = incoming,
            cutoffUnixSeconds = (System.currentTimeMillis() / 1000L) - EVENT_RETENTION_SECONDS,
        )

    private fun ingestWateringEvent(deviceId: String, event: BeetWateringEvent) {
        eventCache.saveWateringEvent(deviceId, event)
        host.updateState { state -> state.copy(recentEvents = mergeWateringEvents(state.recentEvents, listOf(event))) }
    }

    private fun ingestSystemEvent(deviceId: String, event: BeetSystemEvent) {
        eventCache.saveSystemEvent(deviceId, event)
        host.updateState { state -> state.copy(systemEvents = mergeSystemEvents(state.systemEvents, listOf(event))) }
    }

    private suspend fun synchronizeControllerTimeIfNeeded(): Boolean {
        val deviceState = host.state.value.deviceState ?: return false
        if (deviceState.timeValid) {
            host.session.syncedTimeBootId = deviceState.bootId
            return true
        }
        if (deviceState.bootId > 0L && host.session.syncedTimeBootId == deviceState.bootId) {
            Log.w(TAG, "synchronizeControllerTimeIfNeeded refusing duplicate set_time attempt for bootId=${deviceState.bootId}")
            return false
        }
        val unixSeconds = System.currentTimeMillis() / 1000L
        val result = runCatching { sendCommand(BeetJsonCodec.setTime(unixSeconds)) }.getOrNull() ?: return false
        if (result.status == "accepted") {
            repeat(10) {
                val refreshed = host.state.value.deviceState
                if (refreshed?.timeValid == true) {
                    host.session.syncedTimeBootId = refreshed.bootId
                    return true
                }
                delay(150L)
            }
        }
        Log.w(TAG, "synchronizeControllerTimeIfNeeded timed out waiting for time_valid after set_time")
        return false
    }

    private fun configureServices(gatt: BluetoothGatt): Boolean {
        val service: BluetoothGattService = gatt.getService(BeetBluetoothSupport.serviceUuid) ?: return false
        host.session.controllerInfoCharacteristic = service.getCharacteristic(BeetBluetoothSupport.controllerInfoUuid)
        host.session.stateStreamCharacteristic = service.getCharacteristic(BeetBluetoothSupport.stateStreamUuid)
        host.session.controlPointCharacteristic = service.getCharacteristic(BeetBluetoothSupport.controlPointUuid)
        host.session.commandResultCharacteristic = service.getCharacteristic(BeetBluetoothSupport.commandResultUuid)
        Log.d(
            TAG,
            "configureServices(controllerInfo=${host.session.controllerInfoCharacteristic != null}, stateStream=${host.session.stateStreamCharacteristic != null}, controlPoint=${host.session.controlPointCharacteristic != null}, commandResult=${host.session.commandResultCharacteristic != null})",
        )
        if (host.session.controllerInfoCharacteristic == null ||
            host.session.stateStreamCharacteristic == null ||
            host.session.controlPointCharacteristic == null ||
            host.session.commandResultCharacteristic == null
        ) {
            return false
        }

        host.session.descriptorQueue.clear()
        host.session.descriptorQueue.add(host.session.commandResultCharacteristic!! to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        host.session.descriptorQueue.add(host.session.stateStreamCharacteristic!! to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        return writeNextDescriptor(gatt)
    }

    private fun writeNextDescriptor(gatt: BluetoothGatt): Boolean {
        val next = host.session.descriptorQueue.removeFirstOrNull() ?: return readControllerInfo(gatt)
        val characteristic = next.first
        val descriptor = characteristic.getDescriptor(BeetBluetoothSupport.clientConfigUuid) ?: return false
        Log.d(TAG, "writeNextDescriptor(uuid=${characteristic.uuid}, queueRemaining=${host.session.descriptorQueue.size})")
        @Suppress("MissingPermission")
        gatt.setCharacteristicNotification(characteristic, true)
        descriptor.value = next.second
        @Suppress("MissingPermission")
        return gatt.writeDescriptor(descriptor)
    }

    private fun readControllerInfo(gatt: BluetoothGatt): Boolean {
        val characteristic = host.session.controllerInfoCharacteristic ?: return false
        if (host.state.value.controllerInfo != null) {
            cancelControllerInfoRetry("controller info already loaded")
            Log.d(TAG, "Skipping controller info read because it is already loaded")
            return true
        }
        if (!host.session.initialSyncCompleted && host.state.value.connection.phase != BeetConnectionPhase.Connected) {
            host.updateConnection(BeetConnectionPhase.Syncing, strings.get(R.string.runtime_reading_controller_info))
        } else {
            Log.d(
                TAG,
                "Reading controller info without phase downgrade (initialSyncCompleted=${host.session.initialSyncCompleted}, phase=${host.state.value.connection.phase})",
            )
        }
        host.session.controllerInfoReadAttempts += 1
        Log.d(TAG, "Reading controller info, attempt=${host.session.controllerInfoReadAttempts}")
        @Suppress("MissingPermission")
        val started = gatt.readCharacteristic(characteristic)
        if (!started) {
            Log.w(TAG, "Controller info read did not start on attempt=${host.session.controllerInfoReadAttempts}")
            scheduleControllerInfoRetry(gatt, "read start returned false")
        }
        return true
    }

    private fun scheduleControllerInfoRetry(gatt: BluetoothGatt, reason: String) {
        if (host.session.initialSyncCompleted) {
            Log.d(TAG, "Ignoring controller info retry because initial sync already completed: reason=$reason")
            return
        }
        if (host.state.value.controllerInfo != null) {
            Log.d(TAG, "Ignoring controller info retry because controller info is already loaded: reason=$reason")
            return
        }
        if (host.session.controllerInfoReadAttempts >= MAX_CONTROLLER_INFO_READ_ATTEMPTS) {
            Log.w(TAG, "Controller info read exhausted retries: reason=$reason")
            return
        }
        cancelControllerInfoRetry("reschedule: $reason")
        Log.w(TAG, "Scheduling controller info retry attempt=${host.session.controllerInfoReadAttempts + 1} reason=$reason")
        controllerInfoRetryJob = host.scope.launch {
            delay(CONTROLLER_INFO_READ_RETRY_DELAY_MS)
            if (host.session.currentGatt != gatt) {
                Log.d(TAG, "Skipping controller info retry because the GATT session changed")
                controllerInfoRetryJob = null
                return@launch
            }
            if (host.session.controllerInfoCharacteristic == null) {
                Log.d(TAG, "Skipping controller info retry because controller info characteristic is unavailable")
                controllerInfoRetryJob = null
                return@launch
            }
            if (host.session.initialSyncCompleted) {
                Log.d(TAG, "Skipping controller info retry because initial sync already completed")
                controllerInfoRetryJob = null
                return@launch
            }
            if (host.state.value.controllerInfo != null) {
                Log.d(TAG, "Skipping controller info retry because controller info is already loaded")
                controllerInfoRetryJob = null
                return@launch
            }
            controllerInfoRetryJob = null
            readControllerInfo(gatt)
        }
    }

    private fun completeInitialSyncIfReady() {
        if (host.session.initialSyncCompleted) {
            return
        }
        if (!host.session.initialDeviceFrameReceived || host.session.syncedPairCount() != 8) {
            return
        }
        host.session.initialSyncCompleted = true
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        cancelControllerInfoRetry("initial sync completed")
        host.persistLastAddress(host.currentAddress)
        Log.d(TAG, "Initial sync completed for session address=${host.currentAddress}")
        host.updateConnection(BeetConnectionPhase.Connected, strings.get(R.string.runtime_connected_to_controller))
        refreshValveConfig()
        startBackgroundEventSync()
    }

    private fun handleControllerInfo(payload: ByteArray) {
        val info = try {
            BeetJsonCodec.parseControllerInfo(payload.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            Log.e(TAG, "Controller info payload parse failed", error)
            disconnectGatt(clearSelection = false, reason = "invalid controller info payload")
            host.clearSession()
            host.updateConnection(BeetConnectionPhase.Error, strings.get(R.string.runtime_controller_info_invalid))
            return
        }
        if (info.protocolVersion != EXPECTED_PROTOCOL_VERSION) {
            Log.e(TAG, "Unsupported protocol version ${info.protocolVersion}, expected=${EXPECTED_PROTOCOL_VERSION}")
            disconnectGatt(clearSelection = false, reason = "unsupported protocol version ${info.protocolVersion}")
            host.clearSession()
            host.updateConnection(
                BeetConnectionPhase.Error,
                strings.get(R.string.runtime_unsupported_protocol, info.protocolVersion, EXPECTED_PROTOCOL_VERSION),
            )
            return
        }
        host.session.controllerInfoReadAttempts = 0
        cancelControllerInfoRetry("controller info read succeeded")
        Log.d(TAG, "handleControllerInfo(deviceId=${info.deviceId}, protocol=${info.protocolVersion}, pairCount=${info.pairCount})")
        host.updateState { it.copy(controllerInfo = info) }
    }

    private fun handleStatePayload(payload: ByteArray) {
        val json = payload.toString(StandardCharsets.UTF_8)
        val message = try {
            BeetJsonCodec.parseStateMessage(json)
        } catch (error: Exception) {
            Log.e(TAG, "State payload parse failed: $json", error)
            null
        }
        when (message) {
            is BeetStateMessage.DeviceStateUpdate -> {
                val deviceState = message.data
                host.session.initialDeviceFrameReceived = true
                if (deviceState.timeValid) {
                    host.session.syncedTimeBootId = deviceState.bootId
                }
                Log.d(TAG, "handleStatePayload(deviceFrame battery=${deviceState.batteryMillivolts} activePumps=${deviceState.activePumps})")
                host.updateState { state ->
                    state.copy(
                        deviceState = deviceState,
                        connectedAtMillis = if (state.connectedAtMillis == 0L) System.currentTimeMillis() else state.connectedAtMillis,
                        connectedAtControllerUptimeSeconds = if (state.connectedAtControllerUptimeSeconds == 0L) deviceState.uptimeSeconds else state.connectedAtControllerUptimeSeconds,
                    )
                }
                completeInitialSyncIfReady()
            }
            is BeetStateMessage.PairStateUpdate -> {
                val pairState = message.data
                host.session.markPairSynced(pairState.pairIndex)
                Log.d(TAG, "handleStatePayload(pairFrame pair=${pairState.pairIndex} state=${pairState.state} syncedPairs=${host.session.syncedPairCount()})")
                host.updateState { state ->
                    state.copy(
                        pairStates = state.pairStates.map { existing ->
                            if (existing.pairIndex == pairState.pairIndex) pairState else existing
                        },
                    )
                }
                handleMoistureTestState(pairState)
                completeInitialSyncIfReady()
            }
            null -> {
                Log.w(TAG, "Ignoring unknown state payload: $json")
            }
            is BeetStateMessage.SystemEventUpdate -> {
                val deviceId = host.state.value.controllerInfo?.deviceId
                if (deviceId != null) {
                    ingestSystemEvent(deviceId, message.data)
                } else {
                    host.updateState { it.copy(systemEvents = mergeSystemEvents(it.systemEvents, listOf(message.data))) }
                }
            }
        }
    }

    private fun handleCommandPayload(payload: ByteArray) {
        val payloadString = payload.toString(StandardCharsets.UTF_8)
        val chunkFrame = try {
            BeetJsonCodec.parseCommandChunk(payloadString)
        } catch (error: Exception) {
            Log.e(TAG, "Command chunk parse failed payload=$payloadString", error)
            commandChunkAssembler.reset()
            return
        }
        val decodedPayload = if (chunkFrame != null) {
            try {
                commandChunkAssembler.consume(chunkFrame, System.currentTimeMillis())
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Command chunk reassembly failed id=${chunkFrame.id} index=${chunkFrame.index} count=${chunkFrame.count}",
                    error,
                )
                commandChunkAssembler.reset()
                return
            } ?: return
        } else {
            if (commandChunkAssembler.hasActiveChunks) {
                Log.w(TAG, "Command chunk reassembly reset due to non-chunk payload while chunked response is active")
                commandChunkAssembler.reset()
            }
            payloadString
        }
        val result = try {
            BeetJsonCodec.parseCommandResult(decodedPayload)
        } catch (error: Exception) {
            Log.e(TAG, "Command payload parse failed", error)
            return
        }
        result.calibration?.let { calibration ->
            host.updateState { state -> state.copy(calibrations = state.calibrations + (calibration.pairIndex to calibration)) }
        }
        result.historySummary?.let { summary ->
            host.updateState { it.copy(historySummary = summary) }
        }
        result.event?.let { event ->
            val deviceId = host.state.value.controllerInfo?.deviceId
            if (deviceId != null) {
                ingestWateringEvent(deviceId, event)
            } else {
                host.updateState { state -> state.copy(recentEvents = mergeWateringEvents(state.recentEvents, listOf(event))) }
            }
        }
        result.systemHistorySummary?.let { summary ->
            host.updateState { it.copy(systemHistorySummary = summary) }
        }
        result.systemEvent?.let { event ->
            val deviceId = host.state.value.controllerInfo?.deviceId
            if (deviceId != null) {
                ingestSystemEvent(deviceId, event)
            } else {
                host.updateState { state -> state.copy(systemEvents = mergeSystemEvents(state.systemEvents, listOf(event))) }
            }
        }
        result.valveConfig?.let { config ->
            host.updateState { it.copy(valveConfig = config) }
        }
        host.session.pendingCommand?.complete(result)
    }

    private fun resetSyncState() {
        Log.d(TAG, "resetSyncState()")
        commandChunkAssembler.reset()
        cancelControllerInfoRetry("reset sync state")
        host.resetSyncState()
    }

    private fun disconnectGatt(clearSelection: Boolean, reason: String) {
        Log.d(TAG, "disconnectGatt(reason=$reason, clearSelection=$clearSelection, currentAddress=${host.currentAddress}, phase=${host.state.value.connection.phase})")
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        cancelControllerInfoRetry("disconnect gatt: $reason")
        eventSyncJob?.cancel()
        eventSyncJob = null
        pendingMoistureTests.clear()
        val gatt = host.session.currentGatt
        host.session.currentGatt = null
        resetSyncState()
        host.session.pendingCommand?.cancel()
        host.session.pendingCommand = null
        host.updateState { state ->
            state.copy(
                calibrationsRefreshing = false,
                eventsLoading = false,
                eventSync = BeetEventSyncState(),
                valveConfigRefreshing = false,
            )
        }
        if (clearSelection) {
            host.currentAddress = null
            host.updateState { it.copy(selectedAddress = null) }
        }
        if (gatt != null) {
            @Suppress("MissingPermission")
            gatt.disconnect()
            @Suppress("MissingPermission")
            gatt.close()
        }
    }

    private fun cancelControllerInfoRetry(reason: String) {
        val retryJob = controllerInfoRetryJob ?: return
        Log.d(TAG, "Cancelling controller info retry: reason=$reason active=${retryJob.isActive}")
        retryJob.cancel()
        controllerInfoRetryJob = null
    }

    private fun messageForResult(result: BeetCommandResult): String = commandMessageForResult(result, strings)

    private fun handleMoistureTestState(pairState: BeetPairState) {
        val hasSeenActiveState = pendingMoistureTests[pairState.pairIndex] ?: return
        when {
            pairState.state == "MOISTURE_TEST" -> {
                pendingMoistureTests[pairState.pairIndex] = true
            }
            hasSeenActiveState && pairState.state == "IDLE" -> {
                pendingMoistureTests.remove(pairState.pairIndex)
                host.setCommandMessage(strings.get(R.string.runtime_moisture_test_passed, pairState.pairIndex))
            }
            hasSeenActiveState && (pairState.blocked || pairState.state == "FAULT") -> {
                pendingMoistureTests.remove(pairState.pairIndex)
                host.setCommandMessage(
                    strings.get(
                        R.string.runtime_moisture_test_failed,
                        pairState.pairIndex,
                        pairBlockReasonLabel(pairState.blockReason),
                    ),
                )
            }
        }
    }

    private val gattCallback = beetGattCallback(
        onConnectionStateChange = { gatt, status, newState ->
            Log.d(TAG, "onConnectionStateChange(status=$status, newState=$newState, address=${gatt.device.address})")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val staleBondCandidate =
                    status == 22 &&
                        host.currentAddress != null &&
                        host.state.value.connection.phase in setOf(
                            BeetConnectionPhase.Connecting,
                            BeetConnectionPhase.DiscoveringServices,
                            BeetConnectionPhase.Syncing,
                        )
                disconnectGatt(clearSelection = false, reason = "gatt error status=$status")
                host.clearSession()
                    if (staleBondCandidate) {
                        host.recoverFromStaleBond(gatt.device.address, status)
                    } else {
                        host.requestStartScan(detail = strings.get(R.string.runtime_ble_connection_error, status))
                    }
                return@beetGattCallback
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    host.updateConnection(BeetConnectionPhase.DiscoveringServices, strings.get(R.string.runtime_negotiating_ble_session))
                    @Suppress("MissingPermission")
                    if (!gatt.requestMtu(DESIRED_MTU)) {
                        @Suppress("MissingPermission")
                        gatt.discoverServices()
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    disconnectGatt(clearSelection = false, reason = "gatt disconnected callback")
                    host.clearSession()
                    if (!host.manualDisconnectRequested) {
                        host.requestStartScan(detail = strings.get(R.string.runtime_controller_disconnected))
                    } else {
                        host.updateConnection(BeetConnectionPhase.Disconnected, strings.get(R.string.runtime_disconnected_from_controller))
                    }
                }
            }
        },
        onMtuChanged = { gatt, mtu, status ->
            Log.d(TAG, "onMtuChanged status=$status mtu=$mtu")
            @Suppress("MissingPermission")
            gatt.discoverServices()
        },
        onServicesDiscovered = { gatt, status ->
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS || !configureServices(gatt)) {
                disconnectGatt(clearSelection = false, reason = "services discovered failed status=$status")
                host.clearSession()
                host.requestStartScan(detail = strings.get(R.string.runtime_gatt_service_incomplete))
            }
        },
        onDescriptorWrite = { gatt, descriptor, status ->
            Log.d(TAG, "onDescriptorWrite uuid=${descriptor.characteristic.uuid} status=$status queueRemaining=${host.session.descriptorQueue.size}")
            if (status != BluetoothGatt.GATT_SUCCESS || !writeNextDescriptor(gatt)) {
                disconnectGatt(clearSelection = false, reason = "descriptor write failed status=$status uuid=${descriptor.characteristic.uuid}")
                host.clearSession()
                host.requestStartScan(detail = strings.get(R.string.runtime_subscription_failed))
            }
        },
        onCharacteristicRead = { gatt, characteristic, status ->
            Log.d(TAG, "onCharacteristicRead uuid=${characteristic.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                scheduleControllerInfoRetry(gatt, "read callback status=$status")
                return@beetGattCallback
            }
            handleControllerInfo(characteristic.value ?: ByteArray(0))
        },
        onCharacteristicChanged = { _, characteristic ->
            Log.d(TAG, "onCharacteristicChanged uuid=${characteristic.uuid} size=${characteristic.value?.size ?: 0}")
            val payload = characteristic.value ?: ByteArray(0)
            when (characteristic.uuid) {
                BeetBluetoothSupport.stateStreamUuid -> handleStatePayload(payload)
                BeetBluetoothSupport.commandResultUuid -> handleCommandPayload(payload)
            }
        },
    )

    companion object {
        private const val TAG = "BeetGattSession"
        private const val COMMAND_TIMEOUT_MS = 7_000L
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val CONTROLLER_INFO_READ_RETRY_DELAY_MS = 400L
        private const val MAX_CONTROLLER_INFO_READ_ATTEMPTS = 4
        private const val DESIRED_MTU = 247
        private const val EXPECTED_PROTOCOL_VERSION = 8
        private const val MAX_BACKGROUND_EVENT_DOWNLOAD = 120
        private const val INITIAL_SYNC_BATCH_SIZE = 1
        private const val MAX_SYNC_BATCH_SIZE = 8
        private const val SYNC_BATCH_GROWTH_STEP = 1
        private const val SYNC_BURST_DELAY_MS = 20L
        private const val SYNC_PAUSE_POLL_MS = 50L
        private const val SYNC_CONGESTION_DELAY_MS = 150L
        private const val SYNC_TRANSIENT_FAILURE_LIMIT = 2
        private const val EVENT_RETENTION_SECONDS = 30L * 24L * 60L * 60L
        private const val MAX_MANUAL_DURATION_SECONDS = 1200
    }

    private fun pairBlockReasonLabel(code: String): String = when (code) {
        "NONE" -> strings.get(R.string.block_reason_code_none)
        "MOISTURE_RESPONSE_TEST_FAILED" -> strings.get(R.string.block_reason_code_moisture_response_test_failed)
        "SENSOR_READING_INVALID" -> strings.get(R.string.block_reason_code_sensor_reading_invalid)
        "LOW_BATTERY_ABORT" -> strings.get(R.string.block_reason_code_low_battery_abort)
        else -> strings.get(R.string.common_unknown_with_code, code)
    }
}
