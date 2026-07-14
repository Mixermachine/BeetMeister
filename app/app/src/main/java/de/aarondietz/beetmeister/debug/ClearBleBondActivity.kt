package de.aarondietz.beetmeister.debug

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Test-only Activity that clears the BLE bond for a given MAC
 * and immediately finishes.
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
 * **Production-safe.** The Activity is intentionally never
 * launched by production code. The action name is
 * namespaced under `de.aarondietz.beetmeister.debug.*` so a
 * grep makes the test-only intent obvious. The Activity is
 * exported so the test orchestrator can launch it via
 * `am start`, but the action name is specific enough that a
 * regular app or user has no reason to fire it. The
 * worst-case misuse is unpairing the user's BLE
 * peripheral — a low-impact action the user can recover
 * from by re-pairing. The Activity uses a translucent
 * no-title theme + `excludeFromRecents` + `noHistory` so it
 * does not appear in the recents list and never persists a
 * task entry.
 *
 * **Why an Activity (not a BroadcastReceiver):**
 * 1. Lifecycle is explicit (`onCreate` → `onDestroy`). The
 *    orchestrator's `am start` is synchronous; the command
 *    blocks until the Activity is launched and bound to a
 *    window. The orchestrator can read the activity's exit
 *    status from `am start`'s return code + logcat.
 * 2. The Activity runs on the main thread, so the bond
 *    removal completes before the next orchestrator step
 *    runs. BroadcastReceivers run in a binder pool thread
 *    and can be deferred on devices in App Standby
 *    restricted buckets, so a broadcast-fired receiver can
 *    be silently dropped on some configurations.
 * 3. Easier to test the path independently (`adb shell am
 *    start` with a real MAC and a real bonded device).
 *
 * **Why reflection (not direct `BluetoothDevice.removeBond()`):**
 * the method is `@hide @SystemApi` on Android — it is not
 * in the public SDK 26-37 and is reserved for system apps.
 * Regular apps have to call it via reflection. The method
 * has been stable in name across API 26+ (AOSP sources), so
 * reflection is the standard workaround. We swallow all
 * reflection errors and log; the orchestrator proceeds
 * regardless.
 *
 * **Usage from the orchestrator:**
 * ```
 * adb shell am start \
 *     -n de.aarondietz.beetmeister/.debug.ClearBleBondActivity \
 *     -a de.aarondietz.beetmeister.debug.action.CLEAR_BLE_BOND \
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
 * the bond survives. The orchestrator-side `cmd
 * bluetooth_manager remove-bond` (R31 attempt 2) was
 * removed from the Android 16 shell interface. Only the
 * `BluetoothDevice.removeBond()` API actually clears the
 * bond, and that API requires `BLUETOOTH_CONNECT` — only
 * available to apps (or to this Activity, which the app
 * owns).
 */
class ClearBleBondActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mac = intent?.getStringExtra(EXTRA_MAC)
        if (mac.isNullOrBlank()) {
            Log.w(TAG, "missing '$EXTRA_MAC' extra; finishing")
            finish()
            return
        }
        val manager = getSystemService(BluetoothManager::class.java)
        if (manager == null) {
            Log.w(TAG, "no BluetoothManager on this device; finishing")
            finish()
            return
        }
        val adapter = manager.adapter
        if (adapter == null) {
            Log.w(TAG, "no BluetoothAdapter (BT off? no radio?); finishing")
            finish()
            return
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "invalid MAC '$mac': ${e.message}; finishing")
            finish()
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.d(
                TAG,
                "device $mac is not bonded (bondState=${device.bondState}); " +
                    "no removeBond() needed; finishing",
            )
            finish()
            return
        }
        val removed = tryRemoveBondViaReflection(device)
        Log.i(
            TAG,
            "removeBond($mac) returned $removed (bondState now=${device.bondState}); finishing",
        )
        finish()
    }

    companion object {
        private const val TAG = "ClearBleBondAct"
        const val ACTION = "de.aarondietz.beetmeister.debug.action.CLEAR_BLE_BOND"
        const val EXTRA_MAC = "mac"

        /**
         * `BluetoothDevice.removeBond()` is `@hide @SystemApi` on
         * Android — it is not in the public SDK and is reserved
         * for system apps. Regular apps have to call it via
         * reflection. The method has been stable in name across
         * API 26+ (AOSP sources), so reflection is the standard
         * workaround. We swallow all reflection errors and
         * return false; the Activity logs the result and finishes
         * either way.
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
    }
}
