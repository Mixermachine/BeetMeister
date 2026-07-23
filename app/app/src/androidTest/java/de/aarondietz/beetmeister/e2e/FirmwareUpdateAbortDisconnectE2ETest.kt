package de.aarondietz.beetmeister.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import de.aarondietz.beetmeister.MainActivity
import de.aarondietz.beetmeister.e2e.robots.FirmwareUpdateRobot
import de.aarondietz.beetmeister.e2e.robots.SettingsRobot
import de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateTestTags
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * E2E: firmware-update abort + disconnect on the same screen.
 *
 * Preconditions (orchestrator):
 *  - flash old firmware (pinned_tag) to controller
 *  - uninstall app, install APKs, clear BLE bond
 *  - app auto-connects to controller (forced MaintenanceRequired
 *    because old fw has lower protocol version)
 *
 * Test sequence:
 *  1. Connect to controller.
 *  2. On the Maintenance screen: use bundled, tap Install.
 *  3. Wait for transfer to begin (Progress / Abort visible).
 *  4. Assert both AbortButton AND DisconnectButton are visible
 *     on the same screen.
 *  5. Tap AbortButton to stop the firmware update.
 *  6. Wait for the update phase to return to Ready/Idle/Failed
 *     (InstallButton or BundledButton reappears).
 *  7. Assert DisconnectButton is still visible.
 *  8. Tap DisconnectButton.
 *  9. Assert the connection gate (ScanButton) is visible.
 *
 * Timeout: 300 s (5 min). The transfer-start wait dominates;
 * abort + disconnect are UI transitions (<30 s each).
 */
@E2e
class FirmwareUpdateAbortDisconnectE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var fixture: E2eConnectionFixture
    private lateinit var settings: SettingsRobot

    @Before
    fun setUp() {
        fixture = E2eConnectionFixture(composeRule, testSlug = "firmwareUpdateAbortDisconnect")
        settings = SettingsRobot(composeRule)
        // Old firmware triggers forced MaintenanceRequired.
        // connectOnce() waits for the auto-connect + maintenance
        // info to load, then returns.
        fixture.connectOnce()
    }

    @Test(timeout = 420_000)
    fun abortAndDisconnectDuringTransfer() {
        val firmwareUpdate: FirmwareUpdateRobot
        if (fixture.gate.maintenanceScreenVisible()) {
            // Forced maintenance mode: MaintenanceScreen is
            // already showing with a connected controller.
            firmwareUpdate = FirmwareUpdateRobot(composeRule)
        } else {
            // Normal mode: navigate through Settings to open
            // the firmware update screen.
            settings.openSettings()
            settings.assertCurrentFirmwareMatchesOldBuildLabel()
            firmwareUpdate = settings.openFirmwareUpdate()
        }

        // Start the OTA — use bundled firmware, confirm summary, tap Install.
        firmwareUpdate.useBundled()
        firmwareUpdate.assertSummaryShown()
        firmwareUpdate.tapInstall()

        // Wait for the transfer to start (Progress or AbortButton visible).
        firmwareUpdate.awaitTransferStarted(timeoutMillis = 300_000L)

        // Assert BOTH Abort and Disconnect buttons are visible on the SAME screen.
        composeRule
            .onNodeWithTag(MaintenanceUpdateTestTags.AbortButton)
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(MaintenanceUpdateTestTags.DisconnectButton)
            .assertIsDisplayed()

        // Tap Abort to stop the firmware update.
        composeRule
            .onNodeWithTag(MaintenanceUpdateTestTags.AbortButton)
            .performClick()

        // Wait for the update to stop: the AbortButton disappears
        // when the phase leaves the active maintenance states
        // (Starting/Uploading/Reconnecting/Rebooting).
        composeRule.waitUntil(timeoutMillis = 60_000L) {
            !firmwareUpdate.hasAnyTag(MaintenanceUpdateTestTags.AbortButton)
        }

        // Assert DisconnectButton is still visible after abort.
        composeRule
            .onNodeWithTag(MaintenanceUpdateTestTags.DisconnectButton)
            .assertIsDisplayed()

        // Tap Disconnect to leave the maintenance screen.
        composeRule
            .onNodeWithTag(MaintenanceUpdateTestTags.DisconnectButton)
            .performClick()

        // Assert we are disconnected: the maintenance screen
        // should no longer show the current firmware info
        // (controller is disconnected, info is null).
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            !firmwareUpdate.hasAnyTag(MaintenanceUpdateTestTags.CurrentFirmware)
        }
    }
}
