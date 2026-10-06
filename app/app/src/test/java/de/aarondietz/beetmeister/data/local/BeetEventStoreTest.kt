package de.aarondietz.beetmeister.data.local

import android.content.Context
import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Plain Application: the store tests need no DI; the real app class would
// start Koin once per sandbox and fail on the second test in this class.
@Config(application = android.app.Application::class)
class BeetEventStoreTest {
    private lateinit var context: Context
    private lateinit var db: BeetEventDatabase
    private lateinit var store: RoomBeetEventStore

    private val deviceId = "AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = BeetEventDatabase.createInMemory(context)
        store = RoomBeetEventStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /*
     * Room forbids main-thread database access; production callers always run
     * on BLE callback or coroutine dispatcher threads. Robolectric executes
     * tests on the main looper, so route every store call through an executor.
     */
    private fun <T> bg(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return executor.submit<T> { block() }.get().also { executor.shutdown() }
    }

    @Test
    fun saveAndLoadWateringRoundTripSortedDescending() {
        bg { store.saveWateringEvents(deviceId, listOf(wateringEvent(seq = 5L), wateringEvent(seq = 7L))) }
        val loaded = bg { store.loadWateringEvents(deviceId) }
        assertEquals(listOf(7L, 5L), loaded.map { event -> event.sequenceNumber })
        val seven = loaded.first { event -> event.sequenceNumber == 7L }
        assertEquals(1200, seven.moistureBeforePercent)
        assertEquals(3300L, seven.bootId)
    }

    @Test
    fun duplicateSequenceIgnoredKeepsFirstWrite() {
        bg { store.saveWateringEvents(deviceId, listOf(wateringEvent(seq = 9L, pair = 1))) }
        bg { store.saveWateringEvents(deviceId, listOf(wateringEvent(seq = 9L, pair = 2))) }
        val loaded = bg { store.loadWateringEvents(deviceId) }
        assertEquals(1, loaded.size)
        assertEquals(1, loaded.first().pairIndex)
    }

    @Test
    fun watermarkDefaultsAndRoundTrip() {
        assertEquals(0L, bg { store.loadSyncWatermark(deviceId, "watering") })
        bg { store.saveSyncWatermark(deviceId, "watering", 4_000L) }
        bg { store.saveSyncWatermark(deviceId, "watering", 4_100L) }
        assertEquals(4_100L, bg { store.loadSyncWatermark(deviceId, "watering") })
        assertEquals(0L, bg { store.loadSyncWatermark(deviceId, "system") })
    }

    @Test
    fun clearDeviceRemovesEventsAndWatermarks() {
        bg {
            store.saveWateringEvents(deviceId, listOf(wateringEvent(seq = 1L)))
            store.saveSystemEvents(deviceId, listOf(systemEvent(seq = 1L)))
            store.saveSyncWatermark(deviceId, "watering", 1L)
        }
        bg { store.clearDevice(deviceId) }
        assertTrue(bg { store.loadWateringEvents(deviceId) }.isEmpty())
        assertTrue(bg { store.loadSystemEvents(deviceId) }.isEmpty())
        assertEquals(0L, bg { store.loadSyncWatermark(deviceId, "watering") })
    }

    @Test
    fun retentionDropsInvalidBootAndExpiredEvents() {
        val now = System.currentTimeMillis()
        val old = now / 1000L - 31L * 24L * 60L * 60L
        val fresh = now / 1000L - 60L
        val events = listOf(
            wateringEvent(seq = 1L, boot = 0L),                // invalid boot: dropped
            wateringEvent(seq = 2L, ended = old),               // expired: dropped
            wateringEvent(seq = 3L, ended = fresh),             // retained
            wateringEvent(seq = 4L, started = 0L, ended = 0L),  // time-invalid: retained forever
        )
        val fixedStore = RoomBeetEventStore(db, nowMillis = { now })
        bg { fixedStore.saveWateringEvents(deviceId, events) }
        val loaded = bg { fixedStore.loadWateringEvents(deviceId) }
        assertEquals(setOf(3L, 4L), loaded.map { event -> event.sequenceNumber }.toSet())
    }

    @Test
    fun migrationImportsEventsWatermarksAndIsIdempotent() {
        val prefs = context.getSharedPreferences("migration-test-prefs", Context.MODE_PRIVATE)
        val event = wateringEvent(seq = 11L)
        val sysEvent = systemEvent(seq = 3L)
        val eventKey = "$deviceId:watering:11"
        val sysKey = "$deviceId:system:3"
        prefs.edit()
            .putStringSet("$deviceId:watering:index", setOf(eventKey))
            .putString(eventKey, BeetJsonCodec.wateringEventToJson(event))
            .putStringSet("$deviceId:system:index", setOf(sysKey))
            .putString(sysKey, BeetJsonCodec.systemEventToJson(sysEvent))
            .putLong("$deviceId:watering:sync-watermark", 11L)
            .apply()

        bg { migrateLegacyEventPrefs(prefs, db) }

        assertEquals(listOf(11L), bg { store.loadWateringEvents(deviceId) }.map { e -> e.sequenceNumber })
        assertEquals(listOf(3L), bg { store.loadSystemEvents(deviceId) }.map { e -> e.sequenceNumber })
        assertEquals(11L, bg { store.loadSyncWatermark(deviceId, "watering") })

        // Legacy entries removed, marker present.
        assertFalse(prefs.all.containsKey(eventKey))
        assertFalse(prefs.all.containsKey("$deviceId:watering:index"))
        assertTrue(prefs.getBoolean("room-event-migration:v1", false))

        // Second run is a no-op even after new legacy data appears.
        prefs.edit().putLong("$deviceId:system:sync-watermark", 77L).apply()
        bg { migrateLegacyEventPrefs(prefs, db) }
        assertEquals(0L, bg { store.loadSyncWatermark(deviceId, "system") })
    }

    private fun wateringEvent(
        seq: Long,
        pair: Int = 2,
        boot: Long = 3300L,
        started: Long = System.currentTimeMillis() / 1000L - 120L,
        ended: Long = System.currentTimeMillis() / 1000L - 60L,
    ): BeetWateringEvent =
        BeetWateringEvent(
            sequenceNumber = seq,
            pairIndex = pair,
            bootId = boot,
            triggerSource = 1,
            startedAtUnixSeconds = started,
            endedAtUnixSeconds = ended,
            moistureBeforePercent = 1200,
            moistureAfterPercent = 900,
            sensorBeforeMillivolts = 2000,
            sensorAfterMillivolts = 1500,
            requestedDurationSeconds = 30,
            actualDurationSeconds = 28,
            stopReason = 0,
            blockReason = 0,
            batteryStartMillivolts = 4100,
            batteryEndMillivolts = 4080,
            startedUptimeSeconds = 100L,
            endedUptimeSeconds = 130L,
        )

    private fun systemEvent(seq: Long): BeetSystemEvent =
        BeetSystemEvent(
            sequenceNumber = seq,
            eventType = "BLE_CONNECTED",
            reason = 0,
            bootId = 3300L,
            uptimeSeconds = 500L,
            unixSeconds = System.currentTimeMillis() / 1000L - 10L,
            batteryMillivolts = 4000,
            peerAddress = "11:22:33:44:55:66",
            peerAddressType = 1,
            knownPeer = true,
            detail = 0L,
        )
}
