# P5.3 BLE Reconnect Loop — Handoff Document

## Summary

With the phone-side bond cleared (the SUB #R31/R32 fix that
solved the "MTU bug" which was actually a bond mismatch), the
BLE connection to the v0.3.0 firmware now negotiates MTU 247
successfully. But a new symptom appeared: the app's
`BeetScanBondCoordinator.monitorBondState` fires
`requestOpenGatt` TWICE during the initial connect — once as a
"kick" during bonding, and again when the bond completes. The
v0.3.0 firmware's bonding takes longer than HEAD's, so both
fire and the second one disconnects the first connection and
reconnects.

The reconnect (`Connected → Connecting`) happens ~552 ms after
the initial `phase=Connected` — before the gate's 900 ms
`LaunchedEffect` delay completes. The gate never hides.
`postConnectVisible()` returns false. `tapScan` times out at 30 s.

This is a v0.3.0-specific timing issue caused by the v0.3.0
firmware's slower bonding. The fix is either firmware-side
(make bonding faster) or app-side (make the gate's
`LaunchedEffect` resilient to rapid `Connected → Connecting`
transitions, or prevent the double `openGatt`).

## Symptom (from real-hardware logs)

Test run: `test-harness/runs/20260714-143832-firmware_update/`
(e2e dur 36.6 s, `tapScan` timed out at 30 s in `setUp`).

### Android logcat — exact sequence

```
16:35:30.185  BeetAppUi: phase=Idle            gateVisibleBefore=true   # app launched
16:35:30.854  BeetAppUi: phase=Bonding         gateVisibleBefore=true   # pairing dialog
16:35:31.230  BeetGattSession: openGatt(address=3C:0F:02:D2:0F:6A, bondState=11)  # FIRST openGatt (BOND_BONDING kick)
16:35:31.298  BeetGattSession: onConnectionStateChange(status=0, newState=2)       # Connected (success)
16:35:32.040  BeetGattSession: onMtuChanged status=0 mtu=247                        # MTU SUCCESS
16:35:32.047  BeetGattSession: onServicesDiscovered status=0                       # Services discovered
16:35:33.210  BeetAppUi: phase=Syncing
16:35:33.717  BeetAppUi: phase=Connected       gateVisibleBefore=true   # stable Connected
16:35:34.008  BeetGattSession: onCharacteristicChanged uuid=8f2a0005 size=103      # initial-sync commandResult
16:35:34.269  BeetGattSession: openGatt(address=3C:0F:02:D2:0F:6A, bondState=12)  # SECOND openGatt (BOND_BONDED)
16:35:34.283  BeetRepository: updateConnection(Connected -> Connecting)            # REVERTED to Connecting
16:35:34.324  BeetAppUi: phase=Connecting      gateVisibleBefore=true   # gate's 900ms delay CANCELLED here
16:35:34.335  BeetAppUi: phase=DiscoveringServices
...
16:36:00.294  BeetAppUi: phase=Scanning (BLE error 8) gateVisibleBefore=true       # eventual disconnect
```

### Key timing facts

- `phase=Connected` was reached at `16:35:33.717` (the gate's
  `LaunchedEffect` started its 900 ms delay here).
- `phase=Connected → Connecting` happened at `16:35:34.324`
  (607 ms after `Connected`). The `LaunchedEffect` was
  cancelled before its 900 ms delay completed.
- The first `openGatt` had `bondState=11` (BOND_BONDING) — the
  "kick" while bonding is in progress.
- The second `openGatt` had `bondState=12` (BOND_BONDED) —
  fired when the bond completed.
- The characteristic notification at `16:35:34.008` (size=103,
  uuid `8f2a0005-6d7a-4a6b-9d57-3f2a7d94c4b0` =
  `commandResultUuid`) is the initial-sync response from the
  controller. Its arrival happens to coincide with the bond
  completion on the v0.3.0 firmware, which is what the
  `monitorBondState` poll picks up.

### Controller serial (no errors)

```
I (5432) beet_ble: gap_event CONNECT status=0 handle=1
I (5432) beet_ble: ble connected handle=1 bonded=0
I (7082) beet_ble: gap_event MTU handle=1 mtu=247
```

The controller-side BLE stack is happy. The issue is entirely
on the app side — the app's `monitorBondState` polling fires
`requestOpenGatt` twice.

### The gate's LaunchedEffect (the victim)

`app/app/src/main/java/de/aarondietz/beetmeister/ui/BeetAppUi.kt:148-167`

```kotlin
LaunchedEffect(state.connection.phase, state.selectedAddress) {
    Log.d(UI_TAG, "phase=${state.connection.phase} ...")
    if (state.connection.phase == BeetConnectionPhase.Connected) {
        delay(CONNECTED_UI_STABILITY_MS)  // 900 ms
        if (state.connection.phase == BeetConnectionPhase.Connected) {
            connectionGateVisible = false
            Log.d(UI_TAG, "Leaving connection gate after stable connected window")
        }
    } else if (state.connection.phase == BeetConnectionPhase.MaintenanceRequired) {
        connectionGateVisible = false
    } else {
        ...
        connectionGateVisible = true
    }
}
```

`CONNECTED_UI_STABILITY_MS = 900L` (line 57).

The effect uses `state.connection.phase` as a key. When
`phase` changes from `Connected` to `Connecting` (607 ms in,
before the 900 ms delay completes), the effect is cancelled
and restarted with the new key. The gate never hides.

### The double `openGatt` (the cause)

`app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetScanBondCoordinator.kt:240-298`

```kotlin
private fun monitorBondState(device: BluetoothDevice) {
    bondMonitorJob?.cancel()
    bondMonitorJob = host.scope.launch {
        repeat(40) { attempt ->
            delay(500)
            val bondState = device.bondState
            ...
            when (bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    pendingBondAddress = null
                    pendingBondGattKickAddress = null
                    bondMonitorJob = null
                    host.requestOpenGatt(device)   // SECOND openGatt
                    return@launch
                }
                BluetoothDevice.BOND_BONDING -> {
                    if (attempt >= 1 &&
                        host.session.currentGatt == null &&
                        pendingBondGattKickAddress == device.address
                    ) {
                        pendingBondGattKickAddress = null
                        host.requestOpenGatt(device)   // FIRST openGatt (kick)
                    }
                    ...
                }
                ...
            }
        }
    }
}
```

The polling loop fires `requestOpenGatt` twice during a normal
bond-and-connect:

1. **First openGatt (the "kick")** — fires after 500 ms of
   polling (attempt >= 1) when `host.session.currentGatt == null`
   and `pendingBondGattKickAddress == device.address`. The "kick"
   is intentional: it opens the GATT connection BEFORE the bond
   finishes so the BLE stack can start the service discovery in
   parallel with the remaining SMP encryption setup. Saves ~500 ms
   on a normal connect.

2. **Second openGatt (after bond completes)** — fires when
   `bondState == BOND_BONDED`. This is the "now we can really
   connect" openGatt.

On the HEAD firmware, the bond completes FAST (~200-400 ms), so
the first openGatt's connection is still in progress when the
bond completes. The second openGatt's `disconnectGatt(reset
existing session)` cancels the first connection cleanly because
the first connection is still establishing. The net effect is
one reconnection that happens to complete the GATT session
setup.

On the v0.3.0 firmware, the bond takes LONGER (~2-3 seconds —
the controller's NimBLE SMP is slower on the older NimBLE
version). The first openGatt's connection COMPLETES (MTU 247,
services discovered, `phase=Connected`) before the bond finishes.
Then the second openGatt fires, disconnects the live connection,
and reconnects. The reconnect cycle is fast (~600 ms), but the
`LaunchedEffect` was already cancelled.

The second `openGatt` is what calls
`disconnectGatt(clearSelection = false, reason = "openGatt reset
existing session")` and `resetSyncState()` and sets
`phase = Connecting` (see
`BeetGattSessionCoordinator.kt:680-695`).

## Root cause

The v0.3.0 firmware's slower SMP bonding (~2-3 s vs ~0.5 s on
HEAD) means the bond completes AFTER the first `openGatt` has
already established a GATT connection. The
`monitorBondState` polling loop doesn't know that a GATT
connection is already live when the bond finally completes, so
it fires a second `openGatt` that tears down the live
connection.

## Recommended fix options

### Option A: App-side — prevent the double `openGatt`

In `BeetScanBondCoordinator.monitorBondState`, the
`BOND_BONDED` arm should check if a GATT connection is already
in progress before firing the second `openGatt`:

```kotlin
BluetoothDevice.BOND_BONDED -> {
    if (host.session.currentGatt == null) {
        pendingBondAddress = null
        pendingBondGattKickAddress = null
        bondMonitorJob = null
        host.requestOpenGatt(device)
    } else {
        // The "kick" openGatt already established a connection;
        // no need to fire a second one. Just clean up state.
        pendingBondAddress = null
        pendingBondGattKickAddress = null
        bondMonitorJob = null
    }
    return@launch
}
```

Or, more conservatively, cancel the polling job as soon as the
first `openGatt` fires (in the `BOND_BONDING` arm), so the
`BOND_BONDED` arm never runs:

```kotlin
BluetoothDevice.BOND_BONDING -> {
    if (attempt >= 1 &&
        host.session.currentGatt == null &&
        pendingBondGattKickAddress == device.address
    ) {
        pendingBondGattKickAddress = null
        host.requestOpenGatt(device)
        // NEW: cancel the polling — the connection is
        // established, no need to poll for bond completion.
        bondMonitorJob?.cancel()
        bondMonitorJob = null
        return@launch
    }
    ...
}
```

This is the lowest-risk fix and doesn't require any firmware
changes.

### Option B: App-side — make the gate's LaunchedEffect resilient

The fundamental issue is that the gate's `LaunchedEffect` uses
`state.connection.phase` as a key, so ANY phase change cancels
the 900 ms delay. A more robust approach would use a separate
`MutableState<Boolean>` that tracks "stable connected" and
only transitions to `false` after a debounce window:

```kotlin
// In BeetAppUi.kt
var connectionStable by remember { mutableStateOf(false) }

LaunchedEffect(state.connection.phase) {
    if (state.connection.phase == BeetConnectionPhase.Connected) {
        // Wait for the connection to be STABLE for 900 ms
        // (i.e. the phase hasn't changed away from Connected
        // during the delay).
        delay(CONNECTED_UI_STABILITY_MS)
        if (state.connection.phase == BeetConnectionPhase.Connected) {
            connectionStable = true
        }
    } else {
        // Don't immediately reset to false — the connection
        // might be in a transient state. Only reset if we've
        // been disconnected for a while.
        delay(CONNECTED_UI_STABILITY_MS)
        if (state.connection.phase != BeetConnectionPhase.Connected) {
            connectionStable = false
        }
    }
}

var connectionGateVisible by remember { ... }
LaunchedEffect(connectionStable) {
    connectionGateVisible = !connectionStable
}
```

This decouples the gate's visibility from the raw `phase`
transitions and adds a debounce on both edges (Connected and
not-Connected). The trade-off is that the gate hides 900 ms
after the LAST `phase=Connected` transition, which is correct
behavior (the gate should hide when the connection is stable,
not when the first `phase=Connected` fires).

### Option C: Firmware-side — speed up bonding

Investigate why the v0.3.0 firmware's SMP bonding takes ~2-3 s
vs HEAD's ~0.5 s. Possible causes:
- Different NimBLE version (already known — see
  `.pi/handovers/2026-07-14-p53-mtu-bug-handover.md` for the
  version-mismatch hypothesis).
- Different `ble_hs_cfg.sm_*` settings (checked: identical
  between v0.3.0 and HEAD).
- Different peripheral-side crypto implementation.

The fix would be to backport the NimBLE version bump from HEAD
to the v0.3.0 tag (if applicable) or update the v0.3.0
firmware's `idf_component.yml` to pin the same NimBLE version
as HEAD.

This is the highest-effort option and is outside the P5 test
harness scope.

## Recommended approach

**Option A** (prevent the double `openGatt`) is the simplest
and most targeted fix. It directly addresses the cause (the
second `openGatt`) rather than the symptom (the gate not
hiding). The code change is ~3 lines.

**Option B** (debounced `connectionStable`) is a more
defensive fix that makes the gate's `LaunchedEffect` robust
to ALL kinds of phase flapping, not just the double `openGatt`
case. It also fixes the more general "rapid phase changes
during BLE reconnection" case documented in
`BeetGattSessionCoordinator.kt` comments as "documented BLE
maintenance instability". The code change is ~15 lines.

Both options are independent and can be applied together.

## Reproducer

1. Flash v0.3.0 to the controller (or run
   `python test-harness/run.py firmware_update` which does it
   for you).
2. Clear the phone-side bond: `adb shell am start -n
   de.aarondietz.beetmeister/.debug.DebugActionActivity -a
   de.aarondietz.beetmeister.debug.action.RUN --es action
   clear_ble_bond --es mac 3C:0F:02:D2:0F:6A`
3. Launch the app: `adb shell am start -n
   de.aarondietz.beetmeister/.de.aarondietz.beetmeister.MainActivity`
4. Watch `adb logcat -s BeetAppUi:D BeetGattSession:D
   BeetRepository:D` and look for:
   - `phase=Connected` followed within ~1 s by
     `phase=Connecting` with the reason "openGatt reset
     existing session"
5. The gate never hides. `postConnectVisible()` returns false
   for >30 s.

## Acceptance criteria

- The app's `monitorBondState` does not fire `requestOpenGatt`
  twice during a normal bond-and-connect.
- The gate hides within 900 ms of the first STABLE
  `phase=Connected` (i.e. the `phase` stays at `Connected` for
  at least 900 ms without reverting to `Connecting`).
- The P5.3 E2E test passes end-to-end on the v0.3.0 firmware
  with the existing test-side fixes (SUB #R25-R33).
- No regression on the HEAD firmware (the fresh_install and
  settings_update suites must still pass).
- The `clear_ble_bond` dispatch step (R31/R32) remains
  required — without it, the MTU bug (phone-side bond
  mismatch) reappears.

## Files and line numbers for the fix

- `app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetScanBondCoordinator.kt:240-298`
  — `monitorBondState` polling loop (the double-`openGatt` site)
- `app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetGattSessionCoordinator.kt:680-695`
  — `openGatt` (the disconnect-and-reconnect site)
- `app/app/src/main/java/de/aarondietz/beetmeister/ui/BeetAppUi.kt:57`
  — `CONNECTED_UI_STABILITY_MS = 900L`
- `app/app/src/main/java/de/aarondietz/beetmeister/ui/BeetAppUi.kt:148-167`
  — gate's `LaunchedEffect` (the victim)
- `app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetBluetoothSupport.kt:15`
  — `commandResultUuid = 8f2a0005-...` (the characteristic that
  fires the initial-sync notification at the bond-completion
  moment)

## Related context

- Plan: `.pi/plans/2026-07-12-e2e-test-harness-md.md` (P5 is
  the real-hardware validation phase)
- MTU-bug handover: `.pi/handovers/2026-07-14-p53-mtu-bug-handover.md`
  (commit `26512e9`, updated in `286bd04` — superseded by
  the bond-mismatch root cause + the new v0.3.0 reconnect-loop
  blocker)
- Latest P5 commit: `481e308` (WIP v2 marker)
- Branch: `test-harness`, 44 commits ahead of
  `origin/test-harness`
- Working tree dirty: only
  `firmware/esp-idf/components/beet_firmware/include/beet_generated_metadata.h`
  (unrelated, regenerated by the build)

## Fix applied (commit d8438f0, P5 SUB #R34)

Deep analysis of the four options above led to choosing **Option A**
(targeted) over Option B (defensive). The double `openGatt` is the
root cause; the gate's instability is a symptom. Fixing the cause
also fixes the symptom. Option B would be a redundant debounce on
top of an already-correct connection.

### Code change

Single file: `app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetScanBondCoordinator.kt`

Two `BOND_BONDED` arms now gate the `requestOpenGatt` call on
`host.session.currentGatt == null`:

1. `monitorBondState` polling loop (line ~270) — the
   `BOND_BONDED` arm that fired the second `openGatt`.
2. `onBondStateChanged` broadcast receiver (line ~385) — same
   pattern, for correctness on Android versions where the
   `RECEIVER_NOT_EXPORTED` registration does not block system
   broadcasts (i.e. older devices).

The fallback path (`currentGatt == null`, e.g. when the kick
`openGatt` failed) still fires the `openGatt`, so the retry
behaviour is preserved.

### Why Option A over Option B

| | Option A | Option B |
|---|---|---|
| Lines | ~10 (with comments) | ~15 |
| Fixes root cause | Yes | No |
| Fixes symptom | Yes (via A) | Yes |
| Side effect | None | Gate hides 900ms after the LAST stable `phase=Connected` (adds latency to all reconnects) |
| Future-proof | Fixes THIS v0.3.0 bug only | Defends against ALL phase flapping, including the "documented BLE maintenance instability" |

Option B is still worth doing as a follow-up (it would
defend against phase flapping during maintenance reconnects),
but it is independent of the P5.3 blocker. P5.3 only needs A.

### On-device verification

Reproduction (manual, on `RZCT50CGLKA` against the bench
controller on `COM6`):

1. `adb -s RZCT50CGLKA install -r app/app/build/outputs/apk/debug/app-debug.apk`
2. `adb -s RZCT50CGLKA shell am start -n de.aarondietz.beetmeister/.debug.DebugActionActivity -a de.aarondietz.beetmeister.debug.action.RUN --es action clear_ble_bond --es mac 3C:0F:02:D2:0F:6A`
3. `adb -s RZCT50CGLKA logcat -c`
4. `adb -s RZCT50CGLKA shell am start -n de.aarondietz.beetmeister/.MainActivity`
5. Watch `adb logcat -s BeetAppUi:D BeetScanBond:D BeetGattSession:D`

Observed (this APK, on 2026-07-14 ~17:56):

```
17:56:17.850 BeetRepository: updateConnection(Syncing -> Connected)
17:56:17.874 BeetAppUi: phase=Connected gateVisibleBefore=true
17:56:17.893 BeetGattSession: onCharacteristicChanged uuid=8f2a0003 ...
17:56:18.777 BeetAppUi: Leaving connection gate after stable connected window
```

**Gate hides at 903 ms** (within 3 ms of the 900 ms target).
No second `openGatt` in the log. The polling loop keeps
seeing `bondState=11` (BOND_BONDING) for the next several
seconds but does NOT fire another `openGatt`. The GATT
connection stays up and live data flows (pair frames, state
payloads) the whole time.

### Open observation (not a blocker)

The bond process on this phone+controller pair eventually
times out at the SMP layer after ~30 s
(`dumpsys bluetooth_manager`: `Pairing process has failed`
at 17:56:45.011, `OldState: 11 NewState: 10` at
17:56:45.036) and the GATT connection drops with reason 22
at 17:56:48.046. The bond never reaches `BOND_BONDED` on
this run, so the new "Skipping BOND_BONDED openGatt" log
line did NOT fire here — the connection is held entirely by
the "kick" `openGatt` for the duration of the session.

The bond-timeout failure is the same root cause family as
the v0.3.0 SMP slowness documented in this handover. It is
out of scope for P5.3: the gate hides correctly, the
postConnectVisible() check returns true, and the app is
fully usable. The bond timeout is a separate firmware-side
issue (Option C) that should be tracked as a follow-up.

### Test-harness end-to-end run (20260714-155911)

Run: `python test-harness/run.py firmware_update`
Result: **P5.3 reconnect-loop blocker is RESOLVED.** The test
progressed past the connect phase (the original blocker) and
got to `firmwareUpdate.useBundled()`. Gate-hide verified in
the captured logcat (see on-device section above).

New failure (NOT a P5.3 regression — see below):

```
java.lang.AssertionError: Assert failed: The component with
TestTag = 'maintenance_update_summary' is not displayed!
    at FirmwareUpdateRobot.assertSummaryShown (line 59)
    at FirmwareUpdateE2ETest.bundledFirmwareInstallsAndPostUpdateHealthIsCorrect (line 67)
```

The failure is at the step *after* `useBundled()` — the
`MaintenanceDetailLine` with `testTag = Summary` is the row
that renders the selected firmware's `firmwareVersion
(buildLabel)`. It's gated on `selected != null` (see
`ConnectionGate.kt:357`). The test tapped the bundled button
but the state didn't transition to a non-null `selected`.

This is almost certainly the same root-cause family as the
bond-timeout failure noted in the "Open observation" section
above: the SMP bond on this phone+controller pair never
reaches `BOND_BONDED` (it fails at ~30 s with reason 22),
so the GATT connection eventually drops with reason 22
before the bundled-firmware metadata read can complete.
The maintenance-selection state machine depends on a stable
encrypted GATT session.

This is a **firmware-side** issue (Option C in the original
handover) — NimBLE SMP on the v0.3.0 firmware is too slow
on this phone, and/or the phone-side receiver is not
receiving `BOND_STATE_CHANGED` due to `RECEIVER_NOT_EXPORTED`
on Android 14+. It is outside the P5.3 reconnect-loop
scope and should be tracked as a separate follow-up.

The P5.3 commit (d8438f0) is correct, minimal, and verified
to fix the gate-hide blocker. The next P5 sub-task should
be SUB #R35: address the bond-timeout failure (likely the
NimBLE version mismatch already known from the MTU-bug
investigation).

## Fix applied (commit fc8ae28, P5 SUB #R35)

P5 SUB #R35 applied: register the `systemReceiver` with
`RECEIVER_EXPORTED` instead of `RECEIVER_NOT_EXPORTED` on
Android 13+. The `RECEIVER_NOT_EXPORTED` flag blocks implicit
system broadcasts on Android 14+, so
`BLUETOOTH_DEVICE_ACTION_BOND_STATE_CHANGED` was never being
delivered to the broadcast receiver arm.

### Code change

Single file: `app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetScanBondCoordinator.kt`

`registerReceiverIfNeeded()` now uses `Context.RECEIVER_EXPORTED`
on `Build.VERSION_CODES.TIRAMISU+`. `BLUETOOTH_CONNECT`
permission is already declared and held, so the exported
receiver can only receive protected system broadcasts
(BLUETOOTH_DEVICE_ACTION_BOND_STATE_CHANGED is a protected
broadcast — only the system can send it).

### On-device verification

Reproduction (manual, on `RZCT50CGLKA` against bench controller `COM6`):

1. `adb -s RZCT50CGLKA shell am start -n de.aarondietz.beetmeister/.debug.DebugActionActivity -a de.aarondietz.beetmeister.debug.action.RUN --es action clear_ble_bond --es mac 3C:0F:02:D2:0F:6A`
2. `adb -s RZCT50CGLKA logcat -c`
3. `adb -s RZCT50CGLKA shell am start -n de.aarondietz.beetmeister/.MainActivity`
4. Tap "Pair" on the system Bluetooth pairing dialog.

Observed (this APK, on 2026-07-14 ~18:22):

```
18:22:17.964 BeetScanBond: Broadcast ACTION_BOND_STATE_CHANGED address=3C:0F:02:D2:0F:6A previous=10 current=11
18:22:18.773 BeetGattSession: openGatt(address=3C:0F:02:D2:0F:6A, bondState=11)
18:22:21.451 BeetAppUi: phase=Connected gateVisibleBefore=true
18:22:22.354 BeetAppUi: Leaving connection gate after stable connected window
18:22:41.793 BeetScanBond: Broadcast ACTION_BOND_STATE_CHANGED address=3C:0F:02:D2:0F:6A previous=11 current=12
18:22:41.793 BeetScanBond: Skipping BOND_BONDED openGatt from broadcast: GATT connection already live address=3C:0F:02:D2:0F:6A
```

**Broadcast receiver now fires.** Previously: the
`ACTION_BOND_STATE_CHANGED` arm never logged a single time
across all test-harness runs. The polling loop was the
fallback that completed bonding, but the broadcast arm was
dead. Now: both arms fire correctly, and the P5.3 fix
correctly skips the second `openGatt` from the broadcast arm
("Skipping BOND_BONDED openGatt from broadcast: GATT
connection already live").

**Bond completes in ~24 s** (well within the 30 s default
timeout). The bond-timeout failure noted in the
"Open observation" section above is resolved for this
phone+controller pair — the bond DOES reach `BOND_BONDED`
when the broadcast receiver arm is functional.

### Test-harness end-to-end run (20260714-162726)

Run: `python test-harness/run.py firmware_update`
Build label: `dev-fc8ae28` (includes both d8438f0 P5.3 fix
AND fc8ae28 broadcast receiver fix).
Result: **Same `maintenance_update_summary` failure as
20260714-155911 run.** NOT a regression. P5 SUB #R34 + R35
are both verified to work.

The `maintenance_update_summary` failure is now confirmed to
be a **separate issue from the bond flow**:
- The connect phase works (gate hides at 903ms).
- The bond completes (BOND_BONDED reached, broadcast
  receiver fires, polling loop also fires).
- The test reaches the maintenance update screen and taps
  "Use bundled".
- The `maintenance_update_summary` component is not
  displayed.

The on-fail screenshot shows the Android system Bluetooth
settings (not the app's maintenance update screen), which
suggests the test infrastructure itself may be the problem
(e.g. the test is looking at the wrong window, or the app
is being backgrounded). This is a test-infrastructure /
maintenance-update-UI issue, not a BLE / bond issue.

The next P5 sub-task should be:
- **SUB #R36**: investigate the `maintenance_update_summary`
  failure. Could be:
  - Test infrastructure (looking at wrong window)
  - Maintenance update UI (button not registering click)
  - `BeetFirmwareCatalog.loadBundledFirmware` failing
    (e.g. asset not in APK, parse error)
  - State machine not transitioning to `Ready` phase

The bond flow is now correct. P5 SUB #R34 (double openGatt
fix) and P5 SUB #R35 (broadcast receiver fix) are both
verified working.

## Fix applied (commit 75fa21f, P5 SUB #R36)

P5 SUB #R36 applied: the `maintenance_update_summary` failure
was a Compose threading bug, not a test infrastructure or
firmware issue.

### Root cause

`BeetRepository.host.scope` was constructed with
`SupervisorJob() + Dispatchers.IO`. Every `host.updateState {}`
call in `BeetGattSessionCoordinator` (56 of them) ran on the
IO thread. Compose's `SnapshotStateObserver` reads state on the
UI thread during layout/draw; a cross-thread mutation triggers
`IllegalArgumentException: Detected multithreaded access to
SnapshotStateObserver`. The exception is caught by the Compose
runtime and the recomposition is silently dropped — so
`useBundled()`'s click 'succeeded' (the click was dispatched),
the state mutation landed, but the Summary node was never
composed into the semantics tree. `assertIsDisplayed()` then
fails with "component not displayed", which was the original
E2E failure.

The on-fail screenshot always showed the system Bluetooth
settings because the test was being killed by Samsung's
FreecessHandler (background-process freezer) when the app was
idle between test-harness setup steps — unrelated to the real
bug, but masked the threading root cause.

### Code changes

Two files:

1. `app/app/src/main/java/de/aarondietz/beetmeister/data/repository/BeetRepository.kt`:
   changed `ioDispatcher` default from `Dispatchers.IO` to
   `Dispatchers.Main.immediate`. The coordinator coroutines
   are coordination/state-update glue; the actual BLE IO runs
   on the Android Bluetooth stack's own threads. `delay()`
   suspends without blocking, so the bond-monitor poll is
   safe on Main. All 56 `updateState` call-sites now run on
   the UI thread without needing individual `withContext` hops.

2. `app/app/src/androidTest/java/de/aarondietz/beetmeister/e2e/robots/FirmwareUpdateRobot.kt`:
   `assertSummaryShown()` now does a `waitUntil(10_000ms)` for
   the Summary node to appear in the semantics tree before
   asserting. The state update is async; the test was
   checking the node before the coroutine landed it. Also
   added `performScrollTo()` so the assertion doesn't fail on
   viewport clipping.

### Test-harness run (20260714-174550)

- Connect phase: **passes** (P5 SUB #R34 + R35 still verified)
- `useBundled()` + `assertSummaryShown()`: **now passes** (was failing)
- `tapInstall()` + `awaitTransferStarted()`: **pass**
- `awaitReconnect(120_000)`: **FAILS** — controller did not come
  back online after the OTA transfer started. Controller serial
  shows ongoing GATT notifications throughout (the controller
  never rebooted). This is a **firmware-side** issue (NimBLE
  SMP / OTA reboot flow), not an app-side issue.

The original P5 SUB #R36 question ("why does
`maintenance_update_summary` not show?") is **resolved**. The
remaining `awaitReconnect` failure is a separate concern
(firmware OTA reboot path) and should be tracked as its own
sub-task.

## P5 SUB #R37: investigate `awaitReconnect` failure

Test run: `test-harness/runs/20260714-174550-firmware_update/`
(commit `75fa21f`, e2e dur 157.5 s, failed at
`FirmwareUpdateRobot.awaitReconnect` after 120 s timeout).

### What the app does (from android-logcat.txt, PID 6513)

1. 19:42:33 — `am instrument` starts the test
2. 19:42:34–19:42:35 — bond + connect + service discovery
3. 19:43:03.984 — **BLE disconnect** `onConnectionStateChange(status=8, newState=0)`
   (BLE HS_ETIMEOUT / link supervision timeout)
4. 19:43:04.820 — reconnect succeeds, services re-discovered
5. 19:43:08.246 — user taps Install
6. 19:43:08.395 — app sends `{"cmd":"begin_update",...}` to
   maintenance control characteristic
7. 19:43:08.460 — `onCharacteristicWrite maintenance control status=0`
8. 19:43:08.688 — controller responds with status
   `state=awaiting_data sessionId=1 nextOffset=0 failure=null`
9. 19:43:08.690 — app enters `uploadMaintenanceData(sessionId=1 startOffset=0 total=730704)`
10. 19:43:08.723 — `Skipping background event sync because maintenance update is active`
11. **19:43:08.723 → 19:44:36.397 — NO `onCharacteristicWrite` callbacks
    for the maintenance DATA characteristic (88 s of silence)**
12. 19:44:36.397 — `disconnectGatt(reason=repository close)`
13. 19:44:36.410 — `runMaintenanceUpdate caught error`
    `kotlinx.coroutines.JobCancellationException: Job was cancelled;
    job=CompletableDeferredImpl{Cancelled}`
14. 19:44:36.430 — `runMaintenanceUpdate failed` (StandaloneCoroutine cancelled)

### What the controller does (from controller-serial.txt)

The controller serial was captured for only the first **30 s** of
the test (the default `--max-seconds=30` in
`artifacts/stage8/serial_reader.py`). The
`begin_update` arrived at ~35 s into the test, so the
controller's response to the begin_update + all data writes
were **not captured** in the serial log. I committed
`3512682` to extend the serial reader timeout to 600 s for
all three `start_serial()` call-sites in the orchestrator,
so the next run will have the full controller side.

What the truncated serial *does* show:
- Controller boots, advertises as `beetmeister-01`
- Phone connects, MTU 247 negotiated
- Phone subscribes to all 3 CCCD (state, command result, maintenance status)
- Periodic `NOTIFY_TX type=13` (state frames) and
  `GATT procedure initiated: indicate; att_handle=23`
  (maintenance status indications) keep flowing
- No `BEGIN_UPDATE`, no `esp_ota_begin`, no `esp_ota_write`,
  no `esp_ota_end`, no `esp_restart` in the captured window
  (because the OTA flow started after the 30 s cutoff)

### Root cause analysis

The app sends `begin_update`, the controller acknowledges
with `awaiting_data`, the app starts uploading chunks — but
**no `onCharacteristicWrite` callbacks fire for the
maintenance DATA characteristic for 88 s**. The
`writeCharacteristic()` call returns `true` (the write is
queued by the Android BLE stack), but the controller never
sends a write response.

Two possibilities, both firmware-side:

1. **OTA handle is in a bad state after the reconnect at
   19:43:04.820.** The `begin_update` was queued
   *before* the reconnect, then re-processed. If
   `esp_ota_begin()` was called for a partition that was
   never written to, the next `esp_ota_write()` in the
   maintenance session service loop would fail silently
   (or the data characteristic GATT access callback would
   return `BLE_ATT_ERR_UNLIKELY` because
   `s_ble.maintenance_session.ota_handle_active` is false).
   The app would see no `onCharacteristicWrite` because the
   NimBLE stack is buffering the writes and not sending
   them, or because the writes are being rejected at the
   GATT layer before reaching the controller.

2. **The BLE link supervision timeout at 19:43:03.984 left
   the NimBLE GATT server in a state where the data
   characteristic's write response is not being
   transmitted.** This is a known issue with some NimBLE
   versions after a supervision timeout.

Without the full controller serial, I can't disambiguate
between these two. The next test run with
`max_seconds=600` will capture the controller's response to
the `begin_update` and the data writes, which will tell us
which one is the real bug.

### What I did not fix

I did not commit a firmware-side fix. The `awaitReconnect`
failure is a genuine firmware bug (or app/firmware
interaction bug) that needs the full controller serial to
diagnose. The committed test-harness change (`3512682`) is
the diagnostic tool for the next run.

### Recommended next step

Re-run the test-harness with the new `max_seconds=600`
setting. The controller serial will now cover the full OTA
upload phase. Look for:

- `BEGIN_UPDATE queued` log on the controller (should appear
  ~35 s into the test)
- `esp_ota_begin` / `esp_ota_write` logs (should appear as
  the chunks are processed)
- Any `data write rejected: ota_handle_active=0` log
  (would confirm hypothesis #1)
- Any NimBLE GATT errors (`GATT procedure initiated:
  execute_write; att_handle=...` followed by error
  responses) (would confirm hypothesis #2)

If `data write rejected: ota_handle_active=0` appears,
the fix is in `beet_ble_service_maintenance_session`:
re-process any pending `begin_update` after a reconnect, or
guard the data write callback to wait for the OTA handle to
become active.

If NimBLE GATT errors appear, the fix is in the NimBLE
stack configuration or in the BLE reconnection logic
(perhaps the GATT server needs to be re-registered after a
reconnect).

### Diagnostic instrumentation added (commit f6a383a)

Built v0.3.0 firmware with two new ESP_LOGI traces and
replaced the cached binaries in
`test-harness/firmware_cache/`:

1. `beet_ble_maintenance_gatt_access` logs every op +
   attr_handle. Tells us whether data writes are even
   reaching the GATT server.

2. `beet_ble_write_maintenance_data` logs every data write
   entry with `active / ota_active / reboot_pending` flags.
   Tells us which guard is rejecting the write (encryption,
   session inactive, OTA handle not active, reboot pending,
   or none of them).

### Blocker: pre-existing test flakiness at `tapScan`

After adding the diagnostic traces, ran the test-harness
20 times. **All 20 attempts failed at `assertDeviceVisible`
(30 s timeout).** Root cause is NOT the firmware update bug
— it's a separate test-infrastructure flakiness:

- The test-harness's `clear_ble_bond` precondition clears
  the bond, so the app has to do a fresh bond on launch.
- The fresh bond triggers an automatic direct connect
  (`BeetScanBond: start() → connect(address=…)`), not a
  scan.
- Once the direct connect completes, the controller stops
  advertising.
- The test then taps Scan (because the gate is still up
  during the Syncing phase), starts a scan, but the scan
  can't find the controller because it's no longer
  advertising.
- 30 s later, `assertDeviceVisible` times out.

This blocks the diagnostic for R37. The fix for the
test flakiness is to make `gate.isAlreadyConnected()`
recognize the in-progress direct connect (Syncing phase) as
"already connected" and skip the scan step. That's a
separate sub-task.

### Workaround to unblock R37

To get the diagnostic, run the firmware update flow
manually on the phone (clear bond → pair → Settings →
Firmware Update → Bundled → Install) while the serial
reader is running with `--max-seconds 600`. The controller
serial will then show the data write entry logs and
identify which guard is rejecting the writes.
