package de.aarondietz.beetmeister.beet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets

class BeetRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val expectedProtocolVersion = EXPECTED_PROTOCOL_VERSION
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val commandMutex = Mutex()
    private val _state = MutableStateFlow(BeetRepositoryState())
    private val discoveredDevices = BeetDiscoveredDeviceStore()
    private val session = BeetConnectionSession()

    private var receiverRegistered = false
    private var scanCallback: ScanCallback? = null
    private var connectionTimeoutJob: Job? = null
    private var controllerInfoRetryJob: Job? = null
    private var staleBondRecoveryJob: Job? = null
    private var bondMonitorJob: Job? = null
    private var manualDisconnectRequested = false
    private var pendingBondAddress: String? = null
    private var currentAddress: String? = null

    val state: StateFlow<BeetRepositoryState> = _state.asStateFlow()

    fun start() {
        Log.d(TAG, "start()")
        registerReceiverIfNeeded()
        refreshEnvironment()
        val savedAddress = prefs.getString(KEY_LAST_ADDRESS, null)
        if (savedAddress.isNullOrBlank()) {
            startScan(clearResults = true)
        } else {
            connect(savedAddress)
        }
    }

    fun close() {
        Log.d(TAG, "close()")
        stopScan()
        disconnectGatt(clearSelection = false, reason = "repository close")
        if (receiverRegistered) {
            appContext.unregisterReceiver(systemReceiver)
            receiverRegistered = false
        }
        scope.coroutineContext[Job]?.cancel()
    }

    fun refreshEnvironment() {
        when {
            !BeetBluetoothSupport.hasRequiredPermissions(appContext) -> {
                updateConnection(BeetConnectionPhase.PermissionsRequired, "Bluetooth permissions are required.")
            }
            bluetoothAdapter == null -> {
                updateConnection(BeetConnectionPhase.Error, "This device does not support Bluetooth LE.")
            }
            !bluetoothAdapter.isEnabled -> {
                updateConnection(BeetConnectionPhase.BluetoothDisabled, "Bluetooth is turned off.")
            }
            _state.value.connection.phase == BeetConnectionPhase.PermissionsRequired ||
                _state.value.connection.phase == BeetConnectionPhase.BluetoothDisabled -> {
                updateConnection(BeetConnectionPhase.Idle, "Ready to scan.")
            }
        }
    }

    fun startScan(
        detail: String = "Searching for nearby BeetMeister controllers.",
        clearResults: Boolean = false,
    ) {
        Log.d(
            TAG,
            "startScan(clearResults=$clearResults, detail=$detail, callbackActive=${scanCallback != null}, phase=${_state.value.connection.phase})",
        )
        if (!BeetBluetoothSupport.hasRequiredPermissions(appContext)) {
            updateConnection(BeetConnectionPhase.PermissionsRequired, "Bluetooth permissions are required.")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateConnection(BeetConnectionPhase.BluetoothDisabled, "Bluetooth is turned off.")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            updateConnection(BeetConnectionPhase.Error, "Bluetooth LE scanner is unavailable.")
            return
        }
        if (scanCallback != null) {
            updateConnection(BeetConnectionPhase.Scanning, detail)
            return
        }

        if (clearResults) {
            discoveredDevices.clear()
        } else {
            discoveredDevices.pruneStaleDevices(STALE_DEVICE_TIMEOUT_MS)
        }
        publishDiscoveredDevices()
        updateConnection(BeetConnectionPhase.Scanning, detail)

        val callback = beetScanCallback(
            onScanResult = ::addScanResult,
            onScanFailed = { errorCode ->
                scanCallback = null
                updateConnection(BeetConnectionPhase.Error, "Scan failed with code $errorCode.")
            },
        )
        scanCallback = callback

        @Suppress("MissingPermission")
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BeetBluetoothSupport.serviceUuid)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
    }

    fun stopScan() {
        val callback = scanCallback ?: return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        Log.d(TAG, "stopScan()")
        @Suppress("MissingPermission")
        scanner.stopScan(callback)
        scanCallback = null
    }

    fun connect(address: String) {
        Log.d(TAG, "connect(address=$address)")
        if (!BeetBluetoothSupport.hasRequiredPermissions(appContext)) {
            updateConnection(BeetConnectionPhase.PermissionsRequired, "Bluetooth permissions are required.")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateConnection(BeetConnectionPhase.BluetoothDisabled, "Bluetooth is turned off.")
            return
        }

        scope.launch {
            stopScan()
            manualDisconnectRequested = false
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                updateConnection(BeetConnectionPhase.Error, "Invalid controller address.")
                return@launch
            }
            currentAddress = address
            _state.update { it.copy(selectedAddress = address, lastCommandMessage = null) }
            Log.d(TAG, "Resolved device name=${device.name} bondState=${device.bondState}")

            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                pendingBondAddress = address
                updateConnection(BeetConnectionPhase.Bonding, "Opening Android pairing dialog.")
                @Suppress("MissingPermission")
                if (!device.createBond()) {
                    updateConnection(BeetConnectionPhase.Error, "Failed to start BLE bonding.")
                } else {
                    monitorBondState(device)
                }
                return@launch
            }

            openGatt(device)
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        manualDisconnectRequested = true
        prefs.edit().remove(KEY_LAST_ADDRESS).apply()
        stopScan()
        disconnectGatt(clearSelection = true, reason = "manual disconnect")
        clearSession()
        updateConnection(BeetConnectionPhase.Disconnected, "Disconnected from controller.")
    }

    fun clearCommandMessage() {
        _state.update { it.copy(lastCommandMessage = null) }
    }

    fun refreshCalibrations() {
        scope.launch {
            if (_state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            for (pairIndex in 1..8) {
                runCatching { sendCommand(BeetJsonCodec.getCalibration(pairIndex)) }
                    .onFailure { setCommandMessage("Calibration refresh failed for pair $pairIndex.") }
            }
        }
    }

    fun refreshHistorySummary() {
        scope.launch {
            if (_state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            runCatching { sendCommand(BeetJsonCodec.getHistorySummary()) }
                .onFailure { setCommandMessage("History summary could not be loaded.") }
        }
    }

    fun loadRecentEvents(limit: Int = 50) {
        scope.launch {
            val summary = _state.value.historySummary ?: run {
                refreshHistorySummary()
                delay(250)
                _state.value.historySummary
            } ?: return@launch

            _state.update { it.copy(eventsLoading = true, recentEvents = emptyList()) }
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
            _state.update { it.copy(eventsLoading = false, recentEvents = loadedEvents) }
        }
    }

    fun manualStart(pairIndex: Int, durationSeconds: Int?) {
        scope.launch {
            if (durationSeconds != null && durationSeconds !in 1..900) {
                setCommandMessage("Manual duration must be between 1 and 900 seconds.")
                return@launch
            }
            sendUserCommand(BeetJsonCodec.manualStart(pairIndex, durationSeconds))
        }
    }

    fun manualStop(pairIndex: Int) {
        scope.launch {
            sendUserCommand(BeetJsonCodec.manualStop(pairIndex))
        }
    }

    fun resetBlock(pairIndex: Int) {
        scope.launch {
            sendUserCommand(BeetJsonCodec.resetBlock(pairIndex))
        }
    }

    fun disablePair(pairIndex: Int) {
        scope.launch {
            sendUserCommand(BeetJsonCodec.disablePair(pairIndex))
        }
    }

    fun enablePair(pairIndex: Int) {
        scope.launch {
            sendUserCommand(BeetJsonCodec.enablePair(pairIndex))
        }
    }

    fun saveCalibration(pairIndex: Int, dryMillivolts: Int, wetMillivolts: Int) {
        scope.launch {
            if (dryMillivolts <= wetMillivolts || dryMillivolts == 0 || wetMillivolts == 0) {
                setCommandMessage("Dry calibration must be greater than wet calibration.")
                return@launch
            }
            sendUserCommand(BeetJsonCodec.storeCalibration(pairIndex, dryMillivolts, wetMillivolts))
            refreshCalibrations()
        }
    }

    private suspend fun sendUserCommand(payload: String) {
        runCatching { sendCommand(payload) }
            .onSuccess { result -> setCommandMessage(messageForResult(result)) }
            .onFailure { error -> setCommandMessage(error.message ?: "Command failed.") }
    }

    private suspend fun sendCommand(payload: String): BeetCommandResult {
        return commandMutex.withLock {
            val gatt = session.currentGatt ?: error("No connected controller.")
            val controlPoint = session.controlPointCharacteristic ?: error("Control point is unavailable.")
            val deferred = CompletableDeferred<BeetCommandResult>()
            session.pendingCommand = deferred

            controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            controlPoint.value = payload.toByteArray(StandardCharsets.UTF_8)
            @Suppress("MissingPermission")
            val writeStarted = gatt.writeCharacteristic(controlPoint)
            if (!writeStarted) {
                session.pendingCommand = null
                error("Could not send command over BLE.")
            }

            try {
                withTimeout(COMMAND_TIMEOUT_MS) { deferred.await() }
            } finally {
                session.pendingCommand = null
            }
        }
    }

    private fun addScanResult(result: ScanResult) {
        discoveredDevices.upsert(result)
        publishDiscoveredDevices()
    }

    private fun publishDiscoveredDevices() {
        _state.update {
            it.copy(discoveredDevices = discoveredDevices.snapshot())
        }
    }

    private fun openGatt(device: BluetoothDevice) {
        Log.d(TAG, "openGatt(address=${device.address}, bondState=${device.bondState})")
        bondMonitorJob?.cancel()
        bondMonitorJob = null
        disconnectGatt(clearSelection = false, reason = "openGatt reset existing session")
        resetSyncState()
        updateConnection(BeetConnectionPhase.Connecting, "Connecting to ${device.address}.")
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (_state.value.connection.phase != BeetConnectionPhase.Connected) {
                Log.w(TAG, "Connection timeout fired while phase=${_state.value.connection.phase}")
                disconnectGatt(clearSelection = false, reason = "connection timeout")
                startScan(detail = "Connection timed out. Searching again.")
            }
        }
        @Suppress("MissingPermission")
        session.currentGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun configureServices(gatt: BluetoothGatt): Boolean {
        val service: BluetoothGattService = gatt.getService(BeetBluetoothSupport.serviceUuid) ?: return false
        session.controllerInfoCharacteristic = service.getCharacteristic(BeetBluetoothSupport.controllerInfoUuid)
        session.stateStreamCharacteristic = service.getCharacteristic(BeetBluetoothSupport.stateStreamUuid)
        session.controlPointCharacteristic = service.getCharacteristic(BeetBluetoothSupport.controlPointUuid)
        session.commandResultCharacteristic = service.getCharacteristic(BeetBluetoothSupport.commandResultUuid)
        Log.d(
            TAG,
            "configureServices(controllerInfo=${session.controllerInfoCharacteristic != null}, stateStream=${session.stateStreamCharacteristic != null}, controlPoint=${session.controlPointCharacteristic != null}, commandResult=${session.commandResultCharacteristic != null})",
        )
        if (session.controllerInfoCharacteristic == null ||
            session.stateStreamCharacteristic == null ||
            session.controlPointCharacteristic == null ||
            session.commandResultCharacteristic == null
        ) {
            return false
        }

        session.descriptorQueue.clear()
        session.descriptorQueue.add(session.commandResultCharacteristic!! to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        session.descriptorQueue.add(session.stateStreamCharacteristic!! to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        return writeNextDescriptor(gatt)
    }

    private fun writeNextDescriptor(gatt: BluetoothGatt): Boolean {
        val next = session.descriptorQueue.removeFirstOrNull() ?: return readControllerInfo(gatt)
        val characteristic = next.first
        val descriptor = characteristic.getDescriptor(BeetBluetoothSupport.clientConfigUuid) ?: return false
        Log.d(TAG, "writeNextDescriptor(uuid=${characteristic.uuid}, queueRemaining=${session.descriptorQueue.size})")
        @Suppress("MissingPermission")
        gatt.setCharacteristicNotification(characteristic, true)
        descriptor.value = next.second
        @Suppress("MissingPermission")
        return gatt.writeDescriptor(descriptor)
    }

    private fun readControllerInfo(gatt: BluetoothGatt): Boolean {
        val characteristic = session.controllerInfoCharacteristic ?: return false
        if (_state.value.controllerInfo != null) {
            cancelControllerInfoRetry("controller info already loaded")
            Log.d(TAG, "Skipping controller info read because it is already loaded")
            return true
        }
        if (!session.initialSyncCompleted && _state.value.connection.phase != BeetConnectionPhase.Connected) {
            updateConnection(BeetConnectionPhase.Syncing, "Reading controller info and waiting for live state.")
        } else {
            Log.d(
                TAG,
                "Reading controller info without phase downgrade (initialSyncCompleted=${session.initialSyncCompleted}, phase=${_state.value.connection.phase})",
            )
        }
        session.controllerInfoReadAttempts += 1
        Log.d(TAG, "Reading controller info, attempt=${session.controllerInfoReadAttempts}")
        @Suppress("MissingPermission")
        val started = gatt.readCharacteristic(characteristic)
        if (!started) {
            Log.w(TAG, "Controller info read did not start on attempt=${session.controllerInfoReadAttempts}")
            scheduleControllerInfoRetry(gatt, "read start returned false")
        }
        return true
    }

    private fun scheduleControllerInfoRetry(gatt: BluetoothGatt, reason: String) {
        if (session.initialSyncCompleted) {
            Log.d(TAG, "Ignoring controller info retry because initial sync already completed: reason=$reason")
            return
        }
        if (_state.value.controllerInfo != null) {
            Log.d(TAG, "Ignoring controller info retry because controller info is already loaded: reason=$reason")
            return
        }
        if (session.controllerInfoReadAttempts >= MAX_CONTROLLER_INFO_READ_ATTEMPTS) {
            Log.w(TAG, "Controller info read exhausted retries: reason=$reason")
            return
        }
        cancelControllerInfoRetry("reschedule: $reason")
        Log.w(
            TAG,
            "Scheduling controller info retry attempt=${session.controllerInfoReadAttempts + 1} reason=$reason",
        )
        controllerInfoRetryJob = scope.launch {
            delay(CONTROLLER_INFO_READ_RETRY_DELAY_MS)
            if (session.currentGatt != gatt) {
                Log.d(TAG, "Skipping controller info retry because the GATT session changed")
                controllerInfoRetryJob = null
                return@launch
            }
            if (session.controllerInfoCharacteristic == null) {
                Log.d(TAG, "Skipping controller info retry because controller info characteristic is unavailable")
                controllerInfoRetryJob = null
                return@launch
            }
            if (session.initialSyncCompleted) {
                Log.d(TAG, "Skipping controller info retry because initial sync already completed")
                controllerInfoRetryJob = null
                return@launch
            }
            if (_state.value.controllerInfo != null) {
                Log.d(TAG, "Skipping controller info retry because controller info is already loaded")
                controllerInfoRetryJob = null
                return@launch
            }
            controllerInfoRetryJob = null
            readControllerInfo(gatt)
        }
    }

    private fun completeInitialSyncIfReady() {
        if (session.initialSyncCompleted) {
            return
        }
        if (!session.initialDeviceFrameReceived || session.syncedPairCount() != 8) {
            return
        }
        session.initialSyncCompleted = true
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        cancelControllerInfoRetry("initial sync completed")
        prefs.edit().putString(KEY_LAST_ADDRESS, currentAddress).apply()
        Log.d(TAG, "Initial sync completed for session address=$currentAddress")
        updateConnection(BeetConnectionPhase.Connected, "Connected to controller.")
        refreshCalibrations()
        refreshHistorySummary()
    }

    private fun handleControllerInfo(payload: ByteArray) {
        val info = try {
            BeetJsonCodec.parseControllerInfo(payload.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            Log.e(TAG, "Controller info payload parse failed", error)
            disconnectGatt(clearSelection = false, reason = "invalid controller info payload")
            clearSession()
            updateConnection(BeetConnectionPhase.Error, "Controller info payload is invalid.")
            return
        }
        if (info.protocolVersion != expectedProtocolVersion) {
            Log.e(TAG, "Unsupported protocol version ${info.protocolVersion}, expected=$expectedProtocolVersion")
            disconnectGatt(clearSelection = false, reason = "unsupported protocol version ${info.protocolVersion}")
            clearSession()
            updateConnection(
                BeetConnectionPhase.Error,
                "Unsupported controller protocol ${info.protocolVersion}. Expected $expectedProtocolVersion.",
            )
            return
        }
        session.controllerInfoReadAttempts = 0
        cancelControllerInfoRetry("controller info read succeeded")
        Log.d(TAG, "handleControllerInfo(deviceId=${info.deviceId}, protocol=${info.protocolVersion}, pairCount=${info.pairCount})")
        _state.update { it.copy(controllerInfo = info) }
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
                session.initialDeviceFrameReceived = true
                Log.d(TAG, "handleStatePayload(deviceFrame battery=${deviceState.batteryMillivolts} activePumps=${deviceState.activePumps})")
                _state.update { state -> state.copy(deviceState = deviceState) }
                completeInitialSyncIfReady()
            }

            is BeetStateMessage.PairStateUpdate -> {
                val pairState = message.data
                session.markPairSynced(pairState.pairIndex)
                Log.d(TAG, "handleStatePayload(pairFrame pair=${pairState.pairIndex} state=${pairState.state} syncedPairs=${session.syncedPairCount()})")
                _state.update { state ->
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
            _state.update { state ->
                state.copy(calibrations = state.calibrations + (calibration.pairIndex to calibration))
            }
        }
        result.historySummary?.let { summary ->
            _state.update { it.copy(historySummary = summary) }
        }
        result.event?.let { event ->
            _state.update { state ->
                if (state.recentEvents.any { it.sequenceNumber == event.sequenceNumber }) {
                    state
                } else {
                    state.copy(recentEvents = state.recentEvents + event)
                }
            }
        }
        session.pendingCommand?.complete(result)
    }

    private fun resetSyncState() {
        Log.d(TAG, "resetSyncState()")
        cancelControllerInfoRetry("reset sync state")
        session.resetSyncState()
    }

    private fun disconnectGatt(clearSelection: Boolean, reason: String) {
        Log.d(
            TAG,
            "disconnectGatt(reason=$reason, clearSelection=$clearSelection, currentAddress=$currentAddress, phase=${_state.value.connection.phase})",
        )
        staleBondRecoveryJob?.cancel()
        staleBondRecoveryJob = null
        bondMonitorJob?.cancel()
        bondMonitorJob = null
        cancelControllerInfoRetry("disconnect gatt: $reason")
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        val gatt = session.currentGatt
        session.currentGatt = null
        resetSyncState()
        session.pendingCommand?.cancel()
        session.pendingCommand = null
        if (clearSelection) {
            currentAddress = null
            _state.update { it.copy(selectedAddress = null) }
        }
        if (gatt != null) {
            @Suppress("MissingPermission")
            gatt.disconnect()
            @Suppress("MissingPermission")
            gatt.close()
        }
    }

    private fun clearSession() {
        Log.d(TAG, "clearSession()")
        _state.update {
            it.copy(
                controllerInfo = null,
                deviceState = null,
                calibrations = emptyMap(),
                historySummary = null,
                recentEvents = emptyList(),
                eventsLoading = false,
                pairStates = BeetRepositoryState().pairStates,
            )
        }
    }

    private fun recoverFromStaleBond(address: String, status: Int) {
        val adapter = bluetoothAdapter ?: run {
            startScan(detail = "Bluetooth is unavailable after pairing failure.")
            return
        }
        staleBondRecoveryJob?.cancel()
        staleBondRecoveryJob = scope.launch {
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                startScan(detail = "Saved controller address is invalid. Searching again.")
                return@launch
            }

            var bondState = device.bondState
            var pairingExpired = bondState == BluetoothDevice.BOND_NONE
            for (attempt in 0 until 5) {
                if (pairingExpired) {
                    break
                }
                delay(250)
                bondState = device.bondState
                pairingExpired = bondState == BluetoothDevice.BOND_NONE
            }

            Log.w(TAG, "recoverFromStaleBond(address=$address, status=$status, bondState=$bondState)")
            if (pairingExpired) {
                pendingBondAddress = address
                updateConnection(BeetConnectionPhase.Bonding, "Opening Android pairing dialog.")
                @Suppress("MissingPermission")
                if (!device.createBond()) {
                    prefs.edit().remove(KEY_LAST_ADDRESS).apply()
                    startScan(detail = "Pairing expired. Tap Connect to pair again.")
                } else {
                    monitorBondState(device)
                }
            } else {
                prefs.edit().remove(KEY_LAST_ADDRESS).apply()
                startScan(detail = "Saved pairing expired. Tap Connect to pair again.")
            }
            staleBondRecoveryJob = null
        }
    }

    private fun monitorBondState(device: BluetoothDevice) {
        bondMonitorJob?.cancel()
        bondMonitorJob = scope.launch {
            Log.d(TAG, "monitorBondState(address=${device.address})")
            repeat(40) { attempt ->
                delay(500)
                val bondState = device.bondState
                Log.d(
                    TAG,
                    "monitorBondState poll address=${device.address} bondState=$bondState pending=$pendingBondAddress phase=${_state.value.connection.phase}",
                )
                if (device.address != pendingBondAddress) {
                    bondMonitorJob = null
                    return@launch
                }
                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        pendingBondAddress = null
                        bondMonitorJob = null
                        openGatt(device)
                        return@launch
                    }

                    BluetoothDevice.BOND_BONDING -> {
                        val detail = if (attempt < 2) {
                            "Pairing in progress. Confirm the code on your phone."
                        } else {
                            "Pairing in progress. Waiting for Android to finish bonding."
                        }
                        if (_state.value.connection.phase == BeetConnectionPhase.Bonding &&
                            _state.value.connection.detail != detail) {
                            updateConnection(BeetConnectionPhase.Bonding, detail)
                        }
                    }

                    BluetoothDevice.BOND_NONE -> {
                        if (_state.value.connection.phase == BeetConnectionPhase.Bonding) {
                            pendingBondAddress = null
                            bondMonitorJob = null
                            startScan(detail = "Bonding was cancelled or failed. Searching again.")
                            return@launch
                        }
                    }
                }
            }
            if (device.address == pendingBondAddress && _state.value.connection.phase == BeetConnectionPhase.Bonding) {
                pendingBondAddress = null
                startScan(detail = "Bonding timed out. Searching again.")
            }
            bondMonitorJob = null
        }
    }

    private fun updateConnection(phase: BeetConnectionPhase, detail: String?) {
        val previous = _state.value.connection
        Log.d(TAG, "updateConnection(${previous.phase} -> $phase, previousDetail=${previous.detail}, detail=$detail, selected=$currentAddress)")
        _state.update { it.copy(connection = BeetConnectionState(phase, detail)) }
    }

    private fun setCommandMessage(message: String) {
        _state.update { it.copy(lastCommandMessage = message) }
    }

    private fun cancelControllerInfoRetry(reason: String) {
        val retryJob = controllerInfoRetryJob ?: return
        Log.d(TAG, "Cancelling controller info retry: reason=$reason active=${retryJob.isActive}")
        retryJob.cancel()
        controllerInfoRetryJob = null
    }

    private fun messageForResult(result: BeetCommandResult): String {
        return commandMessageForResult(result)
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(systemReceiver, filter)
        }
        receiverRegistered = true
    }

    private val systemReceiver = beetSystemReceiver(
        onBluetoothStateChanged = {
            Log.d(TAG, "Broadcast ACTION_STATE_CHANGED enabled=${bluetoothAdapter?.isEnabled}")
            refreshEnvironment()
            if (bluetoothAdapter?.isEnabled == true &&
                _state.value.connection.phase == BeetConnectionPhase.Idle &&
                _state.value.discoveredDevices.isEmpty()
            ) {
                startScan()
            }
        },
        onBondStateChanged = { device, bondState, previousBondState ->
            if (device?.address != pendingBondAddress) {
                return@beetSystemReceiver
            }
            val bondedDevice = device ?: return@beetSystemReceiver
            Log.d(
                TAG,
                "Broadcast ACTION_BOND_STATE_CHANGED address=${bondedDevice.address} previous=$previousBondState current=$bondState pending=$pendingBondAddress",
            )
            when (bondState) {
                BluetoothDevice.BOND_BONDING -> {
                    updateConnection(BeetConnectionPhase.Bonding, "Pairing in progress. Confirm the code on your phone.")
                }

                BluetoothDevice.BOND_BONDED -> {
                    pendingBondAddress = null
                    openGatt(bondedDevice)
                }

                BluetoothDevice.BOND_NONE -> {
                    if (previousBondState == BluetoothDevice.BOND_BONDING) {
                        pendingBondAddress = null
                        startScan(detail = "Bonding was cancelled or failed. Searching again.")
                    }
                }
            }
        },
    )

    private val gattCallback = beetGattCallback(
        onConnectionStateChange = { gatt, status, newState ->
            Log.d(TAG, "onConnectionStateChange(status=$status, newState=$newState, address=${gatt.device.address})")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val staleBondCandidate =
                    status == 22 &&
                        currentAddress != null &&
                        _state.value.connection.phase in setOf(
                            BeetConnectionPhase.Connecting,
                            BeetConnectionPhase.DiscoveringServices,
                            BeetConnectionPhase.Syncing,
                        )
                disconnectGatt(clearSelection = false, reason = "gatt error status=$status")
                clearSession()
                if (staleBondCandidate) {
                    recoverFromStaleBond(gatt.device.address, status)
                } else {
                    startScan(detail = "BLE connection error $status. Searching again.")
                }
                return@beetGattCallback
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    updateConnection(BeetConnectionPhase.DiscoveringServices, "Negotiating BLE session.")
                    @Suppress("MissingPermission")
                    if (!gatt.requestMtu(DESIRED_MTU)) {
                        @Suppress("MissingPermission")
                        gatt.discoverServices()
                    }
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    disconnectGatt(clearSelection = false, reason = "gatt disconnected callback")
                    clearSession()
                    if (!manualDisconnectRequested) {
                        startScan(detail = "Controller disconnected. Searching again.")
                    } else {
                        updateConnection(BeetConnectionPhase.Disconnected, "Disconnected from controller.")
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
                clearSession()
                startScan(detail = "BeetMeister GATT service is incomplete. Searching again.")
            }
        },
        onDescriptorWrite = { gatt, descriptor, status ->
            Log.d(TAG, "onDescriptorWrite uuid=${descriptor.characteristic.uuid} status=$status queueRemaining=${session.descriptorQueue.size}")
            if (status != BluetoothGatt.GATT_SUCCESS || !writeNextDescriptor(gatt)) {
                disconnectGatt(clearSelection = false, reason = "descriptor write failed status=$status uuid=${descriptor.characteristic.uuid}")
                clearSession()
                startScan(detail = "Failed to subscribe to controller updates. Searching again.")
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
        private const val TAG = "BeetRepository"
        private const val PREFS_NAME = "beetmeister_prefs"
        private const val KEY_LAST_ADDRESS = "last_controller_address"
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val COMMAND_TIMEOUT_MS = 7_000L
        private const val CONTROLLER_INFO_READ_RETRY_DELAY_MS = 400L
        private const val MAX_CONTROLLER_INFO_READ_ATTEMPTS = 4
        private const val STALE_DEVICE_TIMEOUT_MS = 30_000L
        private const val DESIRED_MTU = 247
        private const val EXPECTED_PROTOCOL_VERSION = 2

        fun requiredPermissions(): Array<String> = BeetBluetoothSupport.requiredPermissions()

        fun hasRequiredPermissions(context: Context): Boolean = BeetBluetoothSupport.hasRequiredPermissions(context)
    }
}
