#!/usr/bin/env python3
"""Symlink / launcher for take-screenshot.py from skill directory."""
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
SCRIPT_PATH = REPO_ROOT / "scripts" / "dev" / "take-screenshot.py"

if __name__ == "__main__":
    if not SCRIPT_PATH.exists():
        print(f"Error: Script not found at {SCRIPT_PATH}", file=sys.stderr)
        sys.exit(1)
    res = subprocess.run([sys.executable, str(SCRIPT_PATH)] + sys.argv[1:])
    sys.exit(res.returncode)
