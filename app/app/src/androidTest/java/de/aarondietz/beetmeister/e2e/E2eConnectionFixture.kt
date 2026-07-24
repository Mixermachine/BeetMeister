package de.aarondietz.beetmeister.e2e

import android.view.KeyEvent
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
        // v0.3.0 which enters MaintenanceRequired mode.
        // The ConnectionGate never renders; the forced
        // MaintenanceScreen replaces it.
        if (gate.maintenanceScreenVisible()) {
            screenshots.captureStep("maintenanceRequired")
            dismissBluetoothPairingDialog()
            composeRule.waitUntil(timeoutMillis = 30_000) {
                gate.maintenanceScreenConnected()
            }
            screenshots.captureStep("afterConnect")
            E2eConnectionState.isConnected = true
            return
        }

        dismissBluetoothPairingDialog()

        if (!gate.isAlreadyConnected()) {
            gate.tapScan()
            if (!gate.isAlreadyConnected()) {
                gate.assertDeviceVisible()
                if (!gate.isAlreadyConnected()) {
                    gate.tapConnect()
                    dismissBluetoothPairingDialog()
                }
            }
        }
        gate.assertConnected()
        screenshots.captureStep("afterConnect")
        E2eConnectionState.isConnected = true
    }

    /**
     * Dismiss a pending OS-level Bluetooth pairing dialog using
     * `UiDevice` (cross-process; the dialog is in system_server).
     *
     * Handles both Passkey Display (entering 6-digit PIN into EditText)
     * and Just Works pairing dialogs.
     */
    private fun dismissBluetoothPairingDialog() {
        val device = UiDevice.getInstance(
            InstrumentationRegistry.getInstrumentation(),
        )
        val candidates = listOf("Pair", "Pairing", "OK", "Allow")
        val deadline = System.currentTimeMillis() + 15_000L
        android.util.Log.d("E2E_PASSKEY", "dismissBluetoothPairingDialog loop started")
        while (System.currentTimeMillis() < deadline) {
            val pinField = device.findObject(By.clazz("android.widget.EditText"))
            val allTexts = try {
                device.findObjects(By.clazz("android.widget.TextView")).take(5).map { it.text }
            } catch (_: Exception) { emptyList() }
            android.util.Log.d("E2E_PASSKEY", "pinField=$pinField texts=$allTexts")

            if (pinField != null) {
                android.util.Log.d("E2E_PASSKEY", "Found pinField! Typing keycodes for $TEST_PASSKEY")
                pinField.click()
                try { Thread.sleep(200L) } catch (_: Exception) {}
                for (ch in TEST_PASSKEY) {
                    if (ch in '0'..'9') {
                        device.pressKeyCode(KeyEvent.KEYCODE_0 + (ch - '0'))
                    }
                }
                try { Thread.sleep(300L) } catch (_: Exception) {}
                for (label in candidates) {
                    val btn = device.findObject(By.text(label))
                    if (btn != null) {
                        android.util.Log.d("E2E_PASSKEY", "Clicked button=$label")
                        btn.click()
                        device.waitForIdle(2_000L)
                        return
                    }
                }
            }
            val pairingTitle = device.findObject(By.textContains("Pair"))
            if (pairingTitle != null) {
                android.util.Log.d("E2E_PASSKEY", "Found pairingTitle=${pairingTitle.text}")
                try { Thread.sleep(300L) } catch (_: Exception) {}
                val recheckPin = device.findObject(By.clazz("android.widget.EditText"))
                if (recheckPin != null) {
                    android.util.Log.d("E2E_PASSKEY", "recheckPin found! Typing keycodes for $TEST_PASSKEY")
                    recheckPin.click()
                    try { Thread.sleep(200L) } catch (_: Exception) {}
                    for (ch in TEST_PASSKEY) {
                        if (ch in '0'..'9') {
                            device.pressKeyCode(KeyEvent.KEYCODE_0 + (ch - '0'))
                        }
                    }
                    try { Thread.sleep(300L) } catch (_: Exception) {}
                }
                for (label in candidates) {
                    val node = device.findObject(By.text(label))
                    if (node != null && node != pairingTitle) {
                        android.util.Log.d("E2E_PASSKEY", "Clicked candidate node=$label")
                        node.click()
                        device.waitForIdle(2_000L)
                        return
                    }
                }
            }
            try {
                Thread.sleep(500L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        android.util.Log.d("E2E_PASSKEY", "dismissBluetoothPairingDialog loop finished (no dialog found)")
    }

    companion object {
        private const val TEST_PASSKEY = "123456"
    }
}
