package de.aarondietz.beetmeister.beet

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.CompletableDeferred

internal class BeetConnectionSession {
    var currentGatt: BluetoothGatt? = null
    var controlPointCharacteristic: BluetoothGattCharacteristic? = null
    var controllerInfoCharacteristic: BluetoothGattCharacteristic? = null
    var stateStreamCharacteristic: BluetoothGattCharacteristic? = null
    var commandResultCharacteristic: BluetoothGattCharacteristic? = null
    var pendingCommand: CompletableDeferred<BeetCommandResult>? = null
    var initialDeviceFrameReceived = false
    var initialSyncCompleted = false
    var controllerInfoReadAttempts = 0
    val descriptorQueue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()

    private val syncedPairs = linkedSetOf<Int>()

    fun resetSyncState() {
        controllerInfoCharacteristic = null
        stateStreamCharacteristic = null
        controlPointCharacteristic = null
        commandResultCharacteristic = null
        descriptorQueue.clear()
        initialDeviceFrameReceived = false
        initialSyncCompleted = false
        controllerInfoReadAttempts = 0
        syncedPairs.clear()
    }

    fun markPairSynced(pairIndex: Int) {
        syncedPairs += pairIndex
    }

    fun syncedPairCount(): Int = syncedPairs.size
}
