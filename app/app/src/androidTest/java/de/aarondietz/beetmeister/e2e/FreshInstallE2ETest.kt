package de.aarondietz.beetmeister.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.aarondietz.beetmeister.BuildConfig
import de.aarondietz.beetmeister.MainActivity
import de.aarondietz.beetmeister.e2e.robots.OverviewRobot
import de.aarondietz.beetmeister.e2e.robots.SettingsRobot
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * E2E: fresh-install suite.
 *
 * **Orchestrator does the heavy lifting:** the host pipeline
 * (Phase 4's fresh_install dispatch) uninstalls the app, erases
 * the controller's `appcfg` + `events` + `sysevents` partitions
 * via `esptool erase_region`, then installs the freshly-built
 * debug APK + grants BLE permissions. By the time this test
 * class is invoked, the app is on its first launch, the
 * controller is advertising fresh, and the controller's `device_id`
 * is preserved.
 *
 * The on-device Kotlin test then just connects + asserts the
 * visible data:
 *  - Overview has all 8 pair rows.
 *  - The first pair's moisture percentage is non-empty.
 *  - Settings shows a populated Controller Info card (device_id,
 *    firmware_version non-empty) and the runtime protocol
 *    version matches `BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION`.
 *
 * The `Overview 8 pairs + Settings Controller Info` surface
 * matches the plan's "Visible data" pass criteria. Adding a
 * `valve_config` / `watering_interval` assertion is intentionally
 * out of scope for this test class — those are covered by
 * [SettingsUpdateE2ETest] where the readback is a *setting* change
 * round-trip, not a "controller reported state" check.
 *
 * Connect happens **once** in [setUp] via the
 * [E2eConnectionFixture]'s `connectOnce()` (idempotent). The
 * subsequent `@Test`s share that connection.
 */
@E2e
class FreshInstallE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var fixture: E2eConnectionFixture
    private lateinit var overview: OverviewRobot
    private lateinit var settings: SettingsRobot

    @Before
    fun setUp() {
        fixture = E2eConnectionFixture(composeRule, testSlug = "freshInstall")
        overview = OverviewRobot(composeRule)
        settings = SettingsRobot(composeRule)
        fixture.connectOnce()
    }

    @Test
    fun overviewShowsAllEightPairsWithMoisture() {
        overview.assertPairRowsRendered(count = 8)
        overview.assertPairMoistureNonEmpty(index = 0)
    }

    @Test
    fun settingsControllerInfoShowsDeviceIdFirmwareAndProtocol() {
        settings.openSettings()
        settings.assertControllerInfoDisplayed()
        settings.assertDeviceIdNonEmpty()
        settings.assertFirmwareVersionNonEmpty()
        settings.assertProtocolVersion(expected = BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION)
    }
}
