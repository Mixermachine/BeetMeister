# Windows Workflow

## Installed Paths

- `IDF_PATH`: `C:\esp\v6.0\esp-idf`
- ESP-IDF project root: `C:\git\BeetMeister\firmware\esp-idf`
- Toolchain root: `C:\Espressif\tools`
- Python venv used by the wrapper: `C:\Espressif\tools\python\v6.0\venv`

## Preferred Command Path

Use the repo-local wrapper script:

```powershell
powershell -ExecutionPolicy Bypass -File .\skills\esp-idf-installation\scripts\invoke-idf.ps1 <idf.py args...>
```

Examples:

```powershell
cd C:\git\BeetMeister\firmware\esp-idf
powershell -ExecutionPolicy Bypass -File ..\..\skills\esp-idf-installation\scripts\invoke-idf.ps1 set-target esp32s3
powershell -ExecutionPolicy Bypass -File ..\..\skills\esp-idf-installation\scripts\invoke-idf.ps1 build
powershell -ExecutionPolicy Bypass -File ..\..\skills\esp-idf-installation\scripts\invoke-idf.ps1 -p COM4 flash monitor
```

For bench diagnostics capture from the project root:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev\capture-bench-diagnostics.ps1 -Port COM7 -DurationSeconds 15 -BenchOnly
```

## Known Failure Signatures

Treat these as installation issues, not project issues:

- `C:\Users\admin\.espressif\python_env\idf6.0_py3.13_env\Scripts\python.exe doesn't exist`
- `C:\Users\admin\.espressif\espidf.constraints.v6.0.txt doesn't exist`
- `No module named 'esp_idf_monitor'`

## Repair

Run:

```powershell
cd C:\esp\v6.0\esp-idf
.\install.bat
```

Then reopen the ESP-IDF terminal and retry the build.

## Notes

- `COM4` is the last confirmed BeetMeister board port.
- The current bring-up app uses the onboard addressable LED on `GPIO48`.
- The project target is `esp32s3`.
