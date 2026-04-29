# BeetMeister Verification And Acceptance Specification

## Scope

This document turns the baseline requirements into verifiable activities and release gates.

## Test pyramid

| Level | Purpose | Minimum content |
| --- | --- | --- |
| Host-simulated | Fast logic verification with mocks and deterministic inputs | conversion logic, duration lookup, block logic, scheduler, persistence rules |
| QEMU smoke | Boot and control-flow smoke validation without hardware flashing | boot, partition access abstraction, wake path, command-path smoke |
| Target-based ESP32-S3 | Firmware behavior on real silicon | NVS, ADC, GPIO, sleep wake reasons, BLE, Wi-Fi |
| Hardware bench | End-to-end behavior with actual board, relay, sensors, battery, and pumps | analog behavior, pump concurrency, voltage sag, communication and persistence |
| Soak | Long-duration stability | 24-hour or longer wake, sleep, watering, reconnect, and OTA scenarios |

## Requirement traceability matrix

| Requirement | Verification activities |
| --- | --- |
| `RQ-001` | `VT-001` target boot inventory, `VT-021` BLE pair-state enumeration |
| `RQ-002` | `VT-002` host pair-model tests |
| `RQ-003` | `VT-003` host scheduler interval test, `VT-022` target wake scheduler test |
| `RQ-004` | `VT-004` host no-comms autonomy test, `VT-032` bench Wi-Fi outage scenario |
| `RQ-005` | `VT-023` MQTT interface test, `VT-024` BLE interface test, `VT-033` OTA trigger test |
| `RQ-006` | `VT-005` host moisture conversion table tests |
| `RQ-007` | `VT-006` host default calibration test |
| `RQ-008` to `RQ-013` | `VT-007` host watering lookup boundary tests |
| `RQ-014` | `VT-008` host automatic pre-run timing test, `VT-025` target runtime timing test |
| `RQ-015` | `VT-009` host delta-threshold comparison tests |
| `RQ-016` | `VT-010` host block-duration test, `VT-034` bench failed-sanity scenario |
| `RQ-017` | `VT-011` host blocked-pair rejection tests, `VT-023` MQTT reset-block test |
| `RQ-018` | `VT-012` host manual-start gating tests, `VT-024` BLE manual-start rejection test |
| `RQ-019` | `VT-013` host queue fairness test, `VT-035` bench three-pump concurrency test |
| `RQ-020` | `VT-026` target ADC battery classification test |
| `RQ-021` | `VT-014` host deep-low-battery transition test, `VT-036` bench 3.20 V idle test |
| `RQ-022` | `VT-015` host adaptive recovery-check cadence test, `VT-027` target wake-timer test |
| `RQ-023` | `VT-016` host idle-sleep inactivity test, `VT-037` bench low-power-entry test |
| `RQ-024` | `VT-017` host low-battery-abort test, `VT-038` bench watering sag-abort test |
| `RQ-025` | `VT-018` host calibration persistence test, `VT-028` target NVS calibration test |
| `RQ-026` | `VT-019` host snapshot persistence test, `VT-029` target reboot-recovery test |
| `RQ-027` | `VT-020` host event-record content test, `VT-030` target event-write test |
| `RQ-028` | `VT-031` target dedicated-partition test |
| `RQ-029` | `VT-020` host ring reconstruction test, `VT-030` target reboot scan test |
| `RQ-030` | `VT-023` MQTT discovery, command-path, and BLE-bond-clear management test |
| `RQ-031` | `VT-023` MQTT entity inventory test |
| `RQ-032` | `VT-024` BLE bonding, connect, state, and command test |
| `RQ-033` | `VT-024` BLE calibration test, `VT-028` target persistence test |
| `RQ-034` | `VT-033` OTA functional test |
| `RQ-035` | `VT-022` target relay-safe-boot test |
| `RQ-036` | `VT-023` MQTT state-enum test, `VT-024` BLE state-enum test |
| `RQ-037` | `VT-005` host invalid-reading tests, `VT-034` bench disconnect-sensor scenario |
| `RQ-038` | `VT-004` host no-comms test, `VT-032` bench offline autonomy scenario |
| `RQ-039` | `VT-033` OTA persistence and rollback tests |
| `RQ-040` | Document review gate before implementation milestone `M2` |

## Scenario-based test cases

### `VT-005` Moisture conversion and invalid-reading tests

- Input: default calibration with readings below wet, above dry, and at both boundaries.
- Expected result: values clamp to 100, 0, and exact boundary percentages with no overflow.
- Pass condition: computed percentages and invalid flags match the conversion rules for every vector.

### `VT-007` Watering lookup boundary tests

- Input: moisture values 100, 81, 80, 79, 70, 69, 60, 59, 50, 49, and 0.
- Expected result: durations 0, 0, 10, 60, 60, 120, 120, 180, 180, 240, and 240 seconds.
- Pass condition: every boundary matches the requirements exactly.

### `VT-009` Sanity-threshold tests

- Input: pre-run and post-run percentages with deltas 4.0, 3.1, 3.0, and 2.9.
- Expected result: only deltas strictly greater than 3.0 pass.
- Pass condition: 3.0 is rejected and causes block behavior.

### `VT-013` Queue fairness test

- Input: four simultaneous watering requests when three pumps are already allowed.
- Expected result: the fourth request remains `WAITING_FOR_SLOT` and starts next when the earliest active slot ends.
- Pass condition: no more than three pumps overlap and queue order is FIFO by acceptance time.

### `VT-020` Event-ring reconstruction tests

- Input: empty ring, partially filled ring, wrapped ring, and ring with one corrupted record.
- Expected result: highest valid `seq_no` is recovered, next slot is correct, newest-first readout is stable.
- Pass condition: reconstruction is deterministic and ignores corrupted records.

### `VT-023` Home Assistant MQTT tests

- Input: controller boot, reconnect, manual start command, stop command, reset block command, clear BLE bonds command, and broker outage.
- Expected result: discovery appears, state topics update, commands are acknowledged idempotently, BLE bonds can be cleared through Home Assistant, offline autonomy is preserved.
- Pass condition: Home Assistant entities remain usable and state after reconnect matches persisted controller truth.

### `VT-024` BLE contract tests

- Input: unbonded app connect, bonded app connect, subscribe, manual start, manual stop, reset block, calibration command, second bonded phone connect after the first disconnects, and reconnect after Home Assistant clears bonds.
- Expected result: unbonded GATT access is rejected, bonded access succeeds, controller emits required state frames, command results are returned, multi-phone bonding works over time, and cleared bonds force re-pairing.
- Pass condition: protocol messages match the BLE specification, unbonded reads and writes are denied, and persisted calibration survives reboot.

### `VT-033` OTA tests

- Input: valid image, wrong-target image, interrupted download, failed first boot, and low-battery OTA attempt.
- Expected result: valid image boots, wrong-target image is rejected, interrupted download preserves current image, rollback works, low-battery attempt is rejected.
- Pass condition: device remains bootable and persistent data remains intact in every case.

### `VT-035` Three-pump concurrency bench test

- Input: three active pumps plus one additional automatic request.
- Expected result: the fourth pair waits and does not energize until one active pump stops.
- Pass condition: oscilloscope or logged GPIO timeline shows no four-pump overlap.

### `VT-038` Watering low-battery abort bench test

- Input: controlled supply droop below 3.10 V during active watering.
- Expected result: pump stops immediately, event is logged with `LOW_BATTERY_ABORT`.
- Pass condition: pump-off edge and logged event both reflect the abort.

### `VT-039` System-event storage and coverage test

- Input: controller boot, entry to idle sleep, entry to deep-low-battery sleep, BLE connect, BLE disconnect, BLE bond success, BLE bond failure, and clear-bonds command.
- Expected result: watering history remains in `events` only, system events are written to `sysevents` only, and each captured system event carries event type, reason, battery voltage, boot identifier, uptime, and valid time-state metadata.
- Pass condition: event reads and summaries show the expected event classes in the correct ring with no watering or system-event mixing.

### `VT-040` Event timestamp and legacy-ignore test

- Input: new v2 event records with valid time, unresolved current-boot records, unresolved older-boot records, and legacy v1 records.
- Expected result: current-boot unresolved records are backfilled after `set_time`, older unresolved records remain persisted but hidden, and legacy records are ignored.
- Pass condition: history ordering remains by `seq_no`, no unresolved older-boot or legacy records are shown in normal history views, and backfilled current-boot timestamps are stable.

### `VT-041` App background event-sync test

- Input: initial connect with empty cache, reconnect with partially populated cache, and arrival of new live events while history download is active.
- Expected result: live device and pair state appears without waiting for full history download, only missing sequence numbers are requested, cached events are reused, and new streamed events appear without manual refresh.
- Pass condition: event-sync progress advances in the background, no duplicate events are persisted locally, and reconnect does not refetch already cached history unnecessarily.

### `VT-042` Events-screen graph and filter test

- Input: watering events spanning the last 24 hours and last 7 days plus mixed system-event types.
- Expected result: the graph totals differ correctly between `Last 24 hours` and `Last 7 days`, and system-event filters isolate Bluetooth, sleep, startup, and MQTT classes correctly.
- Pass condition: graph totals match the event data exactly and filter toggles show only the intended event classes.

### `VT-043` Overview running-since test

- Input: connected controller with reported uptime and a client connection timestamp.
- Expected result: the Overview screen computes `Running since` on the client from connection time and controller uptime rather than relying on persisted controller wall-clock history alone.
- Pass condition: the displayed `Running since` value advances consistently across reconnect and continues to match controller uptime.

### `VT-044` Event-sync progress-indicator UX test

- Input: connect to a controller with missing cached event history and observe the top-right connected-status area during background sync.
- Expected result: a visible clockwise outline-fill progress indicator is present while sync is active in the connected-status chip and clears when sync is complete.
- Pass condition: the indicator advances with background event download progress, uses the intended gray-to-primary outline-fill treatment, and does not obscure live connection state text.

## Entry and exit criteria by milestone

| Milestone | Entry criteria | Exit criteria |
| --- | --- | --- |
| `M0` Documentation baseline | Repo scaffolding exists | All normative docs in this package exist and are cross-consistent |
| `M1` Hardware bench bring-up | `M0` complete | Board powers safely, ADC inputs read, relay outputs stay off at boot |
| `M2` Core firmware logic | `M1` complete | Host tests for scheduler, conversion, blocks, storage, and battery all pass |
| `M3` Connectivity | `M2` complete | MQTT and BLE tests pass on target hardware |
| `M4` OTA and release candidate | `M3` complete | OTA tests, persistence tests, and release checklist all pass |

## Bench setups and external dependencies

- adjustable lab supply that can emulate 3.60 V to 3.00 V battery conditions
- at least one real ESP32-S3 controller board matching the hardware spec
- relay board and representative pumps with the intended pump power rail
- sensor test fixture or moisture-sensor substitute with controllable analog output
- local Wi-Fi network with reachable MQTT broker and Home Assistant instance
- Android device with BLE support
- local HTTP server hosting OTA images

## Release acceptance checklist

- All host-simulated tests pass.
- QEMU smoke tests pass for the selected release branch.
- Target-based ESP32-S3 tests pass on real hardware.
- Bench tests pass for battery, pump concurrency, sensor sanity, MQTT, BLE, and persistence.
- At least one 24-hour soak test passes with no invalid watering, missed scheduled checks, or corrupted persisted data.
- OTA upgrade from the previous release succeeds and preserves configuration and history.
- No open blocking defects remain in battery handling, pump scheduling, storage, BLE, MQTT, or OTA.
