package de.aarondietz.beetmeister.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import de.aarondietz.beetmeister.ui.NavigationSuiteTestTags
import de.aarondietz.beetmeister.ui.feature.connection.ConnectionGateTestTags
import de.aarondietz.beetmeister.ui.feature.overview.OverviewTestTags

/**
 * Shared connect-once fixture for the E2E test classes.
 *
 * Per the harness plan, the class-shared connect happens in the
 * test class's `@Before` exactly once for the whole class (so the
 * BLE connect is amortized across all sibling @Tests). The fixture
 * tracks an `isConnected` flag so subsequent @Before invocations
 * are a no-op; this keeps the test-class code uniform and matches
 * the plan's "one @Before per class" intent.
 *
 * The fixture also owns the per-class [E2eScreenshotHelper] so each
 * test class can call `fixture.screenshots.captureStep("afterConnect")`
 * in its own logic without re-wiring.
 *
 * The connect path follows the on-device flow the orchestrator's
 * fresh_install / settings_update suites rely on:
 *  1. Wait for the ConnectionGate to render.
 *  2. Tap the Scan button.
 *  3. Wait for the first device card to appear (or fail on timeout).
 *  4. Tap the first device card's Connect button.
 *  5. Wait for the NavigationSuiteScaffold to render (any nav item
 *     is sufficient; we use the Settings nav-item tag because it
 *     is the most semantically anchored).
 *  6. Take an after-connect screenshot.
 *
 * **Cascade policy (resolved P5):** if the class-shared connect
 * drops mid-suite, the suite **reports FAIL — no retry is added
 * to `@Before`**. A cascade is a genuine surfaced failure, not
 * suppressed. The Phase 2 deferral is closed by the design of
 * this fixture: it has a single connect path, no retry, no
 * reconnect-once-it-dropped, and no `@After` cleanup.
 */
internal class E2eConnectionFixture(
    val composeRule: ComposeTestRule,
    testSlug: String,
) {
    val screenshots: E2eScreenshotHelper = E2eScreenshotHelper(testSlug = testSlug)

    @Volatile
    private var connected: Boolean = false

    /**
     * Idempotent class-shared connect. The first call runs the full
     * scan/connect/wait path; subsequent calls (one per remaining
     * `@Before` invocation before each `@Test`) are a no-op.
     */
    fun connectOnce() {
        if (connected) return
        tapScan()
        assertFirstDeviceVisible()
        tapFirstDeviceConnect()
        assertConnected()
        screenshots.captureStep("afterConnect")
        connected = true
    }

    private fun tapScan() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule
                .onAllNodesWithTag(ConnectionGateTestTags.ScanButton)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(ConnectionGateTestTags.ScanButton).performClick()
    }

    private fun assertFirstDeviceVisible() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule
                .onAllNodesWithTag(ConnectionGateTestTags.DeviceCard)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onAllNodesWithTag(ConnectionGateTestTags.DeviceCard)
            .assertCountEquals(1)
    }

    private fun tapFirstDeviceConnect() {
        composeRule
            .onAllNodesWithTag(ConnectionGateTestTags.DeviceConnectButton)
            .onFirst()
            .performClick()
    }

    private fun assertConnected() {
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule
                .onAllNodesWithTag(NavigationSuiteTestTags.SettingsNavItem)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // The Overview screen is the default top-level destination after
        // connect, so its list tag is also a strong "we are past the
        // gate" signal. We don't assert on it because some tests will
        // navigate to Settings first.
        composeRule
            .onNodeWithTag(OverviewTestTags.List)
            .assertIsDisplayed()
    }
}
