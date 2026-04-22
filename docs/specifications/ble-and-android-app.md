# BeetMeister BLE And Android App Specification

## Scope

This document defines the v1 controller-to-app contract.
The controller is the BLE peripheral.
The Android app is the BLE central.

Current firmware implementation note:
- v1 is implemented on top of the ESP-IDF NimBLE stack.
- The BLE transport is built on the shared transport-neutral controller interface so MQTT can later reuse the same command and state model.

## BLE role model

- The controller shall advertise when awake and not in deep low-battery sleep.
- The Android app shall scan for BeetMeister controllers by advertised local name and service UUID.
- Only one BLE central connection is required in v1.
- Multiple phones may be bonded over time, but only one bonded central may be connected at once.
- Loss of BLE connectivity shall not alter autonomous watering behavior.

## Authentication policy

v1 requires standard BLE bonding before any GATT access is granted.
The controller shall use OS-managed BLE pairing and bonding only.
v1 does not add an application-layer PIN, token, user account, or exclusive owner-phone model.

### Bonded-access rules

- Unbonded clients may discover BeetMeister advertisements.
- Unbonded clients shall not read `controller_info`.
- Unbonded clients shall not subscribe to `state_stream`.
- Unbonded clients shall not write `control_point`.
- Unbonded clients shall not receive `command_result`.
- A previously bonded client shall regain access after reconnect without a second application-layer login step.
- The controller shall support multiple bonded phones over time.
- Clearing BLE bonds shall remove all stored bonds, disconnect any active BLE session, and force re-pairing before further access.

### Planned future button-based local control and bond admission

The following feature is planned for a later version and is not part of v1:

- The controller may add one physical push button on `GPIO13` for local UI and BLE bond admission.
- Under the planned local UI model, short press moves through menu items and long press selects an option or changes a value.
- Under that future model, an unbonded phone may initiate a pairing or bonding request, but the controller shall accept the new bond only while the physical button is actively pressed.
- Existing bonded phones shall continue to reconnect normally without requiring the button.
- Rejected new-bond attempts caused by the button not being pressed shall not clear or disturb existing bonds.
- The final button specification shall define how bond-admit handling overrides or coexists with the local menu so the button meaning is unambiguous.
- The Android app shall treat this future condition as `pairing_requested_waiting_for_button` rather than a generic controller-offline state.

## GATT service layout

### Primary service

- Service UUID: `8f2a0001-6d7a-4a6b-9d57-3f2a7d94c4b0`

### Characteristics

| Characteristic | UUID | Properties | Purpose |
| --- | --- | --- | --- |
| `controller_info` | `8f2a0002-6d7a-4a6b-9d57-3f2a7d94c4b0` | Read | Static metadata such as device ID, firmware version, pair count, and protocol version |
| `state_stream` | `8f2a0003-6d7a-4a6b-9d57-3f2a7d94c4b0` | Notify | Streaming device and pair state frames |
| `control_point` | `8f2a0004-6d7a-4a6b-9d57-3f2a7d94c4b0` | Write with response | App-to-controller command channel |
| `command_result` | `8f2a0005-6d7a-4a6b-9d57-3f2a7d94c4b0` | Indicate | Acknowledgement and command completion messages |

All four characteristics shall require a bonded link before use.

## Message framing

- All BLE application payloads shall be UTF-8 JSON objects.
- Each JSON message shall fit within one ATT packet after MTU negotiation to 247 bytes.
- If MTU negotiation fails, the controller shall still support commands whose payload fits in 100 bytes or less.
- `state_stream` notifications shall send one compact object per frame rather than a large full-state blob.

## `controller_info` payload

Example payload:

```json
{
  "device_id": "beetmeister-01",
  "protocol_version": 1,
  "firmware_version": "0.1.0",
  "pair_count": 8
}
```

## `state_stream` payloads

The controller shall emit two frame types:

- device frame
- pair frame

### Device frame

```json
{
  "type": "device",
  "battery_state": "ACTIVE",
  "battery_mv": 3340,
  "time_valid": true,
  "next_check_in_s": 4812,
  "active_pumps": 1,
  "wifi_connected": true,
  "mqtt_connected": true
}
```

### Pair frame

```json
{
  "type": "pair",
  "pair": 3,
  "state": "WATERING",
  "moisture_pct": 47,
  "sensor_mv": 1420,
  "blocked": false,
  "block_reason": "NONE",
  "remaining_s": 92,
  "source": "AUTOMATIC"
}
```

On subscription to `state_stream`, the controller shall send one device frame followed by one pair frame per pair.
After that, the controller shall notify only changed frames.

## `control_point` commands

### Manual start

```json
{
  "cmd": "manual_start",
  "pair": 3,
  "duration_s": 120
}
```

`duration_s` is optional.
If omitted, the controller shall use its default manual duration for the current pair state.
If provided, it shall override the default manual duration for that accepted run only.

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

### Trigger OTA

```json
{
  "cmd": "start_ota",
  "url": "http://192.168.1.10/fw/beetmeister.bin"
}
```

## `command_result` semantics

Every accepted or rejected command shall produce one result frame.

```json
{
  "cmd": "manual_start",
  "pair": 3,
  "status": "accepted",
  "reason": "slot_allocated"
}
```

or

```json
{
  "cmd": "manual_start",
  "pair": 3,
  "status": "rejected",
  "reason": "pair_blocked"
}
```

The `reason` field shall use stable machine-readable strings.
Accepted commands that take time to complete, such as calibration or OTA start, shall later emit a second completion result.

## Command rules

- Commands shall be idempotent where meaningful.
- `manual_stop` on a non-watering pair shall return `accepted` with reason `already_stopped`.
- `reset_block` on an unblocked pair shall return `accepted` with reason `not_blocked`.
- `manual_start` shall reject `duration_s` values below 1 second or above 900 seconds.
- `store_calibration` shall reject invalid dry or wet values and dry values that are not greater than wet values.
- Commands shall be rejected while the controller is in `DEEP_LOW_BATTERY`.
- Commands that would violate controller safety rules shall be rejected rather than queued indefinitely.
- Access attempts from an unbonded client shall be rejected before command parsing.

## Calibration workflow

1. App connects and verifies or establishes BLE bonding through the OS pairing flow.
2. App subscribes to `state_stream`.
3. User opens the pair calibration screen.
4. App reads live sensor values from pair frames.
5. User captures or enters `dry_mv` and `wet_mv`.
6. App sends `store_calibration`.
7. Controller validates and persists the record.
8. Controller publishes a result frame and then a fresh pair state frame.

## Manual control workflow

1. User connects to the controller and completes bonding if the phone is not already bonded.
2. User selects a pair.
3. App shows the current pair state, blocked status, moisture, and battery gating.
4. User taps start or stop.
5. App sends the matching control command.
6. Controller responds on `command_result`.
7. App updates the UI from the result and subsequent state frames.

## Disconnect handling

- BLE disconnect after a command is acknowledged as accepted shall not cancel the accepted command.
- BLE disconnect before a command response is received leaves the command outcome unknown to the app and the app shall refresh state on reconnect.
- On reconnect the controller shall resend the current device frame and one current pair frame per pair.
- If the Android bond record is missing or stale, the app shall prompt for re-pairing instead of treating the controller as offline.

## Android app minimum screen set

- Device list: discover nearby controllers and show connection state.
- Device dashboard: show battery state, battery voltage, next scheduler check, and pair summary cards.
- Pair detail: show current moisture, current state, remaining watering time, block state, and manual start or stop actions.
- Calibration screen: show live sensor value and allow storing dry and wet calibration references.
- Settings screen: show firmware version, protocol version, and OTA trigger input.

## App behavior requirements

- The app shall initiate or redirect to the system BLE pairing flow before attempting protected GATT operations on an unbonded controller.
- The app shall clearly distinguish sleeping or unreachable controller states from command rejection states.
- The app shall clearly distinguish pairing failure or re-pair-required states from controller offline states.
- The app shall show `Blocked`, `Low battery`, and `Fault` as explicit user-facing states.
- The app shall show command failures with the machine-readable reason mapped to a human-readable message.
- The app shall not assume the controller is continuously awake.
