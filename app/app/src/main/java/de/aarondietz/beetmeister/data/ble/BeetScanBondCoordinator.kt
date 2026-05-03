package de.aarondietz.beetmeister.data.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import de.aarondietz.beetmeister.data.repository.BeetRepositoryCallbacks
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class BeetScanBondCoordinator(
    private val host: BeetRepositoryCallbacks,
) {
    private var receiverRegistered = false
    private var scanCallback: ScanCallback? = null
    private var staleBondRecoveryJob: Job? = null
    private var bondMonitorJob: Job? = null
    private var pendingBondAddress: String? = null

    fun start() {
        Log.d(TAG, "start()")
        registerReceiverIfNeeded()
        refreshEnvironment()
        val savedAddress = host.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ADDRESS, null)
        if (savedAddress.isNullOrBlank()) {
            startScan(clearResults = true)
        } else {
            connect(savedAddress)
        }
    }

    fun close() {
        Log.d(TAG, "close()")
        stopScan()
        bondMonitorJob?.cancel()
        bondMonitorJob = null
        staleBondRecoveryJob?.cancel()
        staleBondRecoveryJob = null
        if (receiverRegistered) {
            host.appContext.unregisterReceiver(systemReceiver)
            receiverRegistered = false
        }
    }

    fun refreshEnvironment() {
        val adapter = host.bluetoothAdapter
        when {
            !BeetBluetoothSupport.hasRequiredPermissions(host.appContext) -> {
                updateConnection(BeetConnectionPhase.PermissionsRequired, "Bluetooth permissions are required.")
            }
            adapter == null -> {
                updateConnection(BeetConnectionPhase.Error, "This device does not support Bluetooth LE.")
            }
            !adapter.isEnabled -> {
                updateConnection(BeetConnectionPhase.BluetoothDisabled, "Bluetooth is turned off.")
            }
            host.state.value.connection.phase == BeetConnectionPhase.PermissionsRequired ||
                host.state.value.connection.phase == BeetConnectionPhase.BluetoothDisabled -> {
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
            "startScan(clearResults=$clearResults, detail=$detail, callbackActive=${scanCallback != null}, phase=${host.state.value.connection.phase})",
        )
        if (!BeetBluetoothSupport.hasRequiredPermissions(host.appContext)) {
            updateConnection(BeetConnectionPhase.PermissionsRequired, "Bluetooth permissions are required.")
            return
        }
        val adapter = host.bluetoothAdapter
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
            host.discoveredDevices.clear()
        } else {
            host.discoveredDevices.pruneStaleDevices(STALE_DEVICE_TIMEOUT_MS)
        }
        host.publishDiscoveredDevices()
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
        val scanner = host.bluetoothAdapter?.bluetoothLeScanner ?: return
        Log.d(TAG, "stopScan()")
        @Suppress("MissingPermission")
        scanner.stopScan(callback)
        scanCallback = null
    }

    fun connect(address: String) {
        Log.d(TAG, "connect(address=$address)")
        if (!BeetBluetoothSupport.hasRequiredPermissions(host.appContext)) {
            updateConnection(BeetConnectionPhase.PermissionsRequired, "Bluetooth permissions are required.")
            return
        }
        val adapter = host.bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateConnection(BeetConnectionPhase.BluetoothDisabled, "Bluetooth is turned off.")
            return
        }

        host.scope.launch {
            stopScan()
            host.manualDisconnectRequested = false
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                updateConnection(BeetConnectionPhase.Error, "Invalid controller address.")
                return@launch
            }
            host.currentAddress = address
            host.updateState { it.copy(selectedAddress = address, lastCommandMessage = null) }
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

            host.requestOpenGatt(device)
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        stopScan()
        bondMonitorJob?.cancel()
        bondMonitorJob = null
        staleBondRecoveryJob?.cancel()
        staleBondRecoveryJob = null
        pendingBondAddress = null
    }

    private fun addScanResult(result: ScanResult) {
        host.discoveredDevices.upsert(result)
        host.publishDiscoveredDevices()
    }

    private fun updateConnection(phase: BeetConnectionPhase, detail: String?) {
        host.updateConnection(phase, detail)
    }

    fun recoverFromStaleBond(address: String, status: Int) {
        val adapter = host.bluetoothAdapter ?: run {
            startScan(detail = "Bluetooth is unavailable after pairing failure.")
            return
        }
        staleBondRecoveryJob?.cancel()
        staleBondRecoveryJob = host.scope.launch {
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
                    host.removeLastAddress()
                    startScan(detail = "Pairing expired. Tap Connect to pair again.")
                } else {
                    monitorBondState(device)
                }
            } else {
                host.removeLastAddress()
                startScan(detail = "Saved pairing expired. Tap Connect to pair again.")
            }
            staleBondRecoveryJob = null
        }
    }

    private fun monitorBondState(device: BluetoothDevice) {
        bondMonitorJob?.cancel()
        bondMonitorJob = host.scope.launch {
            Log.d(TAG, "monitorBondState(address=${device.address})")
            repeat(40) { attempt ->
                delay(500)
                val bondState = device.bondState
                Log.d(
                    TAG,
                    "monitorBondState poll address=${device.address} bondState=$bondState pending=$pendingBondAddress phase=${host.state.value.connection.phase}",
                )
                if (device.address != pendingBondAddress) {
                    bondMonitorJob = null
                    return@launch
                }
                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        pendingBondAddress = null
                        bondMonitorJob = null
                        host.requestOpenGatt(device)
                        return@launch
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        val detail = if (attempt < 2) {
                            "Pairing in progress. Confirm the code on your phone."
                        } else {
                            "Pairing in progress. Waiting for Android to finish bonding."
                        }
                        if (host.state.value.connection.phase == BeetConnectionPhase.Bonding &&
                            host.state.value.connection.detail != detail
                        ) {
                            updateConnection(BeetConnectionPhase.Bonding, detail)
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        if (host.state.value.connection.phase == BeetConnectionPhase.Bonding) {
                            pendingBondAddress = null
                            bondMonitorJob = null
                            startScan(detail = "Bonding was cancelled or failed. Searching again.")
                            return@launch
                        }
                    }
                }
            }
            if (device.address == pendingBondAddress && host.state.value.connection.phase == BeetConnectionPhase.Bonding) {
                pendingBondAddress = null
                startScan(detail = "Bonding timed out. Searching again.")
            }
            bondMonitorJob = null
        }
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
            host.appContext.registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            host.appContext.registerReceiver(systemReceiver, filter)
        }
        receiverRegistered = true
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    Log.d(TAG, "Broadcast ACTION_STATE_CHANGED enabled=${host.bluetoothAdapter?.isEnabled}")
                    refreshEnvironment()
                    if (host.bluetoothAdapter?.isEnabled == true &&
                        host.state.value.connection.phase == BeetConnectionPhase.Idle &&
                        host.state.value.discoveredDevices.isEmpty()
                    ) {
                        startScan()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                    }
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    onBondStateChanged(device, bondState, previousBondState)
                }
            }
        }
    }

    private fun onBondStateChanged(device: BluetoothDevice?, bondState: Int, previousBondState: Int) {
        if (device?.address != pendingBondAddress) {
            return
        }
        val bondedDevice = device ?: return
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
                host.requestOpenGatt(bondedDevice)
            }
            BluetoothDevice.BOND_NONE -> {
                if (previousBondState == BluetoothDevice.BOND_BONDING) {
                    pendingBondAddress = null
                    startScan(detail = "Bonding was cancelled or failed. Searching again.")
                }
            }
        }
    }

    companion object {
        private const val TAG = "BeetScanBond"
        private const val PREFS_NAME = "beetmeister_prefs"
        private const val KEY_LAST_ADDRESS = "last_controller_address"
        private const val STALE_DEVICE_TIMEOUT_MS = 30_000L
    }
}
