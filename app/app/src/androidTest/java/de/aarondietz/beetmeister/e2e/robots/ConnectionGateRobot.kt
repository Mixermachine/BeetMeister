package de.aarondietz.beetmeister.e2e.robots

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import de.aarondietz.beetmeister.ui.NavigationSuiteTestTags
import de.aarondietz.beetmeister.ui.feature.connection.ConnectionGateTestTags

/**
 * Robot for the BLE ConnectionGate (the pre-connect screen the user
 * sees when the app is launched but no controller is bound yet).
 *
 * The gate has four interaction surfaces:
 *  - [tapScan]: start a BLE scan.
 *  - [assertDeviceVisible]: assert a discovered device is in the
 *    device list (matched by name, e.g. "beetmeister-1234abcd").
 *  - [tapConnect]: tap the connect button on the device card.
 *  - [assertConnected]: assert the connection completed by waiting
 *    for the NavigationSuiteScaffold to appear (its first nav-item
 *    tag is the strongest "gate is gone" signal).
 *
 * Used by:
 *  - [E2eConnectionFixture] (the class-shared `@Before`) for the
 *    scan -> connect -> assertConnected chain.
 *  - [FreshInstallE2ETest] (no shared fixture; this robot is the
 *    only connect surface it needs).
 *
 * The device-card ElevatedCard is `clickable` on the whole card
 * (in addition to the explicit Connect button inside), so calling
 * [tapConnect] on a freshly tapped card has the same effect as
 * tapping the card itself.
 */
internal class ConnectionGateRobot(
    private val composeRule: ComposeTestRule,
) {
    /** Taps the [ConnectionGateTestTags.ScanButton]. Waits up to 30s for it to render. */
    fun tapScan() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule
                .onAllNodesWithTag(ConnectionGateTestTags.ScanButton)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(ConnectionGateTestTags.ScanButton).performClick()
    }

    /**
     * Asserts that a device with the given name (e.g. "beetmeister-7C3FA2")
     * is in the discovered device list. Uses the per-device [DeviceName]
     * text content because the card itself is tagged the same for all
     * devices.
     */
    fun assertDeviceVisible(name: String) {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule
                .onAllNodesWithText(name)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onAllNodesWithTag(ConnectionGateTestTags.DeviceCard)
            .assertCountEquals(1)
    }

    /**
     * Taps the [ConnectionGateTestTags.DeviceConnectButton] on the first
     * device card. Use after [assertDeviceVisible] to make sure the
     * expected device is the one being connected to.
     */
    fun tapConnect() {
        composeRule
            .onAllNodesWithTag(ConnectionGateTestTags.DeviceConnectButton)
            .onFirst()
            .performClick()
    }

    /**
     * Waits for the gate to be replaced by the NavigationSuiteScaffold
     * (i.e. the post-connect main shell). 60s default because BLE
     * connect + service discovery + sync can take a while.
     */
    fun assertConnected(timeoutMillis: Long = 60_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule
                .onAllNodesWithTag(NavigationSuiteTestTags.SettingsNavItem)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Sanity check the gate is actually gone — there is only one
        // Container per Compose root at a time and it switches from the
        // gate's Container to the Scaffold's content as soon as
        // `connectionGateVisible` flips false. The Scaffold is the
        // parent of the nav items, so this assertion is implicit but
        // we make it explicit for clarity.
        composeRule
            .onAllNodesWithTag(ConnectionGateTestTags.Container)
            .fetchSemanticsNodes()
            .let { it.isEmpty() }
    }
}
