package de.aarondietz.beetmeister.beet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class BeetRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BeetRepositoryCallbacks {
    override val appContext: Context = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)
    override val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val _state = MutableStateFlow(BeetRepositoryState())
    override val state: StateFlow<BeetRepositoryState> = _state.asStateFlow()
    override val discoveredDevices = BeetDiscoveredDeviceStore()
    override val session = BeetConnectionSession()
    override var currentAddress: String? = null
    override var manualDisconnectRequested: Boolean = false

    private val scanBondCoordinator = BeetScanBondCoordinator(this)
    private val gattSessionCoordinator = BeetGattSessionCoordinator(this)

    fun start() = scanBondCoordinator.start()

    fun close() {
        Log.d(TAG, "close()")
        scanBondCoordinator.close()
        gattSessionCoordinator.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    fun refreshEnvironment() = scanBondCoordinator.refreshEnvironment()

    fun startScan(
        detail: String = "Searching for nearby BeetMeister controllers.",
        clearResults: Boolean = false,
    ) = scanBondCoordinator.startScan(detail, clearResults)

    fun stopScan() = scanBondCoordinator.stopScan()

    fun connect(address: String) = scanBondCoordinator.connect(address)

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        manualDisconnectRequested = true
        removeLastAddress()
        scanBondCoordinator.disconnect()
        gattSessionCoordinator.disconnect(clearSelection = true, reason = "manual disconnect")
        clearSession()
        updateConnection(BeetConnectionPhase.Disconnected, "Disconnected from controller.")
    }

    fun refreshCalibrations() = gattSessionCoordinator.refreshCalibrations()

    fun refreshHistorySummary() = gattSessionCoordinator.refreshHistorySummary()

    fun loadRecentEvents(limit: Int = 50) = gattSessionCoordinator.loadRecentEvents(limit)

    fun manualStart(pairIndex: Int, durationSeconds: Int?) = gattSessionCoordinator.manualStart(pairIndex, durationSeconds)

    fun manualStop(pairIndex: Int) = gattSessionCoordinator.manualStop(pairIndex)

    fun clearPairError(pairIndex: Int) = gattSessionCoordinator.clearPairError(pairIndex)

    fun resetBlock(pairIndex: Int) = clearPairError(pairIndex)

    fun disablePair(pairIndex: Int) = gattSessionCoordinator.disablePair(pairIndex)

    fun enablePair(pairIndex: Int) = gattSessionCoordinator.enablePair(pairIndex)

    fun togglePairEnabled(pairIndex: Int) {
        val pairState = state.value.pairStates.firstOrNull { it.pairIndex == pairIndex } ?: return
        if (pairState.enabled) {
            disablePair(pairIndex)
        } else {
            enablePair(pairIndex)
        }
    }

    fun saveCalibration(pairIndex: Int, dryMillivolts: Int, wetMillivolts: Int) =
        gattSessionCoordinator.saveCalibration(pairIndex, dryMillivolts, wetMillivolts)

    override fun updateConnection(phase: BeetConnectionPhase, detail: String?) {
        val previous = state.value.connection
        Log.d(TAG, "updateConnection(${previous.phase} -> $phase, previousDetail=${previous.detail}, detail=$detail, selected=$currentAddress)")
        _state.update { it.copy(connection = BeetConnectionState(phase, detail)) }
    }

    override fun updateState(transform: (BeetRepositoryState) -> BeetRepositoryState) {
        _state.update(transform)
    }

    override fun publishDiscoveredDevices() {
        _state.update { it.copy(discoveredDevices = discoveredDevices.snapshot()) }
    }

    override fun setCommandMessage(message: String) {
        _state.update { it.copy(lastCommandMessage = message) }
    }

    override fun clearCommandMessage() {
        _state.update { it.copy(lastCommandMessage = null) }
    }

    override fun clearSession() {
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

    override fun resetSyncState() {
        session.resetSyncState()
    }

    override fun persistLastAddress(address: String?) {
        prefs.edit().putString(KEY_LAST_ADDRESS, address).apply()
    }

    override fun removeLastAddress() {
        prefs.edit().remove(KEY_LAST_ADDRESS).apply()
    }

    override fun requestOpenGatt(device: BluetoothDevice) {
        gattSessionCoordinator.openGatt(device)
    }

    override fun requestStartScan(detail: String, clearResults: Boolean) {
        startScan(detail, clearResults)
    }

    override fun recoverFromStaleBond(address: String, status: Int) {
        scanBondCoordinator.recoverFromStaleBond(address, status)
    }

    companion object {
        private const val TAG = "BeetRepository"
        private const val PREFS_NAME = "beetmeister_prefs"
        private const val KEY_LAST_ADDRESS = "last_controller_address"

        fun requiredPermissions(): Array<String> = BeetBluetoothSupport.requiredPermissions()

        fun hasRequiredPermissions(context: Context): Boolean = BeetBluetoothSupport.hasRequiredPermissions(context)
    }
}
