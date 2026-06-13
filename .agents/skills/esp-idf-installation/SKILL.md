---
name: esp-idf-installation
description: Work with BeetMeister's Windows ESP-IDF installation for building, flashing, monitoring, and repairing the local ESP32-S3 firmware project. Use when Codex needs to run or troubleshoot ESP-IDF commands in this repo, especially under `firmware/esp-idf`, or when `IDF_PATH`, the Windows toolchain, the Python environment, or the serial port setup is involved.
---

# ESP-IDF Installation

Use the BeetMeister ESP-IDF installation exactly as documented here instead of guessing paths or relying on a generic Windows ESP-IDF setup.

## Quick Start

- Treat [firmware/esp-idf](C:/git/BeetMeister/firmware/esp-idf:1) as the ESP-IDF project root.
- Treat `C:\esp\v6.0\esp-idf` as `IDF_PATH`.
- Treat `C:\Espressif\tools` as the installed toolchain root.
- Prefer the repo-local wrapper script at [scripts/invoke-idf.ps1](C:/git/BeetMeister/.agents/skills/esp-idf-installation/scripts/invoke-idf.ps1:1) for `idf.py` commands on this machine.
- Read [references/windows.md](C:/git/BeetMeister/.agents/skills/esp-idf-installation/references/windows.md:1) when exact commands or recovery steps are needed.

## Run Commands

Run ESP-IDF commands from [firmware/esp-idf](C:/git/BeetMeister/firmware/esp-idf:1).

Use the wrapper script when:
- `idf.py` is not on `PATH`
- `export.ps1` points at a broken `.espressif` Python environment
- the command needs the BeetMeister-specific Windows tool paths

Typical invocations:

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 set-target esp32s3
powershell -ExecutionPolicy Bypass -File .\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 build
powershell -ExecutionPolicy Bypass -File .\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 -p COM4 flash monitor
```

Important:
- Do not start `build`, `flash`, or `monitor` workflows in parallel against the same `firmware/esp-idf/build` directory.
- `flash` already includes the required build step.
- Running standalone `build` and `flash` at the same time can corrupt the active CMake or Ninja regeneration step and produce a false build failure.

Check the active serial port before flashing if it is not already known. `COM4` is the last confirmed BeetMeister port, not a guaranteed constant.

## Repair Workflow

Repair the ESP-IDF installation when `idf.py` fails with missing `.espressif` files such as:
- `C:\Users\admin\.espressif\python_env\idf6.0_py3.13_env\Scripts\python.exe`
- `C:\Users\admin\.espressif\espidf.constraints.v6.0.txt`

Use this sequence:
1. Run `C:\esp\v6.0\esp-idf\install.bat` from a normal Windows shell.
2. Reopen the ESP-IDF terminal after installation completes.
3. Return to [firmware/esp-idf](C:/git/BeetMeister/firmware/esp-idf:1).
4. Retry the wrapper script or plain `idf.py`.

## Project Assumptions

- Target chip: `esp32s3`
- Project root: [firmware/esp-idf](C:/git/BeetMeister/firmware/esp-idf:1)
- Installed IDF root: `C:\esp\v6.0\esp-idf`
- Toolchain root: `C:\Espressif\tools`
- First bring-up app: onboard LED blink on `GPIO48`

Do not invent alternate install paths unless the repo or the user explicitly changes them.

## References

- Read [references/windows.md](C:/git/BeetMeister/.agents/skills/esp-idf-installation/references/windows.md:1) for exact environment variables, command examples, and known failure signatures.
