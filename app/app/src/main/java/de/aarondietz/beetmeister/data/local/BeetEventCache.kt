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
            if (event != null && shouldRetainWateringEvent(event, cutoffUnixSeconds)) {
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
            if (event != null && shouldRetainSystemEvent(event, cutoffUnixSeconds)) {
                kept += event
                keysToKeep += key
            } else {
                prefs.edit().remove(key).apply()
            }
        }
        prefs.edit().putStringSet(indexKey, keysToKeep).apply()
        return kept
    }

    fun clearDevice(deviceId: String) {
        val wateringIndexKey = wateringIndexKey(deviceId)
        val systemIndexKey = systemIndexKey(deviceId)
        val editor = prefs.edit()

        loadKeys(wateringIndexKey).forEach { key -> editor.remove(key) }
        loadKeys(systemIndexKey).forEach { key -> editor.remove(key) }
        editor.remove(wateringIndexKey)
        editor.remove(systemIndexKey)
        editor.apply()
    }

    fun saveWateringEvent(deviceId: String, event: BeetWateringEvent) {
        saveWateringEvents(deviceId, listOf(event))
    }

    /**
     * Batched variant: one index rewrite and one prune pass per batch.
     * The cache index is a single StringSet, so per-event saves rebuild the
     * whole set and are quadratic over a backlog sync.
     */
    fun saveWateringEvents(deviceId: String, events: List<BeetWateringEvent>) {
        if (events.isEmpty()) {
            return
        }
        val cutoffUnixSeconds = retentionCutoffUnixSeconds()
        val indexKey = wateringIndexKey(deviceId)
        val keys = loadKeys(indexKey).toMutableSet()
        val editor = prefs.edit()
        events.forEach { event ->
            if (!shouldRetainWateringEvent(event, cutoffUnixSeconds)) {
                return@forEach
            }
            val key = wateringEventKey(deviceId, event.sequenceNumber)
            keys += key
            editor.putString(key, BeetJsonCodec.wateringEventToJson(event))
        }
        editor.putStringSet(indexKey, keys)
        editor.apply()
        pruneWateringOlderThan(deviceId, cutoffUnixSeconds)
    }

    fun saveSystemEvent(deviceId: String, event: BeetSystemEvent) {
        saveSystemEvents(deviceId, listOf(event))
    }

    /** Batched variant; see saveWateringEvents for the rationale. */
    fun saveSystemEvents(deviceId: String, events: List<BeetSystemEvent>) {
        if (events.isEmpty()) {
            return
        }
        val cutoffUnixSeconds = retentionCutoffUnixSeconds()
        val indexKey = systemIndexKey(deviceId)
        val keys = loadKeys(indexKey).toMutableSet()
        val editor = prefs.edit()
        events.forEach { event ->
            if (!shouldRetainSystemEvent(event, cutoffUnixSeconds)) {
                return@forEach
            }
            val key = systemEventKey(deviceId, event.sequenceNumber)
            keys += key
            editor.putString(key, BeetJsonCodec.systemEventToJson(event))
        }
        editor.putStringSet(indexKey, keys)
        editor.apply()
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
            if (event != null && shouldRetainWateringEvent(event, cutoffUnixSeconds)) {
                keysToKeep += key
            } else {
                prefs.edit().remove(key).apply()
            }
        }
        prefs.edit().putStringSet(indexKey, keysToKeep).apply()
    }

    private fun shouldRetainWateringEvent(event: BeetWateringEvent, cutoffUnixSeconds: Long): Boolean =
        event.bootId > 0L && (!event.timeValid || event.endedAtUnixSeconds >= cutoffUnixSeconds)

    private fun pruneSystemOlderThan(deviceId: String, cutoffUnixSeconds: Long) {
        val indexKey = systemIndexKey(deviceId)
        val keysToKeep = linkedSetOf<String>()
        loadKeys(indexKey).forEach { key ->
            val event = prefs.getString(key, null)
                ?.let(BeetJsonCodec::systemEventFromJson)
            if (event != null && shouldRetainSystemEvent(event, cutoffUnixSeconds)) {
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
