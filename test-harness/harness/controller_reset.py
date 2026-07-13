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

import shutil
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


def _python_exe() -> str:
    """Return the Python interpreter that should host esptool.py.

    `esptool.py` ships inside the ESP-IDF tree. We invoke it as
    `python esptool.py` (NOT directly) because esptool imports
    `cryptography` and friends that live in the IDF Python env.
    On Windows the IDF env is `C:\\esp\\v6.0\\esp-idf\\python_env\\...`.
    """
    return sys.executable


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
    esptool = config.require("env.esptool_exe", config.env.esptool_exe)
    if not Path(esptool).exists():
        raise RuntimeError(
            f"esptool not found at {esptool}. Set [env].esptool_exe in config.toml."
        )
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
    py = _python_exe()
    for part in targets:
        cmd = [
            py,
            esptool,
            "--port", port,
            "erase_region",
            f"0x{part.offset:x}",
            f"0x{part.size:x}",
        ]
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
