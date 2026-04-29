# BeetMeister Storage And Partition Specification

## Scope

This document fixes the flash layout, persistence ownership, record definitions, and write-lifecycle rules for BeetMeister v1.

## OTA-first partition layout

The controller shall use an OTA-first layout with no factory application partition.
The initial firmware image shall be flashed to `ota_0`.

## Baseline 16 MB flash map

| Partition | Type | Subtype | Offset | Size | Purpose |
| --- | --- | --- | --- | --- | --- |
| `nvs` | data | nvs | `0x9000` | `0x6000` | ESP-IDF system-managed state such as Wi-Fi and BLE |
| `otadata` | data | ota | `0xF000` | `0x2000` | ESP-IDF OTA slot selection metadata |
| `phy_init` | data | phy | `0x11000` | `0x1000` | Radio PHY init data |
| `appcfg` | data | nvs | `0x12000` | `0x10000` | Application configuration, calibrations, runtime snapshots |
| `events` | data | nvs | `0x22000` | `0x4E000` | Dedicated event-ring storage |
| `ota_0` | app | ota_0 | `0x70000` | `0x600000` | Active or candidate application slot 0 |
| `ota_1` | app | ota_1 | `0x670000` | `0x600000` | Active or candidate application slot 1 |
| `sysevents` | data | nvs | `0xC70000` | `0x10000` | Dedicated system-event-ring storage |
| `reserved` | data | `0x40` | `0xC80000` | `0x380000` | Reserved for future expansion |

The `reserved` partition shall not be used in v1.

## Partition ownership rules

- The default `nvs` partition shall not contain BeetMeister-owned configuration, calibration, or event records.
- `appcfg` is the only application partition for mutable configuration and runtime snapshots.
- `events` is the application partition for watering-event history.
- `sysevents` is the application partition for impactful startup, sleep, Bluetooth lifecycle, and future MQTT or OTA lifecycle events.
- OTA image swapping and boot selection shall use `otadata`, `ota_0`, and `ota_1` only.

## Configuration persistence rules

- Configuration writes shall occur only on explicit operator changes, OTA migrations, or first-boot initialization.
- Calibration writes shall occur only when a calibration command completes successfully.
- Runtime snapshot writes shall occur on pair state transitions, block-state changes, accepted manual commands, and immediately before sleep entry.
- The implementation shall coalesce repeated snapshot updates so the same pair is not flushed more than once every 5 seconds unless a run is ending or a safety-critical state change occurs.
- No periodic background write loop is permitted.

## Event ring behavior

### Capacity and format

- The event ring shall store exactly 1000 records.
- Each record shall have a fixed-size binary payload with a maximum serialized size of 64 bytes.
- Records are addressed by `slot_index = seq_no mod 1000`.

### Reconstruction rules

- The controller shall reconstruct the newest valid event by scanning all 1000 slots and selecting the highest valid `seq_no`.
- The next event shall use `seq_no = highest_valid_seq_no + 1`.
- The next write slot shall therefore be `seq_no mod 1000`.
- No persistent head pointer or tail pointer shall be stored.

### Validity rules

- A record is valid only when its schema version is supported, its CRC passes, and its `pair_index` and enum values are in range.
- A partially written or corrupted record shall be ignored during reconstruction.
- If all slots are invalid, the ring shall be treated as empty and the next written event shall use `seq_no = 1`.

### Readout semantics

- Local readout order shall be newest first by descending `seq_no`.
- Readout filtered by pair shall preserve newest-first ordering.
- MQTT event handoff shall publish completed events in `seq_no` order when reconnecting after offline time if previously unreported events remain in the ring.
- Event ordering shall never depend on timestamp fields.

## `appcfg` data definitions

## Application configuration record

| Field | Type | Max bytes |
| --- | --- | --- |
| `schema_version` | `u16` | 2 |
| `device_id` | UTF-8 string | 24 |
| `pair_count` | `u8` | 1 |
| `watering_interval_s` | `u32` | 4 |
| `idle_sleep_threshold_mv` | `u16` | 2 |
| `deep_sleep_threshold_mv` | `u16` | 2 |
| `deep_sleep_resume_mv` | `u16` | 2 |
| `watering_abort_threshold_mv` | `u16` | 2 |
| `inactivity_sleep_timeout_s` | `u16` | 2 |
| `mqtt_broker_host` | UTF-8 string | 64 |
| `mqtt_broker_port` | `u16` | 2 |
| `mqtt_username` | UTF-8 string | 32 |
| `mqtt_password` | UTF-8 string | 64 |
| `mqtt_base_topic` | UTF-8 string | 48 |
| `ota_base_url` | UTF-8 string | 96 |
| `flags` | `u16` bitfield | 2 |

## Pair calibration record

| Field | Type | Max bytes |
| --- | --- | --- |
| `pair_index` | `u8` | 1 |
| `dry_mv` | `u16` | 2 |
| `wet_mv` | `u16` | 2 |
| `calibrated_at_unix_s` | `u32` | 4 |
| `source` | `u8 enum` | 1 |

## Pair runtime snapshot record

| Field | Type | Max bytes |
| --- | --- | --- |
| `pair_index` | `u8` | 1 |
| `pair_state` | `u8 enum` | 1 |
| `last_moisture_pct` | `u8` | 1 |
| `last_sensor_mv` | `u16` | 2 |
| `sensor_valid` | `bool` | 1 |
| `block_reason` | `u8 enum` | 1 |
| `block_until_unix_s` | `u32` | 4 |
| `block_remaining_s` | `u32` | 4 |
| `active_run_id` | `u32` | 4 |
| `active_run_source` | `u8 enum` | 1 |
| `run_started_unix_s` | `u32` | 4 |
| `run_elapsed_s` | `u16` | 2 |
| `run_target_s` | `u16` | 2 |
| `next_check_due_unix_s` | `u32` | 4 |
| `next_check_due_in_s` | `u32` | 4 |

## Event record

The serialized event record shall fit in 64 bytes and contain:

| Field | Type | Max bytes |
| --- | --- | --- |
| `schema_version` | `u8` | 1 |
| `seq_no` | `u64` | 8 |
| `boot_id` | `u32` | 4 |
| `pair_index` | `u8` | 1 |
| `trigger_source` | `u8 enum` | 1 |
| `started_at_unix_s` | `u32` | 4 |
| `ended_at_unix_s` | `u32` | 4 |
| `time_valid` | `bool` | 1 |
| `moisture_before_pct` | `u8` | 1 |
| `moisture_after_pct` | `u8` | 1 |
| `sensor_before_mv` | `u16` | 2 |
| `sensor_after_mv` | `u16` | 2 |
| `requested_duration_s` | `u16` | 2 |
| `actual_duration_s` | `u16` | 2 |
| `stop_reason` | `u8 enum` | 1 |
| `block_reason` | `u8 enum` | 1 |
| `battery_start_mv` | `u16` | 2 |
| `battery_end_mv` | `u16` | 2 |
| `started_uptime_s` | `u32` | 4 |
| `ended_uptime_s` | `u32` | 4 |
| `crc32` | `u32` | 4 |

- Legacy `v1` watering records shall be ignored by new firmware.
- New firmware shall write controller sleep records only to `sysevents`, not to the watering ring.

## System Event Record

The serialized system event record shall fit in 64 bytes and contain sequence number, boot identifier, event type, reason, uptime timestamp, optional Unix timestamp, battery voltage, optional raw BLE peer address, and CRC.

- The system event ring shall store exactly 256 records.
- System event sequence numbers are independent from watering event sequence numbers.
- `boot_id` shall be monotonic across controller boots and shall be persisted outside the event rings.
- `occurred_uptime_s` shall always be set. `occurred_unix_s` shall be `0` with `time_valid = false` when no wall-clock source is available.
- When wall-clock time becomes valid during a boot, unresolved records from the same `boot_id` shall be backfilled in place.
- Unresolved records from older boots shall remain persisted but shall be ignored by history reads and summaries.
- Bluetooth connect, disconnect, bond success, bond failure, and bond-clear events shall be logged. MQTT event enum values are reserved until MQTT support is implemented.

## Reset, sleep, and OTA compatibility

- Application configuration, calibrations, runtime snapshots, watering events, and system events shall survive reboot, deep sleep, and OTA.
- Unsupported future schema versions shall trigger a safe migration path or a hard rejection before normal startup continues.
- OTA shall never erase `appcfg`, `events`, or `sysevents`.
- A failed OTA attempt shall not modify the active-slot application data in a way that invalidates `appcfg`, `events`, or `sysevents`.

## Ownership and lifecycle summary

| Datum | Owner | Write trigger | Retention |
| --- | --- | --- | --- |
| Wi-Fi credentials | ESP-IDF system | Provisioning or user update | Persistent |
| MQTT config | BeetMeister app | Explicit config update | Persistent |
| Calibration | BeetMeister app | Successful calibration | Persistent |
| Runtime snapshot | BeetMeister app | State transition or sleep | Persistent |
| Watering event | BeetMeister app | End of watering attempt | Persistent until overwritten by ring wrap |
| System event | BeetMeister app | Startup, sleep, BLE lifecycle, future MQTT/OTA lifecycle | Persistent until overwritten by ring wrap |
