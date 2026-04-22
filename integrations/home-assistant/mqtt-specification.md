# BeetMeister Home Assistant MQTT Specification

## Scope

Home Assistant integration is MQTT-first and uses MQTT discovery.
The controller is the source of truth.
Home Assistant is the supervisory UI and long-term history sink.

## Topic namespace

The base namespace shall be:

`beetmeister/<device_id>/`

Topic classes:

| Topic | Purpose |
| --- | --- |
| `beetmeister/<device_id>/availability` | Online or offline status |
| `beetmeister/<device_id>/state/device` | Device-wide JSON state |
| `beetmeister/<device_id>/state/pair/<n>` | Pair JSON state |
| `beetmeister/<device_id>/event/watering` | Completed watering events |
| `beetmeister/<device_id>/cmd/manual_start/<n>` | Manual start command |
| `beetmeister/<device_id>/cmd/manual_stop/<n>` | Manual stop command |
| `beetmeister/<device_id>/cmd/reset_block/<n>` | Block reset command |
| `beetmeister/<device_id>/cmd/clear_ble_bonds` | Clear all stored BLE bonds |
| `beetmeister/<device_id>/cmd/ota` | OTA trigger command |
| `beetmeister/<device_id>/ack/<command>/<target>` | Command acknowledgement, where `<target>` is pair number or `device` |

## Availability behavior

- MQTT last will shall publish `offline` to the availability topic with retain enabled.
- On successful MQTT connect, the controller shall publish `online` retained.
- Availability shall reflect MQTT session state, not watering capability.

## Discovery strategy

- Every discovery config topic shall be retained.
- Every entity shall include a shared device block with `identifiers = ["<device_id>"]`.
- Discovery object IDs shall be stable across reboots and firmware updates.
- Discovery shall be republished on first MQTT connect after boot and after Home Assistant restart detection if implemented later.
- Entity names shall use the pattern `BeetMeister <device_id> Pair <n> <function>` for pair-scoped entities and `BeetMeister <device_id> <function>` for device-scoped entities.

Discovery topic pattern:

`homeassistant/<component>/<device_id>_<object_id>/config`

## Entity inventory

| Entity | Component | Object ID | State topic | Command topic |
| --- | --- | --- | --- | --- |
| Pair moisture | `sensor` | `pair<n>_moisture` | `state/pair/<n>` | None |
| Pair pump active | `binary_sensor` | `pair<n>_pump_active` | `state/pair/<n>` | None |
| Pair time remaining | `sensor` | `pair<n>_remaining` | `state/pair/<n>` | None |
| Pair blocked | `binary_sensor` | `pair<n>_blocked` | `state/pair/<n>` | None |
| Pair reset block | `button` | `pair<n>_reset_block` | None | `cmd/reset_block/<n>` |
| Pair manual start | `button` | `pair<n>_manual_start` | None | `cmd/manual_start/<n>` |
| Pair manual stop | `button` | `pair<n>_manual_stop` | None | `cmd/manual_stop/<n>` |
| Clear BLE bonds | `button` | `clear_ble_bonds` | None | `cmd/clear_ble_bonds` |
| Battery voltage | `sensor` | `battery_voltage` | `state/device` | None |
| Battery state | `sensor` | `battery_state` | `state/device` | None |
| Controller health | `sensor` | `controller_health` | `state/device` | None |

No aggregate moisture or aggregate watering entity is required in v1.

## Payload definitions

### Device state payload

```json
{
  "device_id": "beetmeister-01",
  "battery_mv": 3340,
  "battery_state": "ACTIVE",
  "active_pumps": 1,
  "next_check_in_s": 4812,
  "time_valid": true,
  "wifi_connected": true,
  "mqtt_connected": true,
  "health": "ok"
}
```

### Pair state payload

```json
{
  "pair": 3,
  "state": "WATERING",
  "moisture_pct": 47,
  "sensor_mv": 1420,
  "pump_active": true,
  "remaining_s": 92,
  "blocked": false,
  "block_reason": "NONE",
  "source": "AUTOMATIC"
}
```

### Watering event payload

```json
{
  "seq_no": 17,
  "pair": 3,
  "trigger_source": "AUTOMATIC",
  "started_at_unix_s": 1713686400,
  "ended_at_unix_s": 1713686520,
  "time_valid": true,
  "moisture_before_pct": 47,
  "moisture_after_pct": 56,
  "requested_duration_s": 120,
  "actual_duration_s": 120,
  "stop_reason": "COMPLETED",
  "block_reason": "NONE"
}
```

## Command payloads

- `cmd/manual_start/<n>` payload: `PRESS`
- `cmd/manual_stop/<n>` payload: `PRESS`
- `cmd/reset_block/<n>` payload: `PRESS`
- `cmd/clear_ble_bonds` payload: `PRESS`
- `cmd/ota` payload: JSON object with `url`

Acknowledgement topics:

- pair-scoped commands shall acknowledge on `ack/<command>/<n>`
- `clear_ble_bonds` shall acknowledge on `ack/clear_ble_bonds/device`
- `cmd/ota` shall acknowledge on `ack/ota/device`

## Command semantics

- Commands shall be idempotent.
- Repeating `manual_stop` on an idle pair shall produce an acknowledgement with `status = "accepted"` and `reason = "already_stopped"`.
- Repeating `reset_block` on an unblocked pair shall produce `status = "accepted"` and `reason = "not_blocked"`.
- `clear_ble_bonds` shall clear all stored BLE bonds, disconnect any active BLE session, and produce an acknowledgement with `status = "accepted"` and reason `bonds_cleared` or `no_bonds`.
- `manual_start` on a blocked, faulted, or battery-forbidden pair shall produce `status = "rejected"` with a stable reason string.

## State publication rules

- `state/device` and `state/pair/<n>` shall be retained.
- The controller shall publish all retained state topics within 5 seconds after MQTT session establishment.
- During active watering, `state/pair/<n>` shall be refreshed at least once per second for remaining time updates.
- When not watering, pair state shall publish on change only.
- `event/watering` payloads shall not be retained.

## Reconnect and offline behavior

- Loss of Wi-Fi or broker access shall not stop autonomous watering.
- On reconnect, the controller shall republish retained device and pair state.
- If the controller completed watering events while MQTT was unavailable, it shall publish those stored events on reconnect in ascending `seq_no` order.
- Home Assistant is the owner of long-term history. The controller is not required to support arbitrary historical queries over MQTT in v1.
- Home Assistant is the primary administrative recovery path for clearing BLE bonds when a phone is lost or must be re-paired.
