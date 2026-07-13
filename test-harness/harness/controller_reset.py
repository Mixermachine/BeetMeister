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
    """Compose an esptool invocation, optionally sourcing the IDF env.

    esptool.py needs the IDF Python env (cryptography, reedsolo).
    Two cases (P3 finding SUB #4 — unify the esptool invocation style):
      - `idf_env_script` set: source it (cmd /c on Windows, bash -c
        on POSIX) so `python` on PATH becomes the IDF python, then
        run `python <esptool_exe> <args>`.
      - `idf_env_script` empty: assume the host interpreter already
        has esptool deps (user ran `export` or pip-installed
        esptool). Run `<sys.executable> <esptool_exe> <args>`.
    Both cases prefix `python` so `esptool_exe` is ALWAYS treated
    as a Python script (NOT executed directly) — matches the
    existing controller_reset path + fixes firmware._image_metadata
    which used to run `[esptool_exe, ...]` without a python prefix.
    """
    esptool = config.require("env.esptool_exe", config.env.esptool_exe)
    if not Path(esptool).exists():
        raise RuntimeError(
            f"esptool not found at {esptool}. Set [env].esptool_exe in config.toml."
        )
    env_script = config.env.idf_env_script
    if not env_script:
        return [sys.executable, esptool, *args]
    if sys.platform.startswith("win"):
        quoted = f'"{env_script}" && python "{esptool}" ' + " ".join(args)
        return ["cmd", "/c", quoted]
    quoted = f'source "{env_script}" && python "{esptool}" ' + " ".join(args)
    return ["bash", "-c", quoted]


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
