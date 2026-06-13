#!/usr/bin/env python3

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate STL files for all SCAD files in this directory."
    )
    parser.add_argument(
        "--openscad-path",
        help="Explicit path to the OpenSCAD executable.",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Rebuild all STL files even if they look up to date.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show which STL files would be generated without running OpenSCAD.",
    )
    return parser.parse_args()


def resolve_openscad_path(requested_path: str | None) -> str:
    if requested_path:
        candidate = Path(requested_path).expanduser()
        if not candidate.exists():
            raise FileNotFoundError(
                f"OpenSCAD executable not found at '{candidate}'."
            )
        return str(candidate.resolve())

    candidates = []
    if sys.platform.startswith("win"):
        candidates.extend(
            [
                Path(r"C:\Program Files\OpenSCAD\openscad.exe"),
                Path(r"C:\Program Files (x86)\OpenSCAD\openscad.exe"),
                Path.home() / "AppData" / "Local" / "Programs" / "OpenSCAD" / "openscad.exe",
            ]
        )
    elif sys.platform == "darwin":
        candidates.extend(
            [
                Path("/Applications/OpenSCAD.app/Contents/MacOS/OpenSCAD"),
                Path("/opt/homebrew/bin/openscad"),
                Path("/usr/local/bin/openscad"),
            ]
        )
    else:
        candidates.extend(
            [
                Path("/usr/bin/openscad"),
                Path("/usr/local/bin/openscad"),
                Path("/snap/bin/openscad"),
            ]
        )

    for candidate in candidates:
        if candidate.exists():
            return str(candidate.resolve())

    command_path = shutil.which("openscad")
    if command_path:
        return command_path

    raise FileNotFoundError(
        "OpenSCAD executable not found. Install OpenSCAD, put it on PATH, or pass --openscad-path."
    )


def should_build(scad_path: Path, stl_path: Path, force: bool) -> bool:
    if force or not stl_path.exists():
        return True
    return scad_path.stat().st_mtime > stl_path.stat().st_mtime


def generate_stl(openscad_exe: str, scad_path: Path, stl_path: Path) -> None:
    result = subprocess.run(
        [openscad_exe, "--export-format", "binstl", "-o", str(stl_path), str(scad_path)],
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"OpenSCAD failed for '{scad_path.name}' with exit code {result.returncode}."
        )


def main() -> int:
    args = parse_args()
    script_dir = Path(__file__).resolve().parent
    scad_files = sorted(script_dir.glob("*.scad"))

    if not scad_files:
        print(f"No .scad files found in '{script_dir}'.")
        return 0

    openscad_exe = resolve_openscad_path(args.openscad_path)

    for scad_path in scad_files:
        stl_path = scad_path.with_suffix(".stl")
        if not should_build(scad_path, stl_path, args.force):
            print(f"Up to date: {scad_path.name}")
            continue

        print(f"Generating: {scad_path.name} -> {stl_path.name}")
        if args.dry_run:
            continue

        generate_stl(openscad_exe, scad_path, stl_path)

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # pragma: no cover - CLI failure path
        print(error, file=sys.stderr)
        raise SystemExit(1)
