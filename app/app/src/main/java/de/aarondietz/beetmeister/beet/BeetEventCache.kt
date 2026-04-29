package de.aarondietz.beetmeister.beet

import android.content.SharedPreferences

internal class BeetEventCache(
    private val prefs: SharedPreferences,
) {
    fun loadWateringEvents(deviceId: String): List<BeetWateringEvent> =
        loadKeys(wateringIndexKey(deviceId)).mapNotNull { key ->
            prefs.getString(key, null)
                ?.let(BeetJsonCodec::wateringEventFromJson)
                ?.takeIf { it.bootId > 0L && it.timeValid }
        }

    fun loadSystemEvents(deviceId: String): List<BeetSystemEvent> =
        loadKeys(systemIndexKey(deviceId)).mapNotNull { key ->
            prefs.getString(key, null)
                ?.let(BeetJsonCodec::systemEventFromJson)
                ?.takeIf { it.bootId > 0L && it.timeValid }
        }

    fun saveWateringEvent(deviceId: String, event: BeetWateringEvent) {
        val key = wateringEventKey(deviceId, event.sequenceNumber)
        prefs.edit()
            .putString(key, BeetJsonCodec.wateringEventToJson(event))
            .putStringSet(wateringIndexKey(deviceId), loadKeys(wateringIndexKey(deviceId)) + key)
            .apply()
    }

    fun saveSystemEvent(deviceId: String, event: BeetSystemEvent) {
        val key = systemEventKey(deviceId, event.sequenceNumber)
        prefs.edit()
            .putString(key, BeetJsonCodec.systemEventToJson(event))
            .putStringSet(systemIndexKey(deviceId), loadKeys(systemIndexKey(deviceId)) + key)
            .apply()
    }

    private fun loadKeys(indexKey: String): Set<String> = prefs.getStringSet(indexKey, emptySet()).orEmpty()

    private fun wateringIndexKey(deviceId: String): String = "$deviceId:watering:index"

    private fun systemIndexKey(deviceId: String): String = "$deviceId:system:index"

    private fun wateringEventKey(deviceId: String, sequence: Long): String = "$deviceId:watering:$sequence"

    private fun systemEventKey(deviceId: String, sequence: Long): String = "$deviceId:system:$sequence"
}
