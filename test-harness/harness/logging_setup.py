"""Structured logging setup for the BeetMeister test harness.

Two handlers:
  console  — INFO, WARNING, ERROR, CRITICAL  (sparse; what the operator sees)
  file     — DEBUG + above                    (comprehensive; written to run dir)

The file is named ``harness-<iso-timestamp>.log`` under the run directory.
Call ``configure_logging()`` once before the orchestrator starts, then use
``logging.getLogger("harness")`` everywhere.

Usage::

    from harness.logging_setup import configure_logging, get_logger

    configure_logging(run_dir)
    log = get_logger()
    log.info("starting firmware_update suite")
    log.debug("subprocess cmd: %s", cmd)
"""

from __future__ import annotations

import logging
import os
import sys
from pathlib import Path
from typing import Optional

_LOGGER_NAME = "harness"
_log_file_path: Optional[Path] = None


def get_logger() -> logging.Logger:
    """Return the harness logger (already configured after configure_logging())."""
    return logging.getLogger(_LOGGER_NAME)


def log_file_path() -> Optional[Path]:
    """The absolute path to the harness debug log file, or None before setup."""
    return _log_file_path


def configure_logging(run_dir: Path) -> None:
    """Wire the harness logger with console (INFO+) + file (DEBUG+) handlers.

    Must be called exactly once per run, before any harness code logs.
    Prints the log file path to stdout on success so the operator can
    find it in the console output.

    Args:
        run_dir: The per-suite output directory (e.g.
                 ``runs/20260716-180318-firmware_update/``).
    """
    global _log_file_path

    run_dir.mkdir(parents=True, exist_ok=True)

    logger = logging.getLogger(_LOGGER_NAME)
    logger.setLevel(logging.DEBUG)
    logger.propagate = False  # Don't forward to root logger

    # --- console handler: INFO + above, minimal format ---------------
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setLevel(logging.INFO)
    console_fmt = logging.Formatter(
        "[harness] %(levelname)-5s %(message)s",
    )
    console_handler.setFormatter(console_fmt)
    logger.addHandler(console_handler)

    # --- file handler: DEBUG + above, timestamped format ---------------
    # Use a simple ISO-ish timestamp in the filename so each log is
    # unique within the run (even if configure_logging is called more
    # than once, e.g. for dry-run-then-real runs).
    import datetime as _dt
    ts = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    log_path = run_dir / f"harness-{ts}.log"
    _log_file_path = log_path.resolve()

    file_handler = logging.FileHandler(str(log_path), encoding="utf-8")
    file_handler.setLevel(logging.DEBUG)
    file_fmt = logging.Formatter(
        "%(asctime)s [%(levelname)-5s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    file_handler.setFormatter(file_fmt)
    logger.addHandler(file_handler)

    # Print the log file path to console so the operator knows where to
    # find full details. Use a bare print() here, not the logger,
    # because the logger's handlers haven't taken effect yet for this
    # message (and we WANT this on stdout even if the caller hasn't
    # started logging yet).
    print(f"[harness] LOG_FILE = {log_path}", file=sys.stdout)
