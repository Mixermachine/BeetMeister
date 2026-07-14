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
