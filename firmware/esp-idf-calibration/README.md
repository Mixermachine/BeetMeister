# BeetMeister ADC Calibration Firmware

This is a separate calibration-only ESP-IDF project. It exists so ADC and eFuse
calibration checks can be run without modifying the main BeetMeister firmware in
`firmware/esp-idf`.

Important limitations:

- On `ESP32-S3` with the current `ESP-IDF v6.0` install, Espressif does not
  expose a supported internal-ADC-Vref-to-GPIO route. The low-level
  `adc_ll_vref_output()` implementation for `esp32s3` is stubbed with `abort()`.
- So this image reports the per-chip ADC calibration data from eFuse and the
  runtime calibration scheme being used, but it does not physically drive Vref
  onto `GPIO11`.
- This calibration project is configured to match the working BeetMeister board
  baseline: `16 MB` flash, octal PSRAM enabled, and `DIO` flash mode at `80 MHz`.

What it logs:

- chip revision and features
- whether line-fitting eFuse data is available
- per-unit/per-attenuation ADC eFuse init codes and calibration voltages
- runtime ADC calibration scheme used for the `GPIO2` battery path
- repeated `GPIO2` raw and converted voltage readings

Build and flash from this project root:

```powershell
cd C:\git\BeetMeister\firmware\esp-idf-calibration
powershell -ExecutionPolicy Bypass -File ..\..\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 set-target esp32s3
powershell -ExecutionPolicy Bypass -File ..\..\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 -p COM7 flash monitor
```
