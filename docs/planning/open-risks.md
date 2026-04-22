# BeetMeister Open Risks

## Active risks

| ID | Risk | Impact | Required resolution |
| --- | --- | --- | --- |
| `R-001` | Direct LiFePO4 feed into the board 3.3 V input may behave differently across board revisions. | Brownouts, regulator bypass mismatch, or undefined power-path behavior. | Validate the exact development board revision electrically before field deployment. |
| `R-002` | Relay board logic-high threshold at low supply voltage may be marginal. | Missed relay activation or inconsistent switching. | Bench-test the chosen relay board across 3.10 to 3.60 V controller conditions. |
| `R-003` | Moisture sensor variation may exceed the default voltage references. | Incorrect watering without calibration. | Characterize at least two sensors and confirm default mapping before relying on uncalibrated operation. |
| `R-004` | BLE uses bonding only and no additional application-layer credential in v1. | Any previously bonded phone remains trusted until bonds are cleared, and new bonds do not yet require local physical presence. | Validate that bond clearing through Home Assistant is sufficient for the deployment environment or prioritize the planned bond-admit push button feature. |
| `R-005` | Three simultaneous pumps may create larger voltage droop than the planning assumptions allow. | Unexpected low-battery aborts or resets. | Measure sag on the final pump and battery configuration during bench testing. |
| `R-006` | Event replay after long broker outages may need backpressure handling. | MQTT reconnect burst or broker overload. | Validate reconnect behavior with a near-full local event ring. |
| `R-007` | A single future `GPIO13` button is expected to serve both local UI and BLE bond-admit duties. | Ambiguous press handling could make the local menu confusing or make bond admission hard to trigger reliably. | Define the mode and timing rules for short press, long press, and bond-admit override before implementing local button control. |
