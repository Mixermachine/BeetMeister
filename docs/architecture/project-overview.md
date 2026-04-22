# BeetMeister Project Overview And Glossary

## Purpose

BeetMeister is a battery-powered irrigation controller built around an ESP32-S3.
It manages eight irrigation pairs, where each pair consists of one capacitive soil-moisture sensor and one pump controlled through a relay channel.
The controller shall remain operational without Home Assistant or the Android app and shall treat local automatic watering as the primary mission.

## System scope

The documented v1 system includes:

- one ESP32-S3-WROOM N16R8 controller
- eight capacitive analog moisture sensors
- eight relay-controlled low-voltage pumps
- one LiFePO4 battery supply and charger path
- Wi-Fi connectivity to a local Home Assistant installation via MQTT
- BLE connectivity to an Android application written in Kotlin and Compose
- HTTP-based OTA firmware updates
- persistent storage for configuration, calibration, runtime snapshots, and a 1000-entry watering history

The documented v1 system excludes:

- cloud services
- autonomous timekeeping hardware such as an RTC
- per-pair flow sensing
- pump current measurement
- application-layer BLE credentials or user accounts beyond standard bonding

## Canonical pair model

A `pair` is the basic control unit of BeetMeister.
Each pair binds exactly one moisture sensor input, one relay output, one pump, one calibration record, one runtime snapshot, and one block state.
All automatic watering, manual watering, telemetry, history, and fault handling are evaluated per pair unless this package states otherwise.

## Major operating modes

| Mode | Meaning | Entry condition | Exit condition |
| --- | --- | --- | --- |
| Autonomous | Controller performs scheduled checks and automatic watering without external commands. | Normal boot or wake. | Never disabled in v1. |
| Connected Wi-Fi | Controller is awake and MQTT-capable. | Wi-Fi and MQTT are available while battery policy allows awake operation. | Inactivity sleep, low battery, or connectivity loss. |
| Connected BLE | Android app is actively connected over BLE. | App connects while controller is awake. | App disconnects or controller sleeps. |
| Idle Low Power | Controller suspends radio activity but preserves runtime state and next scheduler wakeup. | Battery below 3.30 V and no active watering and no external write command for 5 minutes. | External wake or scheduled check. |
| Deep Low Battery | Controller stays in deep sleep and performs adaptive timer-based recovery checks only. | Idle battery at or below 3.20 V. | Battery recovery above 3.25 V on a wake check. |
| OTA Maintenance | Controller downloads and applies a new firmware image. | Explicit OTA request with battery and activity prerequisites met. | OTA success, OTA failure, or abort. |

## Externally visible functions

| Function | Meaning | Primary interfaces | Persisted locally |
| --- | --- | --- | --- |
| Automatic watering | Scheduled per-pair watering based on moisture and safety rules. | MQTT telemetry, BLE state stream | Yes |
| Manual watering | Operator starts or stops a pair outside the automatic schedule. | MQTT command topics, BLE control point | Yes |
| Calibration | Operator stores per-pair dry and wet reference voltages. | BLE control point, optional mirrored MQTT command path | Yes |
| Block and reset | Pair is prevented from watering for safety reasons until expiry or operator reset. | MQTT state plus reset action, BLE state plus reset command | Yes |
| Telemetry | Current pair state, battery, schedule, and health information. | MQTT state topics, BLE state stream | Snapshot only |
| History | Completed watering attempts with timestamps, durations, moisture values, and stop reasons. | Local ring buffer, MQTT event handoff | Yes |
| OTA | Controller applies a new firmware image from HTTP. | MQTT command path, BLE command path | OTA metadata only |

## Glossary

| Term | Definition |
| --- | --- |
| Controller | The ESP32-S3 firmware instance running BeetMeister. |
| Pair | One moisture sensor plus one pump/relay channel treated as one control unit. |
| Check cycle | The controller activity that evaluates all enabled pairs every 2 hours. |
| Moisture percentage | The normalized moisture value from 0 to 100 derived from measured voltage and calibration. |
| Dry reference | The voltage that represents 0 percent moisture for one sensor. |
| Wet reference | The voltage that represents 100 percent moisture for one sensor. |
| Sanity check | The mandatory 10-second pre-run before automatic watering, followed by moisture delta evaluation. |
| Block | A temporary safety lock that prevents a pair from watering automatically or manually. |
| Runtime snapshot | Persisted per-pair state needed to restore behavior after reset, sleep, or OTA. |
| Event ring | The persistent 1000-entry watering-event buffer stored in a dedicated NVS partition. |
| Idle Low Power | Sleep policy entered below 3.30 V when the controller is inactive. |
| Deep Low Battery | Adaptive recovery-check deep sleep entered at or below 3.20 V idle battery voltage. |
| Manual watering | A user-initiated watering action that bypasses scheduler timing but not safety rules. |
| Availability | The controller online or offline indication exposed to MQTT consumers. |
| Wall-clock time | UTC Unix time when the controller has a valid time source. |
| Monotonic time | Millisecond uptime or timer-relative duration that is valid only within the current boot. |
