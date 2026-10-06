package de.aarondietz.beetmeister.data.local

import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent

/**
 * Durable local store for controller events and per-device burst-sync
 * watermarks. All methods are synchronous and must be called from background
 * threads (BLE callback threads or coroutine dispatchers), matching the
 * legacy SharedPreferences cache's usage context.
 */
internal interface BeetEventStore {
    fun loadWateringEvents(deviceId: String): List<BeetWateringEvent>

    fun loadSystemEvents(deviceId: String): List<BeetSystemEvent>

    fun saveWateringEvents(deviceId: String, events: List<BeetWateringEvent>)

    fun saveSystemEvents(deviceId: String, events: List<BeetSystemEvent>)

    /**
     * Highest event/system-event sequence number confirmed synced for
     * [deviceId] and [kindKey] ("watering"/"system"). Burst sync resumes
     * here instead of re-streaming the whole controller ring each connect.
     */
    fun loadSyncWatermark(deviceId: String, kindKey: String): Long

    fun saveSyncWatermark(deviceId: String, kindKey: String, seq: Long)

    fun clearDevice(deviceId: String)
}
