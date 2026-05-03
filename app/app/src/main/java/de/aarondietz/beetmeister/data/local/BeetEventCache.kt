package de.aarondietz.beetmeister.data.local

import android.content.SharedPreferences
import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent

internal class BeetEventCache(
    private val prefs: SharedPreferences,
) {
    fun loadWateringEvents(deviceId: String): List<BeetWateringEvent> {
        val cutoffUnixSeconds = retentionCutoffUnixSeconds()
        val indexKey = wateringIndexKey(deviceId)
        val kept = mutableListOf<BeetWateringEvent>()
        val keysToKeep = linkedSetOf<String>()

        loadKeys(indexKey).forEach { key ->
            val event = prefs.getString(key, null)
                ?.let(BeetJsonCodec::wateringEventFromJson)
            if (event != null &&
                event.bootId > 0L &&
                event.timeValid &&
                event.endedAtUnixSeconds >= cutoffUnixSeconds
            ) {
                kept += event
                keysToKeep += key
            } else {
                prefs.edit().remove(key).apply()
            }
        }
        prefs.edit().putStringSet(indexKey, keysToKeep).apply()
        return kept
    }

    fun loadSystemEvents(deviceId: String): List<BeetSystemEvent> {
        val cutoffUnixSeconds = retentionCutoffUnixSeconds()
        val indexKey = systemIndexKey(deviceId)
        val kept = mutableListOf<BeetSystemEvent>()
        val keysToKeep = linkedSetOf<String>()

        loadKeys(indexKey).forEach { key ->
            val event = prefs.getString(key, null)
                ?.let(BeetJsonCodec::systemEventFromJson)
            if (event != null &&
                event.bootId > 0L &&
                event.timeValid &&
                event.unixSeconds >= cutoffUnixSeconds
            ) {
                kept += event
                keysToKeep += key
            } else {
                prefs.edit().remove(key).apply()
            }
        }
        prefs.edit().putStringSet(indexKey, keysToKeep).apply()
        return kept
    }

    fun saveWateringEvent(deviceId: String, event: BeetWateringEvent) {
        val cutoffUnixSeconds = retentionCutoffUnixSeconds()
        if (!event.timeValid || event.endedAtUnixSeconds < cutoffUnixSeconds) {
            return
        }
        val key = wateringEventKey(deviceId, event.sequenceNumber)
        val indexKey = wateringIndexKey(deviceId)
        val keys = loadKeys(indexKey).toMutableSet()
        keys += key
        prefs.edit()
            .putString(key, BeetJsonCodec.wateringEventToJson(event))
            .putStringSet(indexKey, keys)
            .apply()
        pruneWateringOlderThan(deviceId, cutoffUnixSeconds)
    }

    fun saveSystemEvent(deviceId: String, event: BeetSystemEvent) {
        val cutoffUnixSeconds = retentionCutoffUnixSeconds()
        if (!event.timeValid || event.unixSeconds < cutoffUnixSeconds) {
            return
        }
        val key = systemEventKey(deviceId, event.sequenceNumber)
        val indexKey = systemIndexKey(deviceId)
        val keys = loadKeys(indexKey).toMutableSet()
        keys += key
        prefs.edit()
            .putString(key, BeetJsonCodec.systemEventToJson(event))
            .putStringSet(indexKey, keys)
            .apply()
        pruneSystemOlderThan(deviceId, cutoffUnixSeconds)
    }

    private fun loadKeys(indexKey: String): Set<String> = prefs.getStringSet(indexKey, emptySet()).orEmpty()

    private fun wateringIndexKey(deviceId: String): String = "$deviceId:watering:index"

    private fun systemIndexKey(deviceId: String): String = "$deviceId:system:index"

    private fun wateringEventKey(deviceId: String, sequence: Long): String = "$deviceId:watering:$sequence"

    private fun systemEventKey(deviceId: String, sequence: Long): String = "$deviceId:system:$sequence"

    private fun retentionCutoffUnixSeconds(): Long = (System.currentTimeMillis() / 1000L) - RETENTION_SECONDS

    private fun pruneWateringOlderThan(deviceId: String, cutoffUnixSeconds: Long) {
        val indexKey = wateringIndexKey(deviceId)
        val keysToKeep = linkedSetOf<String>()
        loadKeys(indexKey).forEach { key ->
            val event = prefs.getString(key, null)
                ?.let(BeetJsonCodec::wateringEventFromJson)
            if (event != null && event.timeValid && event.endedAtUnixSeconds >= cutoffUnixSeconds) {
                keysToKeep += key
            } else {
                prefs.edit().remove(key).apply()
            }
        }
        prefs.edit().putStringSet(indexKey, keysToKeep).apply()
    }

    private fun pruneSystemOlderThan(deviceId: String, cutoffUnixSeconds: Long) {
        val indexKey = systemIndexKey(deviceId)
        val keysToKeep = linkedSetOf<String>()
        loadKeys(indexKey).forEach { key ->
            val event = prefs.getString(key, null)
                ?.let(BeetJsonCodec::systemEventFromJson)
            if (event != null && event.timeValid && event.unixSeconds >= cutoffUnixSeconds) {
                keysToKeep += key
            } else {
                prefs.edit().remove(key).apply()
            }
        }
        prefs.edit().putStringSet(indexKey, keysToKeep).apply()
    }

    private companion object {
        private const val RETENTION_SECONDS = 30L * 24L * 60L * 60L
    }
}
