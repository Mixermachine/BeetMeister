package de.aarondietz.beetmeister

import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.strings.BeetStringResolver
import java.util.Locale

internal class TestBeetStringResolver : BeetStringResolver {
    override val locale: Locale = Locale.US

    override fun get(id: Int, vararg args: Any): String {
        val template = when (id) {
            R.string.placeholder_dash -> "-"
            R.string.common_none -> "None"
            R.string.common_today -> "Today"
            R.string.common_yesterday -> "Yesterday"
            R.string.common_unknown_with_code -> "Unknown (%1\$s)"
            R.string.events_pending_time_sync -> "Pending time sync"
            R.string.events_ignored_legacy -> "Ignored legacy"
            R.string.events_boot_section_title -> "Boot %1\$d"
            R.string.events_relative_uptime -> "t+%1\$s"
            R.string.events_filter_system -> "System"
            R.string.events_filter_bluetooth -> "Bluetooth"
            R.string.events_filter_sleep -> "Sleep"
            R.string.events_filter_startup -> "Startup"
            R.string.events_filter_mqtt -> "MQTT"
            R.string.events_filter_ota -> "OTA"
            R.string.common_millivolts -> "%1\$d mV"
            R.string.format_duration_hours_minutes -> "%1\$dh %2\$dm"
            R.string.format_duration_minutes_seconds -> "%1\$dm %2\$ds"
            R.string.format_duration_seconds_only -> "%1\$ds"
            R.string.events_disconnected_reason -> "Disconnected"
            R.string.events_disconnect_code -> "Disconnect code %1\$d"
            R.string.events_bond_status -> "Bond status %1\$d"
            R.string.events_publish_failed -> "Publish failed"
            R.string.events_publish_status -> "Publish status %1\$d"
            R.string.events_known_peer -> "Known peer"
            R.string.events_new_peer -> "New peer"
            R.string.events_known_peer_address -> "Known %1\$s"
            R.string.events_new_peer_address -> "New %1\$s"
            R.string.stop_reason_idle_low_power_sleep -> "Idle low-power sleep"
            R.string.command_message_bonds_cleared -> "Bluetooth bonds cleared."
            R.string.command_message_no_bonds -> "No Bluetooth bonds to clear."
            R.string.command_message_pair_prefix -> "Pair %1\$d: %2\$s."
            R.string.command_reason_bonds_cleared -> "Bluetooth bonds cleared"
            R.string.command_reason_no_bonds -> "No Bluetooth bonds to clear"
            R.string.command_reason_busy -> "Controller is busy"
            R.string.command_reason_rate_limited -> "Too many commands; try again"
            else -> error("Missing test string for resource id $id")
        }
        return if (args.isEmpty()) template else String.format(locale, template, *args)
    }
}
