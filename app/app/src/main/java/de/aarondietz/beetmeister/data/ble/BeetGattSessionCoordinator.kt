package de.aarondietz.beetmeister.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.net.Uri
import android.os.SystemClock
import de.aarondietz.beetmeister.logging.BeetLog
import de.aarondietz.beetmeister.BuildConfig
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.data.firmware.BeetFirmwareImagePackage
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
import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo
import de.aarondietz.beetmeister.model.controller.BeetPairCombined
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.repository.displayedPairCount
import de.aarondietz.beetmeister.model.repository.isFollower
import de.aarondietz.beetmeister.model.repository.isLead
import de.aarondietz.beetmeister.model.repository.leadFor
import de.aarondietz.beetmeister.model.stream.BeetEventSyncPhase
import de.aarondietz.beetmeister.model.stream.BeetEventSyncState
import de.aarondietz.beetmeister.model.stream.BeetStateMessage
import de.aarondietz.beetmeister.model.update.BeetFirmwarePackageSummary
import de.aarondietz.beetmeister.model.update.BeetMaintenanceStatus
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdatePhase
import de.aarondietz.beetmeister.model.update.isActiveMaintenancePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import kotlin.math.max

internal class BeetGattSessionCoordinator(
    private val host: BeetRepositoryCallbacks,
) : BeetMaintenanceLink, BeetRuntimeCommandLink {
    private enum class ExpectedControllerAction {
        None,
        Reboot,
        FactoryReset,
    }

    private val strings get() = host.strings
    private val commandMutex = Mutex()
    private val eventCache = BeetEventCache(host.appContext.getSharedPreferences("beetmeister_event_cache", android.content.Context.MODE_PRIVATE))
    private val maintenanceUpdater = BeetMaintenanceUpdater(host, this)
    private var connectionTimeoutJob: Job? = null
    private var controllerInfoRetryJob: Job? = null
    private var eventSyncJob: Job? = null
    @Volatile
    private var syncPauseRequested = false
    private val runtimeCommands = BeetRuntimeCommands(host, this)
    private val commandChunkAssembler = BeetCommandResultChunkAssembler()
    private var negotiatedMtu = DEFAULT_MTU
    private var expectedControllerAction: ExpectedControllerAction = ExpectedControllerAction.None
    private var expectedControllerActionUntilMs: Long = 0L
    @Volatile
    private var lastGattResetAt: Long = 0L

    fun close() {
        BeetLog.d(TAG, "close()")
        disconnectGatt(clearSelection = false, reason = "repository close")
        cancelControllerInfoRetry("repository close")
        eventSyncJob?.cancel()
        eventSyncJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        maintenanceUpdater.shutdown()
    }

    fun clearCommandMessage() {
        host.clearCommandMessage()
    }

    // --- BeetMaintenanceLink (bridges the extracted maintenance engine back to this session) ---

    override val mtu: Int get() = negotiatedMtu
    override val gattResetAtMs: Long get() = lastGattResetAt

    override fun cancelConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    override fun suspendRuntimeSync(reason: String) {
        BeetLog.d(TAG) { "suspendRuntimeSync(reason=$reason)" }
        eventSyncJob?.cancel()
        eventSyncJob = null
        host.updateState { state ->
            state.copy(
                eventsLoading = false,
                eventSync = BeetEventSyncState(),
                valveConfigRefreshing = false,
                wateringIntervalRefreshing = false,
            )
        }
    }

    private fun setExpectedControllerAction(action: ExpectedControllerAction) {
        expectedControllerAction = action
        expectedControllerActionUntilMs = SystemClock.elapsedRealtime() + EXPECTED_CONTROLLER_ACTION_TIMEOUT_MS
    }

    private fun clearExpectedControllerAction() {
        expectedControllerAction = ExpectedControllerAction.None
        expectedControllerActionUntilMs = 0L
    }

    private fun expectedControllerActionActive(): Boolean =
        expectedControllerAction != ExpectedControllerAction.None &&
            SystemClock.elapsedRealtime() <= expectedControllerActionUntilMs

    private fun clearCachedControllerHistory() {
        val deviceId = host.state.value.controllerInfo?.deviceId ?: return
        eventCache.clearDevice(deviceId)
        host.updateState { state ->
            state.copy(
                historySummary = null,
                systemHistorySummary = null,
                recentEvents = emptyList(),
                systemEvents = emptyList(),
                eventSync = BeetEventSyncState(),
            )
        }
    }

    fun sendRawCommand(payload: String): BeetCommandResult = runtimeCommands.sendRawCommand(payload)

    fun refreshEvents() {
        startBackgroundEventSync(limit = 10000)
    }

    fun refreshCalibrations() = runtimeCommands.refreshCalibrations()

    fun refreshHistorySummary() {
        startBackgroundEventSync(force = true)
    }

    fun loadRecentEvents(limit: Int = 50) {
        startBackgroundEventSync(force = true, limit = limit)
    }

    fun manualStart(pairIndex: Int, durationSeconds: Int?) = runtimeCommands.manualStart(pairIndex, durationSeconds)

    fun manualStop(pairIndex: Int) = runtimeCommands.manualStop(pairIndex)

    fun moistureTestStart(pairIndex: Int) = runtimeCommands.moistureTestStart(pairIndex)

    fun clearPairError(pairIndex: Int) = runtimeCommands.clearPairError(pairIndex)

    fun resetBlock(pairIndex: Int) = clearPairError(pairIndex)

    fun disablePair(pairIndex: Int) = runtimeCommands.disablePair(pairIndex)

    fun enablePair(pairIndex: Int) = runtimeCommands.enablePair(pairIndex)

    fun saveCalibration(pairIndex: Int, dryMillivolts: Int, wetMillivolts: Int) =
        runtimeCommands.saveCalibration(pairIndex, dryMillivolts, wetMillivolts)

    fun loadPairWiring(pairIndex: Int) = runtimeCommands.loadPairWiring(pairIndex)

    suspend fun fetchPairNamesInternal() = runtimeCommands.fetchPairNamesInternal()

    fun loadPairNames() = runtimeCommands.loadPairNames()

    fun storePairName(pairIndex: Int, name: String) = runtimeCommands.storePairName(pairIndex, name)

    suspend fun fetchPairCombinedInternal() = runtimeCommands.fetchPairCombinedInternal()

    fun refreshPairCombined() = runtimeCommands.refreshPairCombined()

    fun setPairSensorSource(pairIndex: Int, leadPairIndex: Int?) = runtimeCommands.setPairSensorSource(pairIndex, leadPairIndex)

    fun loadPairCombined(pairIndex: Int) = runtimeCommands.loadPairCombined(pairIndex)

    fun storePairCombined(pairIndex: Int, followersMask: Int) = runtimeCommands.storePairCombined(pairIndex, followersMask)

    fun loadPairConfig(pairIndex: Int) = runtimeCommands.loadPairConfig(pairIndex)

    fun storePairConfig(
        pairIndex: Int,
        targetLevel: de.aarondietz.beetmeister.model.controller.TargetMoistureLevel,
        durationMultiplier: Int,
    ) = runtimeCommands.storePairConfig(pairIndex, targetLevel, durationMultiplier)

    fun refreshValveConfig() = runtimeCommands.refreshValveConfig()

    fun refreshWateringInterval() = runtimeCommands.refreshWateringInterval()

    fun saveValveConfig(config: BeetValveConfig) = runtimeCommands.saveValveConfig(config)

    fun saveWateringInterval(seconds: Int) = runtimeCommands.saveWateringInterval(seconds)

    fun refreshMaxActivePumps() = runtimeCommands.refreshMaxActivePumps()

    fun storeMaxActivePumps(max: Int) = runtimeCommands.storeMaxActivePumps(max)

    fun previewValvePosition(pulseMicros: Int) = runtimeCommands.previewValvePosition(pulseMicros)

    fun openValve() = runtimeCommands.openValve()

    fun closeValve() = runtimeCommands.closeValve()

    fun rebootController() = runtimeCommands.rebootController()

    fun factoryResetController() = runtimeCommands.factoryResetController()

    fun runScheduler() = runtimeCommands.runScheduler()

    fun prepareBundledFirmware() = maintenanceUpdater.prepareBundledFirmware()

    fun prepareCustomFirmware(uri: android.net.Uri) = maintenanceUpdater.prepareCustomFirmware(uri)

    fun startMaintenanceUpdate() = maintenanceUpdater.startMaintenanceUpdate()

    fun abortMaintenanceUpdate() = maintenanceUpdater.abortMaintenanceUpdate()

    fun openGatt(device: BluetoothDevice) {
        BeetLog.d(TAG) { "openGatt(address=${device.address}, bondState=${device.bondState})" }
        lastGattResetAt = System.currentTimeMillis()
        disconnectGatt(clearSelection = false, reason = "openGatt reset existing session")
        resetSyncState()
        host.updateConnection(
            BeetConnectionPhase.Connecting,
            if (expectedControllerActionActive()) {
                expectedControllerActionConnectingDetail()
            } else {
                strings.get(R.string.runtime_connecting_to_controller, device.address)
            },
        )
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = host.scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            val phase = host.state.value.connection.phase
            if (phase == BeetConnectionPhase.Connected) {
                return@launch
            }
            if (maintenanceUpdater.isUploadActive && maintenanceUpdater.isConnectionHealthy()) {
                BeetLog.d(TAG) { "Connection timeout ignored because maintenance resume is healthy phase=$phase" }
                return@launch
            }
            BeetLog.w(TAG, "Connection timeout fired while phase=$phase")
            disconnectGatt(clearSelection = false, reason = "connection timeout")
            host.clearSession()
            host.requestStartScan(
                detail = if (expectedControllerAction == ExpectedControllerAction.Reboot) {
                    clearExpectedControllerAction()
                    strings.get(R.string.runtime_reboot_reconnect_failed)
                } else {
                    strings.get(R.string.runtime_connection_timed_out)
                },
            )
        }
        @Suppress("MissingPermission")
        host.session.currentGatt = device.connectGatt(host.appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect(clearSelection: Boolean, reason: String) {
        BeetLog.d(TAG) { "disconnect(clearSelection=$clearSelection, reason=$reason)" }
        disconnectGatt(clearSelection, reason)
    }

    override fun applyUserCommandSideEffects(result: BeetCommandResult) {
        when {
            result.command == "reboot_controller" && result.status == "accepted" ->
                setExpectedControllerAction(ExpectedControllerAction.Reboot)
            result.command == "factory_reset" && result.status == "accepted" -> {
                host.removeLastAddress()
                clearCachedControllerHistory()
                setExpectedControllerAction(ExpectedControllerAction.FactoryReset)
            }
        }
    }

    override suspend fun <T> withSyncPausedForCommand(block: suspend () -> T): T {
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

    override suspend fun sendCommand(payload: String): BeetCommandResult {
        return commandMutex.withLock {
            val gatt = host.session.currentGatt ?: error(strings.get(R.string.runtime_no_connected_controller))
            val controlPoint = host.session.controlPointCharacteristic ?: error(strings.get(R.string.runtime_control_point_unavailable))
            val deferred = CompletableDeferred<BeetCommandResult>()
            host.session.pendingCommand = deferred

            BeetLog.d(TAG) { "sendCommand payload=$payload" }
            controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            controlPoint.value = payload.toByteArray(StandardCharsets.UTF_8)
            @Suppress("MissingPermission")
            val writeStarted = gatt.writeCharacteristic(controlPoint)
            if (!writeStarted) {
                host.session.pendingCommand = null
                error(strings.get(R.string.runtime_ble_send_failed))
            }

            try {
                withTimeout(COMMAND_TIMEOUT_MS) {
                    val result = deferred.await()
                    BeetLog.d(TAG) { "sendCommand result command=${result.command} status=${result.status} reason=${result.reason}" }
                    result
                }
            } catch (timeout: TimeoutCancellationException) {
                BeetLog.w(TAG) { "sendCommand timed out waiting for result payload=$payload" }
                commandChunkAssembler.reset()
                throw timeout
            } finally {
                host.session.pendingCommand = null
            }
        }
    }

    private fun startBackgroundEventSync(force: Boolean = false, limit: Int = MAX_BACKGROUND_EVENT_DOWNLOAD) {
        if (maintenanceUpdater.isUploadActive) {
            BeetLog.d(TAG, "Skipping background event sync because maintenance update is active")
            return
        }
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
                BeetLog.w(TAG) {
                    "startBackgroundEventSync aborted because controller time is unavailable " +
                        "bootId=${deviceState?.bootId} timeValid=${deviceState?.timeValid} syncedTimeBootId=${host.session.syncedTimeBootId}"
                }
                host.updateState {
                    it.copy(
                        calibrationsRefreshing = false,
                        eventsLoading = false,
                        eventSync = BeetEventSyncState(
                            active = false,
                            transferred = 0,
                            total = 0,
                            phase = BeetEventSyncPhase.PausedForCommand,
                        ),
                        valveConfigRefreshing = false,
                        wateringIntervalRefreshing = false,
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

            /* Request high connection priority to reduce connection interval during bulk event sync */
            @Suppress("MissingPermission")
            val priorityRequested = host.session.currentGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) ?: false
            BeetLog.d(TAG) { "startBackgroundEventSync: requested CONNECTION_PRIORITY_HIGH success=$priorityRequested" }

            try {
                /*
                 * Coalesce event ingestion into the repository state in batches.
                 * Per-event state updates during a bulk backlog sync (thousands of events)
                 * cause a recomposition storm on the UI thread which can race with
                 * AndroidComposeView.draw (performMeasureAndLayout crash). SQLite caching
                 * stays per-event; only the state emission is batched.
                 */
                val wateringBuffer = ArrayList<BeetWateringEvent>(EVENT_UI_BATCH_SIZE)
                val systemBuffer = ArrayList<BeetSystemEvent>(EVENT_UI_BATCH_SIZE)
                fun flushIngestedEvents() {
                    if (wateringBuffer.isEmpty() && systemBuffer.isEmpty()) return
                    val watering = wateringBuffer.toList()
                    val system = systemBuffer.toList()
                    wateringBuffer.clear()
                    systemBuffer.clear()
                    host.updateState { state ->
                        state.copy(
                            recentEvents = if (watering.isEmpty()) state.recentEvents else mergeWateringEvents(state.recentEvents, watering),
                            systemEvents = if (system.isEmpty()) state.systemEvents else mergeSystemEvents(state.systemEvents, system),
                        )
                    }
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
                    maxConsecutiveNotFoundLimit = SYNC_CONSECUTIVE_NOT_FOUND_LIMIT,
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
                                transferred = progress.transferred,
                                total = progress.total,
                                phase = progress.phase,
                            ),
                        )
                    }
                },
                onWateringEvent = { event ->
                    eventCache.saveWateringEvent(deviceId, event)
                    wateringBuffer += event
                    if (wateringBuffer.size >= EVENT_UI_BATCH_SIZE) flushIngestedEvents()
                },
                onSystemEvent = { event ->
                    eventCache.saveSystemEvent(deviceId, event)
                    systemBuffer += event
                    if (systemBuffer.size >= EVENT_UI_BATCH_SIZE) flushIngestedEvents()
                },
                fetchWateringEvent = { sequence -> fetchWateringEventForSync(sequence) },
                fetchSystemEvent = { sequence -> fetchSystemEventForSync(sequence) },
            )

            /* Publish any events still buffered when the sync loop finishes */
            flushIngestedEvents()
            } finally {
                /* Restore balanced connection priority when event sync finishes or is cancelled */
                @Suppress("MissingPermission")
                host.session.currentGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                BeetLog.d(TAG) { "startBackgroundEventSync: restored CONNECTION_PRIORITY_BALANCED" }
            }
            host.updateState { state ->
                state.copy(
                    calibrationsRefreshing = false,
                    eventsLoading = false,
                    eventSync = BeetEventSyncState(),
                    valveConfigRefreshing = false,
                    wateringIntervalRefreshing = false,
                )
            }
        }
    }

    private suspend fun fetchWateringEventForSync(sequence: Long): BeetBacklogFetchResult<BeetWateringEvent> {
        val result = runCatching { sendSyncCommand(BeetJsonCodec.getEvent(sequence)) }.getOrNull()
            ?: run {
                BeetLog.w(TAG, "fetchWateringEventForSync seq=$sequence command failed before a result was returned")
                return BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
            }
        val reason = result.reason.lowercase()
        val fetchResult = when {
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
        BeetLog.d(TAG) { "fetchWateringEventForSync seq=$sequence -> status=${fetchResult.status}" }
        return fetchResult
    }

    private suspend fun fetchSystemEventForSync(sequence: Long): BeetBacklogFetchResult<BeetSystemEvent> {
        val result = runCatching { sendSyncCommand(BeetJsonCodec.getSystemEvent(sequence)) }.getOrNull()
            ?: run {
                BeetLog.w(TAG, "fetchSystemEventForSync seq=$sequence command failed before a result was returned")
                return BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
            }
        val reason = result.reason.lowercase()
        val fetchResult = when {
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
        BeetLog.d(TAG) { "fetchSystemEventForSync seq=$sequence -> status=${fetchResult.status}" }
        return fetchResult
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
            BeetLog.w(TAG, "synchronizeControllerTimeIfNeeded refusing duplicate set_time attempt for bootId=${deviceState.bootId}")
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
        BeetLog.w(TAG, "synchronizeControllerTimeIfNeeded timed out waiting for time_valid after set_time")
        return false
    }

    private fun configureServices(gatt: BluetoothGatt): Boolean {
        val runtimeService: BluetoothGattService? = gatt.getService(BeetBluetoothSupport.serviceUuid)
        val maintenanceService: BluetoothGattService? = gatt.getService(BeetBluetoothSupport.maintenanceServiceUuid)
        host.session.controllerInfoCharacteristic = runtimeService?.getCharacteristic(BeetBluetoothSupport.controllerInfoUuid)
        host.session.stateStreamCharacteristic = runtimeService?.getCharacteristic(BeetBluetoothSupport.stateStreamUuid)
        host.session.controlPointCharacteristic = runtimeService?.getCharacteristic(BeetBluetoothSupport.controlPointUuid)
        host.session.commandResultCharacteristic = runtimeService?.getCharacteristic(BeetBluetoothSupport.commandResultUuid)
        host.session.maintenanceInfoCharacteristic = maintenanceService?.getCharacteristic(BeetBluetoothSupport.maintenanceInfoUuid)
        host.session.maintenanceControlCharacteristic = maintenanceService?.getCharacteristic(BeetBluetoothSupport.maintenanceControlUuid)
        host.session.maintenanceStatusCharacteristic = maintenanceService?.getCharacteristic(BeetBluetoothSupport.maintenanceStatusUuid)
        host.session.maintenanceDataCharacteristic = maintenanceService?.getCharacteristic(BeetBluetoothSupport.maintenanceDataUuid)
        BeetLog.d(TAG) {
            "configureServices(runtimeService=${runtimeService != null}, maintenanceService=${maintenanceService != null}, controllerInfo=${host.session.controllerInfoCharacteristic != null}, stateStream=${host.session.stateStreamCharacteristic != null}, controlPoint=${host.session.controlPointCharacteristic != null}, commandResult=${host.session.commandResultCharacteristic != null}, maintenanceInfo=${host.session.maintenanceInfoCharacteristic != null})"
        }
        if (host.session.controllerInfoCharacteristic == null ||
            host.session.stateStreamCharacteristic == null ||
            host.session.controlPointCharacteristic == null ||
            host.session.commandResultCharacteristic == null
        ) {
            if (host.session.maintenanceInfoCharacteristic == null) {
                return false
            }
        }
        host.session.descriptorQueue.clear()
        if (host.session.maintenanceStatusCharacteristic != null) {
            host.session.descriptorQueue.add(host.session.maintenanceStatusCharacteristic!! to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        }
        if (host.session.commandResultCharacteristic != null) {
            host.session.descriptorQueue.add(host.session.commandResultCharacteristic!! to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        }
        if (host.session.stateStreamCharacteristic != null) {
            host.session.descriptorQueue.add(host.session.stateStreamCharacteristic!! to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        return writeNextDescriptor(gatt)
    }

    private fun readMaintenanceInfo(gatt: BluetoothGatt): Boolean {
        val characteristic = host.session.maintenanceInfoCharacteristic ?: return false
        host.updateConnection(BeetConnectionPhase.Syncing, strings.get(R.string.runtime_reading_maintenance_info))
        @Suppress("MissingPermission")
        return gatt.readCharacteristic(characteristic)
    }

    private fun writeNextDescriptor(gatt: BluetoothGatt): Boolean {
        val next = host.session.descriptorQueue.removeFirstOrNull() ?: return when {
            host.session.maintenanceInfoCharacteristic != null && host.state.value.maintenanceInfo == null -> readMaintenanceInfo(gatt)
            host.session.controllerInfoCharacteristic != null -> readControllerInfo(gatt)
            else -> true
        }
        val characteristic = next.first
        val descriptor = characteristic.getDescriptor(BeetBluetoothSupport.clientConfigUuid) ?: return false
        BeetLog.d(TAG) { "writeNextDescriptor(uuid=${characteristic.uuid}, queueRemaining=${host.session.descriptorQueue.size})" }
        @Suppress("MissingPermission")
        gatt.setCharacteristicNotification(characteristic, true)
        descriptor.value = next.second
        @Suppress("MissingPermission")
        return gatt.writeDescriptor(descriptor)
    }

    override fun readControllerInfo(gatt: BluetoothGatt): Boolean {
        val characteristic = host.session.controllerInfoCharacteristic ?: return false
        if (host.state.value.controllerInfo != null) {
            cancelControllerInfoRetry("controller info already loaded")
            BeetLog.d(TAG, "Skipping controller info read because it is already loaded")
            return true
        }
        if (!host.session.initialSyncCompleted && host.state.value.connection.phase != BeetConnectionPhase.Connected) {
            host.updateConnection(BeetConnectionPhase.Syncing, strings.get(R.string.runtime_reading_controller_info))
        } else {
            BeetLog.d(TAG) {
                "Reading controller info without phase downgrade (initialSyncCompleted=${host.session.initialSyncCompleted}, phase=${host.state.value.connection.phase})"
            }
        }
        host.session.controllerInfoReadAttempts += 1
        BeetLog.d(TAG, "Reading controller info, attempt=${host.session.controllerInfoReadAttempts}")
        @Suppress("MissingPermission")
        val started = gatt.readCharacteristic(characteristic)
        if (!started) {
            BeetLog.w(TAG, "Controller info read did not start on attempt=${host.session.controllerInfoReadAttempts}")
            scheduleControllerInfoRetry(gatt, "read start returned false")
        }
        return true
    }

    private fun scheduleControllerInfoRetry(gatt: BluetoothGatt, reason: String) {
        if (host.session.initialSyncCompleted) {
            BeetLog.d(TAG, "Ignoring controller info retry because initial sync already completed: reason=$reason")
            return
        }
        if (host.state.value.controllerInfo != null) {
            BeetLog.d(TAG, "Ignoring controller info retry because controller info is already loaded: reason=$reason")
            return
        }
        if (host.session.controllerInfoReadAttempts >= MAX_CONTROLLER_INFO_READ_ATTEMPTS) {
            BeetLog.w(TAG, "Controller info read exhausted retries: reason=$reason")
            return
        }
        cancelControllerInfoRetry("reschedule: $reason")
        BeetLog.w(TAG, "Scheduling controller info retry attempt=${host.session.controllerInfoReadAttempts + 1} reason=$reason")
        controllerInfoRetryJob = host.scope.launch {
            delay(CONTROLLER_INFO_READ_RETRY_DELAY_MS)
            if (host.session.currentGatt != gatt) {
                BeetLog.d(TAG, "Skipping controller info retry because the GATT session changed")
                controllerInfoRetryJob = null
                return@launch
            }
            if (host.session.controllerInfoCharacteristic == null) {
                BeetLog.d(TAG, "Skipping controller info retry because controller info characteristic is unavailable")
                controllerInfoRetryJob = null
                return@launch
            }
            if (host.session.initialSyncCompleted) {
                BeetLog.d(TAG, "Skipping controller info retry because initial sync already completed")
                controllerInfoRetryJob = null
                return@launch
            }
            if (host.state.value.controllerInfo != null) {
                BeetLog.d(TAG, "Skipping controller info retry because controller info is already loaded")
                controllerInfoRetryJob = null
                return@launch
            }
            controllerInfoRetryJob = null
            readControllerInfo(gatt)
        }
    }

    private fun completeInitialSyncIfReady() {
        if (!host.session.tryCompleteInitialSync()) {
            return
        }
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        cancelControllerInfoRetry("initial sync completed")
        host.persistLastAddress(host.currentAddress)
        clearExpectedControllerAction()
        BeetLog.d(TAG, "Initial sync started for session address=${host.currentAddress}")

        host.scope.launch {
            if (maintenanceUpdater.isUploadActive) {
                BeetLog.d(TAG, "Skipping post-sync refreshes because maintenance update is active")
                host.updateConnection(BeetConnectionPhase.Connected, strings.get(R.string.runtime_connected_to_controller))
                return@launch
            }

            // Load pair names and combined piggyback configuration FIRST before marking connected
            fetchPairNamesInternal()
            fetchPairCombinedInternal()

            if (host.session.currentGatt == null || host.state.value.connection.phase == BeetConnectionPhase.Disconnected) {
                return@launch
            }

            BeetLog.d(TAG, "Initial sync completed for session address=${host.currentAddress}")
            host.updateConnection(BeetConnectionPhase.Connected, strings.get(R.string.runtime_connected_to_controller))

            /*
             * Safely optimize link layer parameters now that connection, discovery, and initial sync are complete:
             * 1. Request 2M PHY if supported by client hardware.
             * 2. Request MTU up to 517 (NimBLE and Android clamp to negotiated max, safe fallback).
             * Never do this during maintenance or initial handshake.
             */
            val currentGatt = host.session.currentGatt
            if (currentGatt != null && !maintenanceUpdater.isUploadActive) {
                @Suppress("MissingPermission")
                try {
                    val phyRequested = currentGatt.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                    )
                    BeetLog.i(TAG) { "Requested 2M PHY post-connect: success=$phyRequested" }
                } catch (e: Exception) {
                    BeetLog.w(TAG, "setPreferredPhy failed or unsupported", e)
                }

                @Suppress("MissingPermission")
                try {
                    if (negotiatedMtu < HIGH_SPEED_MTU) {
                        val mtuRequested = currentGatt.requestMtu(HIGH_SPEED_MTU)
                        BeetLog.i(TAG) { "Requested MTU $HIGH_SPEED_MTU post-connect: success=$mtuRequested" }
                    } else {
                        BeetLog.i(TAG) { "Skipping post-connect MTU upgrade; already negotiated $negotiatedMtu" }
                    }
                } catch (e: Exception) {
                    BeetLog.w(TAG, "requestMtu post-connect failed", e)
                }
            }

            refreshValveConfig()
            refreshWateringInterval()
            refreshMaxActivePumps()

            delay(POST_CONNECT_EVENT_SYNC_DELAY_MS)
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) return@launch
            startBackgroundEventSync()
        }
    }

    private fun handleControllerInfo(payload: ByteArray) {
        val info = try {
            BeetJsonCodec.parseControllerInfo(payload.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            BeetLog.e(TAG, "Controller info payload parse failed", error)
            disconnectGatt(clearSelection = false, reason = "invalid controller info payload")
            host.clearSession()
            host.updateConnection(BeetConnectionPhase.Error, strings.get(R.string.runtime_controller_info_invalid))
            return
        }
        if (info.protocolVersion != BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION) {
            BeetLog.e(
                TAG,
                "Unsupported protocol version ${info.protocolVersion}, expected=${BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION}",
            )
            disconnectGatt(clearSelection = false, reason = "unsupported protocol version ${info.protocolVersion}")
            host.clearSession()
            host.updateConnection(
                BeetConnectionPhase.Error,
                strings.get(
                    R.string.runtime_unsupported_protocol,
                    info.protocolVersion,
                    BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
                ),
            )
            return
        }
        host.session.controllerInfoReadAttempts = 0
        cancelControllerInfoRetry("controller info read succeeded")
        host.session.markControllerInfoLoaded(info.pairCount)
        BeetLog.d(TAG) { "handleControllerInfo(deviceId=${info.deviceId}, protocol=${info.protocolVersion}, pairCount=${info.pairCount})" }
        host.updateState { it.copy(controllerInfo = info) }
        completeInitialSyncIfReady()
    }

    override fun hasCompleteRuntimeService(): Boolean =
        host.session.controllerInfoCharacteristic != null &&
            host.session.stateStreamCharacteristic != null &&
            host.session.controlPointCharacteristic != null &&
            host.session.commandResultCharacteristic != null

    private fun handleStatePayload(payload: ByteArray) {
        val json = payload.toString(StandardCharsets.UTF_8)
        val message = try {
            BeetJsonCodec.parseStateMessage(json)
        } catch (error: Exception) {
            BeetLog.e(TAG, "State payload parse failed: $json", error)
            null
        }
        when (message) {
            is BeetStateMessage.DeviceStateUpdate -> {
                val deviceState = message.data
                host.session.initialDeviceFrameReceived = true
                if (deviceState.timeValid) {
                    host.session.syncedTimeBootId = deviceState.bootId
                }
                BeetLog.d(TAG) { "handleStatePayload(deviceFrame battery=${deviceState.batteryMillivolts} activePumps=${deviceState.activePumps})" }
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
                val syncedPairs = host.session.markPairSynced(pairState.pairIndex)
                BeetLog.d(TAG) { "handleStatePayload(pairFrame pair=${pairState.pairIndex} state=${pairState.state} syncedPairs=$syncedPairs)" }
                host.updateState { state ->
                    state.copy(
                        pairStates = state.pairStates + (pairState.pairIndex to pairState),
                    )
                }
                runtimeCommands.onPairStateForMoistureTest(pairState)
                completeInitialSyncIfReady()
            }
            null -> {
                BeetLog.w(TAG, "Ignoring unknown state payload: $json")
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
            BeetLog.e(TAG, "Command chunk parse failed payload=$payloadString", error)
            commandChunkAssembler.reset()
            return
        }
        val decodedPayload = if (chunkFrame != null) {
            try {
                BeetLog.d(TAG) { "Received command chunk id=${chunkFrame.id} index=${chunkFrame.index}/${chunkFrame.count}" }
                commandChunkAssembler.consume(chunkFrame, System.currentTimeMillis())?.also {
                    BeetLog.d(TAG) { "Completed chunk reassembly id=${chunkFrame.id} totalLen=${it.length}" }
                }
            } catch (error: Exception) {
                BeetLog.e(
                    TAG,
                    "Command chunk reassembly failed id=${chunkFrame.id} index=${chunkFrame.index} count=${chunkFrame.count}",
                    error,
                )
                commandChunkAssembler.reset()
                return
            } ?: return
        } else {
            if (commandChunkAssembler.hasActiveChunks) {
                BeetLog.w(TAG, "Command chunk reassembly reset due to non-chunk payload while chunked response is active")
                commandChunkAssembler.reset()
            }
            payloadString
        }
        val result = try {
            BeetJsonCodec.parseCommandResult(decodedPayload)
        } catch (error: Exception) {
            BeetLog.e(TAG, "Command payload parse failed", error)
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
        result.wateringInterval?.let { interval ->
            host.updateState { it.copy(wateringInterval = interval) }
        }
        result.pairWiring?.let { wiring ->
            host.updateState { state ->
                state.copy(
                    pairWirings = state.pairWirings + (wiring.pairIndex to wiring),
                    pairWiringLoading = state.pairWiringLoading - wiring.pairIndex,
                    pairWiringErrors = state.pairWiringErrors - wiring.pairIndex,
                )
            }
        }
        result.maxActivePumps?.let { maxPumps ->
            host.updateState { it.copy(maxActivePumps = maxPumps.maxActivePumps) }
        }
        result.pairNames?.let { names ->
            val namesMap = names.names.mapIndexed { index, name ->
                (index + 1) to name
            }.toMap()
            host.updateState { it.copy(pairNames = namesMap) }
        }
        host.session.pendingCommand?.complete(result)
    }

    private fun resetSyncState() {
        BeetLog.d(TAG, "resetSyncState()")
        commandChunkAssembler.reset()
        cancelControllerInfoRetry("reset sync state")
        host.resetSyncState()
    }

    override fun disconnectGatt(clearSelection: Boolean, reason: String) {
        BeetLog.d(TAG) { "disconnectGatt(reason=$reason, clearSelection=$clearSelection, currentAddress=${host.currentAddress}, phase=${host.state.value.connection.phase})" }
        lastGattResetAt = System.currentTimeMillis()
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        cancelControllerInfoRetry("disconnect gatt: $reason")
        eventSyncJob?.cancel()
        eventSyncJob = null
        runtimeCommands.clearPendingMoistureTests()
        val gatt = host.session.currentGatt
        host.session.currentGatt = null
        resetSyncState()
        host.session.pendingCommand?.cancel()
        host.session.pendingCommand = null
        maintenanceUpdater.onSessionTeardown()
        host.updateState { state ->
            state.copy(
                calibrationsRefreshing = false,
                eventsLoading = false,
                eventSync = BeetEventSyncState(),
                valveConfigRefreshing = false,
                wateringIntervalRefreshing = false,
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
        BeetLog.d(TAG) { "Cancelling controller info retry: reason=$reason active=${retryJob.isActive}" }
        retryJob.cancel()
        controllerInfoRetryJob = null
    }

    private fun isCurrentGatt(gatt: BluetoothGatt, callback: String): Boolean {
        val current = host.session.currentGatt
        if (current === gatt) {
            return true
        }
        BeetLog.d(TAG) {
            "Ignoring stale $callback callback for address=${gatt.device.address}, currentAddress=${current?.device?.address}"
        }
        return false
    }

    private fun messageForResult(result: BeetCommandResult): String = commandMessageForResult(result, strings)

    private fun expectedControllerActionConnectingDetail(): String = when (expectedControllerAction) {
        ExpectedControllerAction.Reboot -> strings.get(R.string.runtime_reboot_reconnecting)
        ExpectedControllerAction.FactoryReset -> strings.get(R.string.runtime_factory_reset_waiting)
        ExpectedControllerAction.None -> strings.get(R.string.runtime_negotiating_ble_session)
    }

    private fun handleExpectedControllerActionDisconnect() {
        when (expectedControllerAction) {
            ExpectedControllerAction.Reboot -> {
                val reconnectDevice = maintenanceUpdater.resolveReconnectDevice()
                if (!expectedControllerActionActive() || reconnectDevice == null) {
                    clearExpectedControllerAction()
                    host.clearSession()
                    host.requestStartScan(detail = strings.get(R.string.runtime_reboot_reconnect_failed))
                    return
                }
                host.updateConnection(BeetConnectionPhase.Connecting, strings.get(R.string.runtime_reboot_reconnecting))
                host.clearSession()
                host.scope.launch {
                    delay(EXPECTED_REBOOT_RECONNECT_DELAY_MS)
                    if (expectedControllerAction != ExpectedControllerAction.Reboot || host.session.currentGatt != null) {
                        return@launch
                    }
                    host.requestOpenGatt(reconnectDevice)
                }
            }
            ExpectedControllerAction.FactoryReset -> {
                clearExpectedControllerAction()
                host.currentAddress = null
                host.clearSession()
                host.updateState { it.copy(selectedAddress = null) }
                host.requestStartScan(detail = strings.get(R.string.runtime_factory_reset_complete), clearResults = true)
            }
            ExpectedControllerAction.None -> {}
        }
    }

    private val gattCallback = beetGattCallback(
        onConnectionStateChange = { gatt, status, newState ->
            if (!isCurrentGatt(gatt, "onConnectionStateChange")) {
                return@beetGattCallback
            }
            BeetLog.d(TAG) { "onConnectionStateChange(status=$status, newState=$newState, address=${gatt.device.address})" }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val staleBondCandidate =
                    status == 22 &&
                        host.currentAddress != null &&
                        host.state.value.connection.phase in setOf(
                            BeetConnectionPhase.Connecting,
                            BeetConnectionPhase.DiscoveringServices,
                            BeetConnectionPhase.Syncing,
                            BeetConnectionPhase.MaintenanceRequired,
                        )
                val maintenanceActive = maintenanceUpdater.isUploadActive
                disconnectGatt(clearSelection = false, reason = "gatt error status=$status")
                if (expectedControllerActionActive() && !maintenanceActive) {
                    handleExpectedControllerActionDisconnect()
                    return@beetGattCallback
                }
                host.clearSession()
                if (maintenanceActive) {
                    val detail = if (maintenanceUpdater.expectedRebootDisconnect) {
                        strings.get(R.string.maintenance_rebooting_after_update)
                    } else {
                        strings.get(
                            R.string.maintenance_reconnecting_attempt,
                            maintenanceUpdater.reconnectAttempts + 1,
                            MAX_MAINTENANCE_RECONNECT_ATTEMPTS,
                        )
                    }
                    host.updateConnection(BeetConnectionPhase.MaintenanceRequired, detail)
                } else if (staleBondCandidate) {
                    host.recoverFromStaleBond(gatt.device.address, status)
                } else {
                    host.requestStartScan(detail = strings.get(R.string.runtime_ble_connection_error, status))
                }
                return@beetGattCallback
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    host.updateConnection(
                        BeetConnectionPhase.DiscoveringServices,
                        if (expectedControllerActionActive()) {
                            expectedControllerActionConnectingDetail()
                        } else {
                            strings.get(R.string.runtime_negotiating_ble_session)
                        },
                    )
                    @Suppress("MissingPermission")
                    if (!gatt.requestMtu(INITIAL_MTU)) {
                        negotiatedMtu = DEFAULT_MTU
                        host.session.serviceDiscoveryStarted = true
                        @Suppress("MissingPermission")
                        gatt.discoverServices()
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    val maintenanceActive = maintenanceUpdater.isUploadActive
                    disconnectGatt(clearSelection = false, reason = "gatt disconnected callback")
                    if (expectedControllerActionActive() && !maintenanceActive) {
                        handleExpectedControllerActionDisconnect()
                        return@beetGattCallback
                    }
                    host.clearSession()
                    if (maintenanceActive) {
                        host.updateConnection(
                            BeetConnectionPhase.MaintenanceRequired,
                            if (maintenanceUpdater.expectedRebootDisconnect) {
                                strings.get(R.string.maintenance_rebooting_after_update)
                            } else {
                                strings.get(
                                    R.string.maintenance_reconnecting_attempt,
                                    maintenanceUpdater.reconnectAttempts + 1,
                                    MAX_MAINTENANCE_RECONNECT_ATTEMPTS,
                                )
                            },
                        )
                    } else if (!host.manualDisconnectRequested) {
                        host.requestStartScan(detail = strings.get(R.string.runtime_controller_disconnected))
                    } else {
                        host.updateConnection(BeetConnectionPhase.Disconnected, strings.get(R.string.runtime_disconnected_from_controller))
                    }
                }
            }
        },
        onMtuChanged = { gatt, mtu, status ->
            if (!isCurrentGatt(gatt, "onMtuChanged")) {
                return@beetGattCallback
            }
            BeetLog.i(TAG) { "onMtuChanged status=$status mtu=$mtu" }
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            if (host.session.serviceDiscoveryStarted) {
                BeetLog.d(TAG, "Ignoring duplicate onMtuChanged after service discovery already started")
                return@beetGattCallback
            }
            host.session.serviceDiscoveryStarted = true
            @Suppress("MissingPermission")
            gatt.discoverServices()
        },
        onServicesDiscovered = { gatt, status ->
            if (!isCurrentGatt(gatt, "onServicesDiscovered")) {
                return@beetGattCallback
            }
            BeetLog.d(TAG) { "onServicesDiscovered status=$status" }
            if (host.session.servicesConfigured) {
                BeetLog.d(TAG, "Ignoring duplicate onServicesDiscovered after services already configured")
                return@beetGattCallback
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                host.session.servicesConfigured = true
            }
            if (status != BluetoothGatt.GATT_SUCCESS || !configureServices(gatt)) {
                host.session.servicesConfigured = false
                disconnectGatt(clearSelection = false, reason = "services discovered failed status=$status")
                host.clearSession()
                host.requestStartScan(detail = strings.get(R.string.runtime_gatt_service_incomplete))
            }
        },
        onDescriptorWrite = { gatt, descriptor, status ->
            if (!isCurrentGatt(gatt, "onDescriptorWrite")) {
                return@beetGattCallback
            }
            BeetLog.d(TAG) { "onDescriptorWrite uuid=${descriptor.characteristic.uuid} status=$status queueRemaining=${host.session.descriptorQueue.size}" }
            if (status != BluetoothGatt.GATT_SUCCESS || !writeNextDescriptor(gatt)) {
                disconnectGatt(clearSelection = false, reason = "descriptor write failed status=$status uuid=${descriptor.characteristic.uuid}")
                host.clearSession()
                host.requestStartScan(detail = strings.get(R.string.runtime_subscription_failed))
            }
        },
        onCharacteristicWrite = { _, characteristic, status ->
            maintenanceUpdater.onCharacteristicWriteComplete(characteristic.uuid, status)
        },
        onCharacteristicRead = { gatt, characteristic, status ->
            if (!isCurrentGatt(gatt, "onCharacteristicRead")) {
                return@beetGattCallback
            }
            BeetLog.d(TAG) { "onCharacteristicRead uuid=${characteristic.uuid} status=$status" }
            if (characteristic.uuid == BeetBluetoothSupport.maintenanceInfoUuid) {
                maintenanceUpdater.onMaintenanceInfoRead(gatt, status, characteristic.value ?: ByteArray(0))
                return@beetGattCallback
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                scheduleControllerInfoRetry(gatt, "read callback status=$status")
                return@beetGattCallback
            }
            handleControllerInfo(characteristic.value ?: ByteArray(0))
        },
        onCharacteristicChanged = { gatt, characteristic ->
            if (!isCurrentGatt(gatt, "onCharacteristicChanged")) {
                return@beetGattCallback
            }
            BeetLog.d(TAG) { "onCharacteristicChanged uuid=${characteristic.uuid} size=${characteristic.value?.size ?: 0}" }
            val payload = characteristic.value ?: ByteArray(0)
            when (characteristic.uuid) {
                BeetBluetoothSupport.stateStreamUuid -> handleStatePayload(payload)
                BeetBluetoothSupport.commandResultUuid -> handleCommandPayload(payload)
                BeetBluetoothSupport.maintenanceStatusUuid -> maintenanceUpdater.onMaintenanceStatusPayload(payload)
            }
        },
        onPhyUpdate = { gatt, txPhy, rxPhy, status ->
            if (!isCurrentGatt(gatt, "onPhyUpdate")) {
                return@beetGattCallback
            }
            BeetLog.i(TAG) { "onPhyUpdate status=$status txPhy=$txPhy rxPhy=$rxPhy" }
        },
    )

    companion object {
        private const val TAG = "BeetGattSession"
        private const val COMMAND_TIMEOUT_MS = 7_000L
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val MAINTENANCE_PROGRESS_GAP_RESET_MS = 10_000L
        private const val CONTROLLER_INFO_READ_RETRY_DELAY_MS = 400L
        private const val MAX_CONTROLLER_INFO_READ_ATTEMPTS = 4
        // Kept in sync with BeetMaintenanceUpdater.MAX_MAINTENANCE_RECONNECT_ATTEMPTS for reconnect detail strings.
        private const val MAX_MAINTENANCE_RECONNECT_ATTEMPTS = 3
        private const val DEFAULT_MTU = 23
        // MTU requested during initial handshake and used for maintenance budgets.
        private const val INITIAL_MTU = 247
        // Runtime request after Connected. The effective MTU is always min(client, server);
        // firmware clamps to 247 because NimBLE ESP32-S3 uses 255-byte ACL buffers.
        // If firmware buffer sizing ever increases, this upgrades automatically.
        private const val HIGH_SPEED_MTU = 517
        private const val MAX_BACKGROUND_EVENT_DOWNLOAD = 120
        private const val INITIAL_SYNC_BATCH_SIZE = 1
        private const val MAX_SYNC_BATCH_SIZE = 8
        private const val SYNC_BATCH_GROWTH_STEP = 1
        private const val SYNC_BURST_DELAY_MS = 0L
        private const val SYNC_PAUSE_POLL_MS = 50L
        private const val SYNC_CONGESTION_DELAY_MS = 150L
        private const val SYNC_TRANSIENT_FAILURE_LIMIT = 2
        private const val SYNC_CONSECUTIVE_NOT_FOUND_LIMIT = 3
        private const val EVENT_RETENTION_SECONDS = 30L * 24L * 60L * 60L
        // Batch size for coalescing bulk-sync event ingestion into repository state.
        private const val EVENT_UI_BATCH_SIZE = 20
        private const val EXPECTED_CONTROLLER_ACTION_TIMEOUT_MS = 30_000L
        private const val EXPECTED_REBOOT_RECONNECT_DELAY_MS = 1_000L
        private const val POST_CONNECT_EVENT_SYNC_DELAY_MS = 3_000L
    }

    private class MaintenanceAbortRequestedException : IllegalStateException("Maintenance update aborted")

}
