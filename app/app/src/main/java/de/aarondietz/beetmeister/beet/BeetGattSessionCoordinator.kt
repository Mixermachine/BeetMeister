package de.aarondietz.beetmeister.beet

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets

internal class BeetGattSessionCoordinator(
    private val host: BeetRepositoryCallbacks,
) {
    private val commandMutex = Mutex()
    private var connectionTimeoutJob: Job? = null
    private var controllerInfoRetryJob: Job? = null

    fun close() {
        Log.d(TAG, "close()")
        disconnectGatt(clearSelection = false, reason = "repository close")
        cancelControllerInfoRetry("repository close")
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
            for (pairIndex in 1..8) {
                runCatching { sendCommand(BeetJsonCodec.getCalibration(pairIndex)) }
                    .onFailure { host.setCommandMessage("Calibration refresh failed for pair $pairIndex.") }
            }
        }
    }

    fun refreshHistorySummary() {
        host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            runCatching { sendCommand(BeetJsonCodec.getHistorySummary()) }
                .onFailure { host.setCommandMessage("History summary could not be loaded.") }
        }
    }

    fun loadRecentEvents(limit: Int = 50) {
        host.scope.launch {
            val summary = host.state.value.historySummary ?: run {
                refreshHistorySummary()
                delay(250)
                host.state.value.historySummary
            } ?: return@launch

            host.updateState { it.copy(eventsLoading = true, recentEvents = emptyList()) }
            val loadedEvents = mutableListOf<BeetWateringEvent>()
            var sequence = summary.latestSequenceNumber
            while (sequence > 0 && loadedEvents.size < limit) {
                val result = runCatching { sendCommand(BeetJsonCodec.getEvent(sequence)) }.getOrNull()
                val event = result?.event
                if (event != null) {
                    loadedEvents += event
                }
                sequence--
            }
            host.updateState { it.copy(eventsLoading = false, recentEvents = loadedEvents) }
        }
    }

    fun manualStart(pairIndex: Int, durationSeconds: Int?) {
        host.scope.launch {
            if (durationSeconds != null && durationSeconds !in 1..MAX_MANUAL_DURATION_SECONDS) {
                host.setCommandMessage("Manual duration must be between 1 second and 20 minutes.")
                return@launch
            }
            sendUserCommand(BeetJsonCodec.manualStart(pairIndex, durationSeconds))
        }
    }

    fun manualStop(pairIndex: Int) {
        host.scope.launch { sendUserCommand(BeetJsonCodec.manualStop(pairIndex)) }
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
                host.setCommandMessage("Dry calibration must be greater than wet calibration.")
                return@launch
            }
            sendUserCommand(BeetJsonCodec.storeCalibration(pairIndex, dryMillivolts, wetMillivolts))
            refreshCalibrations()
        }
    }

    fun openGatt(device: BluetoothDevice) {
        Log.d(TAG, "openGatt(address=${device.address}, bondState=${device.bondState})")
        disconnectGatt(clearSelection = false, reason = "openGatt reset existing session")
        resetSyncState()
        host.updateConnection(BeetConnectionPhase.Connecting, "Connecting to ${device.address}.")
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = host.scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                Log.w(TAG, "Connection timeout fired while phase=${host.state.value.connection.phase}")
                disconnectGatt(clearSelection = false, reason = "connection timeout")
                host.clearSession()
                host.requestStartScan(detail = "Connection timed out. Searching again.")
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
        runCatching { sendCommand(payload) }
            .onSuccess { result -> host.setCommandMessage(messageForResult(result)) }
            .onFailure { error -> host.setCommandMessage(error.message ?: "Command failed.") }
    }

    private suspend fun sendCommand(payload: String): BeetCommandResult {
        return commandMutex.withLock {
            val gatt = host.session.currentGatt ?: error("No connected controller.")
            val controlPoint = host.session.controlPointCharacteristic ?: error("Control point is unavailable.")
            val deferred = CompletableDeferred<BeetCommandResult>()
            host.session.pendingCommand = deferred

            controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            controlPoint.value = payload.toByteArray(StandardCharsets.UTF_8)
            @Suppress("MissingPermission")
            val writeStarted = gatt.writeCharacteristic(controlPoint)
            if (!writeStarted) {
                host.session.pendingCommand = null
                error("Could not send command over BLE.")
            }

            try {
                withTimeout(COMMAND_TIMEOUT_MS) { deferred.await() }
            } finally {
                host.session.pendingCommand = null
            }
        }
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
            host.updateConnection(BeetConnectionPhase.Syncing, "Reading controller info and waiting for live state.")
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
        host.updateConnection(BeetConnectionPhase.Connected, "Connected to controller.")
        refreshCalibrations()
        refreshHistorySummary()
    }

    private fun handleControllerInfo(payload: ByteArray) {
        val info = try {
            BeetJsonCodec.parseControllerInfo(payload.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            Log.e(TAG, "Controller info payload parse failed", error)
            disconnectGatt(clearSelection = false, reason = "invalid controller info payload")
            host.clearSession()
            host.updateConnection(BeetConnectionPhase.Error, "Controller info payload is invalid.")
            return
        }
        if (info.protocolVersion != EXPECTED_PROTOCOL_VERSION) {
            Log.e(TAG, "Unsupported protocol version ${info.protocolVersion}, expected=${EXPECTED_PROTOCOL_VERSION}")
            disconnectGatt(clearSelection = false, reason = "unsupported protocol version ${info.protocolVersion}")
            host.clearSession()
            host.updateConnection(
                BeetConnectionPhase.Error,
                "Unsupported controller protocol ${info.protocolVersion}. Expected ${EXPECTED_PROTOCOL_VERSION}.",
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
                Log.d(TAG, "handleStatePayload(deviceFrame battery=${deviceState.batteryMillivolts} activePumps=${deviceState.activePumps})")
                host.updateState { state -> state.copy(deviceState = deviceState) }
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
                completeInitialSyncIfReady()
            }
            null -> {
                Log.w(TAG, "Ignoring unknown state payload: $json")
            }
        }
    }

    private fun handleCommandPayload(payload: ByteArray) {
        val result = try {
            BeetJsonCodec.parseCommandResult(payload.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            Log.e(TAG, "Command payload parse failed", error)
            return
        }
        Log.d(TAG, "handleCommandPayload(cmd=${result.command} status=${result.status} reason=${result.reason} pair=${result.pairIndex})")
        result.calibration?.let { calibration ->
            host.updateState { state -> state.copy(calibrations = state.calibrations + (calibration.pairIndex to calibration)) }
        }
        result.historySummary?.let { summary ->
            host.updateState { it.copy(historySummary = summary) }
        }
        result.event?.let { event ->
            host.updateState { state ->
                if (state.recentEvents.any { it.sequenceNumber == event.sequenceNumber }) state
                else state.copy(recentEvents = state.recentEvents + event)
            }
        }
        host.session.pendingCommand?.complete(result)
    }

    private fun resetSyncState() {
        Log.d(TAG, "resetSyncState()")
        cancelControllerInfoRetry("reset sync state")
        host.resetSyncState()
    }

    private fun disconnectGatt(clearSelection: Boolean, reason: String) {
        Log.d(TAG, "disconnectGatt(reason=$reason, clearSelection=$clearSelection, currentAddress=${host.currentAddress}, phase=${host.state.value.connection.phase})")
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        cancelControllerInfoRetry("disconnect gatt: $reason")
        val gatt = host.session.currentGatt
        host.session.currentGatt = null
        resetSyncState()
        host.session.pendingCommand?.cancel()
        host.session.pendingCommand = null
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

    private fun messageForResult(result: BeetCommandResult): String = commandMessageForResult(result)

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
                        host.requestStartScan(detail = "BLE connection error $status. Searching again.")
                    }
                return@beetGattCallback
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    host.updateConnection(BeetConnectionPhase.DiscoveringServices, "Negotiating BLE session.")
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
                        host.requestStartScan(detail = "Controller disconnected. Searching again.")
                    } else {
                        host.updateConnection(BeetConnectionPhase.Disconnected, "Disconnected from controller.")
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
                host.requestStartScan(detail = "BeetMeister GATT service is incomplete. Searching again.")
            }
        },
        onDescriptorWrite = { gatt, descriptor, status ->
            Log.d(TAG, "onDescriptorWrite uuid=${descriptor.characteristic.uuid} status=$status queueRemaining=${host.session.descriptorQueue.size}")
            if (status != BluetoothGatt.GATT_SUCCESS || !writeNextDescriptor(gatt)) {
                disconnectGatt(clearSelection = false, reason = "descriptor write failed status=$status uuid=${descriptor.characteristic.uuid}")
                host.clearSession()
                host.requestStartScan(detail = "Failed to subscribe to controller updates. Searching again.")
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
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val CONTROLLER_INFO_READ_RETRY_DELAY_MS = 400L
        private const val MAX_CONTROLLER_INFO_READ_ATTEMPTS = 4
        private const val DESIRED_MTU = 247
        private const val EXPECTED_PROTOCOL_VERSION = 2
        private const val MAX_MANUAL_DURATION_SECONDS = 1200
    }
}
