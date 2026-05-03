package de.aarondietz.beetmeister.model.event

object BeetEventMappings {
    fun triggerSourceLabel(value: Int): String = when (value) {
        1 -> "Automatic"
        2 -> "Manual"
        3 -> "Moisture test"
        else -> "None"
    }

    fun stopReasonLabel(value: Int): String = when (value) {
        0 -> "Completed"
        1 -> "Manual stop"
        2 -> "Low battery abort"
        3 -> "Sanity failure"
        4 -> "Sensor invalid abort"
        5 -> "System abort"
        6 -> "Idle low-power sleep"
        7 -> "Deep low-battery sleep"
        else -> "Unknown"
    }

    fun blockReasonLabel(value: Int): String = when (value) {
        0 -> "None"
        1 -> "Moisture response test failed"
        2 -> "Sensor invalid"
        3 -> "Low battery abort"
        else -> "Unknown"
    }

    fun systemEventLabel(value: String): String = when (value) {
        "STARTUP" -> "Startup"
        "SLEEP" -> "Sleep"
        "BLE_CONNECT" -> "Bluetooth connected"
        "BLE_DISCONNECT" -> "Bluetooth disconnected"
        "BLE_BOND_SUCCESS" -> "Bluetooth bonded"
        "BLE_BOND_FAILED" -> "Bluetooth bond failed"
        "BLE_BONDS_CLEARED" -> "Bluetooth bonds cleared"
        "MQTT_CONNECT" -> "MQTT connected"
        "MQTT_DISCONNECT" -> "MQTT disconnected"
        "MQTT_PUBLISH_FAILED" -> "MQTT publish failed"
        "OTA_STARTED" -> "OTA started"
        "OTA_FAILED" -> "OTA failed"
        "OTA_READY" -> "OTA ready"
        else -> value
    }
}
