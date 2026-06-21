package de.aarondietz.beetmeister.data.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import de.aarondietz.beetmeister.model.command.BeetCommandResult
import kotlinx.coroutines.CompletableDeferred

internal class BeetConnectionSession {
    var currentGatt: BluetoothGatt? = null
    var controlPointCharacteristic: BluetoothGattCharacteristic? = null
    var controllerInfoCharacteristic: BluetoothGattCharacteristic? = null
    var stateStreamCharacteristic: BluetoothGattCharacteristic? = null
    var commandResultCharacteristic: BluetoothGattCharacteristic? = null
    var maintenanceInfoCharacteristic: BluetoothGattCharacteristic? = null
    var maintenanceControlCharacteristic: BluetoothGattCharacteristic? = null
    var maintenanceStatusCharacteristic: BluetoothGattCharacteristic? = null
    var maintenanceDataCharacteristic: BluetoothGattCharacteristic? = null
    var pendingCommand: CompletableDeferred<BeetCommandResult>? = null
    var initialDeviceFrameReceived = false
    var initialSyncCompleted = false
    var controllerInfoReadAttempts = 0
    var syncedTimeBootId: Long = 0L
    var serviceDiscoveryStarted = false
    var servicesConfigured = false
    val descriptorQueue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()

    private val syncedPairs = linkedSetOf<Int>()
    private var controllerInfoLoaded = false
    private var expectedPairCount = 8

    @Synchronized
    fun resetSyncState() {
        controllerInfoCharacteristic = null
        stateStreamCharacteristic = null
        controlPointCharacteristic = null
        commandResultCharacteristic = null
        maintenanceInfoCharacteristic = null
        maintenanceControlCharacteristic = null
        maintenanceStatusCharacteristic = null
        maintenanceDataCharacteristic = null
        descriptorQueue.clear()
        initialDeviceFrameReceived = false
        initialSyncCompleted = false
        controllerInfoReadAttempts = 0
        syncedTimeBootId = 0L
        serviceDiscoveryStarted = false
        servicesConfigured = false
        controllerInfoLoaded = false
        expectedPairCount = 8
        syncedPairs.clear()
    }

    @Synchronized
    fun markPairSynced(pairIndex: Int): Int {
        syncedPairs += pairIndex
        return syncedPairs.size
    }

    @Synchronized
    fun syncedPairCount(): Int = syncedPairs.size

    @Synchronized
    fun markControllerInfoLoaded(pairCount: Int) {
        controllerInfoLoaded = true
        expectedPairCount = pairCount.coerceAtLeast(1)
    }

    @Synchronized
    fun tryCompleteInitialSync(): Boolean {
        if (initialSyncCompleted) {
            return false
        }
        if (!controllerInfoLoaded || syncedPairs.size < expectedPairCount) {
            return false
        }
        initialSyncCompleted = true
        return true
    }
}
