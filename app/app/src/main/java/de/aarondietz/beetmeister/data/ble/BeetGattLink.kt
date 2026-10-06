package de.aarondietz.beetmeister.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.data.repository.BeetRepositoryCallbacks
import de.aarondietz.beetmeister.logging.BeetLog
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.stream.BeetEventSyncState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

/**
 * Session-state hooks the GATT link needs from the coordinator during the
 * handshake and teardown sequences. Each method is a single decision point
 * that remained with the session state machine (expected controller actions,
 * payload parsing, job ownership).
 */
internal interface BeetGattLinkDelegate {
    /** Connecting detail line shown right before the physical connect attempt. */
    fun connectingDetail(deviceAddress: String): String

    /** Negotiation detail line shown when the physical link comes up. */
    fun negotiatingDetail(): String

    fun expectedActionActive(): Boolean

    fun expectedRebootPending(): Boolean

    fun clearExpectedControllerAction()

    fun resetSyncState()

    fun cancelEventSyncJob()

    fun clearPendingMoistureTests()

    fun handleExpectedControllerActionDisconnect()

    fun handleControllerInfo(payload: ByteArray)

    fun handleStatePayload(payload: ByteArray)

    fun handleCommandPayload(payload: ByteArray)
}

/**
 * Physical GATT session extracted from BeetGattSessionCoordinator (Phase 2a of the
 * coordinator split plan): owns the GATT callback, the handshake pipeline
 * (connect -> MTU -> service discovery -> descriptor queue -> controller/maintenance
 * info reads with retry budget), the MTU/PHY post-connect negotiation, connection
 * teardown timers, and the negotiated-MTU/gatt-reset session facts.
 *
 * Pure relocation: no protocol, timing, or state-machine changes. Descriptor order,
 * retry budgets, INITIAL/HIGH_SPEED MTU policy, and callback sequencing are identical.
 */
internal class BeetGattLink(
    private val host: BeetRepositoryCallbacks,
    private val delegate: BeetGattLinkDelegate,
    private val maintenanceUpdater: BeetMaintenanceUpdater,
) : BeetGattWriter {
    private val strings get() = host.strings
    private var connectionTimeoutJob: Job? = null
    private var controllerInfoRetryJob: Job? = null
    private var negotiatedMtu = DEFAULT_MTU

    /** Timestamp of the last GATT reset (stability window for maintenance reconnects). */
    var gattResetAtMs: Long = 0L
        private set

    /** Currently negotiated ATT MTU (drives maintenance payload budgets). */
    val mtu: Int get() = negotiatedMtu

    override fun ensureWritable() {
        host.session.currentGatt ?: error(strings.get(R.string.runtime_no_connected_controller))
        host.session.controlPointCharacteristic ?: error(strings.get(R.string.runtime_control_point_unavailable))
    }

    @Suppress("MissingPermission")
    override fun writeCommand(payload: String): Boolean {
        val gatt = host.session.currentGatt ?: error(strings.get(R.string.runtime_no_connected_controller))
        val controlPoint = host.session.controlPointCharacteristic ?: error(strings.get(R.string.runtime_control_point_unavailable))
        controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        controlPoint.value = payload.toByteArray(StandardCharsets.UTF_8)
        return gatt.writeCharacteristic(controlPoint)
    }

    fun cancelConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    fun openGatt(device: BluetoothDevice) {
        BeetLog.d(TAG) { "openGatt(address=${device.address}, bondState=${device.bondState})" }
        gattResetAtMs = System.currentTimeMillis()
        disconnectGatt(clearSelection = false, reason = "openGatt reset existing session")
        delegate.resetSyncState()
        host.updateConnection(
            BeetConnectionPhase.Connecting,
            delegate.connectingDetail(device.address),
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
                detail = if (delegate.expectedRebootPending()) {
                    delegate.clearExpectedControllerAction()
                    strings.get(R.string.runtime_reboot_reconnect_failed)
                } else {
                    strings.get(R.string.runtime_connection_timed_out)
                },
            )
        }
        @Suppress("MissingPermission")
        host.session.currentGatt = device.connectGatt(host.appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnectGatt(clearSelection: Boolean, reason: String) {
        BeetLog.d(TAG) { "disconnectGatt(reason=$reason, clearSelection=$clearSelection, currentAddress=${host.currentAddress}, phase=${host.state.value.connection.phase})" }
        gattResetAtMs = System.currentTimeMillis()
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        cancelControllerInfoRetry("disconnect gatt: $reason")
        delegate.cancelEventSyncJob()
        delegate.clearPendingMoistureTests()
        val gatt = host.session.currentGatt
        host.session.currentGatt = null
        delegate.resetSyncState()
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

    /**
     * Safely optimize link layer parameters now that connection, discovery, and initial sync are complete:
     * 1. Request 2M PHY if supported by client hardware.
     * 2. Request MTU up to 517 (NimBLE and Android clamp to negotiated max, safe fallback).
     * Never do this during maintenance or initial handshake.
     */
    fun negotiateHighSpeedLink() {
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
    }

    fun hasCompleteRuntimeService(): Boolean =
        host.session.controllerInfoCharacteristic != null &&
            host.session.stateStreamCharacteristic != null &&
            host.session.controlPointCharacteristic != null &&
            host.session.commandResultCharacteristic != null

    fun configureServices(gatt: BluetoothGatt): Boolean {
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

    fun readControllerInfo(gatt: BluetoothGatt): Boolean {
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

    fun cancelControllerInfoRetry(reason: String) {
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
                if (delegate.expectedActionActive() && !maintenanceActive) {
                    delegate.handleExpectedControllerActionDisconnect()
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
                        delegate.negotiatingDetail(),
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
                    if (delegate.expectedActionActive() && !maintenanceActive) {
                        delegate.handleExpectedControllerActionDisconnect()
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
            delegate.handleControllerInfo(characteristic.value ?: ByteArray(0))
        },
        onCharacteristicChanged = { gatt, characteristic ->
            if (!isCurrentGatt(gatt, "onCharacteristicChanged")) {
                return@beetGattCallback
            }
            BeetLog.d(TAG) { "onCharacteristicChanged uuid=${characteristic.uuid} size=${characteristic.value?.size ?: 0}" }
            val payload = characteristic.value ?: ByteArray(0)
            when (characteristic.uuid) {
                BeetBluetoothSupport.stateStreamUuid -> delegate.handleStatePayload(payload)
                BeetBluetoothSupport.commandResultUuid -> delegate.handleCommandPayload(payload)
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
        // Kept in sync with BeetMaintenanceUpdater.MAX_MAINTENANCE_RECONNECT_ATTEMPTS for reconnect detail strings.
        private const val MAX_MAINTENANCE_RECONNECT_ATTEMPTS = 3
        private const val TAG = "BeetGattSession"
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val CONTROLLER_INFO_READ_RETRY_DELAY_MS = 400L
        private const val MAX_CONTROLLER_INFO_READ_ATTEMPTS = 4
        private const val DEFAULT_MTU = 23
        // MTU requested during initial handshake and used for maintenance budgets.
        private const val INITIAL_MTU = 247
        // Runtime request after Connected. The effective MTU is always min(client, server);
        // firmware clamps to 247 because NimBLE ESP32-S3 uses 255-byte ACL buffers.
        // If firmware buffer sizing ever increases, this upgrades automatically.
        private const val HIGH_SPEED_MTU = 517
    }
}
