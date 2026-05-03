package de.aarondietz.beetmeister.beet

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import de.aarondietz.beetmeister.beet.model.command.BeetCommandResult
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
    var syncedTimeBootId: Long = 0L
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
        syncedTimeBootId = 0L
        syncedPairs.clear()
    }

    fun markPairSynced(pairIndex: Int) {
        syncedPairs += pairIndex
    }

    fun syncedPairCount(): Int = syncedPairs.size
}
