# BeetMeister Constraints And Assumptions

## Fixed hardware constraints

- The controller is a 44-pin ESP32-S3 board built around an `ESP32-S3-WROOM-1-N16R8` class module with 16 MB external flash and 8 MB PSRAM.
- Board-level pin planning in this repository assumes the header order of the `ESP32-S3-DevKitC-1` family, which matches the 44-pin layout described in the planning notes.
- On the `ESP32-S3-DevKitC-1` board family, the onboard WS2812 RGB LED is driven by `GPIO48`, not `GPIO38`.
- The battery is a single-cell LiFePO4 pack with nominal operating range centered around 3.2 to 3.6 V and a stated capacity of 15 Ah.
- The battery is intentionally connected directly to the board 3.3 V input in this project and this assumption is not to be normalized away by the documentation.
- The charger path is assumed to terminate at 3.6 V.
- Eight relay inputs are driven directly by ESP32 GPIOs.
- Relay logic is assumed active-low at the signal input.
- Eight capacitive analog moisture sensors are read through ESP32 ADC channels.

## Default environmental assumptions

- The controller has no dedicated real-time clock with battery backup.
- Wall-clock time may be unknown after cold boot until Wi-Fi or another time source becomes available.
- Wi-Fi and Home Assistant may be intermittently unavailable and that shall not halt autonomous control.
- BLE is local-service only and may be unavailable while the controller is sleeping.

## Moisture-sensor assumptions

- The baseline sensor family is the capacitive soil-moisture sensor V2.0.0 type referenced in the planning sketch.
- Default conversion references are 2.45 V for dry air and 0.90 V for very wet conditions.
- Sensor readings outside 0.70 V through 2.70 V are treated as electrically suspicious in v1 unless later hardware characterization proves a different safe range.
- The controller shall sample and smooth sensor readings in firmware before making watering decisions, but the exact filter implementation is left to code as long as the externally specified behavior is preserved.

## Power and pump assumptions

- Pump power may be 3.3 V through 5.0 V depending on the selected pump rail.
- A 5.0 V boosted pump rail is preferred for v1 if the selected pump does not produce adequate flow at battery voltage alone.
- Relay inputs are expected to remain safely off during controller reset through GPIO default state and external pull-up design.
- The documentation assumes no more than three simultaneous pump loads because battery sag, relay-board thermals, and supply noise are not characterized for a higher concurrency limit.

## Firmware and storage assumptions

- ESP-IDF is the firmware framework.
- The default ESP-IDF `nvs` partition is reserved for system-managed data such as Wi-Fi and BLE state.
- Application-owned state shall live only in `appcfg` and `events` partitions plus OTA metadata managed by ESP-IDF.
- Event logging is event-based only and shall not perform continuous writes during active watering.

## Interface assumptions

- Home Assistant integration is MQTT-first and uses discovery.
- The Android app is written in Kotlin and Compose and communicates over BLE.
- BLE access in v1 uses standard OS-managed BLE bonding with no additional application-layer credential.
- Multiple phones may be bonded over time, but only one BLE central connection is required at a time.
- Clearing stored BLE bonds is handled as a Home Assistant device-management action rather than a full factory reset.
- A future security-hardening option may add a physical push button that must be pressed before the controller accepts a new BLE bond.
- End-user OTA is app-driven over the BLE maintenance service with a bundled or explicitly selected firmware image.

## Non-goals

- The controller is not a general irrigation platform for arbitrary sensor counts in v1.
- The controller is not required to stay continuously reachable over Wi-Fi under low-battery sleep policies.
- The controller is not required to retain second-accurate timestamps when no time source exists.
- The controller is not required to keep long-term history locally beyond the 1000-entry ring.
