package de.aarondietz.beetmeister.data.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

internal fun beetScanCallback(
    onScanResult: (ScanResult) -> Unit,
    onScanFailed: (Int) -> Unit,
): ScanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        onScanResult(result)
    }

    override fun onBatchScanResults(results: MutableList<ScanResult>) {
        results.forEach(onScanResult)
    }

    override fun onScanFailed(errorCode: Int) {
        onScanFailed(errorCode)
    }
}

internal fun beetSystemReceiver(
    onBluetoothStateChanged: () -> Unit,
    onBondStateChanged: (BluetoothDevice?, Int, Int) -> Unit,
): BroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> onBluetoothStateChanged()
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

internal fun beetGattCallback(
    onConnectionStateChange: (BluetoothGatt, Int, Int) -> Unit,
    onMtuChanged: (BluetoothGatt, Int, Int) -> Unit,
    onServicesDiscovered: (BluetoothGatt, Int) -> Unit,
    onDescriptorWrite: (BluetoothGatt, BluetoothGattDescriptor, Int) -> Unit,
    onCharacteristicRead: (BluetoothGatt, BluetoothGattCharacteristic, Int) -> Unit,
    onCharacteristicChanged: (BluetoothGatt, BluetoothGattCharacteristic) -> Unit,
): BluetoothGattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        onConnectionStateChange(gatt, status, newState)
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        onMtuChanged(gatt, mtu, status)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        onServicesDiscovered(gatt, status)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        onDescriptorWrite(gatt, descriptor, status)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        onCharacteristicRead(gatt, characteristic, status)
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        onCharacteristicChanged(gatt, characteristic)
    }
}
