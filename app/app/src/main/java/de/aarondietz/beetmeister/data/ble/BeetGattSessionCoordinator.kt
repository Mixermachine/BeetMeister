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
import de.aarondietz.beetmeister.data.local.BeetEventStore
import de.aarondietz.beetmeister.data.local.BeetEventStores
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
) : BeetMaintenanceLink, BeetRuntimeCommandLink, BeetGattLinkDelegate {
    private enum class ExpectedControllerAction {
        None,
        Reboot,
        FactoryReset,
    }

    private val strings get() = host.strings
    private val eventStore: BeetEventStore by lazy { BeetEventStores.get(host.appContext) }

    private val maintenanceUpdater = BeetMaintenanceUpdater(host, this)
    private val gattLink = BeetGattLink(host, this, maintenanceUpdater)
    private val commandPump = BeetCommandPump(host, gattLink)
    private val runtimeCommands = BeetRuntimeCommands(host, this)
    private val eventSyncEngine = BeetEventSyncEngine(link = this, router = BeetResponseRouter())
    private val eventSyncOrchestrator = BeetSyncOrchestrator(
        host = host,
        pump = commandPump,
        engine = eventSyncEngine,
        link = gattLink,
        maintenanceUpdater = maintenanceUpdater,
    )
    private var expectedControllerAction: ExpectedControllerAction = ExpectedControllerAction.None
    private var expectedControllerActionUntilMs: Long = 0L

    fun close() {
        BeetLog.d(TAG, "close()")
        gattLink.disconnectGatt(clearSelection = false, reason = "repository close")
        maintenanceUpdater.shutdown()
    }

    fun clearCommandMessage() {
        host.clearCommandMessage()
    }

    // --- BeetMaintenanceLink (bridges the extracted maintenance engine back to this session) ---

    override val mtu: Int get() = gattLink.mtu
    override val gattResetAtMs: Long get() = gattLink.gattResetAtMs

    override fun cancelConnectionTimeout() {
        gattLink.cancelConnectionTimeout()
    }

    override fun suspendRuntimeSync(reason: String) {
        BeetLog.d(TAG) { "suspendRuntimeSync(reason=$reason)" }
        eventSyncOrchestrator.cancelEventSyncJob()
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

    private fun expectedControllerActionActive(): Boolean =
        expectedControllerAction != ExpectedControllerAction.None &&
            SystemClock.elapsedRealtime() <= expectedControllerActionUntilMs

    private fun clearCachedControllerHistory() {
        val deviceId = host.state.value.controllerInfo?.deviceId ?: return
        eventStore.clearDevice(deviceId)
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
        eventSyncOrchestrator.startBackgroundEventSync(limit = 10000)
    }

    fun refreshCalibrations() = runtimeCommands.refreshCalibrations()

    fun refreshHistorySummary() {
        eventSyncOrchestrator.startBackgroundEventSync(force = true)
    }

    fun loadRecentEvents(limit: Int = 50) {
        eventSyncOrchestrator.startBackgroundEventSync(force = true, limit = limit)
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
        gattLink.openGatt(device)
    }

    fun disconnect(clearSelection: Boolean, reason: String) {
        BeetLog.d(TAG) { "disconnect(clearSelection=$clearSelection, reason=$reason)" }
        gattLink.disconnectGatt(clearSelection, reason)
    }

    // --- BeetGattLinkDelegate (session-state decisions the GATT link asks the coordinator to make) ---

    override fun connectingDetail(deviceAddress: String): String =
        if (expectedControllerActionActive()) {
            expectedControllerActionConnectingDetail()
        } else {
            strings.get(R.string.runtime_connecting_to_controller, deviceAddress)
        }

    override fun negotiatingDetail(): String = expectedControllerActionConnectingDetail()

    override fun expectedActionActive(): Boolean = expectedControllerActionActive()

    override fun expectedRebootPending(): Boolean = expectedControllerAction == ExpectedControllerAction.Reboot

    override fun clearExpectedControllerAction() {
        expectedControllerAction = ExpectedControllerAction.None
        expectedControllerActionUntilMs = 0L
    }

    override fun resetSyncState() {
        BeetLog.d(TAG, "resetSyncState()")
        commandPump.resetChunkAssembler()
        gattLink.cancelControllerInfoRetry("reset sync state")
        host.resetSyncState()
    }

    override fun cancelEventSyncJob() {
        eventSyncOrchestrator.cancelEventSyncJob()
    }

    override fun clearPendingMoistureTests() {
        runtimeCommands.clearPendingMoistureTests()
    }

    private fun mergeWateringEvents(current: List<BeetWateringEvent>, incoming: List<BeetWateringEvent>): List<BeetWateringEvent> =
        eventSyncOrchestrator.mergeWateringEvents(current, incoming)

    private fun mergeSystemEvents(current: List<BeetSystemEvent>, incoming: List<BeetSystemEvent>): List<BeetSystemEvent> =
        eventSyncOrchestrator.mergeSystemEvents(current, incoming)

    private fun ingestWateringEvent(deviceId: String, event: BeetWateringEvent) {
        eventSyncOrchestrator.ingestWateringEvent(deviceId, event)
    }

    private fun ingestSystemEvent(deviceId: String, event: BeetSystemEvent) {
        eventSyncOrchestrator.ingestSystemEvent(deviceId, event)
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

    override suspend fun <T> withSyncPausedForCommand(block: suspend () -> T): T =
        commandPump.withSyncPausedForCommand(block)

    private suspend fun awaitSyncResumeIfNeeded() {
        commandPump.awaitSyncResumeIfNeeded()
    }

    private suspend fun sendSyncCommand(payload: String): BeetCommandResult =
        commandPump.sendSyncCommand(payload)

    override suspend fun sendCommand(payload: String): BeetCommandResult =
        commandPump.sendCommand(payload)

    private fun completeInitialSyncIfReady() {
        if (!host.session.tryCompleteInitialSync()) {
            return
        }
        gattLink.cancelConnectionTimeout()
        gattLink.cancelControllerInfoRetry("initial sync completed")
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

            gattLink.negotiateHighSpeedLink()

            refreshValveConfig()
            refreshWateringInterval()
            refreshMaxActivePumps()

            delay(POST_CONNECT_EVENT_SYNC_DELAY_MS)
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) return@launch
            eventSyncOrchestrator.startBackgroundEventSync()
        }
    }

    override fun handleControllerInfo(payload: ByteArray) {
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
        gattLink.cancelControllerInfoRetry("controller info read succeeded")
        host.session.markControllerInfoLoaded(info.pairCount)
        BeetLog.d(TAG) { "handleControllerInfo(deviceId=${info.deviceId}, protocol=${info.protocolVersion}, pairCount=${info.pairCount})" }
        host.updateState { it.copy(controllerInfo = info) }
        completeInitialSyncIfReady()
    }

    override fun hasCompleteRuntimeService(): Boolean = gattLink.hasCompleteRuntimeService()

    override fun readControllerInfo(gatt: BluetoothGatt): Boolean = gattLink.readControllerInfo(gatt)

    override fun handleStatePayload(payload: ByteArray) {
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
                val burstConsumed = eventSyncOrchestrator.router.onEventFrame(BeetStreamKind.SYSTEM)
                if (burstConsumed) {
                    eventSyncOrchestrator.ingestBurstSystemEvent(message.data)
                } else if (deviceId != null) {
                    ingestSystemEvent(deviceId, message.data)
                } else {
                    host.updateState { it.copy(systemEvents = mergeSystemEvents(it.systemEvents, listOf(message.data))) }
                }
            }

            is BeetStateMessage.WateringEventUpdate -> {
                eventSyncOrchestrator.router.onEventFrame(BeetStreamKind.WATERING)
                eventSyncOrchestrator.ingestBurstWateringEvent(message.data)
            }

            is BeetStateMessage.StreamEndUpdate -> {
                eventSyncOrchestrator.router.onStreamEnd(message.data)
            }
        }
    }

    override fun handleCommandPayload(payload: ByteArray) {
        val result = commandPump.decodeCommandPayload(payload) ?: return
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
        commandPump.completePendingResult(result)
    }

    override fun disconnectGatt(clearSelection: Boolean, reason: String) {
        gattLink.disconnectGatt(clearSelection, reason)
    }

    private fun messageForResult(result: BeetCommandResult): String = commandMessageForResult(result, strings)

    private fun expectedControllerActionConnectingDetail(): String = when (expectedControllerAction) {
        ExpectedControllerAction.Reboot -> strings.get(R.string.runtime_reboot_reconnecting)
        ExpectedControllerAction.FactoryReset -> strings.get(R.string.runtime_factory_reset_waiting)
        ExpectedControllerAction.None -> strings.get(R.string.runtime_negotiating_ble_session)
    }

    override fun handleExpectedControllerActionDisconnect() {
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

    companion object {
        private const val TAG = "BeetGattSession"
        private const val MAINTENANCE_PROGRESS_GAP_RESET_MS = 10_000L

        private const val EXPECTED_CONTROLLER_ACTION_TIMEOUT_MS = 30_000L
        private const val EXPECTED_REBOOT_RECONNECT_DELAY_MS = 1_000L
        private const val POST_CONNECT_EVENT_SYNC_DELAY_MS = 3_000L
    }

    private class MaintenanceAbortRequestedException : IllegalStateException("Maintenance update aborted")

}
