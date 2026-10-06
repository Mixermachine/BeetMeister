package de.aarondietz.beetmeister.data.local

import android.content.Context
import android.content.SharedPreferences
import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Room-backed event store. Replaces the legacy SharedPreferences StringSet
 * cache: batch writes become single transactions, dedupe is enforced by the
 * (deviceId, seqNo) primary key, and queries are indexed instead of rebuilding
 * whole index sets per save.
 */
internal class RoomBeetEventStore(
    private val db: BeetEventDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : BeetEventStore {
    private val dao: BeetEventDao get() = db.eventDao()

    /*
     * Phase 1 keeps the legacy blocking call contract (SharedPreferences was
     * also synchronous from whatever thread the coordinator used, including
     * the main-dispatched sync coroutine). Room rejects main-thread access, so
     * every operation is forced onto the IO dispatcher here. Phase 2 lifts the
     * interface to suspend functions and removes runBlocking.
     */
    private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }

    override fun loadWateringEvents(deviceId: String): List<BeetWateringEvent> =
        io { dao.loadWatering(deviceId).map { entity -> entity.toModel() } }

    override fun loadSystemEvents(deviceId: String): List<BeetSystemEvent> =
        io { dao.loadSystem(deviceId).map { entity -> entity.toModel() } }

    override fun saveWateringEvents(deviceId: String, events: List<BeetWateringEvent>) {
        if (events.isEmpty()) return
        val cutoff = retentionCutoffUnixSeconds()
        val rows = events
            .filter { event -> retainWateringEvent(event, cutoff) }
            .map { event -> event.toEntity(deviceId) }
        if (rows.isNotEmpty()) io { dao.saveWateringBatch(rows, cutoff) }
    }

    override fun saveSystemEvents(deviceId: String, events: List<BeetSystemEvent>) {
        if (events.isEmpty()) return
        val cutoff = retentionCutoffUnixSeconds()
        val rows = events
            .filter { event -> retainSystemEvent(event, cutoff) }
            .map { event -> event.toEntity(deviceId) }
        if (rows.isNotEmpty()) io { dao.saveSystemBatch(rows, cutoff) }
    }

    override fun loadSyncWatermark(deviceId: String, kindKey: String): Long =
        io { dao.watermark(deviceId, kindKey) ?: 0L }

    override fun saveSyncWatermark(deviceId: String, kindKey: String, seq: Long) {
        io { dao.putWatermark(BeetSyncStateEntity(deviceId = deviceId, kind = kindKey, watermarkSeq = seq)) }
    }

    override fun clearDevice(deviceId: String) {
        io { dao.clearDevice(deviceId) }
    }

    private fun retentionCutoffUnixSeconds(): Long = (nowMillis() / 1000L) - RETENTION_SECONDS

    private fun retainWateringEvent(event: BeetWateringEvent, cutoffUnixSeconds: Long): Boolean =
        event.bootId > 0L && (!event.timeValid || event.endedAtUnixSeconds >= cutoffUnixSeconds)

    private fun retainSystemEvent(event: BeetSystemEvent, cutoffUnixSeconds: Long): Boolean =
        event.bootId > 0L && (!event.timeValid || event.unixSeconds >= cutoffUnixSeconds)

    companion object {
        private const val RETENTION_SECONDS = 30L * 24L * 60L * 60L
    }
}

/**
 * One-shot import of the legacy SharedPreferences event cache into Room.
 * Key layout mirrors the removed BeetEventCache implementation:
 *   "<deviceId>:watering:index" / "<deviceId>:system:index"  -> StringSet of event keys
 *   "<deviceId>:watering:<seq>" / "<deviceId>:system:<seq>"  -> Moshi JSON records
 *   "<deviceId>:<kind>:sync-watermark"                       -> Long watermark
 * After a successful import the legacy entries are deleted; the completion
 * marker survives so the migration never runs twice.
 */
internal fun migrateLegacyEventPrefs(
    prefs: SharedPreferences,
    db: BeetEventDatabase,
) {
    if (prefs.getBoolean(MIGRATION_MARKER, false)) return
    val dao = db.eventDao()
    val cutoffUnixSeconds = (System.currentTimeMillis() / 1000L) - RETENTION_SECONDS
    val all = prefs.all
    var failure: Throwable? = null

    all.keys
        .filter { key -> key.endsWith(":watering:index") || key.endsWith(":system:index") }
        .forEach { indexKey ->
            val deviceId = indexKey.substringBefore(":watering:index").substringBefore(":system:index")
            val keys = prefs.getStringSet(indexKey, emptySet()).orEmpty()
            val isWatering = indexKey.endsWith(":watering:index")
            val wateringRows = mutableListOf<BeetWateringEventEntity>()
            val systemRows = mutableListOf<BeetSystemEventEntity>()
            keys.forEach { key ->
                val json = prefs.getString(key, null) ?: return@forEach
                if (isWatering) {
                    BeetJsonCodec.wateringEventFromJson(json)
                        ?.takeIf { event ->
                            event.bootId > 0L && (!event.timeValid || event.endedAtUnixSeconds >= cutoffUnixSeconds)
                        }
                        ?.let { event -> wateringRows += event.toEntity(deviceId) }
                } else {
                    BeetJsonCodec.systemEventFromJson(json)
                        ?.takeIf { event ->
                            event.bootId > 0L && (!event.timeValid || event.unixSeconds >= cutoffUnixSeconds)
                        }
                        ?.let { event -> systemRows += event.toEntity(deviceId) }
                }
            }
            runCatching {
                if (wateringRows.isNotEmpty()) dao.insertWatering(wateringRows)
                if (systemRows.isNotEmpty()) dao.insertSystem(systemRows)
            }.onFailure { error -> failure = error }
        }

    if (failure != null) return

    all.keys
        .filter { key -> key.endsWith(":sync-watermark") }
        .forEach { key ->
            // Legacy key format "<deviceId>:<kind>:sync-watermark"; deviceIds are
            // MAC-style and contain colons, so parse from the right.
            val core = key.removeSuffix(":sync-watermark")
            val kind = core.substringAfterLast(":")
            val deviceId = core.substringBeforeLast(":")
            if (kind == "watering" || kind == "system") {
                val seq = prefs.getLong(key, 0L)
                if (seq > 0L) {
                    runCatching { dao.putWatermark(BeetSyncStateEntity(deviceId = deviceId, kind = kind, watermarkSeq = seq)) }
                }
            }
        }

    val editor = prefs.edit()
    all.keys
        .filter { key ->
            key.contains(":watering:") || key.contains(":system:") || key.endsWith(":sync-watermark")
        }
        .filter { key -> key != MIGRATION_MARKER }
        .forEach { key -> editor.remove(key) }
    editor.putBoolean(MIGRATION_MARKER, true)
    editor.apply()
}

private const val MIGRATION_MARKER = "room-event-migration:v1"
private const val RETENTION_SECONDS = 30L * 24L * 60L * 60L

/** Process-wide store provider; first access performs the legacy migration. */
internal object BeetEventStores {
    @Volatile
    private var instance: BeetEventStore? = null

    fun get(context: Context): BeetEventStore {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val app = context.applicationContext
            val db = BeetEventDatabase.create(app)
            runBlocking(Dispatchers.IO) {
                migrateLegacyEventPrefs(
                    app.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE),
                    db,
                )
            }
            return RoomBeetEventStore(db).also { instance = it }
        }
    }

    /** Test seam: swap in an in-memory-backed store. */
    fun setForTests(store: BeetEventStore?) {
        instance = store
    }

    private const val LEGACY_PREFS_NAME = "beetmeister_event_cache"
}
