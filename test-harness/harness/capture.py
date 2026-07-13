"""Logcat + controller-serial subprocess capture.

Reuses the existing bounded `artifacts/stage8/serial_reader.py`
and `artifacts/stage8/logcat_reader.py` — both have hard
timeouts so a stale stream can never block the orchestrator
(this is the lesson from `artifacts/stage8/serial_reader.py`'s
AGENTS.md rule).

We launch them as `subprocess.Popen` (NOT `subprocess.run`) so
the orchestrator controls when they stop. Output is captured to
a per-run file under `runs/<ts>-<suite>/` and the Popen is
returned for the orchestrator to terminate when the run ends.
"""

from __future__ import annotations

import os
import shlex
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, IO, Optional


@dataclass
class Capture:
    """A running capture subprocess + the file it is writing to.

    `_out_fh` is the parent-side file handle passed to
    `Popen(stdout=...)`. It MUST be closed after the subprocess
    exits; otherwise on Windows the file stays locked and the
    run folder cannot be renamed / deleted while the orchestrator
    is alive. `stop()` closes it after `proc.wait()`.
    """

    name: str  # "logcat" or "serial"
    output_path: Path
    proc: subprocess.Popen
    pid_file: Path
    # The parent's stdout file handle. Closed in stop(); see
    # AGENTS.md "Never block forever on serial/console readers" +
    # P3 review finding CRIT #2.
    _out_fh: Optional[IO[Any]] = field(default=None, repr=False)

    def is_running(self) -> bool:
        return self.proc.poll() is None

    def stop(self, timeout_seconds: float = 3.0) -> int:
        """Terminate the capture subprocess AND close the file handle.

        Order matters:
          1. Terminate the subprocess (so it stops writing).
          2. Wait for it to exit (so its last bytes are flushed).
          3. Close the parent-side file handle (so Windows
             releases the file lock and the run folder can be
             renamed / deleted).

        Bounded: never blocks forever. Returns the subprocess
        returncode.
        """
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                rc = self.proc.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                try:
                    rc = self.proc.wait(timeout=timeout_seconds)
                except subprocess.TimeoutExpired:
                    rc = -1
        else:
            rc = self.proc.returncode

        fh = self._out_fh
        if fh is not None:
            try:
                fh.close()
            except Exception:  # noqa: BLE001 - close errors are not fatal
                pass
            self._out_fh = None
        return rc


def _python() -> str:
    return sys.executable


def _resolve_reader(reader_name: str) -> Path:
    """Return the absolute path to the requested reader script.

    Lives in `artifacts/stage8/` (NOT `test-harness/`) because
    stage 8 created them first; the harness reuses them in
    place. If the file is missing, the harness fails loudly at
    the first capture start — better than a silent zero-byte
    log file.
    """
    repo_root = Path(__file__).resolve().parents[2]
    path = repo_root / "artifacts" / "stage8" / f"{reader_name}.py"
    if not path.exists():
        raise RuntimeError(
            f"capture reader not found: {path}. "
            f"The harness reuses artifacts/stage8/{reader_name}.py."
        )
    return path


def start_logcat(
    run_dir: Path,
    adb_serial: str,
    *,
    log_format: str = "threadtime",
    max_seconds: float = 0.0,  # 0 = run until stopped
    idle_exit: float = 0.0,
) -> Capture:
    """Start `logcat_reader.py` writing to `<run_dir>/android-logcat.txt`.

    `max_seconds=0` + `idle_exit=0` means "stream until
    Capture.stop() is called". The orchestrator always calls
    stop() in its `finally:` block.
    """
    output_path = run_dir / "android-logcat.txt"
    reader = _resolve_reader("logcat_reader")
    cmd: list[str] = [_python(), str(reader)]
    if adb_serial:
        cmd += ["-s", adb_serial]
    cmd += ["--format", log_format]
    if max_seconds:
        cmd += ["--max-seconds", str(max_seconds)]
    if idle_exit:
        cmd += ["--idle-exit", str(idle_exit)]
    # `bufsize=0` + universal newlines=False so the reader's
    # binary writes hit disk immediately. The reader uses
    # `sys.stdout.buffer.write` so this is binary passthrough.
    out_fh = output_path.open("wb")
    proc = subprocess.Popen(cmd, stdout=out_fh, stderr=subprocess.STDOUT, bufsize=0)
    pid_file = run_dir / "logcat.pid"
    pid_file.write_text(str(proc.pid), encoding="utf-8")
    return Capture(
        name="logcat", output_path=output_path, proc=proc,
        pid_file=pid_file, _out_fh=out_fh,
    )


def start_serial(
    run_dir: Path,
    port: str,
    *,
    baud: int = 115200,
    max_seconds: float = 0.0,
    idle_exit: float = 0.0,
) -> Optional[Capture]:
    """Start `serial_reader.py` writing to `<run_dir>/controller-serial.txt`.

    Returns None if `port` is empty (the fresh-install + settings
    suites don't require controller capture).
    """
    if not port:
        return None
    output_path = run_dir / "controller-serial.txt"
    reader = _resolve_reader("serial_reader")
    cmd: list[str] = [_python(), str(reader), port, "--baud", str(baud)]
    if max_seconds:
        cmd += ["--max-seconds", str(max_seconds)]
    if idle_exit:
        cmd += ["--idle-exit", str(idle_exit)]
    out_fh = output_path.open("wb")
    proc = subprocess.Popen(cmd, stdout=out_fh, stderr=subprocess.STDOUT, bufsize=0)
    pid_file = run_dir / "controller-serial.pid"
    pid_file.write_text(str(proc.pid), encoding="utf-8")
    return Capture(
        name="serial", output_path=output_path, proc=proc,
        pid_file=pid_file, _out_fh=out_fh,
    )
