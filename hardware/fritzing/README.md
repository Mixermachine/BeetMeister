# BeetMeister Fritzing Deliverables

The following Fritzing artifacts are required before firmware integration moves past bench prototype status:

- `system-wiring.fzz`: full system wiring diagram with controller, eight sensors, relay board, battery, charger, and pump rail
- `controller-pinout.fzz`: controller-centric pinout view matching `hardware/wiring/pin-assignment.md`
- `power-distribution.fzz`: battery, charger, optional boost rail, relay supply, and common-ground diagram

For each `.fzz` file, export:

- one `.png` for quick review
- one `.pdf` for print or bench use

Acceptance rules:

- exported labels match the canonical pair numbers and GPIO numbers
- GPIO48 is shown as reserved for the onboard RGB LED on the assumed 44-pin board
- battery sense path and divider are explicitly labeled
- grounds and pump supply rail are visually unambiguous
