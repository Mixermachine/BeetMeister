# BeetMeister Controller And Electrical Specification

## Controller module

- Baseline controller board: a 44-pin ESP32-S3 development board built around an `ESP32-S3-WROOM-1-N16R8` class module with 16 MB flash and 8 MB PSRAM.
- The board-level pin planning assumes the `ESP32-S3-DevKitC-1` header layout.
- On that board family, the onboard WS2812 RGB LED is on `GPIO48`.
- USB pins used for development access shall remain free from application assignments.

## Pin-capability and startup constraints

- `GPIO0`, `GPIO3`, `GPIO45`, and `GPIO46` are strapping pins and shall not be used for relay outputs or the baseline moisture-sensor bank.
- `GPIO19` and `GPIO20` are reserved for USB and USB-JTAG and shall remain free in the baseline design.
- `GPIO35`, `GPIO36`, and `GPIO37` shall not be used on the `ESP32-S3-WROOM N16R8` module because the module family uses them for Octal PSRAM.
- `GPIO48` is reserved for the onboard LED and shall remain unused externally on the assumed board family.
- The preferred ADC bank for BeetMeister sensors is `ADC1`.
- Battery sensing shall use an ADC-capable GPIO that does not break the contiguous relay-output block.

## Board-row reality check

Using the official `ESP32-S3-DevKitC-1` header order and the updated design priorities, the board can support:

- eight relay outputs entirely on the right header side
- eight moisture-sensor inputs entirely on `ADC1`
- preserved USB, UART debug, and onboard RGB LED

The tradeoff is that one moisture sensor cannot stay on the left header side.
One relay output must move to the lower left header so battery sense can stay on `ADC1`.

## Detailed planned GPIO allocation

The planned baseline allocation is:

- moisture sensors on `GPIO10`, `GPIO9`, `GPIO8`, `GPIO7`, `GPIO6`, `GPIO5`, `GPIO4`, and `GPIO1`
- battery sense on `GPIO2`
- optional `SSD1306` OLED on `GPIO11` (`SDA`) and `GPIO12` (`SCL`)
- future single-button local control and bond-admit input on `GPIO13`
- relay outputs on `GPIO14`, `GPIO21`, `GPIO47`, `GPIO38`, `GPIO39`, `GPIO40`, `GPIO41`, and `GPIO42`

Rationale:

- This places all relay wiring on the right header side while preserving UART, USB, and the board RGB LED.
- This keeps all eight moisture sensors on `ADC1`.
- This places seven moisture-sensor channels on the left header side and one on the right header side.
- This keeps battery sense on `ADC1` by moving one relay output to `GPIO14` on the lower left header.
- Pair numbering is defined by physical bottom-up board order so wiring and software pair indices stay aligned during assembly.

## Currently free non-problematic GPIOs

After the baseline allocation plus the optional OLED display, the cleanest currently free GPIOs are:

- `GPIO15`
- `GPIO16`
- `GPIO17`
- `GPIO18`

These are preferred for later expansion because they are not strapping pins, are not part of the USB interface, are not tied to the onboard LED, and are not consumed by Octal PSRAM on the `N16R8` module variant.

## Power architecture

- The controller 3.3 V input is supplied directly from a single-cell LiFePO4 battery in v1.
- The charge path is assumed to terminate at 3.6 V.
- The controller shall measure battery voltage through a resistor divider into one ADC channel.
- Divider design shall keep the ADC input below the ESP32-S3 ADC reference range at the maximum battery voltage plus margin.
- Pump supply may be either direct battery voltage or a boosted 5.0 V rail.
- If a boost converter is used, pump ground, relay-board ground, and controller ground shall share a common reference.

## Relay and pump design rules

- The relay board shall expose eight individually driven active-high control inputs.
- Each relay input shall have an external pull-down so the relay remains off through ESP32 reset and boot.
- Relay outputs shall default to off in hardware and again in firmware at the first executable point.
- Pump current and relay-coil current budgets shall be validated against the selected power rail before enabling three-pump concurrency.
- The design shall assume up to three simultaneous pumps, not more.
- The preferred relay order by pair number is `GPIO14`, `GPIO21`, `GPIO47`, `GPIO38`, `GPIO39`, `GPIO40`, `GPIO41`, `GPIO42`.

## Moisture-sensor electrical assumptions

- Each moisture sensor uses positive supply, ground, and one analog output.
- Sensor output shall connect only to ADC-capable GPIOs.
- Default conversion references are 2.45 V dry and 0.90 V wet.
- Sensor cables shall use a common ground with the controller.
- Long analog leads shall be kept physically separate from relay and pump wiring where possible.
- The preferred moisture-sensor order by pair number is `GPIO10`, `GPIO9`, `GPIO8`, `GPIO7`, `GPIO6`, `GPIO5`, `GPIO4`, and `GPIO1`.
- All planned moisture channels remain on `ADC1`.

## Battery measurement path

- Battery sensing shall use one dedicated ADC input with a fixed divider ratio documented in the final schematic.
- The preferred battery-sense input is `GPIO2`, keeping both moisture sensing and battery sensing on `ADC1`.
- The divider shall be chosen so 3.60 V battery voltage corresponds to an ADC voltage with at least 10 percent headroom below the ADC input maximum.
- The divider shall have high enough resistance to avoid meaningful battery drain but low enough source impedance for stable ADC sampling.
- A local capacitor at the ADC sense node is recommended for noise reduction.

## Boot-time safety rules

- Relay GPIOs shall not use `GPIO48` because it is the board RGB LED on the assumed 44-pin board.
- Relay GPIOs shall not use any strapping pin.
- GPIOs with unsafe boot strap implications shall be avoided for relay control in the baseline map.
- No pump or relay shall energize because of controller reset, boot ROM behavior, OTA reboot, or deep-sleep wake.

## Planned future single-button local control

- `GPIO13` is reserved as the preferred future input for one normally open momentary push button.
- The planned button is a later feature, not a v1 dependency.
- The planned electrical model is a digital input with a stable default inactive state through pull-up or pull-down design chosen in the final schematic.
- The intended later UI model is:
- short press moves through local menu items
- long press selects an option or changes a value
- The same physical button may also be used for BLE bond admission in a later security-hardening phase.
- When bond-admit behavior is implemented, the controller shall accept a new BLE bond only if a bond request is pending and the button is currently pressed.
- Existing bonded phones shall not require the button for reconnect.
- The final UI specification shall define how bond-admit handling overrides or coexists with the normal local menu flow so the meanings do not conflict.

## Required electrical deliverables

- controller schematic with battery, divider, sensors, and relay connections
- power-distribution view showing battery, charger, optional boost rail, relay supply, and common grounds
- wiring checklist validated on the bench before pump testing
