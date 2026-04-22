# BeetMeister Bench Bring-Up Checklist

Use this checklist before any firmware-controlled watering test.

Start with the board-only measurement pass first. Use [measurement-validation-worksheet.md](C:/git/BeetMeister/hardware/wiring/measurement-validation-worksheet.md:1) together with the bench diagnostics capture helper at [scripts/dev/capture-bench-diagnostics.ps1](C:/git/BeetMeister/scripts/dev/capture-bench-diagnostics.ps1:1).

## First-pass sequence

- Keep `CONFIG_BEET_ENABLE_PUMP_OUTPUTS=n`.
- Keep `CONFIG_BEET_ENABLE_RELAY_SELF_TEST=n` until analog inputs are trusted.
- Validate the battery divider on `GPIO2` before wiring any relays or pumps.
- Validate one controlled moisture input on pair 1 `GPIO10` before wiring all remaining channels.
- Expand to the remaining moisture channels only after pair 1 behaves predictably.

## Wiring checks

- Confirm common ground between controller, relay board, battery measurement divider, sensors, and pump supply.
- Confirm GPIO48 has no external connection if the assumed board RGB LED is kept in service.
- Confirm each sensor signal goes to the intended ADC pin from the pin-assignment table.
- Confirm the `GPIO2` battery input scales correctly on `ADC1`.
- Confirm the displaced relay output on `GPIO14` behaves like the right-side relay outputs and remains off during boot.
- Confirm each relay input goes to the intended relay GPIO and has an external pull-down.
- Confirm battery divider output goes only to the dedicated battery ADC input.
- Confirm pump wiring polarity and relay contact wiring for each pair.

## Power checks

- Power the controller from a bench supply set to 3.30 V before connecting a real battery.
- Verify controller idle current and confirm no relay energizes during boot.
- Sweep the simulated battery voltage through 3.30 V, 3.20 V, and 3.10 V thresholds and confirm the expected firmware state transitions.
- If a boost converter is used, verify output voltage and startup behavior before any pump is connected.

## Signal checks

- Read each moisture sensor ADC input with the sensor disconnected and confirm the firmware flags an invalid reading.
- Feed a known analog voltage into at least one sensor channel and confirm the reported moisture percentage is plausible.
- Verify battery ADC scaling against a multimeter reading.
- Capture `bench battery ...` and `bench pair=...` lines while validating each stage.

## Pump checks

- Test one relay and one pump first.
- Confirm a manual start energizes only the intended relay.
- Confirm a manual stop de-energizes the relay immediately.
- Confirm no more than three relays can be active simultaneously.

## Connectivity checks

- Confirm Wi-Fi connect and MQTT availability when the bench supply is above 3.30 V.
- Confirm BLE advertisement while awake.
- Confirm controller behavior remains safe when Wi-Fi is absent.
