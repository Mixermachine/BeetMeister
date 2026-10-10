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

v1 requires standard BLE bonding (with Passkey Display authentication) before GATT access is granted.
The controller shall use OS-managed BLE pairing and bonding only.
v1 does not add an application-layer PIN, token, user account, or exclusive owner-phone model.

### Bonded-access rules

- Unbonded clients may discover BeetMeister advertisements.
- Unbonded clients shall not read `controller_info`.
- Unbonded clients shall not subscribe to `state_stream`.
- Unbonded clients shall not write `control_point`.
- Unbonded clients shall not receive `command_result`.
- Unbonded clients may read `maintenance_info` to discover maintenance capabilities prior to bonding.
- Maintenance operational characteristics (`maintenance_control`, `maintenance_status`, `maintenance_data`) require an encrypted connection (`_ENC` flags).
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
- `state_stream` messages shall fit within one ATT packet after MTU negotiation.
- `command_result` messages shall be sent as one ATT indication when they fit, or as chunk frames when they do not fit.
- If MTU negotiation fails, the controller shall still support commands whose payload fits in 100 bytes or less.
- `state_stream` notifications shall send one compact object per frame rather than a large full-state blob.
- **Boolean encoding**: runtime protocol booleans use JSON integers `1`/`0` (not `true`/`false`).
  The maintenance protocol uses JSON booleans `true`/`false` (frozen).

## `controller_info` payload

Example payload:

```json
{
  "device_id": "beetmeister-01",
  "protocol_version": 10,
  "firmware_version": "0.1.0",
  "pair_count": 8
}
```

## `state_stream` payloads

The controller shall emit three frame types:

- device frame
- pair frame
- system event frame

### Device frame

```json
{
  "type": "device",
  "data": {
    "battery_state": "ACTIVE",
    "battery_mv": 3340,
    "time_valid": 1,
    "boot_id": 42,
    "next_check_in_s": 4812,
    "active_pumps": 1,
    "wifi_connected": 1,
    "mqtt_connected": 1,
    "uptime_s": 12345,
    "valve_enabled": 1,
    "valve_state": "OPEN"
  }
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
  "blocked": 0,
  "block_reason": "NONE",
  "remaining_s": 92,
  "source": "AUTOMATIC",
  "enabled": 1,
  "sensor_valid": 1
}
```

On subscription to `state_stream`, the controller shall send one device frame followed by one pair frame per pair.
After that, the controller shall notify only changed frames.
New system events may also be notified on `state_stream` as `type = "system_event"` while a bonded client is subscribed.

### System event frame

```json
{
  "type": "system_event",
  "data": {
    "seq": 12,
    "event_type": "BLE_CONNECT",
    "reason": 0,
    "boot_id": 42,
    "uptime_s": 123,
    "unix_s": 0,
    "time_valid": 0,
    "battery_mv": 3340,
    "peer_addr": "AA:BB:CC:DD:EE:FF",
    "peer_addr_type": 1,
    "known_peer": 1,
    "detail": 0
  }
}
```

### Watering event frame (runtime protocol v19)

Burst-streamed watering records are notified on `state_stream` as `type = "event"`.
The `data` object uses exactly the same fields as the `get_event` command result.

```json
{
  "type": "event",
  "data": {
    "seq": 512,
    "pair": 2,
    "boot_id": 77,
    "src": 1,
    "start": 1700000100,
    "end": 1700000158,
    "mb": 40,
    "ma": 55,
    "sb": 1200,
    "sa": 1800,
    "req": 60,
    "act": 58,
    "stop": 0,
    "block": 0,
    "bs": 3300,
    "be": 3280,
    "su": 100,
    "eu": 158
  }
}
```

### Stream end frame (runtime protocol v19)

Every `stream_events` burst terminates with exactly one `type = "stream_end"`
frame (unless the connection drops). `status` is `"complete"` or `"cancelled"`.
`last_seq` is the highest sequence number the pump touched — delivered or
skipped as a gap — so the app can resume at `last_seq + 1` even when a window
over a wrapped ring region delivered nothing.

```json
{
  "type": "stream_end",
  "data": {
    "id": 4,
    "kind": "system",
    "status": "complete",
    "delivered": 301,
    "last_seq": 1200,
    "gaps": 3
  }
}
```

## `control_point` commands

The controller enforces two 1-second-window rate-limit lanes for control-point
commands (runtime protocol v20):

- **`real` lane — 4 commands per second.** All mutating, actuator, and
  NVS-write commands (`manual_start`/`stop`, `relay_test_*`, `reset_block`,
  `store_*`, `clear_ble_bonds`, `enable/disable_pair`, `moisture_test_start`,
  `set_time`, `open/close_valve`, `preview_valve_position`). The tight limit
  paces flash writes and protects actuation paths.
- **`sync_read` lane — 16 commands per second.** All pure read commands
  (`get_calibration`, `get_valve_config`, `get_watering_interval`,
  `get_pair_wiring`, `get_max_active_pumps`, `get_pair_names`,
  `get_pair_combined`, `get_pair_config`) plus the event streaming commands
  (`stream_events`, `stream_cancel`) and the backlog sync queries they back.
  Reads must not consume the mutation budget — UI bursts (initial sync fetches
  all pairs' combined config; the calibration refresh fetches all 8 pairs) are
  legal read traffic.

Over-limit commands are answered with `status = "rejected"` and
`reason = "rate_limited"` (pre-v20: over-limit reads silently consumed the
same 4-command budget as mutations, so connect-time and calibration-refresh
read bursts dropped several reads per second). The app retries rejected
commands across the window boundary.

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

### Relay test start

```json
{
  "cmd": "relay_test_start",
  "pair": 3
}
```

This turns on the selected relay GPIO for bench and board verification while intentionally keeping the boost converter disabled.

### Relay test stop

```json
{
  "cmd": "relay_test_stop",
  "pair": 3
}
```

### Moisture response test start

```json
{
  "cmd": "moisture_test_start",
  "data": {
    "pair": 3
  }
}
```

This starts the same 10-second moisture response check that is used before automatic watering, but it does not continue into a full watering cycle after a successful check.

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

### Firmware update routing

- The runtime BLE command channel does not expose a `start_ota` command.
- Firmware update is handled through the separate BLE maintenance service.
- When the runtime protocol is unsupported, the Android app shall route the user into the maintenance update flow instead of treating the controller as generically broken.

### Set time

```json
{
  "cmd": "set_time",
  "unix_s": 1714412345
}
```

### Pair wiring lookup

```json
{
  "cmd": "get_pair_wiring",
  "data": {
    "pair": 3
  }
}
```

This returns static controller wiring metadata for the selected pair on request.
It shall not be streamed continuously on `state_stream`.

### Controller management

```json
{
  "cmd": "reboot_controller",
  "data": {}
}
```

```json
{
  "cmd": "factory_reset",
  "data": {}
}
```

- `reboot_controller` shall only be accepted while the controller is idle.
- `factory_reset` shall only be accepted while the controller is idle.
- Accepted `factory_reset` shall erase BeetMeister-owned runtime storage, clear BLE bonds, preserve `device_id`, and reboot.
- The runtime BLE command channel still does not expose firmware-image transfer itself; firmware update remains on the maintenance service.

### Watering evaluation interval

```json
{
  "cmd": "get_watering_interval",
  "data": {}
}
```

```json
{
  "cmd": "store_watering_interval",
  "data": {
    "watering_interval_s": 21600
  }
}
```

Valve motion is reported as normal system events when a real valve open or close finishes successfully:

- `VALVE_OPENED`
- `VALVE_CLOSED`

Calibration preview moves are not reported as system events.

### Valve configuration and manual valve commands

```json
{
  "cmd": "get_valve_config",
  "data": {}
}
```

```json
{
  "cmd": "store_valve_config",
  "data": {
    "valve_enabled": 1,
    "servo_min_pulse_us": 500,
    "servo_max_pulse_us": 2500,
    "open_pulse_us": 850,
    "shut_pulse_us": 2050,
    "move_duration_ms": 700,
    "settle_delay_ms": 200,
    "open_hold_ms": 1500
  }
}
```

```json
{
  "cmd": "open_valve",
  "data": {}
}
```

```json
{
  "cmd": "close_valve",
  "data": {}
}
```

```json
{
  "cmd": "preview_valve_position",
  "data": {
    "pulse_us": 1600
  }
}
```

`preview_valve_position` is a transient calibration command.
It may move the servo while the controller is otherwise idle, but it shall not persist a new logical open or shut position by itself.

## `command_result` semantics

Every accepted or rejected command shall produce one result frame.

Valve config results shall return:

```json
{
  "cmd": "get_valve_config",
  "status": "accepted",
  "reason": "none",
  "data": {
    "valve_enabled": 1,
    "servo_min_pulse_us": 500,
    "servo_max_pulse_us": 2500,
    "open_pulse_us": 850,
    "shut_pulse_us": 2050,
    "move_duration_ms": 700,
    "settle_delay_ms": 200,
    "open_hold_ms": 1500
  }
}
```

Watering interval results shall return:

```json
{
  "cmd": "get_watering_interval",
  "status": "accepted",
  "reason": "none",
  "data": {
    "watering_interval_s": 21600
  }
}
```

Pair wiring results shall return:

```json
{
  "cmd": "get_pair_wiring",
  "status": "accepted",
  "reason": "none",
  "data": {
    "pair": 3,
    "moisture_gpio": 10,
    "relay_gpio": 14
  }
}
```

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

Controller-management results shall use these stable accepted reasons:

- `rebooting`
- `factory_reset_started`

The `reason` field shall use stable machine-readable strings.
Accepted commands that take time to complete, such as calibration or OTA start, shall later emit a second completion result.

If a full command-result JSON payload exceeds the negotiated ATT indication payload, the controller shall send chunk frames on `command_result`:

```json
{
  "type": "cmd_chunk",
  "id": 17,
  "i": 0,
  "n": 3,
  "b64": "eyJjbWQiOiJnZXRfc3lzdGVtX2V2ZW50IiwiLi4u"
}
```

- `id` identifies one logical command result.
- `i` is the zero-based chunk index.
- `n` is total chunk count.
- `b64` is one fragment of the base64-encoded full command-result JSON bytes.

## Command rules

- Commands shall be idempotent where meaningful.
- `manual_stop` on a non-watering pair shall return `accepted` with reason `already_stopped`.
- `relay_test_stop` on a non-tested pair shall return `accepted` with reason `already_stopped`.
- `moisture_test_start` shall reject disabled, blocked, faulted, active, invalid-sensor, low-battery, OTA, pump-output-disabled, or no-slot pairs.
- `reset_block` on an unblocked pair shall return `accepted` with reason `not_blocked`.
- `manual_start` shall reject `duration_s` values below 1 second or above 1200 seconds.
- `relay_test_start` shall reject requests while watering, OTA is in progress, or the controller is not in normal active battery state.
- `store_calibration` shall reject invalid dry or wet values and dry values that are not greater than wet values.
- `set_time` shall accept the current Unix time from the app, mark controller time valid for the current boot, and backfill unresolved current-boot events.
- `store_valve_config` shall reject changes while watering or valve motion is already in progress.
- `preview_valve_position` shall reject requests while watering, queued runtime work, valve motion, OTA, or low-battery policy prevents operation.
- `store_watering_interval` shall reject values outside `300..86400` seconds.
- Commands shall be rejected while the controller is in `DEEP_LOW_BATTERY`.
- Commands that would violate controller safety rules shall be rejected rather than queued indefinitely.
- Access attempts from an unbonded client shall be rejected before command parsing.
- `get_system_history_summary` returns `latest_seq_no` and `event_count` for the persistent system-event ring.
- History summaries are answered in O(1): `event_count` is derived as
  `min(latest_seq_no, ring_capacity)` because both rings are append-only with
  contiguous sequence numbers, and watering `pair_totals_s` are maintained
  incrementally (seeded by the boot scan, updated on every append including
  wrap eviction). A full-ring flash scan in the command path would exceed the
  app command timeout once rings are deep.
- The app stores a per-device, per-kind sync watermark after each completed
  burst so reconnects resume instead of re-streaming the full ring; if the
  watermark is ahead of the controller's `latest_seq` (reflash or factory
  reset) the app performs one full resync from `seq 1`.
- `get_system_event` returns one system event by `seq_no`.
- `get_history_summary` and `get_event` remain the watering-history commands.
- `stream_events` (runtime protocol v19) takes `data` `{kind, from_seq, max_events}`
  with `kind` one of `"watering"` or `"system"`, and acknowledges with
  `{stream_id, kind, from_seq, latest_seq, oldest_seq, total}`. `oldest_seq` is
  the oldest still-readable sequence in that ring (head of the circular buffer);
  apps should not resume below `max(sync watermark, oldest_seq)` because older
  records are overwritten. The controller then notifies
  the ring range `from_seq..from_seq+total-1` on `state_stream` (event frames per
  record, ring gaps skipped and counted), at most 8 records per 50 ms controller
  tick with a one-tick backoff whenever the notification buffers run full
  (keeps airtime for command result indications on the shared ATT queue),
  followed by one `stream_end` frame. While a command result is waiting to be
  sent the pump holds back entirely.
- `stream_cancel` (runtime protocol v19) stops all active burst streams silently
  (no terminal frames). A stream is also cancelled with a `cancelled` `stream_end`
  frame when an OTA maintenance session starts or a factory reset is accepted.
- Stream frames never appear on the command result channel and command results
  never appear as stream frames; partial burst progress is resumable because
  delivered records are individually valid and the pump position is reported by
  `last_seq`.

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
- Pair detail: show current moisture, current state, remaining watering time, block state, manual start or stop actions, and on-demand relay or moisture GPIO wiring information for setup.
- Calibration screen: show live sensor value and allow storing dry and wet calibration references.
- Settings screen: show firmware version, protocol version, valve state, and valve timing controls.
- Valve calibration screen: show transient servo preview, pulse range, and saved open or shut markers.

## App behavior requirements

- The app shall initiate or redirect to the system BLE pairing flow before attempting protected GATT operations on an unbonded controller.
- The app shall clearly distinguish sleeping or unreachable controller states from command rejection states.
- The app shall clearly distinguish pairing failure or re-pair-required states from controller offline states.
- The app shall show `Blocked`, `Low battery`, and `Fault` as explicit user-facing states.
- The app shall track pair state per `pair` index (keyed lookup), never by list position, so a partially synced connection can never render one pair's values under another pair's label.
- Until the first `pair_state` frame for a pair has been received, the app shall show a loading indicator for that pair's live values (moisture, sensor millivolts, state, remaining time) instead of numeric values; `0` is a valid measurement and must never be synthesized for an unsynced pair.
- A received frame with `sensor_valid = false` shall still render its measured values (the reading is the diagnostic for a bad sensor or wrong calibration range).
- The app shall show command failures with the machine-readable reason mapped to a human-readable message.
- The app shall not assume the controller is continuously awake.
- The app shall send `set_time` after connect when `time_valid = false` before downloading persisted history.
- The app shall start background event synchronization after live state is connected and shall not block live device or pair updates while history is downloading.
- The app shall de-duplicate downloaded events by sequence number and keep a local per-controller cache.
- The app shall keep unresolved system events and render them in boot-relative order when no wall-clock timestamp is available.
