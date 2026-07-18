"""Controller-side reset: esptool partition erase.

The fresh-install suite needs a deterministic clean controller
state WITHOUT going through the app/BLE. The cleanest way is to
`erase_region` the three NVS partitions that hold user-visible
state (`appcfg`, `events`, `sysevents`) and leave the running
firmware + `device_id` intact.

Why not BLE factory_reset?
  - It's circular: the suite starts with the app UNINSTALLED, but
    factory_reset is sent over BLE from an installed app.
  - It also wipes `device_id` (used as the controller's identity
    throughout the suite); we want to keep it.

Why not `esptool erase_flash`?
  - That erases the running firmware too, leaving an unbootable
    controller. The fresh-install suite assumes the controller
    is already running the current firmware.

No BLE, no app needed. Just USB.
"""

from __future__ import annotations

import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from harness import partition_map
from harness.config import HarnessConfig


# NVS partitions wiped on fresh-install. These hold the per-pair
# config, the event log, and the system-event log; clearing them
# gives the next installed app a clean controller view while
# keeping the firmware image and the controller's identity intact.
DEFAULT_NVS_PARTITIONS: tuple[str, ...] = ("appcfg", "events", "sysevents")


@dataclass(frozen=True)
class EraseResult:
    partition: str
    offset: int
    size: int
    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0


def build_esptool_command(config: HarnessConfig, *args: str) -> list[str]:
    """Compose an esptool invocation.

    esptool.py needs the IDF Python venv (it has `cryptography`,
    `reedsolo`, etc.). The P3 `cmd /c "<export> && python <esptool>"`
    approach was fragile (P5 finding CRIT #R1: Windows
    cmd quoting rules break in non-obvious ways). The P5 fix:
    invoke the IDF venv Python DIRECTLY via `[idf_python_exe,
    esptool_exe, *args]`. esptool runs headless (image_info,
    erase_region, write_flash) and doesn't need any of the IDF
    env vars set, so we don't need to source export.bat at all.

    Resolution order for the Python interpreter:
      1. `config.env.idf_python_exe` (user-set absolute path)
      2. `sys.executable` (the running Python — works if the
         user runs the harness from inside the IDF env)

    The output is always `<python> <esptool_exe> <args>` — a
    single-arg list, no shell, no quoting.
    """
    esptool = config.require("env.esptool_exe", config.env.esptool_exe)
    if not Path(esptool).exists():
        raise RuntimeError(
            f"esptool not found at {esptool}. Set [env].esptool_exe in config.toml."
        )
    python_exe = config.env.idf_python_exe or sys.executable
    if not Path(python_exe).exists():
        raise RuntimeError(
            f"IDF Python venv not found at {python_exe}. Set [env].idf_python_exe "
            f"in config.toml (e.g. C:/Espressif/tools/python/v6.0/venv/Scripts/python.exe)."
        )
    return [python_exe, esptool, *args]


def erase_config_partitions(
    config: HarnessConfig,
    port: str,
    *,
    partitions: tuple[str, ...] = DEFAULT_NVS_PARTITIONS,
    extra_erase: tuple[str, ...] = (),
) -> list[EraseResult]:
    """Erase the controller's user-state NVS partitions.

    Returns one `EraseResult` per partition (in declaration order).
    Raises `RuntimeError` if esptool is missing or the chip is
    unreachable, so the orchestrator can abort the run with a
    clear message.
    """
    if not port:
        raise RuntimeError(
            "controller port is empty. Set [controller].serial_port in config.toml "
            "or pass --controller-port."
        )

    csv_path = config.repo_root / "firmware" / "esp-idf" / "partitions" / "beetmeister.csv"
    if not csv_path.exists():
        raise RuntimeError(f"partitions CSV not found at {csv_path}")
    layout = partition_map.load(csv_path)
    targets = partition_map.require(layout, *partitions, *extra_erase)

    results: list[EraseResult] = []
    for part in targets:
        cmd = build_esptool_command(
            config,
            "--port", port,
            "erase_region",
            f"0x{part.offset:x}",
            f"0x{part.size:x}",
        )
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=False,
        )
        results.append(EraseResult(
            partition=part.name,
            offset=part.offset,
            size=part.size,
            returncode=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
        ))
    return results
