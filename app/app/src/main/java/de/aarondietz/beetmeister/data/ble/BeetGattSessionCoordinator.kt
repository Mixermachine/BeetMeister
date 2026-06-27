package de.aarondietz.beetmeister.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import de.aarondietz.beetmeister.BuildConfig
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.data.firmware.BeetFirmwareCatalog
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
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.stream.BeetEventSyncPhase
import de.aarondietz.beetmeister.model.stream.BeetEventSyncState
import de.aarondietz.beetmeister.model.stream.BeetStateMessage
import de.aarondietz.beetmeister.model.update.BeetFirmwarePackageSummary
import de.aarondietz.beetmeister.model.update.BeetMaintenanceStatus
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdatePhase
import de.aarondietz.beetmeister.model.update.isActiveMaintenancePhase
import kotlinx.coroutines.CompletableDeferred
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
) {
    private class MaintenanceControlWriteException(message: String) : IllegalStateException(message)

    private enum class ExpectedControllerAction {
        None,
        Reboot,
        FactoryReset,
    }

    private val strings get() = host.strings
    private val commandMutex = Mutex()
    private val maintenanceMutex = Mutex()
    private val eventCache = BeetEventCache(host.appContext.getSharedPreferences("beetmeister_event_cache", android.content.Context.MODE_PRIVATE))
    private var connectionTimeoutJob: Job? = null
    private var controllerInfoRetryJob: Job? = null
    private var eventSyncJob: Job? = null
    private var maintenanceReconnectJob: Job? = null
    private var maintenanceUploadJob: Job? = null
    @Volatile
    private var syncPauseRequested = false
    private val pendingMoistureTests = mutableMapOf<Int, Boolean>()
    private val pendingPairWiringLoads = mutableSetOf<Int>()
    private val commandChunkAssembler = BeetCommandResultChunkAssembler()
    private var selectedMaintenancePackage: BeetFirmwareImagePackage? = null
    private var pendingMaintenanceStatus: CompletableDeferred<BeetMaintenanceStatus>? = null
    private var pendingCharacteristicWrite: CompletableDeferred<Unit>? = null
    private var pendingMaintenanceInfoRead: CompletableDeferred<BeetMaintenanceInfo>? = null
    private var initialMaintenanceStatusJob: Job? = null
    private var maintenanceExpectedRebootDisconnect = false
    private var maintenanceReconnectAttempts = 0
    @Volatile
    private var maintenanceAbortRequested = false
    private var maintenanceTransferStartedAtMs: Long? = null
    private var maintenanceLastProgressAtMs: Long? = null
    private var maintenanceLastProgressBytes: Int = 0
    private var maintenanceSmoothedBytesPerSecond: Double? = null
    private var negotiatedMtu = DEFAULT_MTU
    private var expectedControllerAction: ExpectedControllerAction = ExpectedControllerAction.None
    private var expectedControllerActionUntilMs: Long = 0L
    @Volatile
    private var lastGattResetAt: Long = 0L
    private val maintenanceWakeLock: PowerManager.WakeLock by lazy {
        val powerManager = host.appContext.getSystemService(PowerManager::class.java)
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeetMeister:MaintenanceUpdate")
    }

    fun close() {
        Log.d(TAG, "close()")
        disconnectGatt(clearSelection = false, reason = "repository close")
        cancelControllerInfoRetry("repository close")
        eventSyncJob?.cancel()
        eventSyncJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        maintenanceReconnectJob?.cancel()
        maintenanceReconnectJob = null
        maintenanceUploadJob?.cancel()
        maintenanceUploadJob = null
        releaseMaintenanceWakeLock()
    }

    fun clearCommandMessage() {
        host.clearCommandMessage()
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

    private fun resetMaintenanceProgressTracking() {
        maintenanceTransferStartedAtMs = null
        maintenanceLastProgressAtMs = null
        maintenanceLastProgressBytes = 0
        maintenanceSmoothedBytesPerSecond = null
    }

    private fun ensureMaintenanceProgressTracking(initialBytes: Int) {
        val now = SystemClock.elapsedRealtime()
        if (maintenanceTransferStartedAtMs == null) {
            maintenanceTransferStartedAtMs = now
        }
        if (maintenanceLastProgressAtMs == null) {
            maintenanceLastProgressAtMs = now
            maintenanceLastProgressBytes = initialBytes
        }
    }

    private fun maintenanceElapsedSeconds(nowMs: Long = SystemClock.elapsedRealtime()): Int =
        maintenanceTransferStartedAtMs?.let { ((nowMs - it).coerceAtLeast(0L) / 1000L).toInt() } ?: 0

    private fun maintenanceEstimatedRemainingSeconds(
        bytesTransferred: Int,
        totalBytes: Int,
    ): Int? {
        val rate = maintenanceSmoothedBytesPerSecond ?: return null
        if (rate <= 1.0) {
            return null
        }
        val remainingBytes = (totalBytes - bytesTransferred).coerceAtLeast(0)
        if (remainingBytes <= 0) {
            return 0
        }
        return kotlin.math.ceil(remainingBytes / rate).toInt()
    }

    private fun noteMaintenanceProgress(bytesTransferred: Int, totalBytes: Int) {
        val now = SystemClock.elapsedRealtime()
        ensureMaintenanceProgressTracking(bytesTransferred)
        val lastAt = maintenanceLastProgressAtMs ?: now
        val lastBytes = maintenanceLastProgressBytes
        val elapsedMs = now - lastAt
        val deltaBytes = bytesTransferred - lastBytes
        if (elapsedMs >= 1000L && deltaBytes > 0) {
            val instantaneousRate = deltaBytes.toDouble() / (elapsedMs.toDouble() / 1000.0)
            maintenanceSmoothedBytesPerSecond = maintenanceSmoothedBytesPerSecond?.let { existing ->
                (existing * 0.7) + (instantaneousRate * 0.3)
            } ?: instantaneousRate
            maintenanceLastProgressAtMs = now
            maintenanceLastProgressBytes = bytesTransferred
        } else if (bytesTransferred < lastBytes) {
            maintenanceLastProgressAtMs = now
            maintenanceLastProgressBytes = bytesTransferred
        }
        val elapsedSeconds = maintenanceElapsedSeconds(now)
        val estimatedRemainingSeconds =
            if (elapsedSeconds >= 3 && bytesTransferred >= MIN_MAINTENANCE_PAYLOAD_BYTES * 4) {
                maintenanceEstimatedRemainingSeconds(bytesTransferred, totalBytes)
            } else {
                null
            }
        host.updateState { state ->
            state.copy(
                maintenanceUpdate = state.maintenanceUpdate.copy(
                    elapsedSeconds = elapsedSeconds,
                    estimatedRemainingSeconds = estimatedRemainingSeconds,
                ),
            )
        }
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

    fun loadPairWiring(pairIndex: Int) {
        host.scope.launch {
            if (!beetIsValidPairIndex(pairIndex)) {
                return@launch
            }
            val alreadyLoaded = host.state.value.pairWirings.containsKey(pairIndex)
            val alreadyLoading = synchronized(pendingPairWiringLoads) { !pendingPairWiringLoads.add(pairIndex) }
            if (alreadyLoaded || alreadyLoading) {
                return@launch
            }
            host.updateState { state ->
                state.copy(
                    pairWiringLoading = state.pairWiringLoading + pairIndex,
                    pairWiringErrors = state.pairWiringErrors - pairIndex,
                )
            }
            try {
                val result = withSyncPausedForCommand { sendCommand(BeetJsonCodec.getPairWiring(pairIndex)) }
                if (result.status == "accepted" && result.pairWiring != null) {
                    host.updateState { state ->
                        state.copy(
                            pairWiringLoading = state.pairWiringLoading - pairIndex,
                            pairWiringErrors = state.pairWiringErrors - pairIndex,
                        )
                    }
                } else {
                    host.updateState { state ->
                        state.copy(
                            pairWiringLoading = state.pairWiringLoading - pairIndex,
                            pairWiringErrors = state.pairWiringErrors + (pairIndex to commandMessageForResult(result, strings)),
                        )
                    }
                }
            } catch (error: Exception) {
                host.updateState { state ->
                    state.copy(
                        pairWiringLoading = state.pairWiringLoading - pairIndex,
                        pairWiringErrors = state.pairWiringErrors + (pairIndex to (error.message ?: strings.get(R.string.runtime_command_failed))),
                    )
                }
            } finally {
                synchronized(pendingPairWiringLoads) {
                    pendingPairWiringLoads.remove(pairIndex)
                }
            }
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

    fun refreshWateringInterval() {
        host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            host.updateState { state -> state.copy(wateringIntervalRefreshing = true) }
            try {
                runCatching { withSyncPausedForCommand { sendCommand(BeetJsonCodec.getWateringInterval()) } }
            } finally {
                host.updateState { state -> state.copy(wateringIntervalRefreshing = false) }
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

    fun saveWateringInterval(seconds: Int) {
        host.scope.launch {
            if (seconds !in 300..86400) {
                host.setCommandMessage(strings.get(R.string.runtime_watering_interval_invalid))
                return@launch
            }
            sendUserCommand(BeetJsonCodec.storeWateringInterval(seconds))
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

    fun rebootController() {
        host.scope.launch { sendUserCommand(BeetJsonCodec.rebootController()) }
    }

    fun factoryResetController() {
        host.scope.launch { sendUserCommand(BeetJsonCodec.factoryResetController()) }
    }

    fun runScheduler() {
        host.scope.launch { sendUserCommand(BeetJsonCodec.runScheduler()) }
    }

    fun prepareBundledFirmware() {
        host.scope.launch {
            runCatching {
                val (pkg, summary) = BeetFirmwareCatalog.loadBundledFirmware(
                    context = host.appContext,
                    maintenanceInfo = host.state.value.maintenanceInfo,
                    supportedRuntimeProtocolVersion = BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
                )
                selectedMaintenancePackage = pkg
                resetMaintenanceProgressTracking()
                host.updateState { state ->
                    state.copy(
                        maintenanceUpdate = state.maintenanceUpdate.copy(
                            bundledFirmware = summary,
                            selectedFirmware = summary,
                            phase = BeetMaintenanceUpdatePhase.Ready,
                            bytesTransferred = 0,
                            totalBytes = summary.imageSize,
                            elapsedSeconds = 0,
                            estimatedRemainingSeconds = null,
                            retryCount = 0,
                            statusDetail = strings.get(R.string.maintenance_ready_to_install, summary.metadata.buildLabel),
                            errorDetail = null,
                        ),
                    )
                }
            }.onFailure { error ->
                host.updateState { state ->
                    state.copy(
                        maintenanceUpdate = state.maintenanceUpdate.copy(
                            phase = BeetMaintenanceUpdatePhase.Failed,
                            statusDetail = null,
                            errorDetail = error.message ?: strings.get(R.string.maintenance_failed_to_load_bundled),
                        ),
                    )
                }
            }
        }
    }

    fun prepareCustomFirmware(uri: Uri) {
        host.scope.launch {
            runCatching {
                val (pkg, summary) = BeetFirmwareCatalog.loadCustomFirmware(
                    contentResolver = host.appContext.contentResolver,
                    uri = uri,
                    maintenanceInfo = host.state.value.maintenanceInfo,
                    supportedRuntimeProtocolVersion = BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
                )
                selectedMaintenancePackage = pkg
                resetMaintenanceProgressTracking()
                host.updateState { state ->
                    state.copy(
                        maintenanceUpdate = state.maintenanceUpdate.copy(
                            selectedFirmware = summary,
                            phase = BeetMaintenanceUpdatePhase.Ready,
                            bytesTransferred = 0,
                            totalBytes = summary.imageSize,
                            elapsedSeconds = 0,
                            estimatedRemainingSeconds = null,
                            retryCount = 0,
                            statusDetail = strings.get(R.string.maintenance_custom_selected, summary.sourceLabel),
                            errorDetail = null,
                        ),
                    )
                }
            }.onFailure { error ->
                host.updateState { state ->
                    state.copy(
                        maintenanceUpdate = state.maintenanceUpdate.copy(
                            phase = BeetMaintenanceUpdatePhase.Failed,
                            statusDetail = null,
                            errorDetail = error.message ?: strings.get(R.string.maintenance_failed_to_load_custom),
                        ),
                    )
                }
            }
        }
    }

    fun startMaintenanceUpdate() {
        val currentPhase = host.state.value.maintenanceUpdate.phase
        if (maintenanceUploadJob?.isActive == true || currentPhase.isActiveMaintenancePhase()) {
            Log.i(
                TAG,
                "startMaintenanceUpdate ignored because maintenance update is already active " +
                    "phase=$currentPhase jobActive=${maintenanceUploadJob?.isActive == true}",
            )
            return
        }
        val selectedPackage = selectedMaintenancePackage
        if (selectedPackage == null) {
            Log.w(
                TAG,
                "startMaintenanceUpdate ignored because selectedMaintenancePackage is null " +
                    "phase=${host.state.value.maintenanceUpdate.phase} " +
                    "selectedSummary=${host.state.value.maintenanceUpdate.selectedFirmware?.sourceLabel}",
            )
            return
        }
        val selectedSummary = host.state.value.maintenanceUpdate.selectedFirmware
        if (selectedSummary == null) {
            Log.w(
                TAG,
                "startMaintenanceUpdate ignored because selectedFirmware summary is null " +
                    "phase=${host.state.value.maintenanceUpdate.phase}",
            )
            return
        }
        if (isSelectedFirmwareInstalled(selectedSummary)) {
            Log.i(
                TAG,
                "startMaintenanceUpdate ignored because selected firmware is already installed " +
                    "firmware=${selectedSummary.metadata.firmwareVersion} " +
                    "build=${selectedSummary.metadata.buildLabel} kind=${selectedSummary.metadata.imageKind}",
            )
            host.updateState { state ->
                state.copy(
                    maintenanceUpdate = state.maintenanceUpdate.copy(
                        phase = BeetMaintenanceUpdatePhase.Completed,
                        bytesTransferred = selectedSummary.imageSize,
                        totalBytes = selectedSummary.imageSize,
                        elapsedSeconds = 0,
                        estimatedRemainingSeconds = null,
                        retryCount = 0,
                        statusDetail = strings.get(R.string.maintenance_selected_already_installed),
                        errorDetail = null,
                    ),
                )
            }
            return
        }
        Log.d(
            TAG,
            "startMaintenanceUpdate package=${selectedSummary.sourceLabel} " +
                "firmware=${selectedSummary.metadata.firmwareVersion} " +
                "imageKind=${selectedSummary.metadata.imageKind} size=${selectedSummary.imageSize}",
        )
        resetMaintenanceProgressTracking()
        suspendRuntimeSyncForMaintenance("start maintenance update")
        host.updateState { state ->
            state.copy(
                maintenanceUpdate = state.maintenanceUpdate.copy(
                    phase = BeetMaintenanceUpdatePhase.Starting,
                    bytesTransferred = 0,
                    totalBytes = selectedSummary.imageSize,
                    elapsedSeconds = 0,
                    estimatedRemainingSeconds = null,
                    retryCount = 0,
                    statusDetail = strings.get(R.string.maintenance_starting_update),
                    errorDetail = null,
                ),
            )
        }
        val scopeJob = host.scope.coroutineContext.job
        Log.d(TAG, "startMaintenanceUpdate scopeActive=${scopeJob.isActive} scopeCancelled=${scopeJob.isCancelled}")
        val launchedJob = host.scope.launch {
            runMaintenanceUpdate(selectedPackage, selectedSummary)
        }
        maintenanceUploadJob = launchedJob
        Log.d(
            TAG,
            "startMaintenanceUpdate launched jobActive=${maintenanceUploadJob?.isActive} " +
                "jobCancelled=${maintenanceUploadJob?.isCancelled}",
        )
        launchedJob.invokeOnCompletion { error ->
            if (maintenanceUploadJob === launchedJob) {
                maintenanceUploadJob = null
            }
            Log.d(TAG, "maintenanceUploadJob completed cancelled=${launchedJob.isCancelled}", error)
        }
    }

    fun abortMaintenanceUpdate() {
        if (maintenanceUploadJob?.isActive == true) {
            Log.d(TAG, "abortMaintenanceUpdate requested while upload job is active")
            maintenanceAbortRequested = true
            return
        }
        resetMaintenanceUpdateAfterAbort()
    }

    fun openGatt(device: BluetoothDevice) {
        Log.d(TAG, "openGatt(address=${device.address}, bondState=${device.bondState})")
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
            if (maintenanceUploadJob?.isActive == true && isMaintenanceConnectionHealthy()) {
                Log.d(TAG, "Connection timeout ignored because maintenance resume is healthy phase=$phase")
                return@launch
            }
            Log.w(TAG, "Connection timeout fired while phase=$phase")
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
        Log.d(TAG, "disconnect(clearSelection=$clearSelection, reason=$reason)")
        disconnectGatt(clearSelection, reason)
    }

    private suspend fun sendUserCommand(payload: String) {
        runCatching { withSyncPausedForCommand { sendCommand(payload) } }
            .onSuccess { result ->
                when {
                    result.command == "reboot_controller" && result.status == "accepted" ->
                        setExpectedControllerAction(ExpectedControllerAction.Reboot)
                    result.command == "factory_reset" && result.status == "accepted" -> {
                        host.removeLastAddress()
                        clearCachedControllerHistory()
                        setExpectedControllerAction(ExpectedControllerAction.FactoryReset)
                    }
                }
                host.setCommandMessage(messageForResult(result))
            }
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
        if (maintenanceUploadJob?.isActive == true) {
            Log.d(TAG, "Skipping background event sync because maintenance update is active")
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
                    wateringIntervalRefreshing = false,
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
        Log.d(
            TAG,
            "configureServices(runtimeService=${runtimeService != null}, maintenanceService=${maintenanceService != null}, controllerInfo=${host.session.controllerInfoCharacteristic != null}, stateStream=${host.session.stateStreamCharacteristic != null}, controlPoint=${host.session.controlPointCharacteristic != null}, commandResult=${host.session.commandResultCharacteristic != null}, maintenanceInfo=${host.session.maintenanceInfoCharacteristic != null})",
        )
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
        if (!host.session.tryCompleteInitialSync()) {
            return
        }
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        cancelControllerInfoRetry("initial sync completed")
        host.persistLastAddress(host.currentAddress)
        clearExpectedControllerAction()
        Log.d(TAG, "Initial sync completed for session address=${host.currentAddress}")
        host.updateConnection(BeetConnectionPhase.Connected, strings.get(R.string.runtime_connected_to_controller))
        if (maintenanceUploadJob?.isActive == true) {
            Log.d(TAG, "Skipping post-sync refreshes because maintenance update is active")
            return
        }
        refreshValveConfig()
        refreshWateringInterval()
        host.scope.launch {
            delay(POST_CONNECT_EVENT_SYNC_DELAY_MS)
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) return@launch
            startBackgroundEventSync()
        }
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
        if (info.protocolVersion != BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION) {
            Log.e(
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
        Log.d(TAG, "handleControllerInfo(deviceId=${info.deviceId}, protocol=${info.protocolVersion}, pairCount=${info.pairCount})")
        host.updateState { it.copy(controllerInfo = info) }
        completeInitialSyncIfReady()
    }

    private fun handleMaintenanceInfo(gatt: BluetoothGatt, payload: ByteArray) {
        val info = try {
            BeetJsonCodec.parseMaintenanceInfo(payload.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            pendingMaintenanceInfoRead?.completeExceptionally(error)
            pendingMaintenanceInfoRead = null
            Log.e(TAG, "Maintenance info payload parse failed", error)
            disconnectGatt(clearSelection = false, reason = "invalid maintenance info payload")
            host.clearSession()
            host.updateConnection(BeetConnectionPhase.Error, strings.get(R.string.runtime_maintenance_info_invalid))
            return
        }
        Log.d(
            TAG,
            "handleMaintenanceInfo(product=${info.productId}, hardware=${info.hardwareRev}, runtimeProtocol=${info.runtimeProtocolVersion}, maintenanceProtocol=${info.maintenanceProtocolVersion}, imageKind=${info.imageKind})",
        )
        host.updateState { it.copy(maintenanceInfo = info) }
        pendingMaintenanceInfoRead?.complete(info)
        pendingMaintenanceInfoRead = null
        when (
            determineMaintenanceRoute(
                info = info,
                runtimeServiceAvailable = hasCompleteRuntimeService(),
            supportedRuntimeProtocolVersion = BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
            )
        ) {
            BeetMaintenanceRoute.Runtime -> resolveRuntimeOrActiveMaintenanceRoute(gatt, info)
            BeetMaintenanceRoute.ForcedUpdate -> {
                maybePrepareBundledFirmware()
                host.updateConnection(BeetConnectionPhase.MaintenanceRequired, maintenanceRequiredMessage(info))
            }
        }
    }

    private fun resolveRuntimeOrActiveMaintenanceRoute(
        gatt: BluetoothGatt,
        info: BeetMaintenanceInfo,
    ) {
        val maintenanceControl = host.session.maintenanceControlCharacteristic
        val maintenanceStatus = host.session.maintenanceStatusCharacteristic
        if (maintenanceUploadJob?.isActive == true) {
            Log.d(
                TAG,
                "resolveRuntimeOrActiveMaintenanceRoute deferring to active maintenance job " +
                    "phase=${host.state.value.maintenanceUpdate.phase} detail=${host.state.value.maintenanceUpdate.statusDetail}",
            )
            host.updateConnection(
                BeetConnectionPhase.MaintenanceRequired,
                host.state.value.maintenanceUpdate.statusDetail ?: activeMaintenanceDetail(),
            )
            return
        }
        if (maintenanceControl == null || maintenanceStatus == null) {
            if (!readControllerInfo(gatt)) {
                disconnectGatt(clearSelection = false, reason = "runtime service missing after maintenance route runtime")
                host.clearSession()
                host.requestStartScan(detail = strings.get(R.string.runtime_gatt_service_incomplete))
            }
            return
        }
        initialMaintenanceStatusJob?.cancel()
        initialMaintenanceStatusJob = host.scope.launch {
            try {
                val status = sendMaintenanceControl(BeetJsonCodec.maintenanceQueryStatus())
                Log.d(
                    TAG,
                    "resolveRuntimeOrActiveMaintenanceRoute initial maintenance state=${status.state} " +
                        "sessionId=${status.sessionId} nextOffset=${status.nextOffset}",
                )
                if (host.session.currentGatt !== gatt) {
                    return@launch
                }
                if (status.state in setOf("awaiting_data", "transferring", "rebooting")) {
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = null
                    maybePrepareBundledFirmware()
                    host.updateState { state ->
                        state.copy(
                            maintenanceUpdate = state.maintenanceUpdate.copy(
                                phase = when (status.state) {
                                    "rebooting" -> BeetMaintenanceUpdatePhase.Rebooting
                                    "transferring" -> BeetMaintenanceUpdatePhase.Uploading
                                    else -> BeetMaintenanceUpdatePhase.Reconnecting
                                },
                                bytesTransferred = status.bytesReceived.coerceAtLeast(status.nextOffset),
                                totalBytes = if (status.totalBytes > 0) status.totalBytes else state.maintenanceUpdate.totalBytes,
                                retryCount = 0,
                                statusDetail = when (status.state) {
                                    "rebooting" -> strings.get(R.string.maintenance_rebooting_after_update)
                                    "transferring" -> "Uploading firmware: ${status.nextOffset} / ${status.totalBytes} bytes."
                                    else -> strings.get(R.string.maintenance_reconnecting_attempt, 1, MAX_MAINTENANCE_RECONNECT_ATTEMPTS)
                                },
                                errorDetail = null,
                            ),
                        )
                    }
                    host.updateConnection(BeetConnectionPhase.MaintenanceRequired, activeMaintenanceMessage(status))
                    return@launch
                }
                if (!readControllerInfo(gatt)) {
                    disconnectGatt(clearSelection = false, reason = "runtime service missing after maintenance status query")
                    host.clearSession()
                    host.requestStartScan(detail = strings.get(R.string.runtime_gatt_service_incomplete))
                }
            } catch (error: Exception) {
                Log.w(TAG, "resolveRuntimeOrActiveMaintenanceRoute falling back to runtime sync", error)
                if (host.session.currentGatt === gatt && !readControllerInfo(gatt)) {
                    disconnectGatt(clearSelection = false, reason = "runtime service missing after maintenance status fallback")
                    host.clearSession()
                    host.requestStartScan(detail = strings.get(R.string.runtime_gatt_service_incomplete))
                }
            } finally {
                if (initialMaintenanceStatusJob?.isCancelled != false) {
                    initialMaintenanceStatusJob = null
                }
            }
        }
    }

    private fun activeMaintenanceMessage(status: BeetMaintenanceStatus): String = when (status.state) {
        "rebooting" -> strings.get(R.string.maintenance_rebooting_after_update)
        "transferring" -> "Uploading firmware: ${status.nextOffset} / ${status.totalBytes} bytes."
        else -> strings.get(R.string.runtime_maintenance_runtime_unavailable)
    }

    private fun activeMaintenanceDetail(): String = when (host.state.value.maintenanceUpdate.phase) {
        BeetMaintenanceUpdatePhase.Starting -> strings.get(R.string.maintenance_starting_update)
        BeetMaintenanceUpdatePhase.Rebooting -> strings.get(R.string.maintenance_rebooting_after_update)
        BeetMaintenanceUpdatePhase.Uploading -> {
            val update = host.state.value.maintenanceUpdate
            strings.get(R.string.maintenance_uploading_progress, update.bytesTransferred, update.totalBytes)
        }
        BeetMaintenanceUpdatePhase.Reconnecting -> strings.get(
            R.string.maintenance_reconnecting_attempt,
            maxOf(host.state.value.maintenanceUpdate.retryCount, 1),
            MAX_MAINTENANCE_RECONNECT_ATTEMPTS,
        )
        else -> strings.get(R.string.runtime_maintenance_runtime_unavailable)
    }

    private fun maybePrepareBundledFirmware() {
        if (host.state.value.maintenanceUpdate.bundledFirmware == null) {
            prepareBundledFirmware()
        }
    }

    private fun hasCompleteRuntimeService(): Boolean =
        host.session.controllerInfoCharacteristic != null &&
            host.session.stateStreamCharacteristic != null &&
            host.session.controlPointCharacteristic != null &&
            host.session.commandResultCharacteristic != null

    private fun maintenanceRequiredMessage(info: BeetMaintenanceInfo): String =
        if (hasCompleteRuntimeService()) {
            strings.get(
                R.string.runtime_maintenance_update_required,
                info.runtimeProtocolVersion,
                BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
            )
        } else {
            strings.get(R.string.runtime_maintenance_runtime_unavailable)
        }

    private suspend fun sendMaintenanceControl(payload: String): BeetMaintenanceStatus {
        return maintenanceMutex.withLock {
            val gatt = host.session.currentGatt ?: error(strings.get(R.string.runtime_no_connected_controller))
            val controlPoint = host.session.maintenanceControlCharacteristic
                ?: error(strings.get(R.string.maintenance_control_unavailable))
            Log.d(TAG, "sendMaintenanceControl(payload=$payload)")
            val statusDeferred = CompletableDeferred<BeetMaintenanceStatus>()
            val writeDeferred = CompletableDeferred<Unit>()
            pendingMaintenanceStatus = statusDeferred
            pendingCharacteristicWrite = writeDeferred
            controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            controlPoint.value = payload.toByteArray(StandardCharsets.UTF_8)
            @Suppress("MissingPermission")
            val writeStarted = gatt.writeCharacteristic(controlPoint)
            if (!writeStarted) {
                pendingMaintenanceStatus = null
                pendingCharacteristicWrite = null
                error(strings.get(R.string.runtime_ble_send_failed))
            }
            try {
                withTimeout(MAINTENANCE_CONTROL_TIMEOUT_MS) {
                    writeDeferred.await()
                    statusDeferred.await().also { status ->
                        Log.d(
                            TAG,
                            "sendMaintenanceControl result state=${status.state} " +
                                "sessionId=${status.sessionId} nextOffset=${status.nextOffset} " +
                                "failure=${status.failureReason}",
                        )
                    }
                }
            } finally {
                pendingMaintenanceStatus = null
                pendingCharacteristicWrite = null
            }
        }
    }

    private suspend fun writeMaintenanceChunk(chunk: ByteArray) {
        val gatt = host.session.currentGatt ?: error(strings.get(R.string.runtime_no_connected_controller))
        val characteristic = host.session.maintenanceDataCharacteristic
            ?: error(strings.get(R.string.maintenance_data_unavailable))
        val deferred = CompletableDeferred<Unit>()
        pendingCharacteristicWrite = deferred
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = chunk
        @Suppress("MissingPermission")
        val started = gatt.writeCharacteristic(characteristic)
        if (!started) {
            pendingCharacteristicWrite = null
            error(strings.get(R.string.runtime_ble_send_failed))
        }
        try {
            withTimeout(MAINTENANCE_CHUNK_TIMEOUT_MS) { deferred.await() }
        } finally {
            pendingCharacteristicWrite = null
        }
    }

    private suspend fun runMaintenanceUpdate(
        selectedPackage: BeetFirmwareImagePackage,
        selectedFirmware: BeetFirmwarePackageSummary,
    ) {
        Log.d(
            TAG,
            "runMaintenanceUpdate start firmware=${selectedFirmware.metadata.firmwareVersion} " +
                "size=${selectedPackage.imageSize}",
        )
        acquireMaintenanceWakeLock()
        maintenanceReconnectAttempts = 0
        maintenanceExpectedRebootDisconnect = false
        maintenanceAbortRequested = false
        var resumeAllowed = false
        try {
            while (true) {
                try {
                    throwIfMaintenanceAbortRequested()
                    Log.d(TAG, "runMaintenanceUpdate waiting for maintenance connection")
                    waitForMaintenanceConnection()
                    Log.d(TAG, "runMaintenanceUpdate maintenance connection ready")
                    val fullImageAlreadyTransferred =
                        host.state.value.maintenanceUpdate.bytesTransferred >= selectedPackage.imageSize
                    val status = startOrResumeMaintenanceSession(
                        selectedPackage = selectedPackage,
                        selectedFirmware = selectedFirmware,
                        resumeAllowed = resumeAllowed,
                        fullImageAlreadyTransferred = fullImageAlreadyTransferred,
                    )
                    Log.d(
                        TAG,
                        "runMaintenanceUpdate terminal status state=${status.state} " +
                            "sessionId=${status.sessionId} nextOffset=${status.nextOffset} " +
                            "failure=${status.failureReason}",
                    )
                    when (status.state) {
                        "rebooting" -> {
                            maintenanceExpectedRebootDisconnect = true
                            val reconnectDevice =
                                host.session.currentGatt?.device ?: error(strings.get(R.string.runtime_no_connected_controller))
                            host.updateState { state ->
                                state.copy(
                                    maintenanceUpdate = state.maintenanceUpdate.copy(
                                        phase = BeetMaintenanceUpdatePhase.Rebooting,
                                        bytesTransferred = selectedPackage.imageSize,
                                        totalBytes = selectedPackage.imageSize,
                                        elapsedSeconds = maintenanceElapsedSeconds(),
                                        estimatedRemainingSeconds = 0,
                                        statusDetail = strings.get(R.string.maintenance_rebooting_after_update),
                                        errorDetail = null,
                                    ),
                                )
                            }
                            waitForMaintenanceDisconnect()
                            scheduleMaintenanceReconnect(reconnectDevice)
                            delay(MAINTENANCE_RECONNECT_DELAY_MS)
                            resumeAllowed = true
                        }

                        "completed" -> {
                            maintenanceExpectedRebootDisconnect = false
                            host.updateState { state ->
                                state.copy(
                                    maintenanceUpdate = state.maintenanceUpdate.copy(
                                        phase = BeetMaintenanceUpdatePhase.Completed,
                                        bytesTransferred = selectedPackage.imageSize,
                                        totalBytes = selectedPackage.imageSize,
                                        elapsedSeconds = maintenanceElapsedSeconds(),
                                        estimatedRemainingSeconds = 0,
                                        statusDetail = strings.get(R.string.maintenance_update_completed),
                                        errorDetail = null,
                                    ),
                                )
                            }
                            resumeRuntimeSyncAfterCompletedMaintenance()
                            return
                        }

                        "failed" -> error(status.failureReason ?: strings.get(R.string.maintenance_update_failed))
                        else -> error(strings.get(R.string.maintenance_unexpected_status, status.state))
                    }
                } catch (abort: MaintenanceAbortRequestedException) {
                    performMaintenanceAbort()
                    return
                } catch (error: Exception) {
                    Log.w(TAG, "runMaintenanceUpdate caught error reconnectAttempts=$maintenanceReconnectAttempts", error)
                    if (
                        error is MaintenanceControlWriteException ||
                        maintenanceReconnectAttempts >= MAX_MAINTENANCE_RECONNECT_ATTEMPTS ||
                        maintenanceExpectedRebootDisconnect
                    ) {
                        throw error
                    }
                    maintenanceReconnectAttempts += 1
                    host.updateState { state ->
                        state.copy(
                            maintenanceUpdate = state.maintenanceUpdate.copy(
                                phase = BeetMaintenanceUpdatePhase.Reconnecting,
                                retryCount = maintenanceReconnectAttempts,
                                elapsedSeconds = maintenanceElapsedSeconds(),
                                estimatedRemainingSeconds = maintenanceEstimatedRemainingSeconds(
                                    state.maintenanceUpdate.bytesTransferred,
                                    state.maintenanceUpdate.totalBytes,
                                ),
                                statusDetail = strings.get(
                                    R.string.maintenance_reconnecting_attempt,
                                    maintenanceReconnectAttempts,
                                    MAX_MAINTENANCE_RECONNECT_ATTEMPTS,
                                ),
                                errorDetail = null,
                            ),
                        )
                    }
                    scheduleMaintenanceReconnect()
                    resumeAllowed = true
                    delay(MAINTENANCE_RECONNECT_DELAY_MS)
                }
            }
        } catch (abort: MaintenanceAbortRequestedException) {
            performMaintenanceAbort()
        } catch (error: Exception) {
            Log.e(TAG, "runMaintenanceUpdate failed", error)
            host.updateState { state ->
                state.copy(
                    maintenanceUpdate = state.maintenanceUpdate.copy(
                        phase = BeetMaintenanceUpdatePhase.Failed,
                        elapsedSeconds = maintenanceElapsedSeconds(),
                        estimatedRemainingSeconds = null,
                        statusDetail = null,
                        errorDetail = error.message ?: strings.get(R.string.maintenance_update_failed),
                    ),
                )
            }
        } finally {
            if (!maintenanceExpectedRebootDisconnect) {
                resetMaintenanceProgressTracking()
                releaseMaintenanceWakeLock()
            }
        }
    }

    private fun suspendRuntimeSyncForMaintenance(reason: String) {
        Log.d(TAG, "suspendRuntimeSyncForMaintenance(reason=$reason)")
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

    private suspend fun startOrResumeMaintenanceSession(
        selectedPackage: BeetFirmwareImagePackage,
        selectedFirmware: BeetFirmwarePackageSummary,
        resumeAllowed: Boolean,
        fullImageAlreadyTransferred: Boolean,
    ): BeetMaintenanceStatus {
        Log.d(TAG, "startOrResumeMaintenanceSession(resumeAllowed=$resumeAllowed)")
        val initialStatus = if (resumeAllowed) {
            sendMaintenanceControl(BeetJsonCodec.maintenanceQueryStatus())
        } else {
            val status = sendMaintenanceControl(BeetJsonCodec.maintenanceQueryStatus())
            Log.d(TAG, "startOrResumeMaintenanceSession query_status result state=${status.state}")
            status
        }
        Log.d(
            TAG,
            "startOrResumeMaintenanceSession initial state=${initialStatus.state} " +
                "sessionId=${initialStatus.sessionId} nextOffset=${initialStatus.nextOffset}",
        )
        if (resumeAllowed && fullImageAlreadyTransferred && initialStatus.state == "idle") {
            val refreshedMaintenanceInfo = readFreshMaintenanceInfo()
            if (isSelectedFirmwareInstalled(selectedFirmware, refreshedMaintenanceInfo)) {
                Log.i(
                    TAG,
                    "startOrResumeMaintenanceSession treating idle as completed because target firmware is already running",
                )
                return BeetMaintenanceStatus(
                    state = "completed",
                    nextOffset = selectedPackage.imageSize,
                    bytesReceived = selectedPackage.imageSize,
                    totalBytes = selectedPackage.imageSize,
                )
            }
            Log.w(
                TAG,
                "startOrResumeMaintenanceSession idle after full transfer but target firmware is not installed " +
                    "current=${refreshedMaintenanceInfo.firmwareVersion}/${refreshedMaintenanceInfo.buildLabel}/${refreshedMaintenanceInfo.imageKind} " +
                    "target=${selectedFirmware.metadata.firmwareVersion}/${selectedFirmware.metadata.buildLabel}/${selectedFirmware.metadata.imageKind}",
            )
        }
        val uploadStatus = when (initialStatus.state) {
            "awaiting_data", "transferring" -> initialStatus
            "rebooting", "completed" -> return initialStatus
            "idle", "failed" -> {
                val payloadBudget = maintenanceControlPayloadBudget()
                val beginUpdatePayload = BeetJsonCodec.maintenanceBeginUpdate(
                    firmware = selectedFirmware,
                    maxPayloadBytes = payloadBudget,
                )
                Log.d(
                    TAG,
                    "startOrResumeMaintenanceSession begin_update compact=${beginUpdatePayload.compact} " +
                        "size=${beginUpdatePayload.sizeBytes} budget=$payloadBudget",
                )
                sendMaintenanceControl(beginUpdatePayload.json)
            }
            else -> error(strings.get(R.string.maintenance_unexpected_status, initialStatus.state))
        }
        Log.d(
            TAG,
            "startOrResumeMaintenanceSession upload state=${uploadStatus.state} " +
                "sessionId=${uploadStatus.sessionId} nextOffset=${uploadStatus.nextOffset} " +
                "failure=${uploadStatus.failureReason}",
        )
        if (uploadStatus.state == "failed") {
            error(uploadStatus.failureReason ?: strings.get(R.string.maintenance_update_failed))
        }
        uploadMaintenanceData(selectedPackage, uploadStatus)
        return sendMaintenanceControl(BeetJsonCodec.maintenanceFinishUpdate())
    }

    private fun isSelectedFirmwareInstalled(
        selectedFirmware: BeetFirmwarePackageSummary,
        maintenanceInfo: BeetMaintenanceInfo? = host.state.value.maintenanceInfo,
    ): Boolean = BeetFirmwareCatalog.matchesInstalledFirmware(selectedFirmware, maintenanceInfo)

    private fun maintenanceControlPayloadBudget(): Int = (negotiatedMtu - 3).coerceAtLeast(20)

    private suspend fun readFreshMaintenanceInfo(): BeetMaintenanceInfo {
        val gatt = host.session.currentGatt ?: error(strings.get(R.string.runtime_no_connected_controller))
        val characteristic = host.session.maintenanceInfoCharacteristic
            ?: error(strings.get(R.string.runtime_maintenance_info_invalid))
        val deferred = CompletableDeferred<BeetMaintenanceInfo>()
        pendingMaintenanceInfoRead = deferred
        @Suppress("MissingPermission")
        val started = gatt.readCharacteristic(characteristic)
        if (!started) {
            pendingMaintenanceInfoRead = null
            error(strings.get(R.string.runtime_ble_send_failed))
        }
        return try {
            withTimeout(COMMAND_TIMEOUT_MS) { deferred.await() }
        } finally {
            if (pendingMaintenanceInfoRead === deferred) {
                pendingMaintenanceInfoRead = null
            }
        }
    }

    private suspend fun uploadMaintenanceData(
        selectedPackage: BeetFirmwareImagePackage,
        status: BeetMaintenanceStatus,
    ) {
        val sessionId = status.sessionId ?: error(strings.get(R.string.maintenance_missing_session))
        var offset = status.nextOffset
        Log.d(
            TAG,
            "uploadMaintenanceData(sessionId=$sessionId startOffset=$offset total=${selectedPackage.imageSize})",
        )
        host.updateState { state ->
            state.copy(
                maintenanceUpdate = state.maintenanceUpdate.copy(
                    phase = BeetMaintenanceUpdatePhase.Uploading,
                    bytesTransferred = offset,
                    totalBytes = selectedPackage.imageSize,
                    elapsedSeconds = maintenanceElapsedSeconds(),
                    estimatedRemainingSeconds = null,
                    statusDetail = strings.get(R.string.maintenance_uploading_progress, offset, selectedPackage.imageSize),
                    errorDetail = null,
                ),
            )
        }
        noteMaintenanceProgress(offset, selectedPackage.imageSize)
        while (offset < selectedPackage.imageBytes.size) {
            throwIfMaintenanceAbortRequested()
            val payloadLimit = (negotiatedMtu - 11).coerceIn(MIN_MAINTENANCE_PAYLOAD_BYTES, MAX_MAINTENANCE_PAYLOAD_BYTES)
            val end = minOf(offset + payloadLimit, selectedPackage.imageBytes.size)
            val payload = selectedPackage.imageBytes.copyOfRange(offset, end)
            val chunk = ByteArray(8 + payload.size)
            writeLeU32(chunk, 0, sessionId)
            writeLeU32(chunk, 4, offset)
            payload.copyInto(chunk, destinationOffset = 8)
            writeMaintenanceChunk(chunk)
            throwIfMaintenanceAbortRequested()
            offset = end
            if (offset == end && (offset == selectedPackage.imageSize || offset % 16384 == 0)) {
                Log.d(TAG, "uploadMaintenanceData progressed offset=$offset total=${selectedPackage.imageSize}")
            }
            host.updateState { state ->
                state.copy(
                    maintenanceUpdate = state.maintenanceUpdate.copy(
                        phase = BeetMaintenanceUpdatePhase.Uploading,
                        bytesTransferred = offset,
                        totalBytes = selectedPackage.imageSize,
                        statusDetail = strings.get(R.string.maintenance_uploading_progress, offset, selectedPackage.imageSize),
                        errorDetail = null,
                    ),
                )
            }
            noteMaintenanceProgress(offset, selectedPackage.imageSize)
        }
    }

    private suspend fun performMaintenanceAbort() {
        Log.d(TAG, "performMaintenanceAbort()")
        runCatching {
            if (host.session.currentGatt != null && host.session.maintenanceControlCharacteristic != null) {
                sendMaintenanceControl(BeetJsonCodec.maintenanceAbortUpdate())
            }
        }.onFailure { error ->
            Log.w(TAG, "performMaintenanceAbort failed to send abort_update, disconnecting GATT", error)
            disconnectGatt(clearSelection = false, reason = "maintenance abort fallback disconnect")
            host.clearSession()
        }
        resetMaintenanceUpdateAfterAbort()
    }

    private fun resetMaintenanceUpdateAfterAbort() {
        maintenanceAbortRequested = false
        maintenanceExpectedRebootDisconnect = false
        maintenanceReconnectAttempts = 0
        maintenanceUploadJob = null
        releaseMaintenanceWakeLock()
        host.updateState { state ->
            state.copy(
                maintenanceUpdate = state.maintenanceUpdate.copy(
                    phase = BeetMaintenanceUpdatePhase.Idle,
                    bytesTransferred = 0,
                    totalBytes = 0,
                    elapsedSeconds = 0,
                    estimatedRemainingSeconds = null,
                    retryCount = 0,
                    statusDetail = strings.get(R.string.maintenance_update_aborted),
                    errorDetail = null,
                ),
            )
        }
        resetMaintenanceProgressTracking()
    }

    private fun throwIfMaintenanceAbortRequested() {
        if (maintenanceAbortRequested) {
            throw MaintenanceAbortRequestedException()
        }
    }

    private fun resumeRuntimeSyncAfterCompletedMaintenance() {
        val gatt = host.session.currentGatt
        if (gatt == null) {
            Log.d(TAG, "resumeRuntimeSyncAfterCompletedMaintenance skipped because GATT is null")
            return
        }
        if (host.session.controllerInfoCharacteristic == null) {
            Log.d(TAG, "resumeRuntimeSyncAfterCompletedMaintenance skipped because controller info characteristic is unavailable")
            return
        }
        Log.d(TAG, "resumeRuntimeSyncAfterCompletedMaintenance reading controller info")
        if (!readControllerInfo(gatt)) {
            disconnectGatt(clearSelection = false, reason = "post-maintenance runtime sync could not read controller info")
            host.clearSession()
            host.requestStartScan(detail = strings.get(R.string.runtime_gatt_service_incomplete))
        }
    }

    private fun resolveMaintenanceReconnectDevice(
        device: BluetoothDevice? = host.session.currentGatt?.device,
    ): BluetoothDevice? {
        device?.let { return it }
        val address = host.currentAddress ?: return null
        val adapter = host.bluetoothAdapter ?: return null
        return runCatching { adapter.getRemoteDevice(address) }
            .onFailure { error ->
                Log.w(TAG, "resolveMaintenanceReconnectDevice failed for address=$address", error)
            }
            .getOrNull()
    }

    private fun scheduleMaintenanceReconnect(device: BluetoothDevice? = host.session.currentGatt?.device) {
        val reconnectDevice = resolveMaintenanceReconnectDevice(device)
        if (reconnectDevice == null) {
            Log.w(TAG, "scheduleMaintenanceReconnect skipped: no reconnect target")
            return
        }
        maintenanceReconnectJob?.cancel()
        maintenanceReconnectJob = host.scope.launch {
            delay(MAINTENANCE_RECONNECT_DELAY_MS)
            if (host.state.value.maintenanceUpdate.phase in
                setOf(BeetMaintenanceUpdatePhase.Reconnecting, BeetMaintenanceUpdatePhase.Rebooting)
            ) {
                Log.d(TAG, "scheduleMaintenanceReconnect opening address=${reconnectDevice.address}")
                host.requestOpenGatt(reconnectDevice)
            }
        }
    }

    private suspend fun waitForMaintenanceDisconnect() {
        val deadline = System.currentTimeMillis() + MAINTENANCE_RECONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (host.session.currentGatt == null) {
                return
            }
            delay(100L)
        }
        error(strings.get(R.string.maintenance_reconnect_timeout))
    }

    private suspend fun waitForMaintenanceConnection() {
        val deadline = System.currentTimeMillis() + MAINTENANCE_RECONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val phase = host.state.value.connection.phase
            val stableFor = System.currentTimeMillis() - lastGattResetAt
            if ((phase == BeetConnectionPhase.MaintenanceRequired || phase == BeetConnectionPhase.Connected) &&
                host.session.currentGatt != null &&
                host.session.maintenanceControlCharacteristic != null &&
                host.session.maintenanceDataCharacteristic != null &&
                stableFor >= MAINTENANCE_GATT_STABILITY_MS
            ) {
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = null
                Log.d(TAG, "waitForMaintenanceConnection satisfied phase=$phase mtu=$negotiatedMtu stableFor=${stableFor}ms")
                return
            }
            delay(200L)
        }
        error(strings.get(R.string.maintenance_reconnect_timeout))
    }

    private fun isMaintenanceConnectionHealthy(): Boolean {
        val phase = host.state.value.connection.phase
        return (phase == BeetConnectionPhase.MaintenanceRequired || phase == BeetConnectionPhase.Connected) &&
            host.session.currentGatt != null &&
            host.session.maintenanceControlCharacteristic != null &&
            host.session.maintenanceDataCharacteristic != null
    }

    private fun acquireMaintenanceWakeLock() {
        if (!maintenanceWakeLock.isHeld) {
            maintenanceWakeLock.acquire(MAINTENANCE_WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseMaintenanceWakeLock() {
        if (maintenanceWakeLock.isHeld) {
            maintenanceWakeLock.release()
        }
    }

    private fun writeLeU32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
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
                val syncedPairs = host.session.markPairSynced(pairState.pairIndex)
                Log.d(TAG, "handleStatePayload(pairFrame pair=${pairState.pairIndex} state=${pairState.state} syncedPairs=$syncedPairs)")
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
        host.session.pendingCommand?.complete(result)
    }

    private fun handleMaintenanceStatusPayload(payload: ByteArray) {
        val status = try {
            BeetJsonCodec.parseMaintenanceStatus(payload.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            Log.e(TAG, "Maintenance status payload parse failed", error)
            return
        }
        pendingMaintenanceStatus?.complete(status)
        val resolvedTotalBytes =
            if (status.totalBytes > 0) status.totalBytes else host.state.value.maintenanceUpdate.totalBytes
        noteMaintenanceProgress(status.bytesReceived, resolvedTotalBytes)
        host.updateState { state ->
            val preserveTransferredBytes =
                status.state == "idle" &&
                    maintenanceUploadJob?.isActive == true &&
                    state.maintenanceUpdate.totalBytes > 0 &&
                    state.maintenanceUpdate.bytesTransferred >= state.maintenanceUpdate.totalBytes
            state.copy(
                maintenanceUpdate = state.maintenanceUpdate.copy(
                    bytesTransferred = if (preserveTransferredBytes) {
                        state.maintenanceUpdate.bytesTransferred
                    } else {
                        status.bytesReceived
                    },
                    totalBytes = resolvedTotalBytes,
                    elapsedSeconds = maintenanceElapsedSeconds(),
                    estimatedRemainingSeconds = when (status.state) {
                        "awaiting_data", "transferring", "reconnecting" ->
                            maintenanceEstimatedRemainingSeconds(status.bytesReceived, resolvedTotalBytes)
                        "rebooting", "completed" -> 0
                        "failed" -> null
                        else -> state.maintenanceUpdate.estimatedRemainingSeconds
                    },
                    phase = when (status.state) {
                        "awaiting_data", "transferring" -> BeetMaintenanceUpdatePhase.Uploading
                        "rebooting" -> BeetMaintenanceUpdatePhase.Rebooting
                        "completed" -> BeetMaintenanceUpdatePhase.Completed
                        "failed" -> BeetMaintenanceUpdatePhase.Failed
                        else -> state.maintenanceUpdate.phase
                    },
                    statusDetail = when (status.state) {
                        "awaiting_data", "transferring" ->
                            strings.get(R.string.maintenance_uploading_progress, status.bytesReceived, status.totalBytes)
                        "rebooting" -> strings.get(R.string.maintenance_rebooting_after_update)
                        "completed" -> strings.get(R.string.maintenance_update_completed)
                        else -> state.maintenanceUpdate.statusDetail
                    },
                    errorDetail = if (status.state == "failed") {
                        status.failureReason ?: strings.get(R.string.maintenance_update_failed)
                    } else {
                        null
                    },
                ),
            )
        }
    }

    private fun resetSyncState() {
        Log.d(TAG, "resetSyncState()")
        commandChunkAssembler.reset()
        cancelControllerInfoRetry("reset sync state")
        host.resetSyncState()
    }

    private fun disconnectGatt(clearSelection: Boolean, reason: String) {
        Log.d(TAG, "disconnectGatt(reason=$reason, clearSelection=$clearSelection, currentAddress=${host.currentAddress}, phase=${host.state.value.connection.phase})")
        lastGattResetAt = System.currentTimeMillis()
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        cancelControllerInfoRetry("disconnect gatt: $reason")
        eventSyncJob?.cancel()
        eventSyncJob = null
        pendingMoistureTests.clear()
        maintenanceReconnectJob?.cancel()
        maintenanceReconnectJob = null
        val gatt = host.session.currentGatt
        host.session.currentGatt = null
        resetSyncState()
        host.session.pendingCommand?.cancel()
        host.session.pendingCommand = null
        pendingMaintenanceStatus?.cancel()
        pendingMaintenanceStatus = null
        pendingCharacteristicWrite?.cancel()
        pendingCharacteristicWrite = null
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
        Log.d(TAG, "Cancelling controller info retry: reason=$reason active=${retryJob.isActive}")
        retryJob.cancel()
        controllerInfoRetryJob = null
    }

    private fun isCurrentGatt(gatt: BluetoothGatt, callback: String): Boolean {
        val current = host.session.currentGatt
        if (current === gatt) {
            return true
        }
        Log.d(
            TAG,
            "Ignoring stale $callback callback for address=${gatt.device.address}, currentAddress=${current?.device?.address}",
        )
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
                val reconnectDevice = resolveMaintenanceReconnectDevice()
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
            if (!isCurrentGatt(gatt, "onConnectionStateChange")) {
                return@beetGattCallback
            }
            Log.d(TAG, "onConnectionStateChange(status=$status, newState=$newState, address=${gatt.device.address})")
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
                val maintenanceActive = maintenanceUploadJob?.isActive == true
                disconnectGatt(clearSelection = false, reason = "gatt error status=$status")
                if (expectedControllerActionActive() && !maintenanceActive) {
                    handleExpectedControllerActionDisconnect()
                    return@beetGattCallback
                }
                host.clearSession()
                if (maintenanceActive) {
                    val detail = if (maintenanceExpectedRebootDisconnect) {
                        strings.get(R.string.maintenance_rebooting_after_update)
                    } else {
                        strings.get(
                            R.string.maintenance_reconnecting_attempt,
                            maintenanceReconnectAttempts + 1,
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
                    if (!gatt.requestMtu(DESIRED_MTU)) {
                        negotiatedMtu = DEFAULT_MTU
                        host.session.serviceDiscoveryStarted = true
                        @Suppress("MissingPermission")
                        gatt.discoverServices()
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    val maintenanceActive = maintenanceUploadJob?.isActive == true
                    disconnectGatt(clearSelection = false, reason = "gatt disconnected callback")
                    if (expectedControllerActionActive() && !maintenanceActive) {
                        handleExpectedControllerActionDisconnect()
                        return@beetGattCallback
                    }
                    host.clearSession()
                    if (maintenanceActive) {
                        host.updateConnection(
                            BeetConnectionPhase.MaintenanceRequired,
                            if (maintenanceExpectedRebootDisconnect) {
                                strings.get(R.string.maintenance_rebooting_after_update)
                            } else {
                                strings.get(
                                    R.string.maintenance_reconnecting_attempt,
                                    maintenanceReconnectAttempts + 1,
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
            Log.d(TAG, "onMtuChanged status=$status mtu=$mtu")
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            if (host.session.serviceDiscoveryStarted) {
                Log.d(TAG, "Ignoring duplicate onMtuChanged after service discovery already started")
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
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (host.session.servicesConfigured) {
                Log.d(TAG, "Ignoring duplicate onServicesDiscovered after services already configured")
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
            Log.d(TAG, "onDescriptorWrite uuid=${descriptor.characteristic.uuid} status=$status queueRemaining=${host.session.descriptorQueue.size}")
            if (status != BluetoothGatt.GATT_SUCCESS || !writeNextDescriptor(gatt)) {
                disconnectGatt(clearSelection = false, reason = "descriptor write failed status=$status uuid=${descriptor.characteristic.uuid}")
                host.clearSession()
                host.requestStartScan(detail = strings.get(R.string.runtime_subscription_failed))
            }
        },
        onCharacteristicWrite = { _, characteristic, status ->
            if (characteristic.uuid == BeetBluetoothSupport.maintenanceDataUuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingCharacteristicWrite?.complete(Unit)
                } else {
                    pendingCharacteristicWrite?.completeExceptionally(
                        IllegalStateException(strings.get(R.string.maintenance_chunk_write_failed, status)),
                    )
                }
                return@beetGattCallback
            }
            if (characteristic.uuid == BeetBluetoothSupport.maintenanceControlUuid) {
                Log.d(TAG, "onCharacteristicWrite maintenance control status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingCharacteristicWrite?.complete(Unit)
                } else {
                    pendingCharacteristicWrite?.completeExceptionally(
                        MaintenanceControlWriteException(strings.get(R.string.maintenance_control_write_failed, status)),
                    )
                }
            }
        },
        onCharacteristicRead = { gatt, characteristic, status ->
            if (!isCurrentGatt(gatt, "onCharacteristicRead")) {
                return@beetGattCallback
            }
            Log.d(TAG, "onCharacteristicRead uuid=${characteristic.uuid} status=$status")
            if (characteristic.uuid == BeetBluetoothSupport.maintenanceInfoUuid) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    val pendingRead = pendingMaintenanceInfoRead
                    if (pendingRead != null) {
                        pendingRead.completeExceptionally(
                            IllegalStateException(strings.get(R.string.runtime_maintenance_info_invalid)),
                        )
                        pendingMaintenanceInfoRead = null
                        return@beetGattCallback
                    }
                    disconnectGatt(clearSelection = false, reason = "maintenance info read failed status=$status")
                    host.clearSession()
                    host.requestStartScan(detail = strings.get(R.string.runtime_maintenance_info_invalid))
                    return@beetGattCallback
                }
                handleMaintenanceInfo(gatt, characteristic.value ?: ByteArray(0))
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
            Log.d(TAG, "onCharacteristicChanged uuid=${characteristic.uuid} size=${characteristic.value?.size ?: 0}")
            val payload = characteristic.value ?: ByteArray(0)
            when (characteristic.uuid) {
                BeetBluetoothSupport.stateStreamUuid -> handleStatePayload(payload)
                BeetBluetoothSupport.commandResultUuid -> handleCommandPayload(payload)
                BeetBluetoothSupport.maintenanceStatusUuid -> handleMaintenanceStatusPayload(payload)
            }
        },
    )

    companion object {
        private const val TAG = "BeetGattSession"
        private const val COMMAND_TIMEOUT_MS = 7_000L
        private const val MAINTENANCE_CONTROL_TIMEOUT_MS = 10_000L
        private const val MAINTENANCE_CHUNK_TIMEOUT_MS = 10_000L
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val MAINTENANCE_RECONNECT_TIMEOUT_MS = 30_000L
        private const val MAINTENANCE_RECONNECT_DELAY_MS = 1_000L
        private const val MAINTENANCE_GATT_STABILITY_MS = 600L
        private const val MAINTENANCE_WAKELOCK_TIMEOUT_MS = 20L * 60L * 1000L
        private const val MAX_MAINTENANCE_RECONNECT_ATTEMPTS = 3
        private const val CONTROLLER_INFO_READ_RETRY_DELAY_MS = 400L
        private const val MAX_CONTROLLER_INFO_READ_ATTEMPTS = 4
        private const val DEFAULT_MTU = 23
        // MTU is FROZEN at 247. DO NOT INCREASE.
        // See firmware comment in beet_ble.c for rationale.
        private const val DESIRED_MTU = 247
        private const val MIN_MAINTENANCE_PAYLOAD_BYTES = 20
        private const val MAX_MAINTENANCE_PAYLOAD_BYTES = 236
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
        private const val EXPECTED_CONTROLLER_ACTION_TIMEOUT_MS = 30_000L
        private const val EXPECTED_REBOOT_RECONNECT_DELAY_MS = 1_000L
        private const val POST_CONNECT_EVENT_SYNC_DELAY_MS = 3_000L
    }

    private class MaintenanceAbortRequestedException : IllegalStateException("Maintenance update aborted")

    private fun pairBlockReasonLabel(code: String): String = when (code) {
        "NONE" -> strings.get(R.string.block_reason_code_none)
        "MOISTURE_RESPONSE_TEST_FAILED" -> strings.get(R.string.block_reason_code_moisture_response_test_failed)
        "SENSOR_READING_INVALID" -> strings.get(R.string.block_reason_code_sensor_reading_invalid)
        "LOW_BATTERY_ABORT" -> strings.get(R.string.block_reason_code_low_battery_abort)
        else -> strings.get(R.string.common_unknown_with_code, code)
    }

    private fun beetIsValidPairIndex(pairIndex: Int): Boolean = pairIndex in 1..8
}
