# BeetMeister Fritzing Artifacts

This directory now carries the tracked BeetMeister wiring artifacts plus the generator used to keep them aligned with the documented hardware baseline.

Delivered artifacts:

- `system-wiring.fzz`, `system-wiring.png`, `system-wiring.pdf`
- `controller-pinout.fzz`, `controller-pinout.png`, `controller-pinout.pdf`
- `power-distribution.fzz`, `power-distribution.png`, `power-distribution.pdf`

Tracked source files:

- `source/system-wiring.svg`
- `source/controller-pinout.svg`
- `source/power-distribution.svg`
- `generate_artifacts.py`

Chosen baseline assumptions:

- final-baseline wiring is shown, with bench-only differences annotated inside the diagrams where helpful
- pump power baseline is **direct battery**, not a boost converter
- the charger is shown as a **generic LiFePO4 charger block**, not a fixed module SKU
- the current OLED is part of the baseline on `GPIO11` / `GPIO12`
- `GPIO13` is reserved for the future single-button local UI / BLE bond-admit path

Canonical source of truth:

- pin mapping and pair numbering come from `hardware/wiring/pin-assignment.md`
- electrical baseline comes from `hardware/wiring/controller-and-electrical-specification.md`

Important visual commitments:

- labels match canonical pair numbers and GPIO numbers
- `GPIO48` is shown reserved for the onboard RGB LED on the assumed 44-pin board
- the battery sense path and divider are explicitly labeled
- common ground and the direct battery pump rail are visually unambiguous

Regeneration:

```powershell
python .\hardware\fritzing\generate_artifacts.py
```

Implementation note:

- the current environment does not provide a Fritzing desktop or CLI executable, so each `.fzz` is generated as a valid shareable sketch bundle containing a custom part whose SVG is the diagram itself
- PNG and PDF exports are rendered from the tracked SVG sources through headless Microsoft Edge
