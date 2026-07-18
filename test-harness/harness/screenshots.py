"""Host-initiated screenshot capture.

The hybrid screenshot ownership model (plan section 6.1):

  - In-test step screenshots (after-connect, before-save,
    after-readback) are taken INSIDE the Kotlin E2E tests via
    `UiAutomation.takeScreenshot()` writing PNG to external
    storage. The orchestrator `adb pull`s them into
    `<run_dir>/screenshots/` after `am instrument` exits.

  - Host-initiated screenshots: preconnect (`00-preconnect.png`),
    on-failure (`on-fail-<mtime>.png`), and final
    (`final.png`). Captured via `adb exec-out screencap -p > file`,
    with an `adb shell screencap` + `adb pull` fallback for
    devices that don't support `exec-out`.

`screenshots.py` covers the host-initiated path only. The
Kotlin-internal path lives in `E2eScreenshotHelper.kt`
(androidTest) and is orchestrator-agnostic.
"""

from __future__ import annotations

import shlex
import subprocess
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from harness.adb import Adb


@dataclass
class ScreenshotResult:
    path: Path
    size: int
    method: str  # "exec-out" or "shell+pull"

    @property
    def ok(self) -> bool:
        return self.size > 0


def _mtime_slug() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")


def capture_screenshot(adb: Adb, dest: Path) -> ScreenshotResult:
    """Capture the device screen to `dest` (PNG).

    Tries `adb exec-out screencap -p` first (binary stdout; works
    on all adb 1.0.39+). Falls back to `adb shell screencap -p
    /sdcard/scr.png` + `adb pull` (always works).
    """
    dest.parent.mkdir(parents=True, exist_ok=True)

    cmd = adb._cmd("exec-out", "screencap", "-p")
    try:
        proc = subprocess.run(cmd, capture_output=True, check=False)
        if proc.returncode == 0 and proc.stdout[:8] == b"\x89PNG\r\n\x1a\n":
            dest.write_bytes(proc.stdout)
            return ScreenshotResult(path=dest, size=len(proc.stdout), method="exec-out")
    except FileNotFoundError:
        pass

    # Fallback path.
    remote = "/sdcard/_beet_harness_scr.png"
    shell_cmd = adb._cmd("shell", "screencap", "-p", remote)
    push_proc = subprocess.run(shell_cmd, capture_output=True, check=False)
    if push_proc.returncode != 0:
        return ScreenshotResult(path=dest, size=0, method="shell+pull")
    pull_cmd = adb._cmd("pull", remote, str(dest))
    pull_proc = subprocess.run(pull_cmd, capture_output=True, check=False)
    # Clean up the remote copy.
    subprocess.run(adb._cmd("shell", "rm", remote), capture_output=True, check=False)
    return ScreenshotResult(
        path=dest,
        size=dest.stat().st_size if dest.exists() else 0,
        method="shell+pull",
    )


def capture_preconnect(adb: Adb, run_dir: Path) -> ScreenshotResult:
    """Capture the device screen BEFORE the orchestrator launches the app.

    Named `00-preconnect.png` per the run-folder contract.
    """
    return capture_screenshot(adb, run_dir / "screenshots" / "00-preconnect.png")


def capture_on_failure(adb: Adb, run_dir: Path) -> ScreenshotResult:
    """Capture after `am instrument` exits with a non-zero status."""
    dest = run_dir / "screenshots" / f"on-fail-{_mtime_slug()}.png"
    return capture_screenshot(adb, dest)


def capture_final(adb: Adb, run_dir: Path) -> ScreenshotResult:
    """Capture after a successful `am instrument` exit."""
    return capture_screenshot(adb, run_dir / "screenshots" / "final.png")
