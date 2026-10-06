package de.aarondietz.beetmeister.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import de.aarondietz.beetmeister.BuildConfig
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.data.firmware.BeetFirmwareCatalog
import de.aarondietz.beetmeister.data.firmware.BeetFirmwareImagePackage
import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import de.aarondietz.beetmeister.data.repository.BeetRepositoryCallbacks
import de.aarondietz.beetmeister.logging.BeetLog
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo
import de.aarondietz.beetmeister.model.stream.BeetEventSyncState
import de.aarondietz.beetmeister.model.update.BeetFirmwarePackageSummary
import de.aarondietz.beetmeister.model.update.BeetMaintenanceStatus
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdatePhase
import de.aarondietz.beetmeister.model.update.isActiveMaintenancePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.max

/**
 * Narrow view back into the GATT session coordinator that the maintenance engine needs.
 * Extracted from BeetGattSessionCoordinator without behavior changes; everything else
 * (connection lifecycle, runtime commands, event sync) stays on the coordinator.
 */
internal interface BeetMaintenanceLink {
    /** Currently negotiated ATT MTU (drives maintenance payload budgets). */
    val mtu: Int
    /** Timestamp of the last GATT reset (stability window for maintenance reconnects). */
    val gattResetAtMs: Long
    fun hasCompleteRuntimeService(): Boolean
    fun readControllerInfo(gatt: BluetoothGatt): Boolean
    fun disconnectGatt(clearSelection: Boolean, reason: String)
    fun suspendRuntimeSync(reason: String)
    fun cancelConnectionTimeout()
}

/**
 * Maintenance/OTA engine extracted from BeetGattSessionCoordinator (cluster 1 of the
 * coordinator split plan). Owns all maintenance state: package selection, upload job,
 * control/chunk write defers, reconnect/backoff, wakelock, and progress/ETA tracking.
 *
 * Pure relocation: no protocol, timing, or state-machine changes. Maintenance protocol v1
 * is frozen; any behavior change here needs explicit approval.
 */
internal class BeetMaintenanceUpdater(
    private val host: BeetRepositoryCallbacks,
    private val link: BeetMaintenanceLink,
) {
    private val strings get() = host.strings

    internal class MaintenanceControlWriteException(message: String) : IllegalStateException(message)

    private class MaintenanceAbortRequestedException : IllegalStateException("Maintenance update aborted")

    private val maintenanceMutex = Mutex()
    private var maintenanceReconnectJob: Job? = null
    private var maintenanceUploadJob: Job? = null
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
    private val maintenanceWakeLock: PowerManager.WakeLock by lazy {
        val powerManager = host.appContext.getSystemService(PowerManager::class.java)
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeetMeister:MaintenanceUpdate")
    }

    // --- Views consumed by the coordinator (connection lifecycle / guards) ---

    val isUploadActive: Boolean get() = maintenanceUploadJob?.isActive == true
    val expectedRebootDisconnect: Boolean get() = maintenanceExpectedRebootDisconnect
    val reconnectAttempts: Int get() = maintenanceReconnectAttempts

    /** Repository close: cancel all maintenance jobs and release the wakelock. */
    fun shutdown() {
        maintenanceReconnectJob?.cancel()
        maintenanceReconnectJob = null
        maintenanceUploadJob?.cancel()
        maintenanceUploadJob = null
        releaseMaintenanceWakeLock()
    }

    /**
     * Session teardown hook (called from coordinator disconnectGatt). Cancels exactly the
     * jobs/defers the inline implementation cancelled before extraction - no more, no less.
     */
    fun onSessionTeardown() {
        maintenanceReconnectJob?.cancel()
        maintenanceReconnectJob = null
        pendingMaintenanceStatus?.cancel()
        pendingMaintenanceStatus = null
        pendingCharacteristicWrite?.cancel()
        pendingCharacteristicWrite = null
    }

    // --- Public maintenance API (delegated by the coordinator) ---

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
            BeetLog.i(TAG) {
                "startMaintenanceUpdate ignored because maintenance update is already active " +
                    "phase=$currentPhase jobActive=${maintenanceUploadJob?.isActive == true}"
            }
            return
        }
        val selectedPackage = selectedMaintenancePackage
        if (selectedPackage == null) {
            BeetLog.w(TAG) {
                "startMaintenanceUpdate ignored because selectedMaintenancePackage is null " +
                    "phase=${host.state.value.maintenanceUpdate.phase} " +
                    "selectedSummary=${host.state.value.maintenanceUpdate.selectedFirmware?.sourceLabel}"
            }
            return
        }
        val selectedSummary = host.state.value.maintenanceUpdate.selectedFirmware
        if (selectedSummary == null) {
            BeetLog.w(TAG) {
                "startMaintenanceUpdate ignored because selectedFirmware summary is null " +
                    "phase=${host.state.value.maintenanceUpdate.phase}"
            }
            return
        }
        if (isSelectedFirmwareInstalled(selectedSummary)) {
            BeetLog.i(TAG) {
                "startMaintenanceUpdate ignored because selected firmware is already installed " +
                    "firmware=${selectedSummary.metadata.firmwareVersion} " +
                    "build=${selectedSummary.metadata.buildLabel} kind=${selectedSummary.metadata.imageKind}"
            }
            selectedMaintenancePackage = null
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
                        selectedFirmware = null,
                    ),
                )
            }
            return
        }
        BeetLog.d(TAG) {
            "startMaintenanceUpdate package=${selectedSummary.sourceLabel} " +
                "firmware=${selectedSummary.metadata.firmwareVersion} " +
                "imageKind=${selectedSummary.metadata.imageKind} size=${selectedSummary.imageSize}"
        }
        resetMaintenanceProgressTracking()
        link.suspendRuntimeSync("start maintenance update")
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
        BeetLog.d(TAG, "startMaintenanceUpdate scopeActive=${scopeJob.isActive} scopeCancelled=${scopeJob.isCancelled}")
        val launchedJob = host.scope.launch {
            runMaintenanceUpdate(selectedPackage, selectedSummary)
        }
        maintenanceUploadJob = launchedJob
        BeetLog.d(TAG) {
            "startMaintenanceUpdate launched jobActive=${maintenanceUploadJob?.isActive} " +
                "jobCancelled=${maintenanceUploadJob?.isCancelled}"
        }
        launchedJob.invokeOnCompletion { error ->
            if (maintenanceUploadJob === launchedJob) {
                maintenanceUploadJob = null
            }
            BeetLog.d(TAG) { "maintenanceUploadJob completed cancelled=${launchedJob.isCancelled} error=$error" }
        }
    }

    fun abortMaintenanceUpdate() {
        if (maintenanceUploadJob?.isActive == true) {
            BeetLog.d(TAG, "abortMaintenanceUpdate requested while upload job is active")
            maintenanceAbortRequested = true
            return
        }
        resetMaintenanceUpdateAfterAbort()
    }

    // --- GATT callback entry points (routed by the coordinator) ---

    /** maintenance info read result from onCharacteristicRead. */
    fun onMaintenanceInfoRead(gatt: BluetoothGatt, status: Int, payload: ByteArray) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            val pendingRead = pendingMaintenanceInfoRead
            if (pendingRead != null) {
                pendingRead.completeExceptionally(
                    IllegalStateException(strings.get(R.string.runtime_maintenance_info_invalid)),
                )
                pendingMaintenanceInfoRead = null
                return
            }
            link.disconnectGatt(clearSelection = false, reason = "maintenance info read failed status=$status")
            host.clearSession()
            host.requestStartScan(detail = strings.get(R.string.runtime_maintenance_info_invalid))
            return
        }
        handleMaintenanceInfo(gatt, payload)
    }

    /** maintenance data/control write result from onCharacteristicWrite. */
    fun onCharacteristicWriteComplete(characteristicUuid: java.util.UUID, status: Int) {
        if (characteristicUuid == BeetBluetoothSupport.maintenanceDataUuid) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingCharacteristicWrite?.complete(Unit)
            } else {
                pendingCharacteristicWrite?.completeExceptionally(
                    IllegalStateException(strings.get(R.string.maintenance_chunk_write_failed, status)),
                )
            }
            return
        }
        if (characteristicUuid == BeetBluetoothSupport.maintenanceControlUuid) {
            BeetLog.d(TAG) { "onCharacteristicWrite maintenance control status=$status" }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingCharacteristicWrite?.complete(Unit)
            } else {
                pendingCharacteristicWrite?.completeExceptionally(
                    MaintenanceControlWriteException(strings.get(R.string.maintenance_control_write_failed, status)),
                )
            }
        }
    }

    /** maintenance status indication from onCharacteristicChanged. */
    fun onMaintenanceStatusPayload(payload: ByteArray) {
        handleMaintenanceStatusPayload(payload)
    }

    // --- Maintenance routing after service discovery (was inline in coordinator) ---

    private fun handleMaintenanceInfo(gatt: BluetoothGatt, payload: ByteArray) {
        val info = try {
            BeetJsonCodec.parseMaintenanceInfo(payload.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            pendingMaintenanceInfoRead?.completeExceptionally(error)
            pendingMaintenanceInfoRead = null
            BeetLog.e(TAG, "Maintenance info payload parse failed", error)
            link.disconnectGatt(clearSelection = false, reason = "invalid maintenance info payload")
            host.clearSession()
            host.updateConnection(BeetConnectionPhase.Error, strings.get(R.string.runtime_maintenance_info_invalid))
            return
        }
        BeetLog.d(TAG) {
            "handleMaintenanceInfo(product=${info.productId}, hardware=${info.hardwareRev}, runtimeProtocol=${info.runtimeProtocolVersion}, maintenanceProtocol=${info.maintenanceProtocolVersion}, imageKind=${info.imageKind})"
        }
        host.updateState { it.copy(maintenanceInfo = info) }
        pendingMaintenanceInfoRead?.complete(info)
        pendingMaintenanceInfoRead = null
        when (
            determineMaintenanceRoute(
                info = info,
                runtimeServiceAvailable = link.hasCompleteRuntimeService(),
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
            BeetLog.d(TAG) {
                "resolveRuntimeOrActiveMaintenanceRoute deferring to active maintenance job " +
                    "phase=${host.state.value.maintenanceUpdate.phase} detail=${host.state.value.maintenanceUpdate.statusDetail}"
            }
            host.updateConnection(
                BeetConnectionPhase.MaintenanceRequired,
                host.state.value.maintenanceUpdate.statusDetail ?: activeMaintenanceDetail(),
            )
            return
        }
        if (maintenanceControl == null || maintenanceStatus == null) {
            if (!link.readControllerInfo(gatt)) {
                link.disconnectGatt(clearSelection = false, reason = "runtime service missing after maintenance route runtime")
                host.clearSession()
                host.requestStartScan(detail = strings.get(R.string.runtime_gatt_service_incomplete))
            }
            return
        }
        initialMaintenanceStatusJob?.cancel()
        initialMaintenanceStatusJob = host.scope.launch {
            try {
                val status = sendMaintenanceControl(BeetJsonCodec.maintenanceQueryStatus())
                BeetLog.d(TAG) {
                    "resolveRuntimeOrActiveMaintenanceRoute initial maintenance state=${status.state} " +
                        "sessionId=${status.sessionId} nextOffset=${status.nextOffset}"
                }
                if (host.session.currentGatt !== gatt) {
                    return@launch
                }
                if (status.state in setOf("awaiting_data", "transferring", "rebooting")) {
                    link.cancelConnectionTimeout()
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
                if (!link.readControllerInfo(gatt)) {
                    link.disconnectGatt(clearSelection = false, reason = "runtime service missing after maintenance status query")
                    host.clearSession()
                    host.requestStartScan(detail = strings.get(R.string.runtime_gatt_service_incomplete))
                }
            } catch (error: Exception) {
                BeetLog.w(TAG, "resolveRuntimeOrActiveMaintenanceRoute falling back to runtime sync", error)
                if (host.session.currentGatt === gatt && !link.readControllerInfo(gatt)) {
                    link.disconnectGatt(clearSelection = false, reason = "runtime service missing after maintenance status fallback")
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

    private fun maintenanceRequiredMessage(info: BeetMaintenanceInfo): String =
        if (link.hasCompleteRuntimeService()) {
            strings.get(
                R.string.runtime_maintenance_update_required,
                info.runtimeProtocolVersion,
                BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
            )
        } else {
            strings.get(R.string.runtime_maintenance_runtime_unavailable)
        }

    // --- Maintenance control/write primitives ---

    private suspend fun sendMaintenanceControl(payload: String): BeetMaintenanceStatus {
        return maintenanceMutex.withLock {
            val gatt = host.session.currentGatt ?: error(strings.get(R.string.runtime_no_connected_controller))
            val controlPoint = host.session.maintenanceControlCharacteristic
                ?: error(strings.get(R.string.maintenance_control_unavailable))
            BeetLog.d(TAG) { "sendMaintenanceControl(payload=$payload)" }
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
                        BeetLog.d(TAG) {
                            "sendMaintenanceControl result state=${status.state} " +
                                "sessionId=${status.sessionId} nextOffset=${status.nextOffset} " +
                                "failure=${status.failureReason}"
                        }
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

    // --- Upload state machine ---

    private suspend fun runMaintenanceUpdate(
        selectedPackage: BeetFirmwareImagePackage,
        selectedFirmware: BeetFirmwarePackageSummary,
    ) {
        BeetLog.d(TAG) {
            "runMaintenanceUpdate start firmware=${selectedFirmware.metadata.firmwareVersion} " +
                "size=${selectedPackage.imageSize}"
        }
        acquireMaintenanceWakeLock()
        maintenanceReconnectAttempts = 0
        maintenanceExpectedRebootDisconnect = false
        maintenanceAbortRequested = false
        try {
            while (true) {
                try {
                    throwIfMaintenanceAbortRequested()
                    BeetLog.d(TAG, "runMaintenanceUpdate waiting for maintenance connection")
                    waitForMaintenanceConnection()
                    BeetLog.d(TAG, "runMaintenanceUpdate maintenance connection ready")
                    val fullImageAlreadyTransferred =
                        host.state.value.maintenanceUpdate.bytesTransferred >= selectedPackage.imageSize
                    val status = startOrResumeMaintenanceSession(
                        selectedPackage = selectedPackage,
                        selectedFirmware = selectedFirmware,
                        fullImageAlreadyTransferred = fullImageAlreadyTransferred,
                    )
                    BeetLog.d(TAG) {
                        "runMaintenanceUpdate terminal status state=${status.state} " +
                            "sessionId=${status.sessionId} nextOffset=${status.nextOffset} " +
                            "failure=${status.failureReason}"
                    }
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
                            reconnectAfterReboot(reconnectDevice)
                        }

                        "completed" -> {
                            maintenanceExpectedRebootDisconnect = false
                            selectedMaintenancePackage = null
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
                                        selectedFirmware = null,
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
                    BeetLog.w(TAG, "runMaintenanceUpdate caught error reconnectAttempts=$maintenanceReconnectAttempts", error)
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
                    delay(MAINTENANCE_RECONNECT_DELAY_MS)
                }
            }
        } catch (abort: MaintenanceAbortRequestedException) {
            performMaintenanceAbort()
        } catch (error: Exception) {
            BeetLog.e(TAG, "runMaintenanceUpdate failed", error)
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

    private suspend fun startOrResumeMaintenanceSession(
        selectedPackage: BeetFirmwareImagePackage,
        selectedFirmware: BeetFirmwarePackageSummary,
        fullImageAlreadyTransferred: Boolean,
    ): BeetMaintenanceStatus {
        BeetLog.d(TAG, "startOrResumeMaintenanceSession query_status")
        val initialStatus = sendMaintenanceControl(BeetJsonCodec.maintenanceQueryStatus())
        BeetLog.d(TAG) {
            "startOrResumeMaintenanceSession initial state=${initialStatus.state} " +
                "sessionId=${initialStatus.sessionId} nextOffset=${initialStatus.nextOffset}"
        }
        if (fullImageAlreadyTransferred && initialStatus.state == "idle") {
            val refreshedMaintenanceInfo = readFreshMaintenanceInfo()
            if (isSelectedFirmwareInstalled(selectedFirmware, refreshedMaintenanceInfo)) {
                BeetLog.i(
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
            BeetLog.w(TAG) {
                "startOrResumeMaintenanceSession idle after full transfer but target firmware is not installed " +
                    "current=${refreshedMaintenanceInfo.firmwareVersion}/${refreshedMaintenanceInfo.buildLabel}/${refreshedMaintenanceInfo.imageKind} " +
                    "target=${selectedFirmware.metadata.firmwareVersion}/${selectedFirmware.metadata.buildLabel}/${selectedFirmware.metadata.imageKind}"
            }
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
                BeetLog.d(TAG) {
                    "startOrResumeMaintenanceSession begin_update compact=${beginUpdatePayload.compact} " +
                        "size=${beginUpdatePayload.sizeBytes} budget=$payloadBudget"
                }
                sendMaintenanceControl(beginUpdatePayload.json)
            }
            else -> error(strings.get(R.string.maintenance_unexpected_status, initialStatus.state))
        }
        BeetLog.d(TAG) {
            "startOrResumeMaintenanceSession upload state=${uploadStatus.state} " +
                "sessionId=${uploadStatus.sessionId} nextOffset=${uploadStatus.nextOffset} " +
                "failure=${uploadStatus.failureReason}"
        }
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

    private fun maintenanceControlPayloadBudget(): Int = (link.mtu - 3).coerceAtLeast(20)

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
            withTimeout(FRESH_MAINTENANCE_INFO_TIMEOUT_MS) { deferred.await() }
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
        BeetLog.d(TAG) {
            "uploadMaintenanceData(sessionId=$sessionId startOffset=$offset total=${selectedPackage.imageSize})"
        }
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
            val payloadLimit = (link.mtu - 11).coerceIn(MIN_MAINTENANCE_PAYLOAD_BYTES, MAX_MAINTENANCE_PAYLOAD_BYTES)
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
                BeetLog.d(TAG) { "uploadMaintenanceData progressed offset=$offset total=${selectedPackage.imageSize}" }
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
        BeetLog.d(TAG, "performMaintenanceAbort()")
        runCatching {
            if (host.session.currentGatt != null && host.session.maintenanceControlCharacteristic != null) {
                sendMaintenanceControl(BeetJsonCodec.maintenanceAbortUpdate())
            }
        }.onFailure { error ->
            BeetLog.w(TAG, "performMaintenanceAbort failed to send abort_update, disconnecting GATT", error)
            link.disconnectGatt(clearSelection = false, reason = "maintenance abort fallback disconnect")
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
            BeetLog.d(TAG, "resumeRuntimeSyncAfterCompletedMaintenance skipped because GATT is null")
            return
        }
        if (host.session.controllerInfoCharacteristic == null) {
            BeetLog.d(TAG, "resumeRuntimeSyncAfterCompletedMaintenance skipped because controller info characteristic is unavailable")
            return
        }
        BeetLog.d(TAG, "resumeRuntimeSyncAfterCompletedMaintenance reading controller info")
        if (!link.readControllerInfo(gatt)) {
            link.disconnectGatt(clearSelection = false, reason = "post-maintenance runtime sync could not read controller info")
            host.clearSession()
            host.requestStartScan(detail = strings.get(R.string.runtime_gatt_service_incomplete))
        }
    }

    // --- Reconnect / wait helpers ---

    fun resolveReconnectDevice(
        device: BluetoothDevice? = host.session.currentGatt?.device,
    ): BluetoothDevice? {
        device?.let { return it }
        val address = host.currentAddress ?: return null
        val adapter = host.bluetoothAdapter ?: return null
        return runCatching { adapter.getRemoteDevice(address) }
            .onFailure { error ->
                BeetLog.w(TAG, "resolveMaintenanceReconnectDevice failed for address=$address", error)
            }
            .getOrNull()
    }

    private fun scheduleMaintenanceReconnect(device: BluetoothDevice? = host.session.currentGatt?.device) {
        val reconnectDevice = resolveReconnectDevice(device)
        if (reconnectDevice == null) {
            BeetLog.w(TAG, "scheduleMaintenanceReconnect skipped: no reconnect target")
            return
        }
        maintenanceReconnectJob?.cancel()
        maintenanceReconnectJob = host.scope.launch {
            val isRebooting = host.state.value.maintenanceUpdate.phase == BeetMaintenanceUpdatePhase.Rebooting
            val maxAttempts = if (isRebooting) MAX_REBOOT_RECONNECT_ATTEMPTS else 1
            var attempt = 0
            var delayMs = MAINTENANCE_RECONNECT_DELAY_MS
            while (attempt < maxAttempts) {
                delay(delayMs)
                if (!isActive) return@launch
                val phase = host.state.value.maintenanceUpdate.phase
                if (phase != BeetMaintenanceUpdatePhase.Reconnecting &&
                    phase != BeetMaintenanceUpdatePhase.Rebooting
                ) {
                    return@launch
                }
                attempt++
                if (isRebooting && attempt > 1) {
                    host.updateState { state ->
                        state.copy(
                            maintenanceUpdate = state.maintenanceUpdate.copy(
                                retryCount = attempt,
                                statusDetail = strings.get(
                                    R.string.maintenance_rebooting_reconnect,
                                    attempt,
                                    maxAttempts,
                                ),
                            ),
                        )
                    }
                }
                BeetLog.d(TAG, "scheduleMaintenanceReconnect opening address=${reconnectDevice.address} attempt=$attempt")
                host.requestOpenGatt(reconnectDevice)
                delayMs = (delayMs * 2).coerceAtMost(16_000L)
            }
            if (attempt >= maxAttempts && isRebooting) {
                BeetLog.e(TAG, "scheduleMaintenanceReconnect exhausted $maxAttempts attempts")
                host.updateState { state ->
                    state.copy(
                        maintenanceUpdate = state.maintenanceUpdate.copy(
                            phase = BeetMaintenanceUpdatePhase.Failed,
                            errorDetail = strings.get(R.string.maintenance_reconnect_timeout),
                        ),
                    )
                }
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

    private suspend fun reconnectAfterReboot(device: BluetoothDevice) {
        var attempt = 0
        var delayMs = 1000L
        while (attempt < MAX_REBOOT_RECONNECT_ATTEMPTS) {
            throwIfMaintenanceAbortRequested()
            delay(delayMs)
            attempt++
            host.updateState { state ->
                state.copy(
                    maintenanceUpdate = state.maintenanceUpdate.copy(
                        retryCount = attempt,
                        statusDetail = strings.get(
                            R.string.maintenance_rebooting_reconnect,
                            attempt,
                            MAX_REBOOT_RECONNECT_ATTEMPTS,
                        ),
                    ),
                )
            }
            host.requestOpenGatt(device)
            val settleDeadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < settleDeadline) {
                throwIfMaintenanceAbortRequested()
                val phase = host.state.value.connection.phase
                if (phase == BeetConnectionPhase.MaintenanceRequired || phase == BeetConnectionPhase.Connected) {
                    BeetLog.d(TAG, "reconnectAfterReboot success attempt=$attempt delay=${delayMs}ms phase=$phase")
                    return
                }
                if (phase != BeetConnectionPhase.Connecting &&
                    phase != BeetConnectionPhase.Bonding &&
                    phase != BeetConnectionPhase.DiscoveringServices
                ) {
                    break
                }
                delay(400L)
            }
            BeetLog.d(TAG, "reconnectAfterReboot attempt $attempt failed, backoff ${delayMs}ms")
            delayMs = (delayMs * 2).coerceAtMost(16_000L)
        }
        error(strings.get(R.string.maintenance_reconnect_timeout))
    }

    private suspend fun waitForMaintenanceConnection() {
        val deadline = System.currentTimeMillis() + MAINTENANCE_RECONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val phase = host.state.value.connection.phase
            val stableFor = System.currentTimeMillis() - link.gattResetAtMs
            if ((phase == BeetConnectionPhase.MaintenanceRequired || phase == BeetConnectionPhase.Connected) &&
                host.session.currentGatt != null &&
                host.session.maintenanceControlCharacteristic != null &&
                host.session.maintenanceDataCharacteristic != null &&
                stableFor >= MAINTENANCE_GATT_STABILITY_MS
            ) {
                link.cancelConnectionTimeout()
                BeetLog.d(TAG, "waitForMaintenanceConnection satisfied phase=$phase mtu=${link.mtu} stableFor=${stableFor}ms")
                return
            }
            delay(200L)
        }
        error(strings.get(R.string.maintenance_reconnect_timeout))
    }

    fun isConnectionHealthy(): Boolean {
        val phase = host.state.value.connection.phase
        return (phase == BeetConnectionPhase.MaintenanceRequired || phase == BeetConnectionPhase.Connected) &&
            host.session.currentGatt != null &&
            host.session.maintenanceControlCharacteristic != null &&
            host.session.maintenanceDataCharacteristic != null
    }

    // --- Wake lock ---

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

    // --- Progress / ETA tracking ---

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
        return ceil(remainingBytes / rate).toInt()
    }

    private fun noteMaintenanceProgress(bytesTransferred: Int, totalBytes: Int) {
        val now = SystemClock.elapsedRealtime()
        ensureMaintenanceProgressTracking(bytesTransferred)
        val lastAt = maintenanceLastProgressAtMs ?: now
        val lastBytes = maintenanceLastProgressBytes
        val elapsedMs = now - lastAt
        val deltaBytes = bytesTransferred - lastBytes
        if (bytesTransferred < lastBytes) {
            maintenanceLastProgressAtMs = now
            maintenanceLastProgressBytes = bytesTransferred
        } else if (elapsedMs >= 1000L && deltaBytes > 0) {
            if (elapsedMs > MAINTENANCE_PROGRESS_GAP_RESET_MS) {
                // Long gap (reconnect / BLE hiccup) - reset the baseline
                // instead of computing a contaminated instantaneous rate.
                maintenanceLastProgressAtMs = now
                maintenanceLastProgressBytes = bytesTransferred
            } else {
                val instantaneousRate = deltaBytes.toDouble() / (elapsedMs.toDouble() / 1000.0)
                maintenanceSmoothedBytesPerSecond = maintenanceSmoothedBytesPerSecond?.let { existing ->
                    (existing * 0.7) + (instantaneousRate * 0.3)
                } ?: instantaneousRate
                maintenanceLastProgressAtMs = now
                maintenanceLastProgressBytes = bytesTransferred
            }
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

    private fun handleMaintenanceStatusPayload(payload: ByteArray) {
        val status = try {
            BeetJsonCodec.parseMaintenanceStatus(payload.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            BeetLog.e(TAG, "Maintenance status payload parse failed", error)
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

    private fun writeLeU32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    companion object {
        // Same tag as the coordinator so log filtering is unchanged by the extraction.
        private const val TAG = "BeetGattSession"
        private const val MAINTENANCE_CONTROL_TIMEOUT_MS = 5_000L
        private const val MAINTENANCE_CHUNK_TIMEOUT_MS = 10_000L
        private const val MAINTENANCE_RECONNECT_TIMEOUT_MS = 30_000L
        private const val MAINTENANCE_RECONNECT_DELAY_MS = 1_000L
        private const val MAINTENANCE_GATT_STABILITY_MS = 600L
        private const val MAINTENANCE_WAKELOCK_TIMEOUT_MS = 20L * 60L * 1000L
        // Reset speed baseline when progress gap exceeds this
        // (e.g. after a mid-OTA disconnect + reconnect).
        private const val MAINTENANCE_PROGRESS_GAP_RESET_MS = 10_000L
        private const val MAX_MAINTENANCE_RECONNECT_ATTEMPTS = 3
        private const val MAX_REBOOT_RECONNECT_ATTEMPTS = 8
        private const val MIN_MAINTENANCE_PAYLOAD_BYTES = 20
        private const val MAX_MAINTENANCE_PAYLOAD_BYTES = 236
        // Same value as the coordinator's COMMAND_TIMEOUT_MS (read-fresh-info wait).
        private const val FRESH_MAINTENANCE_INFO_TIMEOUT_MS = 7_000L
    }
}
