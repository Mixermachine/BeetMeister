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
  7. Suite dispatch: per-suite preconditions + a single
     `am instrument` call for the E2E class. P4 wires
     fresh_install + firmware_update + settings_update. Each
     dispatch ends with a `pull_in_test_screenshots` of the
     Kotlin in-test screenshots from
     `/sdcard/Android/data/<package>/files/e2e_screenshots/<slug>/`.
  8. Finalize manifest + return a result object.
  9. Stop captures in `finally:` so the run folder always has a
     closed logcat/serial stream.

Modes (P4 adds `--dry-run-dispatch`):
  - dry_run=True: P3 stop point. Smoke gate only; no dispatch,
    no E2E class.
  - dry_run_dispatch=True: P4 stop point. Smoke gate + dispatch
    is composed (preconditions + am instrument command are
    recorded in dispatch-plan.json + logged), but NOTHING is
    executed. Lets the user verify P4 wiring on a machine
    without hardware.
  - Both False: real run. P5 territory — may FAIL on hardware
    until stabilization is done.
"""

from __future__ import annotations

import json
import re
import shlex
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Optional

from harness import capture, controller_reset, firmware, run_folder, screenshots
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
class DispatchStep:
    """One step in a suite's dispatch plan.

    `cmd` is a stringified, copy-pasteable shell command the
    user can run manually for diagnostics. `executed` is False
    in dry-run-dispatch mode (so the user can see what WOULD
    have run).
    """

    name: str
    description: str
    cmd: str
    executed: bool


@dataclass
class DispatchPlan:
    """A suite's full dispatch plan: preconditions + am instrument.

    Written to `<run_dir>/dispatch-plan.json` in dry-run-dispatch
    mode. In a real run, the file is also written so the user
    can review what was actually executed.
    """

    suite: str
    preconditions: list[DispatchStep] = field(default_factory=list)
    am_instrument: Optional[DispatchStep] = None
    screenshot_pull: Optional[DispatchStep] = None
    extras: dict[str, str] = field(default_factory=dict)

    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2, sort_keys=True)


@dataclass
class OrchestratorResult:
    passed: bool
    manifest_path: Path
    run_dir: Path
    smoke: Optional[AmInstrumentResult]
    e2e: Optional[AmInstrumentResult]
    fail_reason: Optional[str]
    dispatch_plan: Optional[DispatchPlan] = None


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
        dry_run_dispatch: bool = False,
        class_name: str | None = None,
        extras: dict[str, str] | None = None,
        skip_install: bool = False,
        skip_smoke: bool = False,
    ) -> OrchestratorResult:
        """Run the named suite end-to-end.

        Modes:
          - dry_run=True (P3): walk steps 1-6 (everything up to
            the per-suite dispatch) and stop. The smoke gate IS
            exercised; the E2E class is NOT invoked.
          - dry_run_dispatch=True (P4): smoke gate runs, then
            the dispatch is COMPOSED (preconditions + am
            instrument command recorded in dispatch-plan.json +
            logged) but NOTHING is executed. Lets the user
            verify P4 wiring on a machine without hardware.
          - both False: real run. P5 territory — may FAIL on
            hardware until stabilization is done.

        `suite` is one of: "fresh_install", "firmware_update",
        "settings_update".

        Exception safety (P3 review finding CRIT #1): the
        pipeline body is wrapped in `try / except BaseException /
        finally`. The `finally` always:
          1. Stops the logcat + serial capture subprocesses
             (closes the parent-side file handles — P3 finding
             CRIT #2).
          2. Writes the final manifest with end_iso / end_epoch
             + pass/fail.
        without which a Ctrl-C mid-run would leak the readers
        (AGENTS.md "Never block forever on serial/console
        readers") and leave no manifest. After the `finally` we
        re-raise the captured exception so the caller (run.py,
        pytest) can decide how to react.
        """
        if dry_run and dry_run_dispatch:
            raise ValueError(
                "dry_run and dry_run_dispatch are mutually exclusive; "
                "use dry_run=True for the P3 stop point (smoke only) or "
                "dry_run_dispatch=True for the P4 stop point (smoke + dispatch plan)."
            )
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
        dispatch_plan: Optional[DispatchPlan] = None
        reraise_exc: Optional[BaseException] = None
        # Set by _finish() in the finally block; returned below.
        result: Optional[OrchestratorResult] = None

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

            # 6. SMOKE GATE. On fail, mark the manifest but DO NOT
            # early-return; fall through to the finally so the
            # captures are still stopped + the manifest is still
            # written.
            smoke_ok = True
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
                smoke_ok = smoke_result.passed
                if not smoke_ok:
                    manifest.fail_reason = (
                        f"smoke gate failed ({self.config.device.smoke_class}); "
                        f"see {run_dir}/instrumentation-smoke.txt"
                    )
                    self._safe_on_failure_screenshot(run_dir)

            # 7. Suite dispatch. Skipped if the smoke gate failed.
            if smoke_ok:
                if dry_run:
                    # P3 stop point: don't dispatch the per-suite
                    # E2E class. The dry-run proves the build +
                    # install + capture + smoke pipeline works
                    # end-to-end.
                    manifest.pass_ = True
                else:
                    e2e_result, passed, fail_reason, dispatch_plan = self._dispatch(
                        suite=suite,
                        class_name=class_name,
                        extras=extras,
                        run_dir=run_dir,
                        dry_run_dispatch=dry_run_dispatch,
                    )
                    if dispatch_plan is not None:
                        # Always write the dispatch plan so the
                        # user can review what ran (or what
                        # would have run in dry_run_dispatch).
                        (run_dir / "dispatch-plan.json").write_text(
                            dispatch_plan.to_json(), encoding="utf-8",
                        )
                        manifest.dispatch_plan_executed = all(
                            s.executed for s in dispatch_plan.preconditions
                        ) and (
                            dispatch_plan.am_instrument.executed
                            if dispatch_plan.am_instrument else True
                        )
                    if dry_run_dispatch:
                        # P4 stop point: dispatch is composed
                        # + recorded; no E2E result. Mark the
                        # run PASS so the caller can see the
                        # plan was successfully assembled.
                        manifest.pass_ = True
                    else:
                        manifest.pass_ = passed
                        if fail_reason is not None:
                            manifest.fail_reason = fail_reason
                        manifest.e2e_results = (
                            [e2e_result.to_manifest_dict()] if e2e_result else []
                        )
                        # Persist the E2E instrumentation output
                        # for post-mortem reading (matches the
                        # smoke gate's instrumentation-smoke.txt
                        # convention).
                        if e2e_result is not None:
                            (run_dir / "instrumentation-e2e.txt").write_text(
                                e2e_result.stdout + "\n" + e2e_result.stderr,
                                encoding="utf-8",
                            )
                        if not passed:
                            self._safe_on_failure_screenshot(run_dir)

            if manifest.pass_:
                try:
                    screenshots.capture_final(self.adb, run_dir)
                except Exception:  # noqa: BLE001 - final screenshot is best-effort
                    pass
        except KeyboardInterrupt:
            if manifest.fail_reason is None:
                manifest.fail_reason = "interrupted by user (KeyboardInterrupt)"
            reraise_exc = KeyboardInterrupt(manifest.fail_reason)
        except SystemExit as exc:
            if manifest.fail_reason is None:
                manifest.fail_reason = f"interrupted (SystemExit: {exc!r})"
            reraise_exc = SystemExit(manifest.fail_reason)
        except BaseException as exc:  # noqa: BLE001 - we always re-raise after finalize
            if manifest.fail_reason is None:
                manifest.fail_reason = f"orchestrator exception: {exc!r}"
            reraise_exc = exc
            self._safe_on_failure_screenshot(run_dir)
        finally:
            # ALWAYS: stop captures (closes file handles via
            # Capture.stop(), which is the fix for P3 CRIT #2)
            # + finalize the manifest. Idempotent. Safe on the
            # success path, the early-exit path, and any
            # exception path including BaseException.
            result = self._finish(
                run_dir, manifest, logcat_cap, serial_cap,
                smoke_result, e2e_result,
            )

        # Re-raise OUTSIDE the finally so cleanup completes first.
        # On the success path reraise_exc is None; we return the
        # OrchestratorResult so the caller can read pass/fail
        # + manifest path.
        if reraise_exc is not None:
            raise reraise_exc
        assert result is not None  # the finally block always sets it
        # Attach the dispatch plan to the result so the caller
        # can read it without re-parsing the manifest.
        result.dispatch_plan = dispatch_plan
        return result

    def _safe_on_failure_screenshot(self, run_dir: Path) -> None:
        """Best-effort on-failure screencap. Never raises.

        The orchestrator's exception path calls this so a
        screenshot error doesn't shadow the original exception.
        """
        try:
            screenshots.capture_on_failure(self.adb, run_dir)
        except Exception:  # noqa: BLE001
            pass

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
    ) -> OrchestratorResult:
        """Stop captures + finalize the manifest.

        Reads pass/fail + fail_reason from the manifest (which
        the pipeline body has already set). The pipeline body
        never returns early anymore — it just mutates manifest
        state and falls through to the `finally:` block, which
        calls this. So `_finish` is always called exactly once
        per run, regardless of the exit path (success, early
        exit on smoke fail, BaseException).
        """
        # Stop captures BEFORE finalize so the run folder's
        # logcat/serial streams are flushed + closed (and the
        # parent-side file handles are released — P3 CRIT #2).
        if logcat_cap is not None:
            logcat_cap.stop()
        if serial_cap is not None:
            serial_cap.stop()
        passed = bool(manifest.pass_)
        fail_reason = manifest.fail_reason
        run_folder.finalize(manifest, passed=passed, fail_reason=fail_reason)
        return OrchestratorResult(
            passed=passed,
            manifest_path=run_dir / "manifest.json",
            run_dir=run_dir,
            smoke=smoke,
            e2e=e2e,
            fail_reason=fail_reason,
        )

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
        dry_run_dispatch: bool,
    ) -> tuple[
        Optional[AmInstrumentResult],
        bool,
        Optional[str],
        Optional[DispatchPlan],
    ]:
        """Per-suite dispatch (P4).

        Returns `(e2e_result, passed, fail_reason, dispatch_plan)`.

        In `dry_run_dispatch=True` (P4 stop point): the dispatch
        methods compose the full plan (preconditions + am
        instrument command) and return it without executing
        anything. `e2e_result` is None and `passed` is True
        (the plan was successfully assembled).

        In a real run: preconditions execute, am instrument
        runs, screenshot pull happens. `e2e_result` carries
        the per-test pass/fail from instrumentation output.
        """
        if suite == "fresh_install":
            return self._dispatch_fresh_install(
                class_name=class_name,
                extras=extras,
                run_dir=run_dir,
                dry_run_dispatch=dry_run_dispatch,
            )
        if suite == "firmware_update":
            return self._dispatch_firmware_update(
                class_name=class_name,
                extras=extras,
                run_dir=run_dir,
                dry_run_dispatch=dry_run_dispatch,
            )
        if suite == "settings_update":
            return self._dispatch_settings_update(
                class_name=class_name,
                extras=extras,
                run_dir=run_dir,
                dry_run_dispatch=dry_run_dispatch,
            )
        # Unreachable — the run() guard rejects unknown suites.
        raise AssertionError(suite)

    # --- Per-suite dispatch methods -----------------------------------

    def _dispatch_fresh_install(
        self,
        *,
        class_name: str | None,
        extras: dict[str, str] | None,
        run_dir: Path,
        dry_run_dispatch: bool,
    ) -> tuple[
        Optional[AmInstrumentResult],
        bool,
        Optional[str],
        DispatchPlan,
    ]:
        """fresh_install dispatch (plan task 4.1).

        Preconditions:
          1. `adb uninstall` the app (start from a clean slate).
          2. `controller_reset.erase_config_partitions` for
             appcfg + events + sysevents (NVS reset; no app/BLE
             needed).
          3. `install_apks(rebuild=False)` to install the
             already-built APKs (the P3 pipeline built them).
        Then:
          4. `am instrument FreshInstallE2ETest` with the @E2e
             gate arg.
          5. Pull the Kotlin in-test screenshots.
        """
        plan = DispatchPlan(suite="fresh_install", extras=dict(extras or {}))
        class_name = class_name or (
            "de.aarondietz.beetmeister.e2e.FreshInstallE2ETest"
        )
        app_package = self.config.device.app_package
        port = self.config.controller.serial_port

        # 1. uninstall app.
        uninstall_cmd = self.adb._cmd("uninstall", app_package)
        plan.preconditions.append(DispatchStep(
            name="uninstall_app",
            description=f"adb uninstall {app_package}",
            cmd=" ".join(shlex.quote(c) for c in uninstall_cmd),
            executed=False,
        ))

        # 2. erase controller NVS partitions.
        if port:
            from harness import partition_map as _pm
            csv_path = (
                self.config.repo_root
                / "firmware" / "esp-idf" / "partitions" / "beetmeister.csv"
            )
            layout = _pm.load(csv_path)
            targets = _pm.require(layout, "appcfg", "events", "sysevents")
            for part in targets:
                erase_cmd = controller_reset.build_esptool_command(
                    self.config,
                    "--port", port,
                    "erase_region",
                    f"0x{part.offset:x}",
                    f"0x{part.size:x}",
                )
                plan.preconditions.append(DispatchStep(
                    name=f"erase_{part.name}",
                    description=f"esptool erase_region for {part.name} (offset=0x{part.offset:x}, size=0x{part.size:x})",
                    cmd=" ".join(shlex.quote(c) for c in erase_cmd),
                    executed=False,
                ))
        else:
            plan.preconditions.append(DispatchStep(
                name="skip_erase_no_port",
                description="controller serial_port empty; skipping erase_region (controller state is whatever it was)",
                cmd="# skipped: [controller].serial_port not set",
                executed=False,
            ))

        # 3. install already-built APKs (no rebuild).
        install_cmd_str = (
            f"Adb.install_apks(rebuild=False)  # installs the APKs the "
            f"P3 pipeline already built at {self.config.device.app_dir}/app/build/outputs/apk"
        )
        plan.preconditions.append(DispatchStep(
            name="install_built_apks",
            description="adb install -r the app + androidTest APKs (no rebuild; pipeline already built them)",
            cmd=install_cmd_str,
            executed=False,
        ))

        # 4. am instrument FreshInstallE2ETest.
        am_cmd = self._compose_am_instrument_preview(
            class_name=class_name,
            extras=plan.extras,
            beet_run_e2e=True,
        )
        plan.am_instrument = DispatchStep(
            name="am_instrument_fresh_install",
            description=f"am instrument for {class_name} (gate arg: beetRunE2e=true)",
            cmd=am_cmd,
            executed=False,
        )

        # 5. pull in-test screenshots.
        pull_cmd = self._compose_screenshot_pull_preview("freshInstall", run_dir)
        plan.screenshot_pull = DispatchStep(
            name="pull_in_test_screenshots",
            description="adb pull Kotlin in-test screenshots from /sdcard/Android/data/<package>/files/e2e_screenshots/freshInstall/ into the run folder",
            cmd=pull_cmd,
            executed=False,
        )

        if dry_run_dispatch:
            return None, True, None, plan

        # Real run: execute the preconditions + am instrument.
        self._run_precondition(plan.preconditions[0], self._do_uninstall(app_package))
        for step in plan.preconditions[1:-1]:
            # Erase steps live in the middle of preconditions.
            self._execute_erase_step(step)
        # Last precondition is install_built_apks.
        self._run_precondition(plan.preconditions[-1], self._do_install_built_apks())
        e2e_result = self._execute_am_instrument(plan.am_instrument)
        self._execute_screenshot_pull(plan.screenshot_pull, run_dir)
        passed = e2e_result.passed
        fail_reason = None if passed else (
            f"FreshInstallE2ETest failed (rc={e2e_result.returncode}); "
            f"see {run_dir}/instrumentation-e2e.txt"
        )
        return e2e_result, passed, fail_reason, plan

    def _dispatch_firmware_update(
        self,
        *,
        class_name: str | None,
        extras: dict[str, str] | None,
        run_dir: Path,
        dry_run_dispatch: bool,
    ) -> tuple[
        Optional[AmInstrumentResult],
        bool,
        Optional[str],
        DispatchPlan,
    ]:
        """firmware_update dispatch (plan task 4.2).

        Preconditions:
          1. `firmware.flash_old(port)` — erase appcfg + flash
             the cached v0.3.0 .bin (and bootloader +
             partition-table) via the unified
             `build_esptool_command` helper.
          2. `adb uninstall` the app.
          3. `install_apks(rebuild=False)`.
        Then:
          4. `am instrument FirmwareUpdateE2ETest` with the @E2e
             gate arg + the old + new build labels as
             `-e expected_old_build_label` +
             `-e expected_new_build_label` (read by the Kotlin
             test via `InstrumentationRegistry.getArguments()`).
          5. Pull Kotlin in-test screenshots.
        """
        plan = DispatchPlan(suite="firmware_update", extras=dict(extras or {}))
        class_name = class_name or (
            "de.aarondietz.beetmeister.e2e.FirmwareUpdateE2ETest"
        )
        app_package = self.config.device.app_package
        port = self.config.controller.serial_port

        if not port:
            raise RuntimeError(
                "firmware_update suite requires [controller].serial_port. "
                "Set it in config.toml."
            )

        # Resolve the old + new build labels. In dry_run_dispatch
        # we skip the firmware build (it's destructive + needs
        # ESP-IDF on the host) and just compose the plan with
        # placeholder labels: the tag name for old (we'd parse
        # the .bin metadata to get the real value in a real run)
        # + the parsed bundled build_label for new. In a real
        # run, ensure_old_firmware_built reads (or builds) the
        # .bin and parses the actual build_label via
        # `esptool image_info`.
        if dry_run_dispatch:
            old_label = self.config.firmware.pinned_tag
            info_desc = (
                f"(dry-run-dispatch placeholder; the real run will "
                f"parse {self.config.firmware.pinned_tag!r}'s .bin "
                f"metadata via esptool image_info)"
            )
        else:
            try:
                info = firmware.ensure_old_firmware_built(self.config)
            except Exception as exc:  # noqa: BLE001
                raise RuntimeError(
                    f"ensure_old_firmware_built failed before "
                    f"firmware_update dispatch: {exc!r}"
                ) from exc
            old_label = info.build_label
            info_desc = (
                f"cached {info.bin_path.name}, build_label={info.build_label!r}, "
                f"image_sha256={info.image_sha256[:16]}…"
            )
        plan.extras["expected_old_build_label"] = old_label
        plan.extras["expected_new_build_label"] = self._last_bundled_build_label()

        # 1. flash_old.
        flash_step = DispatchStep(
            name="flash_old_v0.3.0",
            description=(
                f"firmware.flash_old(port={port}): erase appcfg + otadata, "
                f"then write_flash bootloader (0x1000) + partition-table "
                f"(0x8000) + beetmeister.bin (ota_0) from firmware_cache/"
            ),
            cmd=(
                f"firmware.flash_old(config, port={port!r}, "
                f"pinned_tag={self.config.firmware.pinned_tag!r})  # {info_desc}"
            ),
            executed=False,
        )
        plan.preconditions.append(flash_step)

        # 2. uninstall.
        uninstall_cmd = self.adb._cmd("uninstall", app_package)
        plan.preconditions.append(DispatchStep(
            name="uninstall_app",
            description=f"adb uninstall {app_package}",
            cmd=" ".join(shlex.quote(c) for c in uninstall_cmd),
            executed=False,
        ))

        # 3. install already-built APKs.
        plan.preconditions.append(DispatchStep(
            name="install_built_apks",
            description="adb install -r the app + androidTest APKs (no rebuild)",
            cmd=f"Adb.install_apks(rebuild=False)  # APKs from {self.config.device.app_dir}/app/build/outputs/apk",
            executed=False,
        ))

        # 4. am instrument FirmwareUpdateE2ETest with both build labels.
        am_cmd = self._compose_am_instrument_preview(
            class_name=class_name,
            extras=plan.extras,
            beet_run_e2e=True,
        )
        plan.am_instrument = DispatchStep(
            name="am_instrument_firmware_update",
            description=(
                f"am instrument for {class_name} with "
                f"expected_old_build_label={plan.extras.get('expected_old_build_label')!r} "
                f"+ expected_new_build_label={plan.extras.get('expected_new_build_label')!r}"
            ),
            cmd=am_cmd,
            executed=False,
        )

        # 5. pull in-test screenshots.
        pull_cmd = self._compose_screenshot_pull_preview("firmwareUpdate", run_dir)
        plan.screenshot_pull = DispatchStep(
            name="pull_in_test_screenshots",
            description="adb pull Kotlin in-test screenshots from /sdcard/Android/data/<package>/files/e2e_screenshots/firmwareUpdate/ into the run folder",
            cmd=pull_cmd,
            executed=False,
        )

        if dry_run_dispatch:
            return None, True, None, plan

        # Real run: execute the preconditions + am instrument.
        self._run_precondition(flash_step, self._do_flash_old())
        self._run_precondition(plan.preconditions[1], self._do_uninstall(app_package))
        self._run_precondition(plan.preconditions[2], self._do_install_built_apks())
        e2e_result = self._execute_am_instrument(plan.am_instrument)
        self._execute_screenshot_pull(plan.screenshot_pull, run_dir)
        passed = e2e_result.passed
        fail_reason = None if passed else (
            f"FirmwareUpdateE2ETest failed (rc={e2e_result.returncode}); "
            f"see {run_dir}/instrumentation-e2e.txt"
        )
        return e2e_result, passed, fail_reason, plan

    def _dispatch_settings_update(
        self,
        *,
        class_name: str | None,
        extras: dict[str, str] | None,
        run_dir: Path,
        dry_run_dispatch: bool,
    ) -> tuple[
        Optional[AmInstrumentResult],
        bool,
        Optional[str],
        DispatchPlan,
    ]:
        """settings_update dispatch (plan task 4.3).

        Preconditions:
          1. `install_apks(rebuild=False)` — no destructive
             action; just install the already-built APKs from a
             green install state.
        Then:
          2. ONE `am instrument SettingsUpdateE2ETest` runs the
             FULL class in a single instrumentation session so
             the class-shared @Before connect fires once for
             all 7 @Test siblings (matches Phase 2 decision).
          3. Pull Kotlin in-test screenshots.
        """
        plan = DispatchPlan(suite="settings_update", extras=dict(extras or {}))
        class_name = class_name or (
            "de.aarondietz.beetmeister.e2e.SettingsUpdateE2ETest"
        )

        # 1. install already-built APKs (no rebuild).
        plan.preconditions.append(DispatchStep(
            name="install_built_apks",
            description="adb install -r the app + androidTest APKs (no rebuild; start from a green install state)",
            cmd=f"Adb.install_apks(rebuild=False)  # APKs from {self.config.device.app_dir}/app/build/outputs/apk",
            executed=False,
        ))

        # 2. am instrument SettingsUpdateE2ETest (full class, 1 call).
        am_cmd = self._compose_am_instrument_preview(
            class_name=class_name,
            extras=plan.extras,
            beet_run_e2e=True,
        )
        plan.am_instrument = DispatchStep(
            name="am_instrument_settings_update",
            description=(
                f"am instrument for {class_name} (FULL CLASS, 1 call; "
                f"class-shared @Before fires once for all 7 @Test siblings)"
            ),
            cmd=am_cmd,
            executed=False,
        )

        # 3. pull in-test screenshots.
        pull_cmd = self._compose_screenshot_pull_preview("settingsUpdate", run_dir)
        plan.screenshot_pull = DispatchStep(
            name="pull_in_test_screenshots",
            description="adb pull Kotlin in-test screenshots from /sdcard/Android/data/<package>/files/e2e_screenshots/settingsUpdate/ into the run folder",
            cmd=pull_cmd,
            executed=False,
        )

        if dry_run_dispatch:
            return None, True, None, plan

        # Real run: execute.
        self._run_precondition(plan.preconditions[0], self._do_install_built_apks())
        e2e_result = self._execute_am_instrument(plan.am_instrument)
        self._execute_screenshot_pull(plan.screenshot_pull, run_dir)
        passed = e2e_result.passed
        fail_reason = None if passed else (
            f"SettingsUpdateE2ETest failed (rc={e2e_result.returncode}); "
            f"see {run_dir}/instrumentation-e2e.txt"
        )
        return e2e_result, passed, fail_reason, plan

    # --- Dispatch helpers ----------------------------------------------

    def _compose_am_instrument_preview(
        self,
        *,
        class_name: str,
        extras: dict[str, str],
        beet_run_e2e: bool,
    ) -> str:
        """Build a copy-pasteable `am instrument` command string.

        The string includes all -e extras (including the @E2e
        gate) so the user can run it manually for diagnostics.
        Matches the actual `Adb.am_instrument` composition.
        """
        parts = list(self.adb._cmd("shell", "am", "instrument", "-w"))
        all_extras = dict(extras)
        if beet_run_e2e:
            all_extras.setdefault(self.config.device.e2e_gate_arg, "true")
        for k, v in all_extras.items():
            parts += ["-e", k, v]
        parts += ["-e", "class", class_name]
        parts += [f"{self.config.device.test_package}/{self.config.device.test_runner}"]
        return " ".join(shlex.quote(p) for p in parts)

    def _compose_screenshot_pull_preview(self, slug: str, run_dir: Path) -> str:
        """Build a copy-pasteable `adb pull` for the in-test screenshots."""
        remote = f"/sdcard/Android/data/{self.config.device.app_package}/files/e2e_screenshots/{slug}"
        dest = run_dir / "screenshots" / slug
        return " ".join(shlex.quote(c) for c in self.adb._cmd("pull", remote, str(dest)))

    def _last_bundled_build_label(self) -> str:
        """Return the most recent parsed build_label from step 5.

        Used by the firmware_update dispatch to compose
        `expected_new_build_label`. Set in `run()` step 5.
        """
        return self._bundled_build_label_cache or ""

    def _parse_bundled_firmware_stamp(self) -> BundledFirmware:
        """Read the GENERATED `bundled-firmware-stamp.json`.

        Caches `build_label` on the instance for the
        firmware_update dispatch.

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
            bundled = BundledFirmware(
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
        self._bundled_build_label_cache = bundled.build_label
        return bundled

    # --- Dispatch executors (real run) ---------------------------------

    def _run_precondition(
        self, step: DispatchStep, action: "callable"  # noqa: F821
    ) -> None:
        """Execute a precondition + mark the DispatchStep as executed.

        Raises on failure so the orchestrator's exception
        handling can mark the run as FAILED.
        """
        action()
        step.executed = True

    def _do_uninstall(self, app_package: str):
        def action() -> None:
            self.adb.uninstall(app_package)
        return action

    def _do_install_built_apks(self):
        def action() -> None:
            self.adb.install_apks(rebuild=False)
        return action

    def _do_flash_old(self):
        def action() -> None:
            firmware.flash_old(self.config, self.config.controller.serial_port)
        return action

    def _execute_erase_step(self, step: DispatchStep) -> None:
        """Execute one of the erase preconditions (already in plan).

        Re-parses the `cmd` to extract the partition name; runs
        `controller_reset.erase_config_partitions` for that one
        partition. (Easier than parsing the shell string.)
        """
        for part_name in ("appcfg", "events", "sysevents"):
            if step.name == f"erase_{part_name}":
                results = controller_reset.erase_config_partitions(
                    self.config, self.config.controller.serial_port,
                    partitions=(part_name,),
                )
                bad = [r for r in results if not r.ok]
                if bad:
                    raise RuntimeError(
                        f"precondition failed: erase_region {part_name} "
                        f"rc={bad[0].returncode}: "
                        f"{bad[0].stderr.strip() or bad[0].stdout.strip()}"
                    )
                step.executed = True
                return
        raise AssertionError(f"unrecognized erase step: {step.name}")

    def _execute_am_instrument(
        self, step: DispatchStep
    ) -> AmInstrumentResult:
        """Invoke `am instrument` for the E2E class + write the run log."""
        class_name = self._class_name_from_step(step)
        result = self.adb.am_instrument(
            class_name=class_name,
            runner=self.config.device.test_runner,
            beet_run_e2e=True,
            extras=self._extras_from_step(step),
            timeout=float(self.config.orchestrator.default_am_instrument_timeout),
        )
        step.executed = True
        return result

    def _class_name_from_step(self, step: DispatchStep) -> str:
        # Convention: the DispatchStep.cmd includes `class <FQN>`.
        # Find the FQN by parsing -e class <FQN>.
        import re as _re
        m = _re.search(r"-e\s+class\s+(\S+)", step.cmd)
        if not m:
            raise AssertionError(f"could not parse class from cmd: {step.cmd}")
        return m.group(1)

    def _extras_from_step(self, step: DispatchStep) -> dict[str, str]:
        import re as _re
        extras: dict[str, str] = {}
        # Find all `-e KEY VALUE` triples BEFORE the `-e class …`.
        m = _re.search(r"-e\s+class\s+\S+", step.cmd)
        prefix = step.cmd[: m.start()] if m else step.cmd
        for em in _re.finditer(r"-e\s+(\S+)\s+(\S+)", prefix):
            extras[em.group(1)] = em.group(2)
        return extras

    def _execute_screenshot_pull(self, step: DispatchStep, run_dir: Path) -> None:
        """Pull the Kotlin in-test screenshots from the device.

        Best-effort: a failed pull is logged on the step but
        does NOT fail the run (screenshots are diagnostic).
        """
        slug = self._slug_from_step(step)
        try:
            pulled = self.adb.pull_in_test_screenshots(
                slug, run_dir / "screenshots" / slug,
            )
            if pulled:
                step.executed = True
                step.description += f"  (pulled {len(pulled)} files)"
        except Exception as exc:  # noqa: BLE001
            step.description += f"  (pull failed: {exc!r})"

    def _slug_from_step(self, step: DispatchStep) -> str:
        for candidate in ("freshInstall", "firmwareUpdate", "settingsUpdate"):
            if candidate in step.name:
                return candidate
        raise AssertionError(f"could not infer screenshot slug from: {step.name}")
