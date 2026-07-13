"""Harness config loading.

`config.toml` is the per-machine user config (git-ignored). The
schema lives in `config.example.toml` next to this file.

Resolution order:
1. `BEET_HARNESS_CONFIG` env var (absolute path) — overrides everything.
2. `<repo>/test-harness/config.toml` — the per-machine copy.
3. `<repo>/test-harness/config.example.toml` — last-resort fallback
   (will fail at runtime for absolute paths that don't resolve on
   the current machine; intended for schema documentation only).

The loader returns a typed `HarnessConfig` object (frozen dataclass)
so callers don't string-parse TOML keys.
"""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


# --- TOML backend selection -------------------------------------------------
# Python 3.11+ ships tomllib; older versions need tomli. We declared
# tomli in requirements.txt for the older case.
if sys.version_info >= (3, 11):
    import tomllib as _toml
else:  # pragma: no cover - exercised on 3.10
    import tomli as _toml  # type: ignore[no-redef]


@dataclass(frozen=True)
class DeviceConfig:
    adb_serial: str = ""
    app_dir: str = ""
    gradlew: str = ""
    app_package: str = "de.aarondietz.beetmeister"
    # androidTest APK's package is conventionally `<app_package>.test`
    # (declared in build.gradle.kts via `namespace`). The runner
    # FQN must be prefixed with THIS package, not the app package,
    # for `am instrument` to find it.
    test_package: str = "de.aarondietz.beetmeister.test"
    test_runner: str = "de.aarondietz.beetmeister.e2e.BeetE2eAwareJUnitRunner"
    e2e_gate_arg: str = "beetRunE2e"
    smoke_class: str = (
        "de.aarondietz.beetmeister.ui.feature.connection."
        "MaintenanceUpdateInstrumentationTest"
    )


@dataclass(frozen=True)
class ControllerConfig:
    serial_port: str = ""
    baud: int = 115200


@dataclass(frozen=True)
class FirmwareConfig:
    pinned_tag: str = "v0.3.0"
    worktree_dir: str = "_build_old"
    erase_appcfg_before_flash: bool = True


@dataclass(frozen=True)
class EnvConfig:
    esptool_exe: str = ""
    idf_py_exe: str = ""
    idf_env_script: str = ""


@dataclass(frozen=True)
class OrchestratorConfig:
    default_am_instrument_timeout: int = 900
    smoke_timeout: int = 180
    post_install_settle: int = 5


@dataclass(frozen=True)
class HarnessConfig:
    harness_dir: Path
    config_path: Path
    device: DeviceConfig
    controller: ControllerConfig
    firmware: FirmwareConfig
    env: EnvConfig
    orchestrator: OrchestratorConfig
    repo_root: Path = field(compare=False)

    def require(self, field_name: str, value: str) -> str:
        """Raise with a clear error if a required absolute path is empty.

        The TOML schema documents which fields are required for which
        suite. We only validate the fields the caller actually asks
        for, so a settings-only run can omit esptool_exe / idf_py_exe.
        """
        if not value:
            raise RuntimeError(
                f"config.toml: required field '{field_name}' is empty. "
                f"Set it in {self.config_path} (see config.example.toml)."
            )
        return value


def _config_path(harness_dir: Path) -> Path:
    """Pick the config source in the documented resolution order."""
    override = os.environ.get("BEET_HARNESS_CONFIG")
    if override:
        return Path(override)
    candidate = harness_dir / "config.toml"
    if candidate.exists():
        return candidate
    return harness_dir / "config.example.toml"


def _from_dict(cls: type, payload: dict[str, Any]) -> Any:
    """Build a dataclass from a dict, ignoring unknown keys for forward compat."""
    import dataclasses

    field_names = {f.name for f in dataclasses.fields(cls)}
    return cls(**{k: v for k, v in payload.items() if k in field_names})


def load(harness_dir: Path | None = None) -> HarnessConfig:
    """Load + validate the harness config.

    `harness_dir` is the `test-harness/` folder; defaults to the
    directory of this file. The repo root is computed as its parent
    (the orchestrator needs repo-root-relative paths for worktrees,
    the bundled-firmware stamp, and the gradlew wrapper).
    """
    if harness_dir is None:
        harness_dir = Path(__file__).resolve().parent.parent
    harness_dir = harness_dir.resolve()
    config_path = _config_path(harness_dir)

    with config_path.open("rb") as fh:
        raw = _toml.load(fh)

    device = _from_dict(DeviceConfig, raw.get("device", {}))
    controller = _from_dict(ControllerConfig, raw.get("controller", {}))
    firmware = _from_dict(FirmwareConfig, raw.get("firmware", {}))
    env = _from_dict(EnvConfig, raw.get("env", {}))
    orchestrator = _from_dict(OrchestratorConfig, raw.get("orchestrator", {}))

    return HarnessConfig(
        harness_dir=harness_dir,
        config_path=config_path,
        device=device,
        controller=controller,
        firmware=firmware,
        env=env,
        orchestrator=orchestrator,
        repo_root=harness_dir.parent.resolve(),
    )
