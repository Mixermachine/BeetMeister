"""The single pipeline owner.

`Orchestrator.run(suite, **kwargs)` is the only entry point every
test class + the `run.py` CLI call. The orchestrator:

  1. Loads the harness config + the device.
  2. Creates the run folder + screenshots/ subdir.
  3. Starts logcat + serial capture (subprocess).
  4. `adb.install_apks()` — gradle build + install both APKs.
  5. Parses the GENERATED `bundled-firmware-stamp.json` (assert
     its `runtime_protocol_version` matches
     `config/protocol_versions.properties`; fail fast on mismatch).
  6. SMOKE GATE: runs the pure-UI
     `MaintenanceUpdateInstrumentationTest` via `am instrument`;
     abort the run on FAIL with a clear message + on-failure
     screenshot.
  7. Suite dispatch (per-suite if block; P3 only fills
     `fresh_install`; `firmware_update` and `settings_update` are
     P4).
  8. Finalize manifest + return a result object.
  9. Stop captures in `finally:` so the run folder always has a
     closed logcat/serial stream.

P3 dry-run: the smoke gate IS exercised. P3 stop point: before
the per-suite E2E class dispatch. P4 fills in the dispatch.
"""

from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

from harness import capture, controller_reset, run_folder, screenshots
from harness.adb import Adb, AmInstrumentResult
from harness.config import HarnessConfig, load


# --- Bundled-firmware stamp -------------------------------------------------

GENERATED_STAMP_PATH = (
    Path("app/app/build/generated/bundledFirmware/main/bundled-firmware-stamp.json")
)
PROTOCOL_VERSIONS_PROPERTIES = Path("config/protocol_versions.properties")


@dataclass
class BundledFirmware:
    build_label: str
    firmware_version: str
    runtime_protocol_version: int
    image_size: int


@dataclass
class OrchestratorResult:
    passed: bool
    manifest_path: Path
    run_dir: Path
    smoke: Optional[AmInstrumentResult]
    e2e: Optional[AmInstrumentResult]
    fail_reason: Optional[str]


# --- Orchestrator -----------------------------------------------------------


class Orchestrator:
    """The single pipeline owner.

    Instantiate once per run (e.g. in a pytest fixture or at the
    top of `run.py`). Reuse across suites if you want to keep
    one device for multiple runs; the orchestrator itself is
    stateless beyond config + adb.
    """

    def __init__(self, config: HarnessConfig | None = None) -> None:
        self.config = config or load()
        self.adb = Adb(self.config)

    # --- public surface ---------------------------------------------------

    def run(
        self,
        suite: str,
        *,
        dry_run: bool = False,
        class_name: str | None = None,
        extras: dict[str, str] | None = None,
        skip_install: bool = False,
        skip_smoke: bool = False,
    ) -> OrchestratorResult:
        """Run the named suite end-to-end.

        `dry_run=True` (P3): walks steps 1-6 (everything up to
        the per-suite E2E dispatch) and stops before the E2E
        class. The smoke gate IS exercised so the run proves
        the full path through the gate.

        `suite` is one of: "fresh_install", "firmware_update",
        "settings_update". P3 only implements "fresh_install" +
        the dry-run path.
        """
        if suite not in {"fresh_install", "firmware_update", "settings_update"}:
            raise ValueError(
                f"unknown suite {suite!r}; "
                f"expected fresh_install / firmware_update / settings_update"
            )

        run_dir, manifest = self._begin_run(suite)
        logcat_cap: Optional[capture.Capture] = None
        serial_cap: Optional[capture.Capture] = None
        smoke_result: Optional[AmInstrumentResult] = None
        e2e_result: Optional[AmInstrumentResult] = None
        fail_reason: Optional[str] = None
        passed = False

        try:
            # 1. validate device.
            device_serial = self.adb.assert_single_device()
            manifest.device_serial = device_serial
            manifest.controller_port = self.config.controller.serial_port
            manifest.pinned_firmware_tag = self.config.firmware.pinned_tag
            run_folder.write(manifest)

            # 2-3. start captures.
            logcat_cap = capture.start_logcat(run_dir, device_serial)
            serial_cap = capture.start_serial(
                run_dir,
                self.config.controller.serial_port,
                baud=self.config.controller.baud,
            )

            # 4. build + install APKs (unless skipped).
            if not skip_install:
                self.adb.install_apks()
                time.sleep(self.config.orchestrator.post_install_settle)

            # 5. parse generated stamp + runtime_protocol_version sanity check.
            bundled = self._parse_bundled_firmware_stamp()
            manifest.build_label = bundled.build_label
            manifest.firmware_version = bundled.firmware_version
            manifest.runtime_protocol_version = bundled.runtime_protocol_version
            run_folder.write(manifest)
            self._assert_runtime_protocol_matches_config(bundled)

            # 6. SMOKE GATE.
            if not skip_smoke:
                smoke_result = self.adb.am_instrument(
                    class_name=self.config.device.smoke_class,
                    runner=self.config.device.test_runner,
                    beet_run_e2e=False,
                    timeout=float(self.config.orchestrator.smoke_timeout),
                )
                manifest.smoke_passed = smoke_result.passed
                manifest.smoke_duration_s = smoke_result.duration_s
                (run_dir / "instrumentation-smoke.txt").write_text(
                    smoke_result.stdout + "\n" + smoke_result.stderr,
                    encoding="utf-8",
                )
                run_folder.write(manifest)
                if not smoke_result.passed:
                    screenshots.capture_on_failure(self.adb, run_dir)
                    fail_reason = (
                        f"smoke gate failed ({self.config.device.smoke_class}); "
                        f"see {run_dir}/instrumentation-smoke.txt"
                    )
                    return self._finish(
                        run_dir, manifest, logcat_cap, serial_cap,
                        smoke_result, e2e_result, passed=False, fail_reason=fail_reason,
                    )

            # 7. Suite dispatch.
            if dry_run:
                # P3 stop point: don't dispatch the per-suite E2E
                # class. The dry-run proves the build + install +
                # capture + smoke pipeline works end-to-end.
                passed = True
            else:
                e2e_result, passed, fail_reason = self._dispatch(
                    suite=suite,
                    class_name=class_name,
                    extras=extras,
                    run_dir=run_dir,
                )
                manifest.e2e_results = (
                    [e2e_result.to_manifest_dict()] if e2e_result else []
                )
                if not passed:
                    screenshots.capture_on_failure(self.adb, run_dir)

            if passed:
                screenshots.capture_final(self.adb, run_dir)
            return self._finish(
                run_dir, manifest, logcat_cap, serial_cap,
                smoke_result, e2e_result, passed=passed, fail_reason=fail_reason,
            )
        except Exception as exc:  # noqa: BLE001 — orchestrator must always finalize
            fail_reason = f"orchestrator exception: {exc!r}"
            try:
                screenshots.capture_on_failure(self.adb, run_dir)
            except Exception:  # noqa: BLE001
                pass
            return self._finish(
                run_dir, manifest, logcat_cap, serial_cap,
                smoke_result, e2e_result, passed=False, fail_reason=fail_reason,
            )

    # --- internals --------------------------------------------------------

    def _begin_run(self, suite: str) -> tuple[Path, run_folder.Manifest]:
        run_dir, manifest = run_folder.create_run_folder(self.config.harness_dir, suite)
        run_folder.write(manifest)
        return run_dir, manifest

    def _finish(
        self,
        run_dir: Path,
        manifest: run_folder.Manifest,
        logcat_cap: Optional[capture.Capture],
        serial_cap: Optional[capture.Capture],
        smoke: Optional[AmInstrumentResult],
        e2e: Optional[AmInstrumentResult],
        *,
        passed: bool,
        fail_reason: Optional[str],
    ) -> OrchestratorResult:
        # Stop captures BEFORE finalize so the run folder's
        # logcat/serial streams are flushed + closed.
        if logcat_cap is not None:
            logcat_cap.stop()
        if serial_cap is not None:
            serial_cap.stop()
        run_folder.finalize(manifest, passed=passed, fail_reason=fail_reason)
        return OrchestratorResult(
            passed=passed,
            manifest_path=run_dir / "manifest.json",
            run_dir=run_dir,
            smoke=smoke,
            e2e=e2e,
            fail_reason=fail_reason,
        )

    def _parse_bundled_firmware_stamp(self) -> BundledFirmware:
        """Read the GENERATED `bundled-firmware-stamp.json`.

        This is the orchestrator's source of truth for the
        expected post-OTA build label. The ROOT
        `bundled-firmware-stamp.json` is stale (its
        `runtime_protocol_version` is 9, not the live 15) and
        is NOT read here.
        """
        path = self.config.repo_root / GENERATED_STAMP_PATH
        if not path.exists():
            raise RuntimeError(
                f"generated bundled-firmware-stamp not found at {path}. "
                f"Run `:app:assembleDebug` first."
            )
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        try:
            return BundledFirmware(
                build_label=data["build_label"],
                firmware_version=data["firmware_version"],
                runtime_protocol_version=int(data["runtime_protocol_version"]),
                image_size=int(data["image_size"]),
            )
        except KeyError as exc:
            raise RuntimeError(
                f"bundled-firmware-stamp at {path} is missing key {exc!s}; "
                f"cannot continue."
            ) from exc

    def _assert_runtime_protocol_matches_config(self, bundled: BundledFirmware) -> None:
        """Fail fast if the bundled firmware's runtime_protocol_version
        disagrees with `config/protocol_versions.properties`.

        Per the plan: the harness surfaces a mis-identified OTA
        rather than attempting it.
        """
        props_path = self.config.repo_root / PROTOCOL_VERSIONS_PROPERTIES
        if not props_path.exists():
            return
        text = props_path.read_text(encoding="utf-8-sig")
        m = re.search(r"^runtime_protocol_version\s*=\s*(\d+)\s*$", text, re.MULTILINE)
        if not m:
            return
        expected = int(m.group(1))
        if bundled.runtime_protocol_version != expected:
            raise RuntimeError(
                f"runtime_protocol_version mismatch: bundled firmware = "
                f"{bundled.runtime_protocol_version}, "
                f"config/protocol_versions.properties = {expected}. "
                f"The harness refuses to attempt an OTA that would be "
                f"mis-identified by the controller. Update the firmware "
                f"build so the generated stamp agrees with the config."
            )

    def _dispatch(
        self,
        *,
        suite: str,
        class_name: str | None,
        extras: dict[str, str] | None,
        run_dir: Path,
    ) -> tuple[Optional[AmInstrumentResult], bool, Optional[str]]:
        """Per-suite dispatch.

        P3 wires only `fresh_install`. `firmware_update` and
        `settings_update` raise NotImplementedError until P4.
        """
        extras = dict(extras or {})
        if suite == "fresh_install":
            class_name = class_name or (
                "de.aarondietz.beetmeister.e2e.FreshInstallE2ETest"
            )
            # P3 actually doesn't dispatch; the dry-run stop point
            # is here. The dispatch is implemented in P4.
            raise NotImplementedError(
                "fresh_install dispatch arrives in P4 (per-suite wiring)"
            )
        if suite == "firmware_update":
            raise NotImplementedError(
                "firmware_update dispatch arrives in P4 (per-suite wiring)"
            )
        if suite == "settings_update":
            raise NotImplementedError(
                "settings_update dispatch arrives in P4 (per-suite wiring)"
            )
        # Unreachable — the run() guard rejects unknown suites.
        raise AssertionError(suite)
