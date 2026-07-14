package de.aarondietz.beetmeister.debug

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Test-only BroadcastReceiver that clears the BLE bond for a
 * given MAC address.
 *
 * Triggered by the test-harness orchestrator
 * (`firmware_update` dispatch's `clear_ble_bond_via_intent`
 * precondition) before the `am instrument` call, so the app's
 * `BeetScanBondCoordinator.start()` auto-connect path does not
 * try to encrypt the link with a stale stored key. The
 * auto-connect then triggers a fresh "Just Works" pairing
 * (the firmware uses `BLE_HS_IO_NO_INPUT_OUTPUT`, so no OS
 * dialog appears) and the test's MTU exchange / service
 * discovery proceed normally.
 *
 * **Production-safe.** The receiver is intentionally never
 * fired by production code. The action name is namespaced
 * under `de.aarondietz.beetmeister.debug.*` so a grep makes
 * the test-only intent obvious. The receiver is exported so
 * the test orchestrator can fire it via
 * `adb shell am broadcast`, but the action name is specific
 * enough that a regular app or user has no reason to fire it.
 * The worst a misuse can do is unpair the user's BLE
 * peripheral — a low-impact action the user can recover from
 * by re-pairing.
 *
 * **Usage from the orchestrator:**
 * ```
 * adb shell am broadcast \
 *     -a de.aarondietz.beetmeister.debug.action.CLEAR_BLE_BOND \
 *     -p de.aarondietz.beetmeister \
 *     --es mac 3C:0F:02:D2:0F:6A
 * ```
 *
 * **P5 finding followup:** the original P5 handover
 * (commit 26512e9) hypothesized a firmware-side MTU bug.
 * The actual root cause is a phone-side bond mismatch
 * (the OS-level bond cache survives `adb uninstall` +
 * reinstall; the controller's NVS is wiped by `flash_old`).
 * The orchestrator-side `pm clear` (P5 SUB #R31 attempt 1)
 * clears the app's data but the bond is re-loaded from a
 * separate persistent store on next Bluetooth app start, so
 * the bond survives. Only the `BluetoothDevice.removeBond()`
 * API actually clears the bond, and that API requires
 * `BLUETOOTH_CONNECT` — only available to apps (or to this
 * receiver, which the app owns).
 *
 * **Why a receiver (not `am instrument` extract test):** the
 * test orchestrator already has a precondition-step
 * structure. Adding an `am broadcast` step is one line; an
 * `am instrument` extract would require two-process
 * coordination (launch a small instrumented class first, wait
 * for it to clear the bond, then launch the real test).
 * The receiver is the right granularity.
 */
class ClearBleBondReceiver : BroadcastReceiver() {
    @Suppress("MissingPermission")  // we never call any BluetoothDevice method that requires the permission at the public API surface
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            return
        }
        val mac = intent.getStringExtra(EXTRA_MAC)
        if (mac.isNullOrBlank()) {
            Log.w(TAG, "missing '$EXTRA_MAC' extra; ignoring broadcast")
            return
        }
        val manager = context.getSystemService(BluetoothManager::class.java)
        if (manager == null) {
            Log.w(TAG, "no BluetoothManager on this device; ignoring")
            return
        }
        val adapter = manager.adapter
        if (adapter == null) {
            Log.w(TAG, "no BluetoothAdapter (BT off? no radio?); ignoring")
            return
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "invalid MAC '$mac': ${e.message}; ignoring")
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.d(
                TAG,
                "device $mac is not bonded (bondState=${device.bondState}); " +
                    "no removeBond() needed",
            )
            return
        }
        val removed = tryRemoveBondViaReflection(device)
        Log.i(
            TAG,
            "removeBond($mac) returned $removed (bondState now=${device.bondState})",
        )
    }

    companion object {
        private const val TAG = "ClearBleBondRx"
        const val ACTION = "de.aarondietz.beetmeister.debug.action.CLEAR_BLE_BOND"
        const val EXTRA_MAC = "mac"

        /**
         * `BluetoothDevice.removeBond()` is `@hide @SystemApi` on
         * Android — it is not in the public SDK and is reserved
         * for system apps. Regular apps have to call it via
         * reflection. The method has been stable in name across
         * API 26+ (AOSP sources), so reflection is the standard
         * workaround. We swallow all reflection errors and
         * return false; the orchestrator logs and continues.
         */
        private fun tryRemoveBondViaReflection(device: BluetoothDevice): Boolean {
            return try {
                val method = BluetoothDevice::class.java.getMethod("removeBond")
                (method.invoke(device) as? Boolean) ?: false
            } catch (e: NoSuchMethodException) {
                Log.w(TAG, "BluetoothDevice.removeBond not found via reflection: ${e.message}")
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
    }
}
