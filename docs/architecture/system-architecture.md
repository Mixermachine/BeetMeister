# BeetMeister System Architecture

## Architectural intent

BeetMeister is designed as an autonomous embedded controller with optional supervisory interfaces.
The controller owns all safety-critical decisions.
Home Assistant and the Android app are clients of the controller, not the source of control truth.

## Top-level components

| Component | Responsibility | Owned data | External dependencies |
| --- | --- | --- | --- |
| ESP32-S3 controller | Scheduling, watering decisions, state persistence, MQTT, BLE, OTA, and sleep management. | Configuration, calibration, runtime snapshots, event ring, active pair state. | Battery, sensors, relay board, Wi-Fi, BLE peer, OTA host. |
| Moisture sensors | Provide analog voltage proportional to soil moisture. | None. | Stable supply and ADC sampling path. |
| Relay board and pumps | Convert controller relay commands into water delivery. | None. | Relay input logic thresholds and external pump power rail. |
| Battery and charger path | Supply the controller and pump subsystem. | None. | LiFePO4 cell behavior and charger cut-off at 3.6 V. |
| Home Assistant | Supervisory control, dashboarding, long-term history, automation. | Discovery state, latest MQTT state, long-term recorder data. | Local MQTT broker and Home Assistant runtime. |
| Android app | Local BLE control and calibration workflow. | UI state and optional cached last-known controller summary. | BLE proximity and awake controller. |
| OTA image host | Serves firmware images over HTTP. | Firmware binaries and manifests if used. | Reachable network path. |

## Boundary rules

- Only the controller shall decide whether watering is allowed.
- Home Assistant and the Android app may request actions but shall not bypass battery, block, or concurrency rules.
- Moisture percentage conversion and watering-duration lookup belong to the controller firmware.
- Long-term history belongs to Home Assistant. The controller retains only the local 1000-entry event ring.
- Wi-Fi credentials and standard radio stack state belong to the default ESP-IDF `nvs` partition. Application-owned state belongs to the dedicated partitions defined in the storage specification.

## Runtime model

### Boot or wake sequence

1. Restore controller configuration, calibrations, runtime snapshots, and event-ring metadata by scanning persisted records.
2. Sample battery voltage and enter the applicable battery state.
3. Initialize relay outputs to off before any pair becomes actionable.
4. Rebuild pair states from persisted snapshots.
5. Bring up Wi-Fi and BLE only if the selected battery state permits normal awake operation.
6. Publish or stream current state to external clients when connectivity becomes available.
7. Run an immediate scheduler evaluation if the wake reason is a scheduled check or if no valid next-check deadline exists.

### Steady-state responsibilities

- scheduler: decides when a pair should be evaluated and queued
- pair controller: evaluates sensor readings, block state, watering duration, and stop conditions
- concurrency controller: guarantees at most three simultaneous pump outputs
- persistence manager: writes only bounded, event-driven state changes
- communications manager: exposes controller-owned state through MQTT and BLE
- power manager: enforces awake, idle-sleep, and deep-low-battery behavior
- OTA manager: performs image download and boot-slot switching only when safe

## Data ownership and flow

### Inputs into the controller

- analog voltage per moisture sensor
- battery voltage sense
- MQTT commands
- BLE commands
- wake reason and elapsed sleep timer events
- OTA request with image URL or configured source

### Outputs from the controller

- relay high or low per pump channel
- MQTT discovery, state, command acknowledgements, and event notifications
- BLE advertisements, state stream, and command acknowledgements
- OTA boot-slot updates
- persisted configuration, snapshots, and event records

## Fault containment

- A fault in one pair shall not stop unrelated pairs unless the failure is battery-wide or controller-wide.
- A blocked pair shall remain visible to MQTT and BLE clients and shall not silently re-enter service before expiry or reset.
- Loss of Wi-Fi or Home Assistant shall not change autonomous watering behavior.
- BLE disconnects shall not cancel a command already acknowledged as accepted.
- OTA failures shall not erase configuration, calibrations, runtime snapshots, or event history.

## Canonical controller states

At device level the controller battery state shall be one of:

- `ACTIVE`
- `IDLE_LOW_POWER`
- `DEEP_LOW_BATTERY`
- `OTA_IN_PROGRESS`

At pair level the controller state shall be one of:

- `IDLE`
- `WAITING_FOR_SLOT`
- `SANITY_CHECK`
- `WATERING`
- `BLOCKED`
- `FAULT`

No other externally visible pair state names are allowed in MQTT, BLE, hardware notes, or test plans unless this document is revised first.
