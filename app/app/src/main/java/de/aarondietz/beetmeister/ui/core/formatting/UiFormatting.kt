package de.aarondietz.beetmeister.ui.core.formatting

import android.bluetooth.BluetoothDevice
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import java.time.Instant
import java.time.ZoneId

internal fun bondStateLabel(bondState: Int): String = when (bondState) {
    BluetoothDevice.BOND_BONDED -> "Bonded"
    BluetoothDevice.BOND_BONDING -> "Bonding"
    else -> "Not bonded"
}

internal fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

internal fun formatDuration(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val remainingSeconds = safeSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${remainingSeconds}s"
        else -> "${remainingSeconds}s"
    }
}

internal fun formatUnixSeconds(unixSeconds: Long): String =
    Instant.ofEpochSecond(unixSeconds)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .toString()

internal fun formatEventTime(event: BeetWateringEvent): String {
    if (!event.timeValid) {
        return "Time unknown"
    }
    return "${formatUnixSeconds(event.startedAtUnixSeconds)} -> ${formatUnixSeconds(event.endedAtUnixSeconds)}"
}

internal fun formatEventSubject(event: BeetWateringEvent): String =
    if (event.isControllerSleepEvent) "Controller sleep" else "Pair ${event.pairIndex}"
