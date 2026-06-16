package de.aarondietz.beetmeister.data.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID

internal object BeetBluetoothSupport {
    val serviceUuid: UUID = UUID.fromString("8f2a0001-6d7a-4a6b-9d57-3f2a7d94c4b0")
    val controllerInfoUuid: UUID = UUID.fromString("8f2a0002-6d7a-4a6b-9d57-3f2a7d94c4b0")
    val stateStreamUuid: UUID = UUID.fromString("8f2a0003-6d7a-4a6b-9d57-3f2a7d94c4b0")
    val controlPointUuid: UUID = UUID.fromString("8f2a0004-6d7a-4a6b-9d57-3f2a7d94c4b0")
    val commandResultUuid: UUID = UUID.fromString("8f2a0005-6d7a-4a6b-9d57-3f2a7d94c4b0")
    val maintenanceServiceUuid: UUID = UUID.fromString("8f2a0006-6d7a-4a6b-9d57-3f2a7d94c4b0")
    val maintenanceInfoUuid: UUID = UUID.fromString("8f2a0007-6d7a-4a6b-9d57-3f2a7d94c4b0")
    val maintenanceControlUuid: UUID = UUID.fromString("8f2a0008-6d7a-4a6b-9d57-3f2a7d94c4b0")
    val maintenanceStatusUuid: UUID = UUID.fromString("8f2a0009-6d7a-4a6b-9d57-3f2a7d94c4b0")
    val maintenanceDataUuid: UUID = UUID.fromString("8f2a000a-6d7a-4a6b-9d57-3f2a7d94c4b0")
    val clientConfigUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasRequiredPermissions(context: Context): Boolean =
        requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
}
