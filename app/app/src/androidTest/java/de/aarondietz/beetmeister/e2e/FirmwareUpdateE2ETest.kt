package de.aarondietz.beetmeister.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.aarondietz.beetmeister.MainActivity
import de.aarondietz.beetmeister.e2e.robots.FirmwareUpdateRobot
import de.aarondietz.beetmeister.e2e.robots.SettingsRobot
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * E2E: firmware-update suite.
 *
 * **Orchestrator does the heavy lifting:** the host pipeline
 * (Phase 4's firmware_update dispatch) ensures the controller is
 * flashed with the older pinned firmware (tag `v0.3.0`), then
 * uninstalls + installs the freshly-built debug APK + grants BLE
 * permissions. By the time this test class is invoked, the
 * controller reports the OLD build label and the app is bundled
 * with the NEW build label.
 *
 * The on-device Kotlin test then drives the real OTA flow:
 *  1. Connect to the controller (class-shared @Before, once for
 *     the class).
 *  2. Open Settings; assert the Controller Info build label
 *     contains the expected OLD build label.
 *  3. Open Firmware Update; "Use bundled"; assert summary shown.
 *  4. Tap Install.
 *  5. Wait for the transfer to start (300s) — `awaitTransferStarted`.
 *  6. Wait for the controller to reboot + reconnect (120s) —
 *     `awaitReconnect`.
 *  7. Open Settings; assert the build label now contains the
 *     expected NEW build label.
 *
 * Expected labels are read from `am instrument -e
 * expected_old_build_label … -e expected_new_build_label …`
 * extras set by the orchestrator.
 *
 * Timeouts: per-step `composeRule.waitUntil` allows generous
 * windows (300s + 120s) for the documented BLE maintenance
 * instability (~30s disconnect, BEGIN_UPDATE large write
 * blocking, reason 133/8). The overall `@Test(timeout = 600_000)`
 * is a 10-minute hard backstop so a stalled step still
 * terminates; the orchestrator parses the timeout as FAIL.
 */
@E2e
class FirmwareUpdateE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var fixture: E2eConnectionFixture
    private lateinit var settings: SettingsRobot

    @Before
    fun setUp() {
        fixture = E2eConnectionFixture(composeRule, testSlug = "firmwareUpdate")
        settings = SettingsRobot(composeRule)
        fixture.connectOnce()
    }

    @Test(timeout = 600_000)
    fun bundledFirmwareInstallsAndPostUpdateHealthIsCorrect() {
        settings.openSettings()
        settings.assertCurrentFirmwareMatchesOldBuildLabel()
        val firmwareUpdate: FirmwareUpdateRobot = settings.openFirmwareUpdate()
        firmwareUpdate.useBundled()
        firmwareUpdate.assertSummaryShown()
        firmwareUpdate.tapInstall()
        firmwareUpdate.awaitTransferStarted(timeoutMillis = 300_000L)
        firmwareUpdate.awaitReconnect(timeoutMillis = 120_000L)
        firmwareUpdate.assertPostUpdateHealthy()
    }
}
