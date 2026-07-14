package de.aarondietz.beetmeister.e2e.robots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.aarondietz.beetmeister.ui.NavigationSuiteTestTags
import de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateTestTags
import de.aarondietz.beetmeister.ui.feature.settings.SettingsTestTags

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
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.Summary).assertIsDisplayed()
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
     */
    fun awaitReconnect(timeoutMillis: Long = 120_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            hasAnyTag(NavigationSuiteTestTags.SettingsNavItem)
        }
    }

    /**
     * After the post-update reconnect, navigates to Settings and
     * asserts the build label on the Controller Info card matches
     * `expected_new_build_label`.
     */
    fun assertPostUpdateHealthy() {
        composeRule
            .onNodeWithTag(NavigationSuiteTestTags.SettingsNavItem)
            .performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            hasAnyTag(SettingsTestTags.ControllerInfoBuildLabel)
        }
        composeRule
            .onNodeWithTag(SettingsTestTags.ControllerInfoBuildLabel)
            .assertTextContains(expectedNew)
    }

    private fun hasAnyTag(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    companion object {
        const val EXTRA_OLD_BUILD_LABEL = "expected_old_build_label"
        const val EXTRA_NEW_BUILD_LABEL = "expected_new_build_label"
    }
}
