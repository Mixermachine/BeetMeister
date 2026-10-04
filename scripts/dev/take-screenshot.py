#!/usr/bin/env python3
"""Cross-platform Android screenshot capture tool.

Works identically on Windows, macOS, and Linux without CRLF byte corruption.
Usage:
    python scripts/dev/take-screenshot.py [name] [--serial SERIAL] [--out-dir DIR]
"""

import argparse
from datetime import datetime
import os
from pathlib import Path
import subprocess
import sys


def capture_screenshot(out_path: Path, serial: str | None = None) -> None:
    adb_cmd = ["adb"]
    if serial:
        adb_cmd.extend(["-s", serial])
    adb_cmd.extend(["exec-out", "screencap", "-p"])

    try:
        # Direct binary stream capture - immune to OS shell CRLF corruption
        with open(out_path, "wb") as f:
            subprocess.run(adb_cmd, stdout=f, stderr=subprocess.PIPE, check=True)
    except subprocess.CalledProcessError:
        # Fallback to /data/local/tmp method if exec-out is unsupported
        if out_path.exists():
            out_path.unlink()
        remote_tmp = "/data/local/tmp/screencap_temp.png"
        base_cmd = ["adb"] + (["-s", serial] if serial else [])
        subprocess.run(base_cmd + ["shell", "screencap", "-p", remote_tmp], check=True)
        subprocess.run(base_cmd + ["pull", remote_tmp, str(out_path)], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(base_cmd + ["shell", "rm", remote_tmp], check=True)

    # Validate valid PNG header (89 50 4E 47 0D 0A 1A 0A)
    with open(out_path, "rb") as f:
        header = f.read(8)
        if header != b"\x89PNG\r\n\x1a\n":
            raise RuntimeError(f"Corrupt PNG captured at {out_path} (header mismatch: {header.hex()})")


def main() -> None:
    parser = argparse.ArgumentParser(description="Capture device screenshot via ADB")
    parser.add_argument("name", nargs="?", default="screen", help="Filename prefix (default: screen)")
    parser.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL"), help="Device serial")
    parser.add_argument(
        "-o", "--out-dir", default="artifacts/screenshots", help="Output directory (default: artifacts/screenshots)"
    )

    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_file = out_dir / f"{args.name}-{timestamp}.png"

    try:
        capture_screenshot(out_file, serial=args.serial)
        print(f"Screenshot saved: {out_file.resolve()}")
    except Exception as e:
        print(f"Error taking screenshot: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
