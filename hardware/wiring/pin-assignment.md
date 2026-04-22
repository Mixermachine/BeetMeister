# BeetMeister Baseline Pin Assignment

This table fixes the baseline v1 pin map.
Changes require updating the hardware spec and any firmware constants derived from it.

## Planning basis

The pin plan below is chosen to satisfy these priorities in order:

1. avoid strapping pins for pumps and regular sensor inputs
2. keep all eight moisture sensors on `ADC1` only
3. place all eight relay outputs on the right header side if possible
4. preserve `GPIO19` and `GPIO20` for USB access during development
5. preserve `GPIO48` for the onboard WS2812 LED on the `ESP32-S3-DevKitC-1` board family
6. preserve `GPIO43` and `GPIO44` for UART debug and recovery access
7. keep as many moisture channels as practical on the left header side
8. avoid `GPIO35` to `GPIO37` because they are unavailable on the `ESP32-S3-WROOM-N16R8` module family with Octal PSRAM

## Physical header order on the 44-pin board

The baseline board-level planning assumes the `ESP32-S3-DevKitC-1` header order below, which matches the layout you described.

Left side, top to bottom (`J1`):

`3V3`, `3V3`, `RST`, `4`, `5`, `6`, `7`, `15`, `16`, `17`, `18`, `8`, `3`, `46`, `9`, `10`, `11`, `12`, `13`, `14`, `5V`, `G`

Right side, top to bottom (`J3`):

`G`, `TX`, `RX`, `1`, `2`, `42`, `41`, `40`, `39`, `38`, `37`, `36`, `35`, `0`, `45`, `48`, `47`, `21`, `20`, `19`, `G`, `G`

## Excluded GPIO groups

| GPIOs | Status | Reason |
| --- | --- | --- |
| `GPIO0`, `GPIO3`, `GPIO45`, `GPIO46` | Do not use for relays or normal sensor inputs | Strapping pins sampled at reset |
| `GPIO19`, `GPIO20` | Reserved | USB D- and D+, used by USB-JTAG and USB device functions |
| `GPIO35`, `GPIO36`, `GPIO37` | Do not use | Connected to Octal PSRAM on `N16R8` modules |
| `GPIO48` | Reserved | Onboard RGB LED on `ESP32-S3-DevKitC-1` |
| `GPIO43`, `GPIO44` | Reserved | UART debug and recovery path |

## Free expansion GPIOs

These GPIOs are currently unassigned after the baseline plan is applied, excluding the optional OLED display add-on.

| GPIOs | Recommended status | Why |
| --- | --- | --- |
| `GPIO15` to `GPIO18` | Preferred free expansion pins | Left-header pins, not strapping pins, not USB pins, not PSRAM pins, and still available after the baseline mapping plus the optional OLED display |

Use notes:

- `GPIO11` and `GPIO12` are now the preferred `SSD1306` OLED lines, with `GPIO11` as `SDA` and `GPIO12` as `SCL`.
- `GPIO15` to `GPIO18` are the cleanest currently free pins for future expansion after the baseline plan plus the optional OLED display is applied.
- `GPIO14` is intentionally not listed as free because it carries the one relay output moved to the left header.
- `GPIO12` is intentionally not listed as a preferred button pin because `GPIO13` is the planned future single-button local control and BLE bond-admit input.
- `GPIO15` to `GPIO18` are `ADC2`-capable and also usable as digital I/O if later needed.

## Detailed planned assignment

This plan is optimized for the physical 44-pin board layout rather than pure GPIO-number ordering.
All eight moisture-sensor inputs stay on `ADC1`.
Battery sense also moves to `ADC1`.
Most relay outputs remain on the right header side, but one relay output moves to the lower left header.
Because `ADC1` exposes only `GPIO1` through `GPIO10`, the battery input must use `GPIO2` and one relay is displaced from the right header.

## Board-layout conclusion

On the assumed 44-pin `ESP32-S3-DevKitC-1` header layout, the chosen compromise is:

- moisture sensors entirely on `ADC1`
- battery sense on `ADC1`
- seven relays on the right header side and one relay on the lower left header
- USB, UART debug, and the onboard RGB LED preserved
- strapping pins and Octal PSRAM pins avoided

The cost of this layout is that one moisture sensor is no longer on the left header side, and one relay output moves to `GPIO14` on the lower left header.

## Pair numbering convention

Pair numbers are assigned by physical wiring order from the bottom of the board upward.

Relay order from bottom to top:

- `GPIO14`
- `GPIO21`
- `GPIO47`
- `GPIO38`
- `GPIO39`
- `GPIO40`
- `GPIO41`
- `GPIO42`

Moisture-sensor order from bottom to top:

- `GPIO10`
- `GPIO9`
- `GPIO8`
- `GPIO7`
- `GPIO6`
- `GPIO5`
- `GPIO4`
- `GPIO1`

Each pair number binds the relay and moisture pin at the same position in those two ordered lists.

| Function | GPIO | Notes |
| --- | --- | --- |
| Moisture sensor pair 1 | GPIO10 | Left header, ADC1, paired with relay on `GPIO14` |
| Moisture sensor pair 2 | GPIO9 | Left header, ADC1, paired with relay on `GPIO21` |
| Moisture sensor pair 3 | GPIO8 | Left header, ADC1, paired with relay on `GPIO47` |
| Moisture sensor pair 4 | GPIO7 | Left header, ADC1, paired with relay on `GPIO38` |
| Moisture sensor pair 5 | GPIO6 | Left header, ADC1, paired with relay on `GPIO39` |
| Moisture sensor pair 6 | GPIO5 | Left header, ADC1, paired with relay on `GPIO40` |
| Moisture sensor pair 7 | GPIO4 | Left header, ADC1, paired with relay on `GPIO41` |
| Moisture sensor pair 8 | GPIO1 | Right header, ADC1, paired with relay on `GPIO42` |
| Battery sense | GPIO2 | Right header, ADC1 input through divider |
| OLED SDA | GPIO11 | Left header, reserved for `SSD1306` I2C data |
| OLED SCL | GPIO12 | Left header, reserved for `SSD1306` I2C clock |
| Future local UI / BLE bond-admit button | GPIO13 | Left header, reserved digital input for later short-press and long-press controls plus bond admission |
| Relay pair 1 | GPIO14 | Left header, digital output, external pull-down, paired with moisture on `GPIO10` |
| Relay pair 2 | GPIO21 | Right header, digital output, external pull-down, paired with moisture on `GPIO9` |
| Relay pair 3 | GPIO47 | Right header, digital output, external pull-down, paired with moisture on `GPIO8` |
| Relay pair 4 | GPIO38 | Right header, digital output, external pull-down, paired with moisture on `GPIO7` |
| Relay pair 5 | GPIO39 | Right header, digital output, external pull-down, paired with moisture on `GPIO6` |
| Relay pair 6 | GPIO40 | Right header, digital output, external pull-down, paired with moisture on `GPIO5` |
| Relay pair 7 | GPIO41 | Right header, digital output, external pull-down, paired with moisture on `GPIO4` |
| Relay pair 8 | GPIO42 | Right header, digital output, external pull-down, paired with moisture on `GPIO1` |
| USB D- | GPIO19 | Reserved for USB |
| USB D+ | GPIO20 | Reserved for USB |
| Onboard RGB LED | GPIO48 | Reserved, do not use externally |
| UART TX debug | GPIO43 | Reserved for development |
| UART RX debug | GPIO44 | Reserved for development |

## Pin-reservation rules

- GPIO48 shall remain reserved for the onboard LED on the assumed 44-pin board.
- GPIO19 and GPIO20 shall remain reserved for USB connectivity.
- GPIO43 and GPIO44 shall remain reserved for debug or recovery access during development.
- Relay outputs shall not be remapped onto ADC pins in v1.
- `GPIO3` shall remain excluded from normal sensor-bank planning because it is a strapping pin.
- Pair numbering shall follow the physical bottom-up wiring order, not raw GPIO number order.
- The preferred relay order by pair number is `GPIO14`, `GPIO21`, `GPIO47`, `GPIO38`, `GPIO39`, `GPIO40`, `GPIO41`, `GPIO42`.
- The preferred moisture-sensor order by pair number is `GPIO10`, `GPIO9`, `GPIO8`, `GPIO7`, `GPIO6`, `GPIO5`, `GPIO4`, `GPIO1`, keeping all moisture sensing on `ADC1`.
- The preferred OLED mapping is `GPIO11` for `SDA` and `GPIO12` for `SCL`.
- `GPIO13` is reserved as the preferred future single-button local control and BLE bond-admit input.
- The preferred currently free expansion pins are `GPIO15` through `GPIO18`.
