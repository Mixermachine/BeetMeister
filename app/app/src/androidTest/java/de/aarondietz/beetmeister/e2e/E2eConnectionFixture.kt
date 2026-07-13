package de.aarondietz.beetmeister.e2e

import androidx.compose.ui.test.junit4.ComposeTestRule
import de.aarondietz.beetmeister.e2e.robots.ConnectionGateRobot

/**
 * Class-shared connect state.
 *
 * JUnit 4 creates a fresh test-class instance for every `@Test`, so
 * per-instance state is reset between sibling `@Test`s. The original
 * `E2eConnectionFixture.@Volatile connected: Boolean` was therefore
 * decorative: every `@Test` re-ran the full scan/connect (~30-60 s)
 * even though the BLE connection itself is process-wide (the
 * `BeetRepository` is a singleton; once `connection.phase ==
 * Connected` is set, every freshly-spawned `MainActivity` lands on
 * the post-connect shell instead of the gate after the 900 ms
 * `CONNECTED_UI_STABILITY_MS` window).
 *
 * The static [isConnected] here is the real class-shared guard.
 * The fixture's [E2eConnectionFixture.connectOnce] is a fast-path
 * no-op for `@Test`s 2..N — it just verifies the post-connect
 * marker is still rendered. The first `@Test` does the actual
 * scan/connect.
 *
 * **Cascade-FAIL (resolved P5):** if the BLE link drops mid-suite,
 * the repository's `connection.phase` reverts from `Connected` and
 * the freshly-spawned `MainActivity` shows the gate again, so the
 * subsequent `@Test`'s `assertStillConnected` timeouts out and the
 * test class reports FAIL. **No retry, no @After disconnect, no
 * reconnect-once-it-dropped** — a mid-suite cascade is a genuine
 * surfaced failure, not suppressed.
 *
 * **Process scope:** the static state is per-process. The
 * orchestrator runs one E2E class per `am instrument` invocation,
 * so the state is effectively per-class in normal flow. A user
 * who runs `-e package de.aarondietz.beetmeister.e2e` (all three
 * classes) would have the state carry across, which is consistent
 * with the BLE connection also carrying across.
 */
internal object E2eConnectionState {
    @Volatile
    var isConnected: Boolean = false
}

/**
 * Shared connect-once fixture for the E2E test classes.
 *
 * Per the harness plan, the class-shared connect happens in the
 * test class's `@Before` exactly once for the whole class. The
 * connect itself is amortized across all sibling `@Test`s via the
 * static [E2eConnectionState.isConnected] flag.
 *
 * The fixture also owns the per-class [E2eScreenshotHelper] so each
 * test class can call `fixture.screenshots.captureStep(...)` in its
 * own logic without re-wiring.
 *
 * The connect path follows the on-device flow the orchestrator's
 * fresh_install / settings_update suites rely on:
 *  1. Wait for the ConnectionGate to render (only needed on the
 *     first `@Test`; subsequent calls fast-path through
 *     [assertStillConnected]).
 *  2. Tap the Scan button.
 *  3. Wait for the expected device (matched by
 *     `expected_device_name` from `am instrument -e`) to appear in
 *     the device list. Use [ConnectionGateRobot.assertDeviceVisible]
 *     so we connect to the **right** controller, not a
 *     non-deterministic first card. If only one device is in BLE
 *     range the assertion still holds; if more than one is
 *     visible the name check disambiguates deterministically.
 *  4. Tap the first device card's Connect button.
 *  5. Wait for the NavigationSuiteScaffold to render (the
 *     [NavigationSuiteTestTags.SettingsNavItem] is the strongest
 *     "gate is gone" signal).
 *  6. Take an after-connect screenshot.
 */
internal class E2eConnectionFixture(
    val composeRule: ComposeTestRule,
    testSlug: String,
) {
    val screenshots: E2eScreenshotHelper = E2eScreenshotHelper(testSlug = testSlug)
    val gate: ConnectionGateRobot = ConnectionGateRobot(composeRule)

    /**
     * Idempotent class-shared connect. The first call runs the
     * full scan/connect/wait path; subsequent calls verify the
     * post-connect state is still present and return.
     *
     * P5 finding SUB #R5: the app's BLE auto-connect is fast
     * enough that the gate can disappear BEFORE the first
     * `@Test`'s `@Before` runs (the BLE stack reconnects via
     * the OS-level bond that survives `adb uninstall`, the
     * scan finds the controller in <1 s, the 900 ms stable
     * `Connected` window passes, the gate is hidden). The
     * original implementation only checked the static
     * [E2eConnectionState.isConnected] flag (which is false
     * on a freshly-loaded class) and tried `tapScan` which
     * then waited 30 s for a [ScanButton] that no longer
     * existed (the gate is gone, so the ScanButton isn't
     * rendered). Both E2E tests failed at the same line.
     *
     * New flow: BEFORE calling [ConnectionGateRobot.tapScan],
     * check whether the post-connect shell is already
     * rendered (the [NavigationSuiteTestTags.SettingsNavItem]
     * tag is the strongest "gate is gone" signal — same
     * signal [ConnectionGateRobot.assertConnected] waits
     * for after a real connect). If the post-connect shell
     * is already up, skip the gate entirely and just verify
     * the post-connect markers; the BLE connection is
     * already in place via the auto-connect.
     */
    fun connectOnce() {
        if (E2eConnectionState.isConnected) {
            gate.assertConnected()
            return
        }
        if (gate.isAlreadyConnected()) {
            // App already past the gate (P5 SUB #R5). No
            // scan/connect needed; just take the after-connect
            // screenshot (the one the original flow captures
            // after `assertConnected` returns) and mark the
            // static state so subsequent `@Test`s fast-path.
            screenshots.captureStep("afterConnect")
            E2eConnectionState.isConnected = true
            return
        }
        gate.tapScan()
        gate.assertDeviceVisible()
        gate.tapConnect()
        gate.assertConnected()
        screenshots.captureStep("afterConnect")
        E2eConnectionState.isConnected = true
    }
}
