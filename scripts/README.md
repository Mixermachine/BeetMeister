Project automation scripts live here.

- `dev`: local development helpers
- `release`: packaging and release helpers

Current development helpers include:

- `dev/capture-bench-diagnostics.ps1`: capture BeetMeister serial output from the ESP32-S3 and summarize the latest `bench battery ...` and `bench pair=...` diagnostics
- `dev/run-firmware-host-tests.ps1`: run the non-hardware firmware host tests, or fall back to compile-only validation when no Windows MSVC/SDK linker environment is available
- `dev/run-firmware-qemu-smoke.ps1`: attempt an `idf.py qemu` smoke run when Espressif QEMU is installed locally

Release helpers include:

- `release/export-bundled-firmware.ps1`: build or validate the release-profile firmware image and stage it into generated Android app assets for bundling
