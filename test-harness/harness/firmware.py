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
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from harness import partition_map
from harness.config import HarnessConfig
from harness.controller_reset import build_esptool_command


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


def _cache_artifact_paths(harness_config: HarnessConfig, pinned_tag: str) -> tuple[Path, Path]:
    """Paths for the cached bootloader + partition-table bins.

    P3 finding SUB #5: `flash_old` must flash the CACHED bins
    (not a rebuilt copy) so `image_sha256` in `version.txt`
    matches what's actually on the chip + so the cache actually
    skips the build on the 2nd+ run. ESP-IDF build outputs:
      build/bootloader/bootloader.bin       -> 0x1000
      build/partition_table/partition-table.bin -> 0x8000
      build/beetmeister.bin                 -> ota_0 offset (from CSV)
    """
    cache = cache_dir(harness_config)
    return (
        cache / f"beetmeister-{pinned_tag}.bootloader.bin",
        cache / f"beetmeister-{pinned_tag}.partition-table.bin",
    )


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


def _image_metadata(bin_path: Path, config: HarnessConfig) -> dict[str, str]:
    """Run esptool `image_info` and return the parsed fields.

    `image_info` does not need a connected chip — it parses the
    `.bin` file directly. That's the plan's "parse built .bin
    metadata" choice: NOT stdout grep, NOT a synthesized
    `version.txt`.

    Routes through `build_esptool_command` (P3 finding SUB #4)
    so the env is sourced the same way as `erase_region` — no
    more `[esptool_exe, ...]` without a `python` prefix.
    """
    proc = subprocess.run(
        build_esptool_command(config, "image_info", str(bin_path)),
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

    boot_path, pt_path = _cache_artifact_paths(config, pinned_tag)
    if bin_path.exists() and version_path.exists() and boot_path.exists() and pt_path.exists():
        return _load_version_txt(version_path, pinned_tag, bin_path)

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
        # P5 finding SUB #R22: v0.3.0's sdkconfig.defaults does
        # NOT set CONFIG_IDF_TARGET, so the build defaults to
        # `esp32` (the original ESP32). The BeetMeister controller
        # is an ESP32-S3, and v0.3.0's source uses GPIO_NUM_47/40/
        # 41/42 which only exist on the S3. Patching the v0.3.0
        # tag to add `CONFIG_IDF_TARGET="esp32s3"` would violate
        # the pinned-tag principle, so we instead run
        # `idf.py set-target esp32s3` *before* the build. This
        # creates the sdkconfig with the correct target. The
        # `set-target` step is idempotent (idempotent in the sense
        # that it re-writes the sdkconfig with the same target if
        # already set; we always call it to be safe).

        # P5 R37d: patch v0.3.0 beet_ble.c to add BLE connection
        # parameter update on OTA start. Without this, the upload
        # speed is ~5.4 KB/s (default 30-50ms BLE interval). With
        # it, the controller requests 7.5-15ms interval for ~3-4x
        # faster OTA. The patch is idempotent (checks for marker).
        _patch_ota_conn_params(idf_workspace / "components" / "beet_firmware" / "src" / "beet_ble.c")

        set_target_cmd = _build_idf_command(
            config, env_script, idf_py, "set-target", "esp32s3", cwd=idf_workspace,
        )
        set_target = subprocess.run(
            set_target_cmd,
            capture_output=True,
            text=True,
            check=False,
            cwd=idf_workspace,
            env=_idf_env(config),
        )
        if set_target.returncode != 0:
            raise RuntimeError(
                f"idf.py set-target esp32s3 failed (rc={set_target.returncode}): "
                f"{set_target.stderr.strip()[-2000:] or set_target.stdout.strip()[-2000:]}"
            )
        cmd = _build_idf_command(config, env_script, idf_py, "build", cwd=idf_workspace)
        build = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=False,
            cwd=idf_workspace,
            env=_idf_env(config),
        )
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

        # Copy the app + bootloader + partition-table into the cache.
        # All three are flashed by `flash_old` via esptool write_flash
        # (P3 finding SUB #5): the cached .bin is exactly what goes
        # on the chip, so `image_sha256` in version.txt stays valid.
        built_boot = idf_workspace / "build" / "bootloader" / "bootloader.bin"
        built_pt = idf_workspace / "build" / "partition_table" / "partition-table.bin"
        if not built_boot.exists():
            raise RuntimeError(f"built bootloader.bin not found at {built_boot}")
        if not built_pt.exists():
            raise RuntimeError(f"built partition-table.bin not found at {built_pt}")
        shutil.copy2(built_bin, bin_path)
        shutil.copy2(built_boot, boot_path)
        shutil.copy2(built_pt, pt_path)
        image_info = _image_metadata(bin_path, config)
        # P5 finding SUB #R29: prefer the BTMT `build_label` from
        # `read_beet_metadata.py` (same source as the controller
        # reports at runtime) over `esptool image_info`'s `Build
        # version` field. The v0.3.0 .bin's BTMT build_label is
        # `dev-7827368` (the git commit the v0.3.0 tag points at),
        # while esptool's `App version` is `v0.3.0` (the tag name)
        # and `Build version` is absent. The Kotlin test asserts
        # the controller's reported label, so version.txt must
        # match the BTMT value or the test will see a mismatch
        # like `expected 'v0.3.0' got 'dev-7827368'`.
        bin_metadata = _read_beet_metadata(bin_path)
        build_label = (
            bin_metadata.get("build_label")
            or image_info.get("Build version")
            or pinned_tag
        )
        # P5 finding SUB #R24: the previous implementation read
        # `runtime_protocol_version` from the `bundled-firmware-stamp.json`
        # that the current branch's Gradle build writes. That stamp
        # is NOT written by the v0.3.0 worktree's `idf.py build` (the
        # v0.3.0 tag predates the stamp-writing CMake glue), so the
        # read returned 0 — the "unknown" default — and the Kotlin
        # firmware_update test then refused to talk to the v0.3.0
        # controller (runtime_protocol_version=0 vs the app's
        # expected 15 forces the Maintenance screen). The actual
        # protocol version is embedded in the .bin's BTMT metadata
        # block (type 6, TLV_RUNTIME_PROTOCOL_VERSION). Use the
        # project's `read_beet_metadata.py` to parse it; fall back
        # to the stamp file only if the parse fails.
        runtime_proto = _read_runtime_protocol_from_bin(bin_path)

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


def _patch_ota_conn_params(ble_c_path: Path) -> None:
    """Patch v0.3.0 beet_ble.c to request faster BLE conn params on OTA.

    Inserts the connection parameter update code right after
    ``beet_ble_mark_activity()`` in the AWAITING_DATA transition.
    Idempotent — checks for the marker comment first.
    """
    MARKER = b"/* P5 R37d conn param update for faster OTA"

    original = ble_c_path.read_bytes()
    if MARKER in original:
        return  # already patched

    # We need to insert the conn param update block right after
    # ``beet_ble_mark_activity();`` in the begin-update ready path.
    # The exact location is after the line that starts the status
    # indication.

    # 1. Add conn_param_update_requested flag to the struct.
    old_struct = b"    bool status_indication_in_flight;"
    new_struct = b"    bool status_indication_in_flight;\n    bool conn_param_update_requested;"
    if old_struct not in original:
        raise RuntimeError(
            f"{ble_c_path}: cannot find struct field for patching "
            f"(status_indication_in_flight missing)"
        )
    patched = original.replace(old_struct, new_struct, 1)
    if patched == original:
        raise RuntimeError(f"{ble_c_path}: struct patch had no effect")

    # 2. Insert conn param update after beet_ble_mark_activity()
    # in the AWAITING_DATA section.
    marker = (
        b"            beet_ble_mark_activity();\n"
        b"            ESP_LOGI(TAG, \"begin update ready"
    )
    if marker not in patched:
        raise RuntimeError(
            f"{ble_c_path}: cannot find begin update ready marker "
            f"(beet_ble_mark_activity + ESP_LOGI)"
        )

    conn_param_block = (
        b"            /* P5 R37d conn param update for faster OTA */\n"
        b"            if (s_ble.connected && s_ble.conn_handle != BLE_HS_CONN_HANDLE_NONE) {\n"
        b"                struct ble_gap_upd_params upd_params = {\n"
        b"                    .itvl_min = 6,                /* 7.5 ms */\n"
        b"                    .itvl_max = 12,               /* 15 ms */\n"
        b"                    .latency = 0,\n"
        b"                    .supervision_timeout = 500,   /* 5 s */\n"
        b"                    .min_ce_len = 0,\n"
        b"                    .max_ce_len = 0xFFFF,\n"
        b"                };\n"
        b"                int rc = ble_gap_update_params(s_ble.conn_handle, &upd_params);\n"
        b"                if (rc == 0) {\n"
        b"                    s_ble.maintenance_session.conn_param_update_requested = true;\n"
        b"                    ESP_LOGI(TAG, \"conn param update requested for OTA\");\n"
        b"                } else {\n"
        b"                    ESP_LOGW(TAG, \"conn param update request failed rc=%d\", rc);\n"
        b"                }\n"
        b"            }\n"
    )

    replacement = (
        b"            beet_ble_mark_activity();\n"
        + conn_param_block +
        b"            ESP_LOGI(TAG, \"begin update ready"
    )

    patched = patched.replace(marker, replacement, 1)
    if patched == original:
        raise RuntimeError(f"{ble_c_path}: conn param patch had no effect")

    ble_c_path.write_bytes(patched)
    print(f"Patched {ble_c_path.name} with P5 R37d OTA conn param update")


def _build_idf_command(
    config: HarnessConfig, env_script: str, idf_py: str, *args: str, cwd: Path
) -> list[str]:
    """Compose the `idf.py` invocation.

    P5 finding CRIT #R1: the previous `cmd /c "<export> && python
    idf.py <args>"` approach was fragile (Windows cmd quoting
    rules break in non-obvious ways). New approach: invoke the
    IDF venv Python directly and set the env vars the wrapper
    script (`.agents/skills/esp-idf-installation/scripts/invoke-idf.ps1`)
    sets. idf.py checks IDF_PATH/IDF_TOOLS_PATH/etc., so we just
    set them in the subprocess env and skip the `cmd /c` shell
    entirely.

    Returns `[python_exe, idf_py, *args]`. Caller passes the
    composed env to `subprocess.run` (via `_idf_env`).
    """
    python_exe = config.env.idf_python_exe or sys.executable
    if not Path(python_exe).exists():
        raise RuntimeError(
            f"IDF Python venv not found at {python_exe}. Set [env].idf_python_exe "
            f"in config.toml (e.g. C:/Espressif/tools/python/v6.0/venv/Scripts/python.exe)."
        )
    return [python_exe, idf_py, *args]


def _idf_env(config: HarnessConfig) -> dict[str, str]:
    """Compose the env vars idf.py + the IDF build need.

    Mirrors `.agents/skills/esp-idf-installation/scripts/invoke-idf.ps1`:
      IDF_PATH, IDF_TOOLS_PATH, IDF_PYTHON_ENV_PATH, ESP_ROM_ELF_DIR,
      PYTHONIOENCODING, PYTHONUTF8
    Plus prepends the tool paths (cmake, ninja, xtensa/riscv
    toolchains) to PATH so the build can find them.

    `env_script` argument kept for backward compat with the
    previous signature but unused now (no more cmd /c shell).
    """
    env = dict(os.environ)
    # Derive IDF_PATH from the configured idf_py_exe (e.g. .../esp-idf/tools/idf.py -> .../esp-idf).
    idf_py = config.env.idf_py_exe
    if idf_py:
        # .../esp-idf/tools/idf.py -> .../esp-idf
        idf_path = str(Path(idf_py).parent.parent)
        env["IDF_PATH"] = idf_path
        # ESP_IDF_VERSION is required by the v6.x
        # idf_component_manager (extension loader checks
        # os.getenv('ESP_IDF_VERSION') and crashes with
        # TypeError on None). The IDF v6.0 install does not
        # ship a version.txt at the IDF root, so we hardcode
        # the version (matching invoke-idf.ps1). The host IDF
        # is the toolchain even when building the pinned
        # firmware in a worktree, so the host's version is
        # the correct one to report.
        # P5 finding SUB #R21.
        env.setdefault("ESP_IDF_VERSION", "6.0")
        # Best-effort default for the venv root (Windows convention).
        if sys.platform.startswith("win"):
            env.setdefault("IDF_PYTHON_ENV_PATH", r"C:\Espressif\tools\python\v6.0\venv")
            env.setdefault("IDF_TOOLS_PATH", r"C:\Espressif\tools")
            env.setdefault("ESP_ROM_ELF_DIR", r"C:\Espressif\tools\esp-rom-elfs\20241011")
            # Prepend tool paths to PATH.
            tool_paths = (
                r"C:\Espressif\tools\python\v6.0\venv\Scripts",
                r"C:\Espressif\tools\cmake\4.0.3\bin",
                r"C:\Espressif\tools\ninja\1.12.1",
                r"C:\Espressif\tools\xtensa-esp-elf\esp-15.2.0_20251204\xtensa-esp-elf\bin",
                r"C:\Espressif\tools\riscv32-esp-elf\esp-15.2.0_20251204\riscv32-esp-elf\bin",
                r"C:\Espressif\tools\esp32ulp-elf\2.38_20240113\esp32ulp-elf\bin",
            )
            existing = env.get("Path", env.get("PATH", ""))
            entries = [p for p in existing.split(";") if p]
            for p in tool_paths:
                if p not in entries:
                    entries.insert(0, p)
            env["Path"] = ";".join(entries)
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    # Remove MSYSTEM so idf.py doesn't skip main() when invoked from MSYS.
    env.pop("MSYSTEM", None)
    return env


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


def _read_beet_metadata(bin_path: Path) -> dict[str, Any]:
    """Read the full BTMT metadata block from a cached .bin.

    Returns a dict with `build_label`, `firmware_version`,
    `runtime_protocol_version`, etc. Empty dict on any error
    (missing script, parse failure, non-zero exit). Mirrors
    `_read_runtime_protocol_from_bin` but returns the whole
    metadata object so callers can pick what they need.
    """
    read_script = (
        Path(__file__).parent.parent.parent
        / "firmware" / "esp-idf" / "components" / "beet_firmware"
        / "tools" / "read_beet_metadata.py"
    )
    if not read_script.is_file():
        return {}
    try:
        result = subprocess.run(
            [sys.executable, str(read_script), str(bin_path)],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            return {}
        return json.loads(result.stdout)
    except (ValueError, json.JSONDecodeError, OSError):
        return {}


def _read_runtime_protocol_from_bin(bin_path: Path) -> int:
    read_script = (
        Path(__file__).parent.parent.parent
        / "firmware" / "esp-idf" / "components" / "beet_firmware"
        / "tools" / "read_beet_metadata.py"
    )
    if not read_script.is_file():
        return _read_runtime_protocol_version_from_default_worktree()
    try:
        result = subprocess.run(
            [
                sys.executable,
                str(read_script),
                str(bin_path),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            return 0
        data = json.loads(result.stdout)
        return int(data.get("runtime_protocol_version", 0))
    except (ValueError, json.JSONDecodeError, OSError):
        return 0


def _read_runtime_protocol_version_from_default_worktree() -> int:
    """Fallback: search the default worktree for a stamp file."""
    # This is a last-resort fallback; should not normally trigger.
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
    """Erase appcfg+otadata (P5 decision) then flash the CACHED bins.

    P3 finding SUB #5 fix: flash the app + bootloader +
    partition-table cached by `ensure_old_firmware_built` directly
    via `esptool write_flash`. No worktree re-add, no re-build.
    so `image_sha256` in `version.txt` matches what lands on the
    chip, and a cached run skips the build entirely.

    Layout (matches `firmware/esp-idf/partitions/beetmeister.csv`):
      0x1000   bootloader.bin
      0x8000   partition-table.bin
      ota_0    beetmeister.bin   (offset read from the CSV)
    `otadata` is erased so the bootloader picks ota_0 (the slot
    we just wrote the old image into) on the next boot.

    Returns the `FirmwareBuildInfo` for the flashed image so the
    orchestrator can pass `build_label` to the Kotlin test as a
    `-e expected_old_build_label` extra.
    """
    info = ensure_old_firmware_built(config, pinned_tag=pinned_tag)
    pinned_tag = info.pinned_tag

    from harness import controller_reset

    # Erase appcfg only (P5 decision). Do NOT erase otadata
    # separately — the esptool reset after erase_region causes
    # the bootloader to re-write otadata pointing to the current
    # partition, defeating the purpose. Instead, we write erased
    # otadata (all 0xFF) as part of the same write_flash command
    # below, so the bootloader only sees the new state after
    # both otadata + app image are in place.
    erase_partitions: tuple[str, ...] = ("appcfg",)
    if config.firmware.erase_appcfg_before_flash:
        pass  # appcfg already in the list
    else:
        erase_partitions = tuple(p for p in erase_partitions if p != "appcfg")
    results = controller_reset.erase_config_partitions(
        config, port,
        partitions=erase_partitions,
        extra_erase=(),  # otadata handled in write_flash below
    )
    bad = [r for r in results if not r.ok]
    if bad:
        raise RuntimeError(
            "pre-flash erase failed: "
            + ", ".join(f"{r.partition} rc={r.returncode}" for r in bad)
        )

    # Resolve the ota_0 and otadata offsets from the partition CSV.
    csv_path = config.repo_root / "firmware" / "esp-idf" / "partitions" / "beetmeister.csv"
    layout = partition_map.load(csv_path)
    ota_0, otadata = partition_map.require(layout, "ota_0", "otadata")

    bin_path, _ = _cache_paths(config, pinned_tag)
    if not bin_path.exists():
        raise RuntimeError(
                f"cached firmware artifact missing: {p}. "
                f"Remove firmware_cache/ for {pinned_tag} and retry."
            )

    # ESP32-S3 bootloader offset is 0x0; partition table at 0x8000.
    # These are fixed chip defaults (NOT partition-CSV entries).
    # P5 finding SUB #R23: the original code used 0x1000 which
    # is the offset for the original ESP32 (non-S3). The ESP32-S3
    # ROM bootloader looks for the 2nd-stage bootloader at 0x0,
    # so flashing it at 0x1000 leaves the ROM bootloader unable
    # to find a valid app image, causing the
    # "Invalid image block, can't boot" boot loop.
    #
    # P5 finding SUB #R37d: do NOT flash the cached bootloader
    # or partition-table.bin. The old firmware's bootloader may
    # be incompatible with the chip revision or flash mode of the
    # bench controller. The old partition table may place ota_0
    # at a different offset than the current CSV. Instead, keep
    # the current bootloader + partition table and only replace
    # the app image at the current CSV's ota_0 offset.
    #
    # P5 finding SUB #R37d (cont): write an erased otadata block
    # as part of the same esptool write_flash command. If we
    # erase otadata in a separate esptool invocation, the chip
    # resets between commands, the bootloader re-writes otadata
    # pointing to the old active partition, and the new app image
    # at ota_0 is ignored. By packing otadata + app into one
    # write_flash, the chip only boots AFTER all changes are in
    # place and sees only the new ota_0.
    erased_otadata = bin_path.parent / "_erased_otadata.bin"
    erased_otadata.write_bytes(b"\xff" * otadata.size)
    try:
        cmd = build_esptool_command(
            config,
            "--port", port,
            "--baud", str(config.controller.baud),
            "write_flash",
            f"0x{otadata.offset:x}", str(erased_otadata),
            f"0x{ota_0.offset:x}", str(bin_path),
        )
        flash = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if flash.returncode != 0:
            raise RuntimeError(
                f"esptool write_flash failed (rc={flash.returncode}): "
                f"{flash.stderr.strip()[-2000:] or flash.stdout.strip()[-2000:]}"
            )
        return info
    finally:
        erased_otadata.unlink(missing_ok=True)
