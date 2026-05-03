package de.aarondietz.beetmeister.beet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import de.aarondietz.beetmeister.beet.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.beet.model.repository.BeetRepositoryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

internal interface BeetRepositoryCallbacks {
    val appContext: Context
    val bluetoothAdapter: BluetoothAdapter?
    val scope: CoroutineScope
    val state: StateFlow<BeetRepositoryState>
    val session: BeetConnectionSession
    val discoveredDevices: BeetDiscoveredDeviceStore
    var currentAddress: String?
    var manualDisconnectRequested: Boolean

    fun updateConnection(phase: BeetConnectionPhase, detail: String?)
    fun updateState(transform: (BeetRepositoryState) -> BeetRepositoryState)
    fun publishDiscoveredDevices()
    fun setCommandMessage(message: String)
    fun clearCommandMessage()
    fun clearSession()
    fun resetSyncState()
    fun persistLastAddress(address: String?)
    fun removeLastAddress()
    fun requestOpenGatt(device: BluetoothDevice)
    fun requestStartScan(detail: String = "Searching for nearby BeetMeister controllers.", clearResults: Boolean = false)
    fun recoverFromStaleBond(address: String, status: Int)
}
