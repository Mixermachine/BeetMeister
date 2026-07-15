package de.aarondietz.beetmeister.e2e.robots

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.aarondietz.beetmeister.ui.feature.connection.ConnectionGateTestTags
import de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateTestTags

/**
 * Robot for the BLE ConnectionGate (the pre-connect screen the user
 * sees when the app is launched but no controller is bound yet).
 *
 * The gate has four interaction surfaces:
 *  - [tapScan]: start a BLE scan.
 *  - [assertDeviceVisible]: assert the **expected** device (matched
 *    by name from the `expected_device_name` `am instrument -e`
 *    extra) is in the device list. Falls back to "exactly one card
 *    visible" when the extra is not set, which assumes single-
 *    controller BLE range.
 *  - [tapConnect]: tap the connect button on the first device card.
 *    Pairs with [assertDeviceVisible] to ensure the expected device
 *    is the one being connected to.
 *  - [assertConnected]: assert the connection completed by waiting
 *    for the NavigationSuiteScaffold to appear (its first nav-item
 *    tag is the strongest "gate is gone" signal).
 *
 * The expected device name is required when more than one
 * controller is in BLE range, which the harness explicitly
 * disambiguates per the FreshInstall step 4 from the plan. The
 * orchestrator (P4) sets `-e expected_device_name <id>`.
 */
internal class ConnectionGateRobot(
    private val composeRule: ComposeTestRule,
) {
    /**
     * Expected controller BLE name passed by the orchestrator via
     * `-e expected_device_name <id>`. May be null only when the
     * BLE range is known to have exactly one controller, in which
     * case [assertDeviceVisible] still verifies a single card is
     * present.
     */
    private val expectedDeviceName: String? =
        InstrumentationRegistry.getArguments().getString(EXTRA_EXPECTED_DEVICE_NAME)

    /**
     * Taps the [ConnectionGateTestTags.ScanButton] if the gate
     * is still showing; no-op if the post-connect shell
     * ([NavigationSuiteTestTags.SettingsNavItem]) is already
     * up (auto-connect race fix, P5 finding SUB #R6).
     *
     * The wait condition accepts EITHER state: the gate's
     * ScanButton OR the post-connect shell's SettingsNavItem.
     * The original implementation only waited for the
     * ScanButton, which races with the app's BLE auto-connect:
     * the scan finds the controller in <1 s, the 900 ms
     * stable-Connected window passes, the gate hides BEFORE
     * the wait fires, the ScanButton never appears, and the
     * test times out at 30 s. The fix is to accept both
     * end states — the original click path stays correct
     * when the gate is up, the no-op path covers the
     * post-connect-already case.
     */
    fun tapScan() {
        // P5 finding SUB #R7: the BLE auto-connect is fast enough
        // that the gate can disappear BEFORE the test's first
        // `waitUntil` fires. Additionally, the `createAndroidComposeRule`
        // semantics tree is populated via a `SnapshotStateObserver`
        // that synchronises state changes from the app's coroutines
        // (the BLE state machine) to the test thread's view of the
        // tree. Without a prior `waitForIdle()`, the test thread can
        // query the tree while the app is mid-recomposition, the
        // `mergeDescendants` semantics node for the NavigationBarItem
        // may not yet be wired up, and `onAllNodesWithTag` returns
        // empty even though the post-connect shell is rendered.
        // The smoke-gate's `MaintenanceUpdateLiveActivityInstrumentationTest`
        // works around the same race by using `hasText` instead of
        // `onAllNodesWithTag` (the merged Text node carries the
        // text content even mid-recomposition). We use the same
        // `hasText` pattern for the post-connect probe and add an
        // explicit `waitForIdle()` before the wait loop so the
        // composition settles after the BLE state transitions
        // complete.
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            scanButtonVisible() || postConnectVisible()
        }
        if (scanButtonVisible()) {
            composeRule
                .onAllNodesWithTag(ConnectionGateTestTags.ScanButton)
                .onFirst()
                .performClick()
        }
        // else: post-connect shell is up; no scan/connect to do
        // (the auto-connect already completed).
    }

    private fun scanButtonVisible(): Boolean = composeRule
        .onAllNodesWithTag(ConnectionGateTestTags.ScanButton)
        .fetchSemanticsNodes()
        .isNotEmpty()

    private fun postConnectVisible(): Boolean = composeRule
        .onAllNodes(hasText(POST_CONNECT_MARKER_TEXT))
        .fetchSemanticsNodes()
        .isNotEmpty()

    /**
     * Asserts the expected device is visible in the discovered
     * device list.
     *
     * If [expectedDeviceName] was provided via the `am instrument
     * -e expected_device_name <id>` extra (the plan's FreshInstall
     * step 4 contract), the robot waits for a device with that
     * name and asserts exactly one card is present.
     *
     * If no extra was set, the robot falls back to "exactly one
     * device card visible" which is acceptable for a single-
     * controller BLE range. The fixture records a follow-up to
     * require the extra in P4 wiring; for now the fallback is
     * the safe default in the dev environment.
     */
    fun assertDeviceVisible() {
        composeRule.waitForIdle()
        val name = expectedDeviceName
        if (name != null) {
            composeRule.waitUntil(timeoutMillis = 30_000) {
                composeRule
                    .onAllNodesWithText(name)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } else {
            composeRule.waitUntil(timeoutMillis = 30_000) {
                composeRule
                    .onAllNodesWithTag(ConnectionGateTestTags.DeviceCard)
                    .fetchSemanticsNodes()
                    .isNotEmpty() || postConnectVisible()
            }
        }
        // P5 finding SUB #R37b: if the auto-connect won the race
        // during the wait, the post-connect shell is up and the
        // gate is gone. Don't assert the now-gone DeviceCard.
        if (postConnectVisible()) return
        composeRule
            .onAllNodesWithTag(ConnectionGateTestTags.DeviceCard)
            .assertCountEquals(1)
    }

    /**
     * Taps the [ConnectionGateTestTags.DeviceConnectButton] on the
     * first (and only, per [assertDeviceVisible]) device card.
     */
    fun tapConnect() {
        composeRule
            .onAllNodesWithTag(ConnectionGateTestTags.DeviceConnectButton)
            .onFirst()
            .performClick()
    }

    /**
     * Waits for the gate to be replaced by the NavigationSuiteScaffold
     * (i.e. the post-connect main shell). 60 s default because BLE
     * connect + service discovery + sync can take a while.
     *
     * The strongest "gate is gone" signal is the Scaffold's
     * `SettingsNavItem` appearing; the gate's [ScanButton] is
     * implicitly gone once the Scaffold is up. We probe by the
     * nav-item text (not the test tag) for the same
     * `SnapshotStateObserver` reason as [postConnectVisible].
     */
    fun assertConnected(timeoutMillis: Long = 60_000L) {
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            postConnectVisible()
        }
    }

    /**
     * Non-blocking check: is the post-connect shell ALREADY
     * rendered? Used by [E2eConnectionFixture.connectOnce] to
     * skip the gate's tapScan path when the app has already
     * auto-connected to the controller (P5 finding SUB #R5).
     *
     * Returns `true` if the [NavigationSuiteTestTags.SettingsNavItem]
     * is currently in the composition tree, `false` otherwise.
     * Does NOT wait — callers that want to wait should use
     * [assertConnected].
     */
    fun isAlreadyConnected(): Boolean = postConnectVisible()

    /**
     * Detects whether the forced MaintenanceRequired
     * screen is current. In this mode the ConnectionGate
     * never renders; the MaintenanceScreen takes over.
     * Used by firmware_update E2E test to skip scan/connect.
     */
    fun maintenanceScreenVisible(): Boolean = composeRule
        .onAllNodesWithTag(MaintenanceUpdateTestTags.Title)
        .fetchSemanticsNodes()
        .isNotEmpty()

    companion object {
        const val EXTRA_EXPECTED_DEVICE_NAME = "expected_device_name"

        /**
         * Localised text of the Settings nav item — the strongest
         * "post-connect shell is up" signal that survives the
         * `mergeDescendants` semantics merge (the merged
         * NavigationBarItem exposes the text content even when
         * the test-thread semantics observer hasn't yet wired
         * up the per-tag resource-id mapping). Mirrors the
         * smoke-gate's `MaintenanceUpdateLiveActivityInstrumentationTest`
         * approach.
         */
        const val POST_CONNECT_MARKER_TEXT = "Settings"
    }
}
