package de.aarondietz.beetmeister.debug

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Test-only generic Activity that dispatches to test "actions"
 * based on the `--es action <name>` extra.
 *
 * Triggered by the test-harness orchestrator before the
 * `am instrument` call in a test dispatch. Each test
 * precondition that needs to talk to the app (e.g. clear a
 * BLE bond, reset maintenance state, force a disconnect)
 * launches this Activity with the appropriate `action`
 * name. The Activity does the work, logs the result, and
 * finishes. `am start` is synchronous and waits for the
 * Activity to be bound to a window, so the orchestrator
 * knows the action is done before the next step runs.
 *
 * **Production-safe.** The Activity is intentionally never
 * launched by production code. The class lives in the
 * `.debug.*` package, the intent action is
 * `de.aarondietz.beetmeister.debug.action.RUN`, and the
 * Activity uses a translucent no-title theme +
 * `excludeFromRecents` + `noHistory` so the user does not
 * see a UI flash and the entry does not appear in the
 * recents list. The worst-case misuse is a side effect of
 * one of the registered actions (e.g. unpair a BLE
 * peripheral) — a low-impact action the user can recover
 * from by re-pairing.
 *
 * **Usage from the orchestrator:**
 * ```
 * adb shell am start \
 *     -n de.aarondietz.beetmeister/.debug.DebugActionActivity \
 *     -a de.aarondietz.beetmeister.debug.action.RUN \
 *     --es action clear_ble_bond \
 *     --es mac 3C:0F:02:D2:0F:6A
 * ```
 *
 * **Adding a new action:** add a new arm to the `when` in
 * [dispatch]. The arm should validate its required extras,
 * do the work, and return. Missing or unknown actions are
 * a no-op (log + finish). No new Activity or manifest
 * entry is needed.
 *
 * **Why an Activity (not a BroadcastReceiver):** `am start`
 * is synchronous and waits for the Activity to be bound
 * to a window, so the orchestrator knows the action is
 * done before the next step. A BroadcastReceiver fires on
 * a binder thread and can be silently dropped on devices
 * in App Standby restricted buckets (Android 8+). An
 * Activity launched via `am start` from the shell user is
 * never dropped.
 *
 * **Why reflection (for Bluetooth operations):**
 * `BluetoothDevice.removeBond()` is `@hide @SystemApi` on
 * Android — it is not in the public SDK 26-37 and is
 * reserved for system apps. Regular apps have to call it
 * via reflection. The method has been stable in name
 * across API 26+ (AOSP sources), so reflection is the
 * standard workaround. We swallow all reflection errors
 * and log; the orchestrator proceeds regardless.
 *
 * **P5 finding followup:** the original P5 handover
 * (commit 26512e9) hypothesized a firmware-side MTU bug.
 * The actual root cause is a phone-side bond mismatch
 * (the OS-level bond cache survives `adb uninstall` +
 * reinstall; the controller's NVS is wiped by `flash_old`).
 * The orchestrator-side `pm clear` (P5 SUB #R31 attempt 1)
 * clears the app's data but the bond is re-loaded from a
 * separate persistent store on next Bluetooth app start, so
 * the bond survives. The orchestrator-side `cmd
 * bluetooth_manager remove-bond` (R31 attempt 2) was
 * removed from the Android 16 shell interface. Only the
 * `BluetoothDevice.removeBond()` API actually clears the
 * bond, and that API requires `BLUETOOTH_CONNECT` — only
 * available to apps (or to this Activity, which the app
 * owns).
 */
class DebugActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.getStringExtra(EXTRA_ACTION)
        if (action.isNullOrBlank()) {
            Log.w(TAG, "missing '$EXTRA_ACTION' extra; finishing")
            finish()
            return
        }
        try {
            val ok = dispatch(action, intent)
            Log.i(TAG, "action='$action' ok=$ok; finishing")
        } catch (e: Throwable) {
            Log.w(
                TAG,
                "action='$action' threw ${e::class.java.simpleName}: ${e.message}; finishing",
            )
        } finally {
            finish()
        }
    }

    /**
     * Dispatch to a registered handler. Returns true on
     * success, false on missing required extra / unknown
     * action. NEVER throws — all exceptions are caught and
     * logged by the caller.
     */
    private fun dispatch(action: String, intent: Intent): Boolean = when (action) {
        ACTION_CLEAR_BLE_BOND -> handleClearBleBond(intent)
        else -> {
            Log.w(
                TAG,
                "unknown action '$action' " +
                    "(supported: ${SUPPORTED_ACTIONS.joinToString(", ")}); finishing",
            )
            false
        }
    }

    /**
     * Handler for `clear_ble_bond`.
     *
     * Required extras:
     * - `mac` (string): the BLE MAC address whose bond to clear.
     *
     * Returns true if the bond was already absent, the
     * removeBond call succeeded, or the device was not
     * present. Returns false only if the required `mac`
     * extra is missing or `removeBond` itself threw.
     */
    private fun handleClearBleBond(intent: Intent): Boolean {
        val mac = intent.getStringExtra(EXTRA_BLE_MAC)
        if (mac.isNullOrBlank()) {
            Log.w(TAG, "clear_ble_bond: missing '$EXTRA_BLE_MAC' extra; finishing")
            return false
        }
        val manager = getSystemService(BluetoothManager::class.java)
        if (manager == null) {
            Log.w(TAG, "clear_ble_bond: no BluetoothManager on this device; finishing")
            return false
        }
        val adapter = manager.adapter
        if (adapter == null) {
            Log.w(TAG, "clear_ble_bond: no BluetoothAdapter (BT off? no radio?); finishing")
            return false
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "clear_ble_bond: invalid MAC '$mac': ${e.message}; finishing")
            return false
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.d(
                TAG,
                "clear_ble_bond: device $mac is not bonded " +
                    "(bondState=${device.bondState}); nothing to do; finishing",
            )
            return true
        }
        val removed = tryRemoveBondViaReflection(device)
        Log.i(
            TAG,
            "clear_ble_bond: removeBond($mac) returned $removed " +
                "(bondState now=${device.bondState}); finishing",
        )
        return removed
    }

    /**
     * `BluetoothDevice.removeBond()` is `@hide @SystemApi` on
     * Android — it is not in the public SDK and is reserved
     * for system apps. Regular apps have to call it via
     * reflection. The method has been stable in name across
     * API 26+ (AOSP sources), so reflection is the standard
     * workaround.
     */
    private fun tryRemoveBondViaReflection(device: BluetoothDevice): Boolean {
        return try {
            val method = BluetoothDevice::class.java.getMethod("removeBond")
            (method.invoke(device) as? Boolean) ?: false
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "BluetoothDevice.removeBond not found via reflection: ${e.message}")
            false
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            Log.w(
                TAG,
                "removeBond invocation failed: " +
                    (cause?.javaClass?.simpleName ?: "null") + " " + (cause?.message ?: ""),
            )
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "removeBond denied (SecurityException): ${e.message}")
            false
        } catch (e: Throwable) {
            Log.w(
                TAG,
                "removeBond reflection failed: ${e::class.java.simpleName} ${e.message}",
            )
            false
        }
    }

    companion object {
        private const val TAG = "DebugActionAct"

        /** The single intent action the manifest registers. */
        const val ACTION = "de.aarondietz.beetmeister.debug.action.RUN"

        /** Required extra naming the operation to perform. */
        const val EXTRA_ACTION = "action"

        // ---- Action names (use these from the orchestrator) ----
        const val ACTION_CLEAR_BLE_BOND = "clear_ble_bond"

        // ---- Action-specific extras (clear_ble_bond) ----
        const val EXTRA_BLE_MAC = "mac"

        // ---- Registry (used by the unknown-action error message) ----
        private val SUPPORTED_ACTIONS = listOf(ACTION_CLEAR_BLE_BOND)
    }
}
