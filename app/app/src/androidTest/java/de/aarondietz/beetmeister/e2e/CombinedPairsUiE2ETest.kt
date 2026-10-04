package de.aarondietz.beetmeister.e2e

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import de.aarondietz.beetmeister.MainActivity
import de.aarondietz.beetmeister.data.repository.BeetRepository
import de.aarondietz.beetmeister.e2e.robots.OverviewRobot
import de.aarondietz.beetmeister.e2e.robots.PairDetailRobot
import org.junit.AfterClass
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import org.koin.core.context.GlobalContext

/**
 * End-to-End test suite exercising combined pairs (sensor sharing) through the UI.
 *
 * Sequence:
 * 1. Combine Pair 2 with lead Pair 1 (Group formation).
 * 2. Expand group with Pair 3 (Pair 1 leads Pair 2 and Pair 3).
 * 3. Remove Pair 2 from group (Group shrinks, Pair 1 leads Pair 3).
 * 4. Remove Pair 3 from group (Group dissolved, all pairs individual again).
 *
 * Tests run in deterministic alphabetical order via [MethodSorters.NAME_ASCENDING].
 * Uses [E2eConnectionFixture.connectOnce] to maintain the single BLE connection
 * across tests without tearing down or reconnecting.
 */
@E2e
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CombinedPairsUiE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var fixture: E2eConnectionFixture
    private lateinit var overviewRobot: OverviewRobot
    private lateinit var pairDetailRobot: PairDetailRobot
    private lateinit var repository: BeetRepository

    @Before
    fun setUp() {
        grantRuntimePermissions()
        val koin = GlobalContext.get()
        repository = koin.get()

        fixture = E2eConnectionFixture(composeRule, testSlug = "combinedPairsUi")
        fixture.connectOnce()

        overviewRobot = OverviewRobot(composeRule)
        pairDetailRobot = PairDetailRobot(composeRule)

        // Reset combined state on Pair 1 before test 1 starts to ensure clean initial slate
        if (cleanSlateNeeded) {
            cleanSlateNeeded = false
            repository.storePairCombined(1, 0)
            composeRule.waitUntil(timeoutMillis = 10_000) {
                val combined = repository.state.value.pairCombined[1]
                combined == null || combined.followersMask == 0
            }
        }
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

    @Test(timeout = 60_000)
    fun test_01_combinePair2WithLeadPair1() {
        // Tap Pair 2 details (index 1)
        overviewRobot.tapPairDetails(1)

        // Select shared sensor (default lead is Pair 1)
        pairDetailRobot.selectSharedSensor()
        pairDetailRobot.assertSharedSensorIsSelected()

        // Wait for repository state to reflect Pair 1 leading Pair 2 (bit 1 = 2)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            val combined = repository.state.value.pairCombined[1]
            combined != null && (combined.followersMask and (1 shl 1)) != 0
        }

        pairDetailRobot.back()

        val leadName = repository.state.value.pairNames[1] ?: "Pair 1"
        // Verify Overview badges
        overviewRobot.assertPairSensorSourceBadgeEquals(0, "Shared (1)")
        overviewRobot.assertPairSensorSourceBadgeEquals(1, "Follows $leadName")
    }

    @Test(timeout = 60_000)
    fun test_02_expandGroupWithPair3() {
        // Tap Pair 3 details (index 2)
        overviewRobot.tapPairDetails(2)

        // Select shared sensor (default lead is Pair 1)
        pairDetailRobot.selectSharedSensor()
        pairDetailRobot.assertSharedSensorIsSelected()

        // Wait for repository state: Pair 1 leads both Pair 2 and Pair 3 (followersMask = 2 | 4 = 6)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            val combined = repository.state.value.pairCombined[1]
            combined != null && combined.followersMask == 6
        }

        pairDetailRobot.back()

        val leadName = repository.state.value.pairNames[1] ?: "Pair 1"
        // Verify Overview badges
        overviewRobot.assertPairSensorSourceBadgeEquals(0, "Shared (2)")
        overviewRobot.assertPairSensorSourceBadgeEquals(2, "Follows $leadName")
    }

    @Test(timeout = 60_000)
    fun test_03_removePair2FromGroup() {
        // Tap Pair 2 details (index 1)
        overviewRobot.tapPairDetails(1)

        // Switch back to dedicated sensor
        pairDetailRobot.selectDedicatedSensor()

        // Wait for repository state: Pair 1 leads only Pair 3 (followersMask = 4)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            val combined = repository.state.value.pairCombined[1]
            combined != null && combined.followersMask == 4
        }

        pairDetailRobot.assertDedicatedSensorIsSelected()
        pairDetailRobot.back()

        val leadName = repository.state.value.pairNames[1] ?: "Pair 1"
        // Verify Overview badges: Pair 1 has Shared (1), Pair 3 has Follows Pair 1
        overviewRobot.assertPairSensorSourceBadgeEquals(0, "Shared (1)")
        overviewRobot.assertPairSensorSourceBadgeEquals(2, "Follows $leadName")
    }

    @Test(timeout = 60_000)
    fun test_04_removePair3AndDissolveGroup() {
        // Tap Pair 3 details (index 2)
        overviewRobot.tapPairDetails(2)

        // Switch back to dedicated sensor
        pairDetailRobot.selectDedicatedSensor()

        // Wait for repository state: Pair 1 has no followers (followersMask = 0)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            val combined = repository.state.value.pairCombined[1]
            combined == null || combined.followersMask == 0
        }

        pairDetailRobot.assertDedicatedSensorIsSelected()
        pairDetailRobot.back()

        // Verify Overview badges: no Shared or Follows badges remaining
        overviewRobot.assertNoPairSensorSourceBadge("Shared")
        overviewRobot.assertNoPairSensorSourceBadge("Follows")
    }

    companion object {
        private var cleanSlateNeeded = true

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            // Safety cleanup: ensure any followers on Pair 1 are cleared
            try {
                val koin = GlobalContext.getOrNull()
                val repo = koin?.getOrNull<BeetRepository>()
                repo?.storePairCombined(1, 0)
            } catch (_: Exception) {
            }
        }
    }
}
