# BeetMeister Milestone Roadmap

## Milestones

| Milestone | Goal | Exit condition |
| --- | --- | --- |
| `M0` Documentation baseline | Freeze terminology, interfaces, and constraints | All normative docs in this package are reviewed for consistency |
| `M1` Electrical prototype | Build safe bench hardware and Fritzing artifacts | Board powers safely, pin map is validated, first ADC and relay checks pass |
| `M2` Core firmware logic | Implement scheduler, pair state machine, persistence, and battery logic | Host and target tests pass for conversion, queueing, blocks, and storage |
| `M3` Connectivity | Implement MQTT discovery plus BLE protocol and Android app skeleton | Home Assistant and BLE control paths operate against target hardware |
| `M4` OTA and release candidate | Implement OTA, rollback behavior, and release gating | OTA, persistence, and soak tests pass |
| `M5` Security hardening | Add optional physical bond-admit button flow for new BLE bonds | New bonds require a pending bond request plus button press, while existing bonds still reconnect normally |

## Sequencing rules

- `M1` shall not redefine pin, battery, or relay assumptions without updating the hardware spec first.
- `M2` shall not invent new pair states, stop reasons, or record fields outside the central specs.
- `M3` shall not publish MQTT or BLE payload fields that are not defined in the integration and BLE specifications.
- `M4` shall not ship a release candidate until the verification specification is implemented to the required depth.
