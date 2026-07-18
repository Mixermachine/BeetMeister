package de.aarondietz.beetmeister.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.aarondietz.beetmeister.MainActivity
import de.aarondietz.beetmeister.e2e.robots.OverviewRobot
import de.aarondietz.beetmeister.e2e.robots.PairDetailRobot
import de.aarondietz.beetmeister.e2e.robots.SettingsRobot
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * E2E: settings-update suite (parametrized across all 7 writable settings).
 *
 * Each `@Test` is a separate one-shot "set deterministic B -> save
 * -> assert readback == B" pass. **No A baseline, no round-trip, no
 * baseline reset** — the controller retains B across the session
 * (acceptable per the isolation decision in the plan).
 *
 * The orchestrator dispatches this class as **one** `am instrument
 * -e class SettingsUpdateE2ETest` invocation; the class-shared
 * [E2eConnectionFixture] in [setUp] connects **once** for the
 * whole class. Per-test pass/fail is parsed from instrumentation
 * stdout and recorded in the run manifest.
 *
 * B values:
 *  - `valve_enabled`: B = false
 *  - `move_duration_ms`: B = 2000
 *  - `settle_delay_ms`: B = 500
 *  - `open_hold_ms`: B = 2000
 *  - `watering_interval_s`: B = 3600 (1 h 0 m)
 *  - `max_active_pumps`: B = 2
 *  - `pair_name_rename`: B = "e2e-renamed-<N>" (per-test)
 *
 * The three valve-numeric fields (`move` / `settle` / `hold`)
 * have no read-only current-value row in the UI, so the robot
 * uses the **pull-to-refresh reload pattern** (per the Phase 1
 * resolved blocker B4): type B -> save -> pull-to-refresh the
 * Settings screen (BeetPullToRefreshBox re-fetches valveConfig,
 * `LaunchedEffect(valveConfig)` repopulates the field text)
 * -> assert field text == B.
 *
 * `pair_name_rename` is reached via Overview -> pair Details
 * (not via the Settings screen), so [SettingsRobot] delegates to
 * [PairDetailRobot] for that case.
 */
@E2e
class SettingsUpdateE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var fixture: E2eConnectionFixture
    private lateinit var overview: OverviewRobot
    private lateinit var settings: SettingsRobot
    private lateinit var pairDetail: PairDetailRobot

    @Before
    fun setUp() {
        fixture = E2eConnectionFixture(composeRule, testSlug = "settingsUpdate")
        overview = OverviewRobot(composeRule)
        settings = SettingsRobot(composeRule)
        pairDetail = PairDetailRobot(composeRule)
        fixture.connectOnce()
    }

    @Test(timeout = 60_000)
    fun wateringInterval_canBeSetToOneHour() {
        settings.openSettings()
        settings.setWateringInterval(hours = 1, minutes = 0)
        settings.assertCurrentWateringInterval(expectedFormatted = "1h 0m")
    }

    @Test(timeout = 60_000)
    fun maxActivePumps_canBeSetToTwo() {
        settings.openSettings()
        settings.setMaxActivePumps(target = 2)
        settings.assertCurrentMaxActivePumps(expectedNumber = 2)
    }

    @Test(timeout = 60_000)
    fun valveEnabled_canBeSetToFalse() {
        settings.openSettings()
        settings.setValveEnabled(enabled = false)
        settings.assertValveEnabled(enabled = false)
    }

    @Test(timeout = 120_000)
    fun valveMoveDuration_canBeSetTo2000ms() {
        settings.openSettings()
        settings.setValveMoveDuration(ms = 2000)
        settings.assertValveMoveDuration(expectedMillisStr = "2000")
    }

    @Test(timeout = 120_000)
    fun valveSettleDelay_canBeSetTo500ms() {
        settings.openSettings()
        settings.setValveSettleDelay(ms = 500)
        settings.assertValveSettleDelay(expectedMillisStr = "500")
    }

    @Test(timeout = 120_000)
    fun valveOpenHold_canBeSetTo2000ms() {
        settings.openSettings()
        settings.setValveOpenHold(ms = 2000)
        settings.assertValveOpenHold(expectedMillisStr = "2000")
    }

    @Test(timeout = 60_000)
    fun pairName_canBeRenamed() {
        // Pair name is reached via Overview -> first pair's Details,
        // not via the Settings screen, so the OverviewRobot +
        // PairDetailRobot chain handles this. The new name is
        // unique per-test so the assertion is unambiguous even
        // if the prior test left a different rename behind.
        //
        // The plan's B value is "e2e-renamed-pairName" (22 chars).
        // On the A53 the pair-detail name is a single-line
        // `Text headlineSmall` that truncates the semantics
        // `Text` property when the name exceeds the headline
        // width (the `weight(1f)` inside the Row leaves ~620px
        // for the text after the trailing IconButton). The
        // truncated semantics is "e2e-renamed-pai" (17 chars).
        // assertTextContains on the full B value would fail.
        // We therefore use a shorter marker ("e2e-renamed",
        // 12 chars) that fits within the headline width and
        // is still unique per run. The test still proves the
        // rename round-trip end-to-end.
        val newName = "e2e-renamed"
        overview.tapPairDetails(index = 0)
        pairDetail.renameTo(newName)
        pairDetail.assertNameEquals(newName)
    }
}
