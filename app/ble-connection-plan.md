# BeetMeister App BLE Connection Plan

## Goal

Implement the Android app against the current BeetMeister BLE contract first.
The app shall work without Home Assistant or garden Wi-Fi and shall support nearby controller administration, calibration, and manual watering.

## Current Firmware Contract

The controller currently exposes a NimBLE GATT server with:

- Service UUID: `8f2a0001-6d7a-4a6b-9d57-3f2a7d94c4b0`
- `controller_info` read characteristic:
  - UUID `8f2a0002-6d7a-4a6b-9d57-3f2a7d94c4b0`
- `state_stream` notify characteristic:
  - UUID `8f2a0003-6d7a-4a6b-9d57-3f2a7d94c4b0`
- `control_point` write-with-response characteristic:
  - UUID `8f2a0004-6d7a-4a6b-9d57-3f2a7d94c4b0`
- `command_result` indicate characteristic:
  - UUID `8f2a0005-6d7a-4a6b-9d57-3f2a7d94c4b0`

All payloads are UTF-8 JSON.
All four characteristics require a bonded link before use.

## Initial App Scope

The first app slice should implement only the commands already live in firmware:

- `manual_start`
- `manual_stop`
- `reset_block`
- `store_calibration`

`manual_start` supports an optional `duration_s` field with valid values `1..1200`.

Do not implement app UI for `clear_ble_bonds` yet. Runtime `start_ota` is removed in favor of the maintenance update flow.
Those commands are not ready as a meaningful end-to-end user flow.

## Android Architecture

Use a layered structure:

1. `BleScanner`
   - scan for BeetMeister advertisements by service UUID and local name
   - expose a list of nearby devices

2. `BleConnectionManager`
   - connect and disconnect
   - manage bonding state
   - discover services and characteristics
   - request MTU
   - subscribe to notifications and indications

3. `BeetBleClient`
   - controller-specific GATT wrapper
   - read `controller_info`
   - subscribe to `state_stream`
   - write JSON to `control_point`
   - read command acknowledgements from `command_result`

4. `BeetRepository`
   - hold the latest device state and pair states
   - expose app-friendly state flows to the UI
   - serialize commands and correlate them with command results

5. `ViewModel` layer
   - `DeviceListViewModel`
   - `DeviceDashboardViewModel`
   - `PairDetailViewModel`
   - `CalibrationViewModel`

## Connection Workflow

### 1. Scan

- Request the required Android Bluetooth permissions for the device API level.
- Scan by BeetMeister service UUID.
- Show:
  - device name
  - address
  - last seen time
  - bonded / unbonded hint if available

### 2. Connect

- Connect to the selected controller.
- Discover services.
- Verify the BeetMeister service and all four characteristics exist.
- Request MTU increase.
- Read `controller_info`.

### 3. Bond

- If the phone is not bonded, trigger the system bonding flow before protected GATT operations.
- If bonding fails, show that as a pairing problem, not as controller offline.
- After bonding succeeds, reconnect if needed and continue service discovery.

### 4. Subscribe

- Enable notifications for `state_stream`.
- Enable indications for `command_result`.
- After subscription, wait for:
  - one device frame
  - one pair frame per pair
- Only after that mark the controller session as fully synced.

## State Model In The App

Maintain two separate in-memory models:

### Device state

- `batteryState`
- `batteryMv`
- `batteryPctApprox`
- `timeValid`
- `nextCheckInSeconds`
- `activePumps`
- `wifiConnected`
- `mqttConnected`

### Pair state

For `pair = 1..8`:

- `state`
- `moisturePct`
- `sensorMv`
- `blocked`
- `blockReason`
- `remainingSeconds`
- `source`

`state_stream` frames must update these incrementally rather than rebuilding everything from scratch each time.

## Command Handling

The app should use a small command model matching firmware exactly.

### Manual start

Example:

```json
{
  "cmd": "manual_start",
  "pair": 3,
  "duration_s": 120
}
```

Behavior:

- `duration_s` is optional
- if omitted, firmware uses the default manual duration
- if supplied, app must validate `1..1200` before sending

### Manual stop

```json
{
  "cmd": "manual_stop",
  "pair": 3
}
```

### Reset block

```json
{
  "cmd": "reset_block",
  "pair": 3
}
```

### Store calibration

```json
{
  "cmd": "store_calibration",
  "pair": 3,
  "dry_mv": 2450,
  "wet_mv": 900
}
```

App-side validation:

- `dry_mv > wet_mv`
- both values non-zero

## Command Result Handling

Each command produces one result frame on `command_result`.

The app should treat `command_result` as authoritative command acknowledgement.

Required handling:

- map `accepted` / `rejected`
- map machine-readable reasons to user-facing text
- refresh visible UI from subsequent `state_stream` frames, not only from the command result

Important reason values already in firmware:

- `slot_allocated`
- `queued_waiting_for_slot`
- `already_stopped`
- `stopped`
- `not_blocked`
- `block_reset`
- `calibration_saved`
- `pair_blocked`
- `pair_faulted`
- `low_battery`
- `slot_unavailable`
- `invalid_calibration`
- `invalid_duration`
- `unsupported_command`
- `ota_in_progress`
- `outputs_disabled`
- `sensor_invalid`
- `already_active`
- `invalid_pair`

## UI Sequence

### Device list

- scan for BeetMeister devices
- connect to one controller

### Dashboard

- show device battery, next check countdown, and all 8 pairs
- allow navigation to pair detail

### Pair detail

- show current pair state
- show moisture and sensor millivolts
- show block reason if blocked
- allow manual start and stop
- allow timed manual start

### Calibration

- show live pair millivolt value
- capture dry and wet references
- submit `store_calibration`

## Connection Edge Cases

The app should explicitly handle these states:

- Bluetooth disabled
- permissions missing
- controller not found
- unbonded and waiting for system pairing
- connected but not yet service-discovered
- bonded but initial sync not complete
- sleeping / unavailable controller
- disconnected after command accepted

Important rule:
- disconnect after an accepted command does not mean the command was cancelled

## First Testing Pass

Verify on real hardware:

1. Scan finds the controller while awake.
2. Bonding works from a clean phone state.
3. `controller_info` reads successfully after bonding.
4. Subscription receives one device frame and 8 pair frames.
5. `manual_start` without `duration_s` works.
6. `manual_start` with `duration_s = 120` works.
7. `manual_stop` returns `already_stopped` on an idle pair.
8. `reset_block` returns `not_blocked` on a clear pair.
9. `store_calibration` persists and is reflected in later state.
10. Reconnect after disconnect restores current state.

## Deferred For Later

- OTA UI
- clear-bonds UI
- local button interaction on `GPIO13`
- MQTT integration inside the app
- multi-controller dashboards beyond a simple device list
