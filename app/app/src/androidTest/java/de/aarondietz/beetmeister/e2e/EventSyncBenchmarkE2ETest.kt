package de.aarondietz.beetmeister.e2e

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import de.aarondietz.beetmeister.MainActivity
import de.aarondietz.beetmeister.data.repository.BeetRepository
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

@E2e
class EventSyncBenchmarkE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var repository: BeetRepository
    private lateinit var fixture: E2eConnectionFixture

    @Before
    fun setUp() {
        grantRuntimePermissions()
        val koin = GlobalContext.get()
        repository = koin.get()
        fixture = E2eConnectionFixture(composeRule, "event_sync_benchmark")
    }

    private fun grantRuntimePermissions() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pkg = "de.aarondietz.beetmeister"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            uiAutomation.grantRuntimePermission(pkg, "android.permission.BLUETOOTH_SCAN")
            uiAutomation.grantRuntimePermission(pkg, "android.permission.BLUETOOTH_CONNECT")
        }
        uiAutomation.grantRuntimePermission(pkg, "android.permission.ACCESS_FINE_LOCATION")
        uiAutomation.grantRuntimePermission(pkg, "android.permission.ACCESS_COARSE_LOCATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            uiAutomation.grantRuntimePermission(pkg, "android.permission.POST_NOTIFICATIONS")
        }
    }

    @Test(timeout = 600_000)
    fun benchmarkEventSyncThroughput() {
        android.util.Log.i(TAG, "Starting Event Sync Throughput Benchmark on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT})")

        // 1. Establish connection via robust fixture (handles pairing/UI gates)
        fixture.connectOnce()

        android.util.Log.i(TAG, "Connected successfully. Waiting for any initial sync to settle...")
        // A device without a sync watermark must first stream the full controller
        // rings (~7000 events at burst rate); allow several minutes on first run.
        composeRule.waitUntil(timeoutMillis = 300_000) {
            !repository.state.value.eventSync.active
        }

        // 2. Inject synthetic events in small chunks.
        // The watering ring currently holds ~5000 records and near-capacity NVS writes
        // (sector erase on wrap) can exceed the 7s runtime command budget when chunks are large.
        val targetWatering = 300
        val targetSystem = 150
        val chunks = buildList {
            repeat(targetWatering / 100) { add(100 to 50) }
        }
        android.util.Log.i(TAG, "Injecting synthetic events ($targetWatering watering, $targetSystem system) in ${chunks.size} chunks...")
        chunks.forEachIndexed { index, (watering, system) ->
            val synthJson = "{\"cmd\":\"generate_synthetic_events\",\"data\":{\"watering_count\":$watering,\"system_count\":$system}}"
            val genResult = repository.sendRawCommand(synthJson)
            android.util.Log.i(TAG, "Synthetic chunk $index result: status=${genResult.status}, reason=${genResult.reason}")
        }

        // 3. Wait deterministically for the firmware background drain to finish.
        //    Drain rate is 10 watering + 10 system per 50 ms controller tick.
        val drainMs = (maxOf(targetWatering, targetSystem).toLong() / 10L + 5L) * 50L
        android.util.Log.i(TAG, "Waiting ${drainMs} ms for firmware synthetic drain to settle...")
        Thread.sleep(drainMs + 1000L)

        // 4. Clear local SQLite/cached events in repository state to measure clean sync over BLE
        repository.clearLocalEvents()

        // 5. Trigger event sync and measure exact timing
        android.util.Log.i(TAG, "Triggering fresh event sync...")
        val tSyncStart = System.currentTimeMillis()
        repository.refreshEvents()

        // Wait for sync to become active
        composeRule.waitUntil(timeoutMillis = 10_000) {
            repository.state.value.eventSync.active
        }

        var maxTransferred = 0
        var totalToSync = 0
        composeRule.waitUntil(timeoutMillis = 600_000) {
            val sync = repository.state.value.eventSync
            if (sync.total > totalToSync) totalToSync = sync.total
            if (sync.transferred > maxTransferred) {
                maxTransferred = sync.transferred
                if (maxTransferred % 100 == 0) {
                    val elapsed = (System.currentTimeMillis() - tSyncStart) / 1000.0
                    val rate = if (elapsed > 0) maxTransferred / elapsed else 0.0
                    android.util.Log.i(TAG, "Sync progress: $maxTransferred / $totalToSync events (elapsed: ${String.format("%.1f", elapsed)}s, current rate: ${String.format("%.1f", rate)} events/s)")
                }
            }
            !sync.active && maxTransferred > 0
        }
        val tSyncEnd = System.currentTimeMillis()
        val totalElapsedSec = (tSyncEnd - tSyncStart) / 1000.0
        val throughput = if (totalElapsedSec > 0) maxTransferred / totalElapsedSec else 0.0

        android.util.Log.i(TAG, "========== BENCHMARK RESULTS ==========")
        android.util.Log.i(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        android.util.Log.i(TAG, "Total Events Synced: $maxTransferred (out of $totalToSync)")
        android.util.Log.i(TAG, "Total Elapsed Time: ${String.format("%.2f", totalElapsedSec)} seconds")
        android.util.Log.i(TAG, "Average Throughput: ${String.format("%.1f", throughput)} events/second")
        android.util.Log.i(TAG, "Average Per-Event Latency: ${String.format("%.2f", (totalElapsedSec * 1000.0) / maxTransferred)} ms/event")
        android.util.Log.i(TAG, "=======================================")
    }

    companion object {
        private const val TAG = "EVENT_SYNC_BENCHMARK"
    }
}
