package de.aarondietz.beetmeister.e2e.robots

import android.app.UiAutomation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import de.aarondietz.beetmeister.ui.NavigationSuiteTestTags
import de.aarondietz.beetmeister.ui.feature.connection.ConnectionGateTestTags
import de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateTestTags
import de.aarondietz.beetmeister.ui.feature.settings.SettingsTestTags
import de.aarondietz.beetmeister.e2e.E2eConnectionFixture

/**
 * Robot for the firmware-update subflow.
 *
 * Reached from [SettingsRobot.openFirmwareUpdate] which taps
 * [SettingsTestTags.FirmwareUpdateOpenButton] and lands the user
 * on the Maintenance screen where the [MaintenanceUpdateTestTags]
 * state machine runs. The robot drives that state machine.
 *
 * Reads `expected_old_build_label` and `expected_new_build_label`
 * from `InstrumentationRegistry.getArguments()`. The orchestrator
 * passes these as `am instrument -e` extras:
 * ```
 * am instrument -w \
 *     -e beetRunE2e true \
 *     -e class de.aarondietz.beetmeister.e2e.FirmwareUpdateE2ETest \
 *     -e expected_old_build_label v0.3.0+abc1234 \
 *     -e expected_new_build_label v0.4.0+def5678 \
 *     ...
 * ```
 *
 * `expected_old_build_label` is the build label of the pre-update
 * controller firmware (from the v0.3.0 `.bin` metadata, parsed in
 * P4). `expected_new_build_label` is the bundled firmware's build
 * label, parsed in P3 from the generated `bundled-firmware-stamp.json`.
 */
internal class FirmwareUpdateRobot(
    private val composeRule: ComposeTestRule,
) {
    private val expectedNew: String =
        InstrumentationRegistry.getArguments().getString(EXTRA_NEW_BUILD_LABEL)
            ?: error("Missing -e $EXTRA_NEW_BUILD_LABEL on the instrumentation runner")

    /** Taps the "Use bundled" button. */
    fun useBundled() {
        composeRule
            .onNodeWithTag(MaintenanceUpdateTestTags.BundledButton)
            .performClick()
    }

    /**
     * Asserts the maintenance summary is shown. The summary appears
     * once the user has selected a firmware (bundled or custom); the
     * Install button is then enabled.
     */
    fun assertSummaryShown() {
        // P5 SUB #R36: prepareBundledFirmware() is async (host.scope.launch).
        // Wait up to 10s for the summary node to appear in the semantics tree
        // before asserting, instead of failing immediately on a missing node.
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule
                .onAllNodesWithTag(MaintenanceUpdateTestTags.Summary)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onNodeWithTag(MaintenanceUpdateTestTags.Summary)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Taps the Install button. */
    fun tapInstall() {
        composeRule
            .onNodeWithTag(MaintenanceUpdateTestTags.InstallButton)
            .performClick()
    }

    /**
     * Waits up to [timeoutMillis] for the controller to start the
     * OTA transfer. The Maintenance screen's update phase moves
     * through Starting -> Uploading -> Reconnecting -> Rebooting;
     * we don't pin to a specific UI state because the exact
     * progression is firmware-version-dependent. We just assert
     * that the update is no longer in the "pre-install" state by
     * waiting for a status detail / progress / abort button to
     * appear.
     */
    fun awaitTransferStarted(timeoutMillis: Long = 300_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            hasAnyTag(MaintenanceUpdateTestTags.Progress) ||
                hasAnyTag(MaintenanceUpdateTestTags.AbortButton) ||
                hasAnyTag(MaintenanceUpdateTestTags.StatusDetail)
        }
    }

    /**
     * Waits for the controller to reconnect after a reboot: the
     * Maintenance screen collapses, the gate becomes visible, and
     * a fresh connect cycle brings us back to the post-connect
     * NavigationSuiteScaffold (any nav item is sufficient).
     *
     * P5 SUB #R37e: after OTA reboot, the MaintenanceScreen may
     * stay visible in Completed/Idle state instead of auto-dismissing
     * (race between phase transitions and connection state). Accept
     * the DisconnectButton on the MaintenanceScreen as a valid
     * terminal state — assertPostUpdateHealthy handles dismissing
     * the screen and navigating to Settings.
     */
    fun awaitReconnect(timeoutMillis: Long = 120_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            hasAnyTag(NavigationSuiteTestTags.SettingsNavItem) ||
                // P5 SUB #R37e: accept MaintenanceScreen only when OTA
                // is in a terminal state (Progress not visible). If
                // Progress IS visible, the OTA is still active and
                // we must keep waiting for the real completion.
                (!hasAnyTag(MaintenanceUpdateTestTags.Progress) &&
                 hasAnyTag(MaintenanceUpdateTestTags.DisconnectButton))
        }
    }

    /**
     * After the post-update reconnect, navigates to Settings and
     * asserts the build label on the Controller Info card matches
     * `expected_new_build_label`.
     */
    fun assertPostUpdateHealthy(fixture: E2eConnectionFixture) {
        // P5 SUB #R37e: after OTA, the MaintenanceScreen may still
        // be visible in a terminal state. The maintenance screen
        // enters via forced mode during firmware update, so BackHandler
        // is disabled and pressBack() finishes the Activity instead.
        // Use the Disconnect button to dismiss the screen, then
        // let the fixture reconnect if the gate appears (fixture
        // handles pairing dialogs, auto-connect races).
        if (hasAnyTag(MaintenanceUpdateTestTags.DisconnectButton)) {
            composeRule
                .onNodeWithTag(MaintenanceUpdateTestTags.DisconnectButton)
                .performClick()
            composeRule.waitUntil(timeoutMillis = 60_000) {
                hasAnyTag(NavigationSuiteTestTags.SettingsNavItem) ||
                    hasAnyTag(ConnectionGateTestTags.ScanButton)
            }
            if (hasAnyTag(ConnectionGateTestTags.ScanButton)) {
                fixture.connectOnce()
            }
        }
        // Nav may momentarily disappear during BLE recomposition
        // after reconnect. Wait until it is solidly visible before
        // clicking.
        composeRule.waitUntil(timeoutMillis = 60_000) {
            hasAnyTag(NavigationSuiteTestTags.SettingsNavItem)
        }
        composeRule
            .onNodeWithTag(NavigationSuiteTestTags.SettingsNavItem, useUnmergedTree = true)
            .performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            hasAnyTag(SettingsTestTags.ControllerInfoBuildLabel)
        }
        composeRule
            .onNodeWithTag(SettingsTestTags.ControllerInfoBuildLabel)
            .assertTextContains(expectedNew)
    }

    private fun hasAnyTag(tag: String): Boolean {
        // P5 finding SUB #R37e: Compose test APIs can throw
        // various exceptions during long-running BLE operations.
        // Catch and return false so the waitUntil loop continues.
        //
        // Use unmerged tree: M3 NavigationSuiteScaffold only
        // includes the selected nav item in the merged semantics
        // tree; non-selected items like SettingsNavItem are
        // invisible in the merged tree.
        return try {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val EXTRA_OLD_BUILD_LABEL = "expected_old_build_label"
        const val EXTRA_NEW_BUILD_LABEL = "expected_new_build_label"
    }
}
