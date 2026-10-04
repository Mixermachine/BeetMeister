package de.aarondietz.beetmeister.e2e

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import de.aarondietz.beetmeister.MainActivity
import de.aarondietz.beetmeister.data.repository.BeetRepository
import de.aarondietz.beetmeister.e2e.robots.ConnectionGateRobot
import de.aarondietz.beetmeister.logging.BeetLog
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

@E2e
class ConnectLatencyE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var repository: BeetRepository
    private lateinit var gateRobot: ConnectionGateRobot

    @Before
    fun setUp() {
        grantRuntimePermissions()
        val koin = GlobalContext.get()
        repository = koin.get()
        gateRobot = ConnectionGateRobot(composeRule)
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

    @Test(timeout = 300_000)
    fun measureRepeatedInitialConnectLatency() {
        val iterations = 5
        val results = mutableListOf<ConnectMetrics>()

        for (i in 1..iterations) {
            android.util.Log.i(TAG, "========== STARTING ITERATION $i / $iterations ==========")

            // Ensure clean disconnect
            if (repository.state.value.connection.phase == BeetConnectionPhase.Connected) {
                android.util.Log.i(TAG, "Iteration $i: Disconnecting existing session...")
                repository.disconnect()
                composeRule.waitUntil(timeoutMillis = 15_000) {
                    repository.state.value.connection.phase != BeetConnectionPhase.Connected
                }
                // Allow Bluetooth stack and NimBLE to complete disconnection and resume advertising
                Thread.sleep(1_500L)
            }

            val t0 = System.currentTimeMillis()
            var t1_scanStarted = 0L
            var t2_deviceDiscovered = 0L
            var t3_connectInitiated = 0L
            var t4_discoveringServices = 0L
            var t5_syncing = 0L
            var t6_connected = 0L

            t1_scanStarted = System.currentTimeMillis()
            android.util.Log.i(TAG, "Iteration $i: Starting scan...")
            repository.startScan(clearResults = true)

            // Wait for device discovery
            composeRule.waitUntil(timeoutMillis = 30_000) {
                val found = repository.state.value.discoveredDevices.isNotEmpty()
                if (found && t2_deviceDiscovered == 0L) {
                    t2_deviceDiscovered = System.currentTimeMillis()
                }
                found
            }

            val targetDevice = repository.state.value.discoveredDevices.first()
            val scanDuration = t2_deviceDiscovered - t1_scanStarted
            android.util.Log.i(TAG, "Iteration $i: Discovered ${targetDevice.name} (${targetDevice.address}) in $scanDuration ms")

            // Connect
            t3_connectInitiated = System.currentTimeMillis()
            android.util.Log.i(TAG, "Iteration $i: Initiating connect to ${targetDevice.address}...")
            repository.connect(targetDevice.address)

            // Track phases
            composeRule.waitUntil(timeoutMillis = 60_000) {
                val phase = repository.state.value.connection.phase
                val now = System.currentTimeMillis()
                if (phase == BeetConnectionPhase.DiscoveringServices && t4_discoveringServices == 0L) {
                    t4_discoveringServices = now
                    android.util.Log.i(TAG, "Iteration $i: Phase -> DiscoveringServices in ${now - t3_connectInitiated} ms")
                }
                if (phase == BeetConnectionPhase.Syncing && t5_syncing == 0L) {
                    t5_syncing = now
                    android.util.Log.i(TAG, "Iteration $i: Phase -> Syncing in ${now - t3_connectInitiated} ms")
                }
                if (phase == BeetConnectionPhase.Connected && t6_connected == 0L) {
                    t6_connected = now
                    android.util.Log.i(TAG, "Iteration $i: Phase -> Connected in ${now - t3_connectInitiated} ms")
                }
                phase == BeetConnectionPhase.Connected
            }

            val metrics = ConnectMetrics(
                iteration = i,
                scanDurationMs = scanDuration,
                connectToServicesMs = if (t4_discoveringServices > 0) t4_discoveringServices - t3_connectInitiated else -1,
                servicesToSyncingMs = if (t5_syncing > 0 && t4_discoveringServices > 0) t5_syncing - t4_discoveringServices else -1,
                syncingToConnectedMs = if (t6_connected > 0 && t5_syncing > 0) t6_connected - t5_syncing else -1,
                totalConnectDurationMs = t6_connected - t3_connectInitiated,
                totalFromStartMs = t6_connected - t0,
            )
            results.add(metrics)

            android.util.Log.i(
                TAG,
                "Iteration $i FINISHED: Total=${metrics.totalFromStartMs}ms [Scan=${metrics.scanDurationMs}ms, GattConnect=${metrics.connectToServicesMs}ms, Services=${metrics.servicesToSyncingMs}ms, Sync=${metrics.syncingToConnectedMs}ms, ConnectOnly=${metrics.totalConnectDurationMs}ms]",
            )
        }

        android.util.Log.i(TAG, "========== SUMMARY OF CONNECT LATENCIES ==========")
        for (r in results) {
            android.util.Log.i(
                TAG,
                "Iter ${r.iteration}: Total=${r.totalFromStartMs}ms (Scan: ${r.scanDurationMs}ms, GattConnect: ${r.connectToServicesMs}ms, ServDisc: ${r.servicesToSyncingMs}ms, Sync: ${r.syncingToConnectedMs}ms, ConnectOnly: ${r.totalConnectDurationMs}ms)",
            )
        }
        val avgTotal = results.map { it.totalFromStartMs }.average().toLong()
        val minTotal = results.minOf { it.totalFromStartMs }
        val maxTotal = results.maxOf { it.totalFromStartMs }
        val avgConnectOnly = results.map { it.totalConnectDurationMs }.average().toLong()
        val avgScan = results.map { it.scanDurationMs }.average().toLong()
        android.util.Log.i(TAG, "SUMMARY: AvgTotal=${avgTotal}ms (Min=${minTotal}ms, Max=${maxTotal}ms), AvgScan=${avgScan}ms, AvgConnectOnly=${avgConnectOnly}ms")
    }

    data class ConnectMetrics(
        val iteration: Int,
        val scanDurationMs: Long,
        val connectToServicesMs: Long,
        val servicesToSyncingMs: Long,
        val syncingToConnectedMs: Long,
        val totalConnectDurationMs: Long,
        val totalFromStartMs: Long,
    )

    companion object {
        private const val TAG = "CONNECT_LATENCY"
    }
}
