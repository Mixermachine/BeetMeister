"""Old-firmware source-of-truth for the firmware-update suite.

The firmware-update suite flashes a pinned OLD image first (so
the post-OTA assertion has a known pre-update build label) and
then drives the app's existing OTA flow to the current bundled
image.

This module:

1. `ensure_old_firmware_built(pinned_tag)` — `git worktree add
   <worktree> <tag>`, build with `idf.py`, cache the resulting
   `.bin` + a parsed `version.txt` (build_label,
   firmware_version, runtime_protocol_version, image_size,
   image_sha256) under `test-harness/firmware_cache/`. The
   worktree is removed after the build.

2. `flash_old(port, ...)`: erase `appcfg` (P5 decision: always
   erase, for deterministic clean state), then `idf.py -p
   <port> flash` of the cached `.bin`.

Status: **P3 — written + import-clean, NOT exercised.** The P3
exit criterion is the smoke gate; firmware.py is first actually
run in P4.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from harness import partition_map
from harness.config import HarnessConfig


@dataclass(frozen=True)
class FirmwareBuildInfo:
    pinned_tag: str
    bin_path: Path
    build_label: str
    firmware_version: str
    runtime_protocol_version: int
    image_size: int
    image_sha256: str

    def to_version_txt(self) -> str:
        return (
            f"pinned_tag={self.pinned_tag}\n"
            f"build_label={self.build_label}\n"
            f"firmware_version={self.firmware_version}\n"
            f"runtime_protocol_version={self.runtime_protocol_version}\n"
            f"image_size={self.image_size}\n"
            f"image_sha256={self.image_sha256}\n"
        )


def cache_dir(harness_config: HarnessConfig) -> Path:
    d = harness_config.harness_dir / "firmware_cache"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _cache_paths(harness_config: HarnessConfig, pinned_tag: str) -> tuple[Path, Path]:
    cache = cache_dir(harness_config)
    bin_name = f"beetmeister-{pinned_tag}.bin"
    return cache / bin_name, cache / f"beetmeister-{pinned_tag}.version.txt"


def _git(*args: str, cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=False,
    )


def _parse_image_info(stdout: str) -> dict[str, str]:
    """Parse the output of `esptool.py image_info`.

    esptool prints one `key: value` per line for fields like
    `Chip type`, `Image type`, `Image version`, `Build version`,
    etc. We only care about a small subset (build version maps to
    the firmware's `build_label`).
    """
    wanted = ("Build version", "Image version", "Chip type")
    out: dict[str, str] = {}
    for line in stdout.splitlines():
        for key in wanted:
            if line.startswith(key + ":"):
                out[key] = line.split(":", 1)[1].strip()
                break
    return out


def _image_metadata(bin_path: Path, esptool_exe: str) -> dict[str, str]:
    """Run esptool `image_info` and return the parsed fields.

    `image_info` does not need a connected chip — it parses the
    `.bin` file directly. That's the plan's "parse built .bin
    metadata" choice: NOT stdout grep, NOT a synthesized
    `version.txt`.
    """
    proc = subprocess.run(
        [esptool_exe, "image_info", str(bin_path)],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"esptool image_info failed (rc={proc.returncode}): "
            f"{proc.stderr.strip() or proc.stdout.strip()}"
        )
    return _parse_image_info(proc.stdout)


def ensure_old_firmware_built(
    config: HarnessConfig,
    *,
    pinned_tag: str | None = None,
) -> FirmwareBuildInfo:
    """Build the pinned old firmware on demand and cache the result.

    If the cached `.bin` already exists for the tag, return the
    cached info. Otherwise: `git worktree add`, build with
    `idf.py`, extract image metadata, write `version.txt`, remove
    the worktree, return the new info.
    """
    pinned_tag = pinned_tag or config.firmware.pinned_tag
    bin_path, version_path = _cache_paths(config, pinned_tag)

    if bin_path.exists() and version_path.exists():
        return _load_version_txt(version_path, pinned_tag, bin_path)

    esptool = config.require("env.esptool_exe", config.env.esptool_exe)
    idf_py = config.require("env.idf_py_exe", config.env.idf_py_exe)
    env_script = config.env.idf_env_script

    if not Path(idf_py).exists():
        raise RuntimeError(
            f"idf.py not found at {idf_py}. Set [env].idf_py_exe in config.toml."
        )

    worktree_dir = config.repo_root / config.firmware.worktree_dir
    if worktree_dir.exists():
        raise RuntimeError(
            f"worktree dir {worktree_dir} already exists from a prior failed build. "
            f"Remove it manually and retry."
        )

    # Add the worktree at the pinned tag.
    add = _git("worktree", "add", str(worktree_dir), pinned_tag, cwd=config.repo_root)
    if add.returncode != 0:
        raise RuntimeError(
            f"`git worktree add` failed (rc={add.returncode}): "
            f"{add.stderr.strip() or add.stdout.strip()}"
        )

    try:
        # Build. `idf.py` needs the IDF env sourced on Windows; on
        # Linux/Mac, export.sh must be sourced. We shell out via
        # the OS-native activator (export.bat / export.sh) if
        # configured; otherwise the user is expected to have set
        # up their shell with the IDF env already.
        idf_workspace = worktree_dir / "firmware" / "esp-idf"
        if not idf_workspace.exists():
            raise RuntimeError(
                f"worktree {worktree_dir} has no firmware/esp-idf/ — "
                f"unexpected pinned_tag {pinned_tag!r}"
            )
        cmd = _build_idf_command(env_script, idf_py, "build", cwd=idf_workspace)
        build = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if build.returncode != 0:
            raise RuntimeError(
                f"idf.py build failed (rc={build.returncode}): "
                f"{build.stderr.strip()[-2000:] or build.stdout.strip()[-2000:]}"
            )

        # Locate the .bin. ESP-IDF writes it to
        # build/<project>.bin under the project dir.
        # The project under firmware/esp-idf is named `beetmeister`.
        built_bin = _find_built_bin(idf_workspace)
        if built_bin is None or not built_bin.exists():
            raise RuntimeError(
                f"could not locate built .bin under {idf_workspace / 'build'}"
            )

        # Copy into the cache and extract metadata.
        shutil.copy2(built_bin, bin_path)
        image_info = _image_metadata(bin_path, esptool)
        build_label = image_info.get("Build version", pinned_tag)
        # ESP-IDF doesn't put runtime_protocol_version into
        # image_info; we read it from the bundled-firmware-stamp
        # the build writes for the BUNDLED image. The v0.3.0
        # tag's stamp lives under firmware/esp-idf/build/...
        runtime_proto = _read_runtime_protocol_version(worktree_dir)

        sha = hashlib.sha256(bin_path.read_bytes()).hexdigest()
        info = FirmwareBuildInfo(
            pinned_tag=pinned_tag,
            bin_path=bin_path,
            build_label=build_label,
            firmware_version=build_label,  # image_info doesn't split these
            runtime_protocol_version=runtime_proto,
            image_size=bin_path.stat().st_size,
            image_sha256=sha,
        )
        version_path.write_text(info.to_version_txt(), encoding="utf-8")
        return info
    finally:
        # Always remove the worktree so a subsequent retry starts
        # from a clean state. `git worktree remove --force` works
        # even if the build left untracked files behind.
        _git("worktree", "remove", "--force", str(worktree_dir), cwd=config.repo_root)
        # Defensive: if the worktree dir still exists (rare; happens
        # if `remove` failed), leave it for the next call to fail
        # loudly — never silently swallow a stuck worktree.


def _build_idf_command(env_script: str, idf_py: str, *args: str, cwd: Path) -> list[str]:
    """Compose the `idf.py` invocation, optionally sourcing export.sh/bat.

    On Windows we use `cmd /c "<export> && python idf.py <args>"`. On
    POSIX we use `bash -c "source <export> && python idf.py <args>"`.
    If no env_script is set, we invoke `idf.py` directly (the user
    is expected to have the IDF env already on PATH).
    """
    if not env_script:
        return ["python", idf_py, *args]
    import sys as _sys
    if _sys.platform.startswith("win"):
        quoted = f'"{env_script}" && python "{idf_py}" ' + " ".join(args)
        return ["cmd", "/c", quoted]
    quoted = f'source "{env_script}" && python "{idf_py}" ' + " ".join(args)
    return ["bash", "-c", quoted]


def _find_built_bin(idf_workspace: Path) -> Path | None:
    """Find the project's built .bin under build/.

    ESP-IDF names the .bin after the `project.cmake` `project(...)`
    line — for BeetMeister, `beetmeister.bin`. We glob for it
    because the exact subpath can vary (build/ vs build/<board>/
    depending on the IDF version).
    """
    build_dir = idf_workspace / "build"
    if not build_dir.exists():
        return None
    candidates = sorted(build_dir.rglob("beetmeister*.bin"))
    return candidates[0] if candidates else None


def _read_runtime_protocol_version(worktree_dir: Path) -> int:
    """Read runtime_protocol_version from the build's stamp.

    The firmware build writes a stamp JSON next to its bundled
    artifact. We parse it; missing file -> 0 (caller treats as
    "unknown" and may warn).
    """
    stamp = worktree_dir / "firmware" / "esp-idf" / "build" / "bundled-firmware-stamp.json"
    if not stamp.exists():
        # Fall back: search the worktree for any stamp.
        candidates = sorted(worktree_dir.rglob("bundled-firmware-stamp.json"))
        stamp = candidates[0] if candidates else stamp
    if not stamp.exists():
        return 0
    try:
        return int(json.loads(stamp.read_text(encoding="utf-8-sig")).get("runtime_protocol_version", 0))
    except (ValueError, json.JSONDecodeError):
        return 0


def _load_version_txt(
    path: Path, pinned_tag: str, bin_path: Path
) -> FirmwareBuildInfo:
    data: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            data[k.strip()] = v.strip()
    return FirmwareBuildInfo(
        pinned_tag=pinned_tag,
        bin_path=bin_path,
        build_label=data.get("build_label", pinned_tag),
        firmware_version=data.get("firmware_version", pinned_tag),
        runtime_protocol_version=int(data.get("runtime_protocol_version", "0")),
        image_size=int(data.get("image_size", "0")),
        image_sha256=data.get("image_sha256", ""),
    )


def flash_old(
    config: HarnessConfig,
    port: str,
    *,
    pinned_tag: str | None = None,
) -> FirmwareBuildInfo:
    """Erase appcfg (P5 decision) then flash the cached old .bin via idf.py.

    Returns the `FirmwareBuildInfo` for the flashed image so the
    orchestrator can pass `build_label` to the Kotlin test as a
    `-e expected_old_build_label` extra.
    """
    info = ensure_old_firmware_built(config, pinned_tag=pinned_tag)

    if config.firmware.erase_appcfg_before_flash:
        from harness import controller_reset
        # `appcfg` is the only partition we erase pre-flash; the
        # rest of the NVS state survives because the firmware
        # rewrite on flash leaves the rest as-is.
        results = controller_reset.erase_config_partitions(
            config, port, partitions=("appcfg",)
        )
        bad = [r for r in results if not r.ok]
        if bad:
            raise RuntimeError(
                "pre-flash appcfg erase failed: "
                + ", ".join(f"{r.partition} rc={r.returncode}" for r in bad)
            )

    idf_py = config.require("env.idf_py_exe", config.env.idf_py_exe)
    idf_workspace = config.repo_root / config.firmware.worktree_dir
    # We don't have a worktree anymore (it was removed by
    # ensure_old_firmware_built). We need to re-add a temporary
    # one to run `idf.py flash` from a project dir, because
    # `idf.py` reads the project's sdkconfig + partitions CSV
    # from cwd. Simpler: just add the worktree back, flash, and
    # remove it again.
    worktree_dir = idf_workspace
    add = _git("worktree", "add", str(worktree_dir), info.pinned_tag, cwd=config.repo_root)
    if add.returncode != 0:
        raise RuntimeError(
            f"`git worktree add` (for flash) failed (rc={add.returncode}): "
            f"{add.stderr.strip() or add.stdout.strip()}"
        )
    try:
        flash_cwd = worktree_dir / "firmware" / "esp-idf"
        cmd = _build_idf_command(
            config.env.idf_env_script, idf_py, "-p", port, "flash", cwd=flash_cwd
        )
        # `idf.py flash` looks for the .bin in build/ relative to
        # the project; we need to either rebuild or override
        # ESP_BIN_PATH. Simplest: rebuild (cached objects speed
        # this up) so the .bin in build/ is the cached one.
        build = subprocess.run(
            _build_idf_command(config.env.idf_env_script, idf_py, "build", cwd=flash_cwd),
            capture_output=True,
            text=True,
            check=False,
        )
        if build.returncode != 0:
            raise RuntimeError(
                f"idf.py build (pre-flash) failed (rc={build.returncode})"
            )
        flash = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if flash.returncode != 0:
            raise RuntimeError(
                f"idf.py flash failed (rc={flash.returncode}): "
                f"{flash.stderr.strip()[-2000:] or flash.stdout.strip()[-2000:]}"
            )
    finally:
        _git("worktree", "remove", "--force", str(worktree_dir), cwd=config.repo_root)
    return info
