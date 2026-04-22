# BeetMeister Measurement Validation Worksheet

Use this worksheet during the first analog bring-up pass with only the ESP32-S3 board, bench supply, battery divider, and one controlled moisture input.

## Session metadata

| Item | Value |
| --- | --- |
| Date | |
| Board | |
| Firmware build | |
| COM port | |
| Divider resistor values | |
| Multimeter model | |
| Bench supply model | |

## Battery validation

Record the multimeter reading at the controller supply, the divider node, and the firmware bench output from `bench battery ...`.

| Supply V | Divider node V | Firmware raw | Firmware sensed mV | Firmware scaled mV | Firmware filtered mV | Battery state | Pass or note |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3.30 | | | | | | | |
| 3.20 | | | | | | | |
| 3.10 | | | | | | | |

Expected direction:

- `sensed_mv` follows the divider node
- `scaled_mv` follows the real supply voltage after divider compensation
- `filtered_mv` stabilizes near `scaled_mv`
- state transitions should align with `ACTIVE`, `IDLE_LOW_POWER`, and `DEEP_LOW_BATTERY` thresholds once the divider is real

## Pair 1 moisture validation

Use pair 1 first because it is `GPIO10` and keeps the first pass simple.

| Condition | Injected V | Firmware raw | Firmware mV | Firmware moisture % | Sample ok | Sensor valid | Pair state | Pass or note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Disconnected | | | | | | | | |
| Mid-range test | | | | | | | | |
| Near dry reference | | | | | | | | |
| Near wet reference | | | | | | | | |

Expected behavior:

- disconnected input remains invalid and should fault the pair
- plausible input returns the pair to `IDLE`
- moisture percentage increases as the injected voltage moves toward the wet reference

## Remaining pair mapping pass

Repeat the same controlled-input method one pair at a time.

| Pair | Moisture GPIO | Injected V | Firmware mV | Firmware moisture % | Sensor valid | Pair state | Pass or note |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | GPIO10 | | | | | | |
| 2 | GPIO9 | | | | | | |
| 3 | GPIO8 | | | | | | |
| 4 | GPIO7 | | | | | | |
| 5 | GPIO6 | | | | | | |
| 6 | GPIO5 | | | | | | |
| 7 | GPIO4 | | | | | | |
| 8 | GPIO1 | | | | | | |

## Exit criteria

Do not move to relay validation until:

- the battery divider ratio is confirmed against multimeter readings
- at least one moisture input is proven stable with a controlled source
- disconnected inputs still fault cleanly
- the validated channel recovers from `FAULT` to `IDLE`
