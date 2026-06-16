# BeetMeister Requirements Baseline

This file is the normative requirements baseline for v1.
Every requirement uses `shall` language and is traceable from the verification specification.

## Functional requirements

- `RQ-001` The controller shall support exactly eight irrigation pairs in v1.
- `RQ-002` The controller shall treat one moisture sensor and one pump relay as one inseparable pair for control, telemetry, history, calibration, and block handling.
- `RQ-003` The controller shall autonomously evaluate watering need for every enabled pair every 2 hours.
- `RQ-004` The controller shall remain capable of autonomous operation when Home Assistant, MQTT, Wi-Fi, BLE, or the Android app are unavailable.
- `RQ-005` The controller shall expose automatic watering, manual watering, calibration, block reset, telemetry, history handoff, and OTA triggers through at least one external interface.

## Moisture and watering requirements

- `RQ-006` The controller shall convert sensor voltage to moisture percentage using per-pair dry and wet calibration values and clamp the result to 0 through 100 percent.
- `RQ-007` The controller shall use 2.45 V as the default dry reference and 0.90 V as the default wet reference until a pair is calibrated.
- `RQ-008` The controller shall skip automatic watering for moisture values from 81 through 100 percent.
- `RQ-009` The controller shall use an automatic watering duration of 10 seconds at 80 percent moisture.
- `RQ-010` The controller shall use an automatic watering duration of 60 seconds for moisture values from 70 through 79 percent.
- `RQ-011` The controller shall use an automatic watering duration of 120 seconds for moisture values from 60 through 69 percent.
- `RQ-012` The controller shall use an automatic watering duration of 180 seconds for moisture values from 50 through 59 percent.
- `RQ-013` The controller shall use an automatic watering duration of 240 seconds for moisture values from 0 through 49 percent.
- `RQ-014` The controller shall perform a 10-second sanity-check pump pre-run before every automatic watering cycle.
- `RQ-015` The controller shall compare the post-pre-run moisture reading to the pre-run moisture reading and shall require a rise of more than 3 percentage points to continue automatic watering.
- `RQ-016` The controller shall block a pair for 24 hours when the automatic sanity check fails.
- `RQ-017` The controller shall not water a blocked pair until the block expires or an operator resets it.
- `RQ-018` The controller shall allow manual watering only when the pair is not blocked, the battery policy permits watering, the sensor state is not faulted, and a pump slot is available.
- `RQ-019` The controller shall allow no more than three pumps to be active at one time across all pairs.

## Battery and power requirements

- `RQ-020` The controller shall measure battery voltage sufficiently to classify battery state using the thresholds in the constraints and firmware specifications.
- `RQ-021` The controller shall enter deep low-battery behavior when idle battery voltage is at or below 3.20 V.
- `RQ-022` In deep low-battery behavior the controller shall wake for adaptive timer-based recovery checks and shall resume normal awake behavior only after battery voltage exceeds 3.25 V.
- `RQ-023` The controller shall enter idle low-power sleep when battery voltage is below 3.30 V and the controller has had no external write command for 5 minutes and no active watering is running.
- `RQ-024` The controller shall permit watering down to 3.10 V while a pump is already active and shall abort or reject watering below that threshold.

## Persistence and history requirements

- `RQ-025` The controller shall persist per-pair calibration values in non-volatile storage.
- `RQ-026` The controller shall persist the application configuration, pair runtime snapshots, and event ring across reset, sleep, and OTA.
- `RQ-027` The controller shall log each watering event with timestamp validity, duration, moisture before and after, and stop reason.
- `RQ-028` The controller shall store watering history as a 1000-entry persistent ring buffer in a dedicated NVS partition separate from the default system `nvs`.
- `RQ-029` The event ring shall use monotonically increasing sequence numbers so the newest record and next write slot can be reconstructed after reboot without a persisted head pointer.

## Interface requirements

- `RQ-030` The controller shall integrate with Home Assistant through MQTT discovery and MQTT command and state topics, including a device-management command to clear stored BLE bonds.
- `RQ-031` The controller shall expose pair moisture, pump state, time remaining, blocked status, block reset, manual start and stop, battery voltage, and battery state to Home Assistant.
- `RQ-032` The controller shall expose controller state and control functions to an Android app over BLE while acting as the BLE peripheral, and shall permit BLE GATT access only to bonded devices.
- `RQ-033` The controller shall allow sensor calibration to be initiated from the Android app and shall store the resulting values persistently.
- `RQ-034` The controller shall provide end-user firmware update capability through the BLE maintenance update path using an OTA-first partition layout with no factory app partition.

## Safety and robustness requirements

- `RQ-035` The controller shall initialize all relay outputs to off on boot, wake, reset, and OTA reboot before any watering logic runs.
- `RQ-036` The controller shall publish or stream enough state for operators to distinguish `IDLE`, `WAITING_FOR_SLOT`, `SANITY_CHECK`, `WATERING`, `BLOCKED`, and `FAULT`.
- `RQ-037` The controller shall reject invalid sensor readings and suspected disconnected-sensor conditions instead of treating them as dry soil.
- `RQ-038` The controller shall preserve automatic watering behavior and next scheduler deadline when communication interfaces are unavailable.
- `RQ-039` The controller shall preserve configuration, calibration, and event history across successful OTA updates and unsuccessful OTA attempts.
- `RQ-040` The project shall define requirement-linked verification activities and release acceptance criteria before implementation proceeds beyond scaffolding.

## Deferred items

The following items are explicitly deferred from v1 and shall not be assumed by implementers:

- application-layer BLE credentials or user accounts
- physical bond-admit button gating for acceptance of new BLE bonds
- one-button local UI on `GPIO13`, including short-press menu navigation and long-press select or value change behavior
- cloud synchronization
- per-pair current sensing or flow sensing
- dynamic pump-duration learning
- automatic relay-board power gating beyond safe default-off wiring practices
