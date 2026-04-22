# BeetMeister Baseline BOM

## Required assemblies

| Item | Quantity | Notes |
| --- | --- | --- |
| ESP32-S3-WROOM N16R8 development board | 1 | Must expose required GPIOs and stable 3.3 V input path |
| Capacitive soil moisture sensor V2.0.0 class | 8 | Analog-output type |
| 8-channel relay board | 1 | Active-high input behavior assumed by the spec |
| Low-voltage water pump | 8 | 3.3 V to 5.0 V operating class |
| LiFePO4 battery pack 15 Ah | 1 | Single-cell pack as project baseline |
| LiFePO4 charger module | 1 | Charge termination at 3.6 V |
| Resistors for battery divider | 2 | Final values to be fixed after ADC characterization |
| Capacitor for battery sense filter | 1 | Recommended |
| Terminal blocks or waterproof connectors | As needed | For pumps, sensors, and battery leads |
| Wire harness and crimping accessories | As needed | Outdoor and low-voltage suitable |

## Open procurement notes

- Verify the selected relay board reliably recognizes 3.3 V logic high down to 3.1 V supply conditions.
- Verify each chosen pump meets expected flow at the selected pump voltage rail.
- Verify sensor mechanical housing and cable quality for garden use.
- Verify the ESP32-S3 board tolerates direct LiFePO4 supply on the intended power input in the actual board revision.
