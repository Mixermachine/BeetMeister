package de.aarondietz.beetmeister.e2e

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
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
     * `@Test`'s `@Before` runs.
     *
     * P5 finding SUB #R25: the firmware_update dispatch flashes
     * an older firmware (v0.3.0) to the controller and uninstalls
     * the app. The phone-side BLE bond is removed. When the app
     * launches and auto-connects, the OS shows a system-level
     * "Pair with beetmeister-01?" dialog that blocks the app's UI.
     * The Compose test rule can't see any semantics nodes. We
     * dismiss the dialog via `UiDevice` (cross-process; the
     * dialog is in system_server, not the test process). The
     * dismiss is a no-op for fresh_install / settings_update
     * (the controller's firmware identity is unchanged there,
     * so the OS bond survives `adb uninstall`).
     */
    fun connectOnce() {
        if (E2eConnectionState.isConnected) {
            gate.assertConnected()
            return
        }
        if (gate.isAlreadyConnected()) {
            screenshots.captureStep("afterConnect")
            E2eConnectionState.isConnected = true
            return
        }
        // P5 SUB #R25/R37d: firmware_update dispatch flashes
        // v0.3.0 which may enter MaintenanceRequired mode.
        // In this forced mode the ConnectionGate never renders;
        // the MaintenanceScreen appears instead and is ready
        // for the firmware update flow. Skip scan/connect.
        if (gate.maintenanceScreenVisible()) {
            screenshots.captureStep("maintenanceRequired")
            E2eConnectionState.isConnected = true
            return
        }
        dismissBluetoothPairingDialog()
        gate.tapScan()
        // P5 finding SUB #R37b: the app's BLE auto-connect
        // (Idle -> Connecting -> ... -> Connected) can race
        // with tapScan(). If tapScan() exits before the
        // auto-connect fires (scanButtonVisible = true,
        // postConnectVisible = false), it punches the scan
        // button and we fall through here. But the auto-
        // connect may have completed by now, hiding the
        // gate entirely. Check: if the post-connect shell
        // is already up, skip the scan-result path.
        if (gate.isAlreadyConnected()) {
            screenshots.captureStep("afterConnect")
            E2eConnectionState.isConnected = true
            return
        }
        gate.assertDeviceVisible()
        // P5 finding SUB #R37b: auto-connect may have completed
        // during assertDeviceVisible(). If the gate is gone,
        // skip tapConnect().
        if (!gate.isAlreadyConnected()) {
            gate.tapConnect()
        }
        gate.assertConnected()
        screenshots.captureStep("afterConnect")
        E2eConnectionState.isConnected = true
    }

    /**
     * Dismiss a pending OS-level Bluetooth pairing dialog using
     * `UiDevice` (cross-process; the dialog is in system_server).
     *
     * The dialog title is "Bluetooth pairing request" with
     * "Pair" and "Cancel" buttons. We click "Pair" to proceed.
     * On AOSP the button text is "Pair"; on Samsung it may be
     * "Pairing". We try both. No-op if the dialog isn't present
     * (the common case for fresh_install / settings_update).
     */
    private fun dismissBluetoothPairingDialog() {
        val device = UiDevice.getInstance(
            InstrumentationRegistry.getInstrumentation(),
        )
        // The dialog may not be present yet (the BLE auto-connect
        // triggers it AFTER the test rule launches the Activity).
        // Retry for up to 10 s: every 500 ms, check for the
        // "Pair" button; if present, click it. The fresh_install /
        // settings_update tests never show the dialog so the
        // loop exits immediately on the first iteration.
        val candidates = listOf("Pair", "Pairing", "OK", "Allow")
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            for (label in candidates) {
                val node = device.findObject(By.text(label))
                if (node != null) {
                    node.click()
                    device.waitForIdle(2_000L)
                    return
                }
            }
            try {
                Thread.sleep(500L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
