# BeetMeister OTA And Release Specification

## OTA source model

- End-user OTA shall be app-driven over the BLE maintenance service.
- The Android app shall provide the firmware image, either from an app-bundled asset or an explicitly selected custom image.
- Garden Wi-Fi is not part of the active end-user OTA path in v1.
- OTA upload shall occur only while the controller is in `ACTIVE`.

## Eligibility rules

The controller shall reject an OTA request when any of the following is true:

- battery voltage is below 3.30 V
- any pair is in `SANITY_CHECK` or `WATERING`
- any pair remains in `WAITING_FOR_SLOT`
- the controller is in `DEEP_LOW_BATTERY`
- another OTA attempt is already in progress

## Image validation

Before marking an uploaded image bootable, the controller shall validate:

- ESP-IDF image integrity checks
- target chip compatibility with ESP32-S3
- image size fits within the inactive OTA slot
- project identifier matches BeetMeister
- embedded BeetMeister maintenance metadata matches the `begin_update` request

The controller shall not require publisher signing in v1. Trust is based on BLE bonding plus image integrity and hardware compatibility checks.

## Slot usage and boot target behavior

- The controller shall always run from `ota_0` or `ota_1`.
- The inactive slot shall be the OTA upload target.
- After a successful upload and validation, the controller shall mark the new slot for next boot using standard ESP-IDF OTA metadata.
- The controller shall reboot into the candidate slot immediately after a successful OTA preparation phase unless the operator aborts before completion.

## First-boot confirmation and rollback

- The newly booted image shall confirm itself only after completing basic startup successfully.
- Basic startup success means configuration load, runtime snapshot load, event-ring scan, battery check, relay initialization, and at least one scheduler timer initialization all succeed.
- If the new image fails before confirmation, standard ESP-IDF rollback behavior shall return the controller to the previous confirmed slot.
- A failed OTA attempt shall surface a clear status to the BLE maintenance updater once communications are available again.

## Persistence across OTA

The following shall survive OTA success or rollback:

- application configuration in `appcfg`
- pair calibration records
- pair runtime snapshots
- event history in `events`

The following may change as part of the new image:

- firmware version
- protocol version
- internal implementation details

## Operational constraints

- Scheduler-triggered automatic watering shall not start while an OTA attempt is in progress.
- Accepted manual watering commands shall be rejected during OTA.
- If battery voltage falls below 3.20 V during OTA upload, the controller shall abort the OTA attempt safely and remain on the current image.
- The controller shall never erase or invalidate the currently bootable slot before a replacement image is fully downloaded and validated.

## Release rules

- Every release image shall declare a semantic version.
- Every release candidate shall pass the verification criteria in the verification specification.
- Every release candidate shall be tested for OTA upgrade from the immediately previous confirmed release.
