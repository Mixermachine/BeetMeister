"""Thin cross-platform adb wrapper.

The orchestrator's only Android-side surface. All adb
invocations go through this class so:

  - `adb -s <serial>` is appended consistently (or omitted when
    the configured device is the only attached one).
  - The cross-platform shell-quoting rules (pathlib, no
    `\\` literals, `shlex.quote` for log) are honored.
  - `am_instrument` composes the `-e key value` extras list
    correctly across platforms (it's the most fragile part of
    the orchestrator).
  - `install_apks` is the single source of truth for "build the
    APKs and install them" — every suite that needs the
    harness-installed APKs goes through here, so the
    smoke-gate-after-build invariant holds.

The wrapper does NOT activate the ESP-IDF env (the env is for
firmware; adb is unrelated).
"""

from __future__ import annotations

import os
import re
import shlex
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional

from harness.config import HarnessConfig
from harness.logging_setup import get_logger

log = get_logger()


@dataclass
class AmInstrumentResult:
    """Outcome of one `am instrument` invocation."""

    class_name: str
    returncode: int
    stdout: str
    stderr: str
    duration_s: float
    extras: dict[str, str]
    passed: bool
    # Parsed per-test results, if any. Empty for the smoke gate.
    # Each entry: {"name": str, "passed": bool, "details": str}
    test_results: list[dict[str, object]]

    def to_manifest_dict(self) -> dict[str, object]:
        return {
            "class": self.class_name,
            "passed": self.passed,
            "returncode": self.returncode,
            "duration_s": self.duration_s,
            "extras": self.extras,
            "tests": self.test_results,
        }


class Adb:
    """Cross-platform adb wrapper.

    `Adb(config)` resolves the adb executable (PATH by default;
    override via env `BEET_HARNESS_ADB`). When `config.device.adb_serial`
    is set, every command gets `-s <serial>`; otherwise the
    device is assumed to be the only attached one and the
    orchestrator will fail fast if `devices()` returns 0 or >1.
    """

    def __init__(self, config: HarnessConfig, *, adb_path: str | None = None) -> None:
        self._config = config
        self._serial = config.device.adb_serial
        self._adb = adb_path or os.environ.get("BEET_HARNESS_ADB") or "adb"
        if not shutil.which(self._adb) and not Path(self._adb).exists():
            # Not fatal yet — the user may set BEET_HARNESS_ADB later
            # or pass it via run.py. We surface a clear error at the
            # first call.
            self._adb_resolved = self._adb
        else:
            self._adb_resolved = self._adb

    def _cmd(self, *args: str) -> list[str]:
        cmd: list[str] = [self._adb]
        if self._serial:
            cmd += ["-s", self._serial]
        cmd += list(args)
        return cmd

    def _run(
        self,
        *args: str,
        timeout: float | None = None,
        check: bool = False,
        capture: bool = True,
        input_bytes: bytes | None = None,
    ) -> subprocess.CompletedProcess[str]:
        cmd = self._cmd(*args)
        return subprocess.run(
            cmd,
            capture_output=capture,
            text=True,
            input=input_bytes.decode() if input_bytes else None,
            timeout=timeout,
            check=check,
        )

    def devices(self) -> list[str]:
        """List attached device serials in the `state == "device"` sense."""
        proc = self._run("devices")
        if proc.returncode != 0:
            raise RuntimeError(
                f"adb devices failed (rc={proc.returncode}): "
                f"{proc.stderr.strip() or proc.stdout.strip()}"
            )
        out: list[str] = []
        for line in proc.stdout.splitlines():
            line = line.strip()
            if not line or line.startswith("List of devices"):
                continue
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                out.append(parts[0])
        return out

    def assert_single_device(self) -> str:
        """Validate that exactly one device is attached; return its serial.

        If `config.device.adb_serial` is set, asserts that serial
        is attached. Otherwise picks the only attached device.
        """
        attached = self.devices()
        if self._serial:
            if self._serial not in attached:
                raise RuntimeError(
                    f"adb: configured serial {self._serial!r} is not attached. "
                    f"Attached: {attached}"
                )
            return self._serial
        if not attached:
            raise RuntimeError("adb: no device attached")
        if len(attached) > 1:
            raise RuntimeError(
                f"adb: multiple devices attached {attached}. "
                f"Set [device].adb_serial in config.toml."
            )
        return attached[0]

    def install(self, apk: Path, *, replace: bool = True) -> None:
        """`adb install -r <apk>`."""
        if not apk.exists():
            raise FileNotFoundError(apk)
        args = ["install", "-r" if replace else "-t", str(apk)]
        proc = self._run(*args, timeout=120.0)
        if proc.returncode != 0:
            raise RuntimeError(
                f"adb install failed (rc={proc.returncode}) for {apk}: "
                f"{proc.stderr.strip() or proc.stdout.strip()}"
            )
        if "Success" not in proc.stdout:
            raise RuntimeError(
                f"adb install did not report Success for {apk}: {proc.stdout}"
            )

    def uninstall(self, package: str) -> bool:
        """`adb uninstall <package>`. Returns True if the package was present."""
        proc = self._run("uninstall", package, timeout=60.0)
        if proc.returncode != 0:
            raise RuntimeError(
                f"adb uninstall failed (rc={proc.returncode}) for {package}: "
                f"{proc.stderr.strip() or proc.stdout.strip()}"
            )
        return "Success" in proc.stdout

    def grant_ble_permissions(self, package: str) -> None:
        """Grant BLUETOOTH_SCAN + BLUETOOTH_CONNECT + location + notifications to the package.

        Idempotent: re-granting is a no-op on the device side.
        """
        self._run("shell", "pm", "grant", package, "android.permission.BLUETOOTH_SCAN")
        self._run("shell", "pm", "grant", package, "android.permission.BLUETOOTH_CONNECT")
        self._run("shell", "pm", "grant", package, "android.permission.ACCESS_FINE_LOCATION")
        self._run("shell", "pm", "grant", package, "android.permission.ACCESS_COARSE_LOCATION")
        self._run("shell", "pm", "grant", package, "android.permission.POST_NOTIFICATIONS")

    def clear_ble_bond_via_intent(self, mac: str, app_package: str) -> bool:
        """Clear the BLE bond for the given MAC by firing the
        `clear_ble_bond` action of the test-only
        `DebugActionActivity` in the app.

        Runs:
            adb shell am start \
                -n <app_package>/.debug.DebugActionActivity \
                -a de.aarondietz.beetmeister.debug.action.RUN \
                --es action clear_ble_bond \
                --es mac <MAC>

        The Activity (in
        `app/.../debug/DebugActionActivity.kt`) dispatches
        on the `action` extra. For `clear_ble_bond` it calls
        `BluetoothDevice.removeBond(mac)` via reflection
        (the method is `@hide @SystemApi`, not in the public
        SDK) and finishes. `am start` is synchronous — the
        command returns when the Activity is bound to a
        window (which for a translucent no-title Activity
        happens within ~100 ms).

        The Activity is **production-safe**: it is never
        launched by production code; the class lives in the
        `.debug.*` package, the intent action is
        `de.aarondietz.beetmeister.debug.action.RUN`, and
        the Activity uses a translucent theme +
        `excludeFromRecents` + `noHistory` so no UI is
        shown to the user and no recents entry is created.
        The worst-case misuse is the side effect of the
        invoked action (e.g. unpair the user's BLE
        peripheral) — a low-impact action the user can
        recover from by re-pairing. See
        `DebugActionActivity.kt` for the full
        production-safety analysis.

        **Why an Activity (not a BroadcastReceiver):**
        lifecycle is explicit (`onCreate` → `onDestroy`).
        `am start` is synchronous and waits for the Activity
        to be bound, so the orchestrator knows the action
        is done before the next step. A BroadcastReceiver
        can be silently dropped on devices in App Standby
        restricted buckets (Android 8+); an Activity
        launched via `am start` from the shell user is never
        dropped (shell is treated as foreground for
        delivery).

        **Why a single Activity with a dispatch `action`
        extra (not one Activity per operation):** adding a
        new test operation (e.g. `reset_maintenance_state`,
        `dump_runtime_config`) is a new `when` arm in
        `DebugActionActivity.dispatch()`. No new manifest
        entry, no new class, no new test code in the
        orchestrator's `Adb` wrapper unless the new
        operation needs its own adb-side args. Keeps the
        manifest and orchestrator's dispatch surface
        minimal.

        Used by the firmware_update dispatch's
        `clear_ble_bond_via_intent` precondition. After
        `flash_old` wipes the controller's NVS, the phone's
        stored link key is stale; the next BLE auto-connect
        tries to encrypt with it and gets HCI_ERR_KEY_MISSING
        (the controller has no matching key), which makes the
        link drop and stalls the test. The dismissal loop
        never finds a "Pair" button on Samsung One UI either
        (the button text doesn't match the [Pair|Pairing|OK|
        Allow] candidates), so the test times out at
        `gate.tapScan`.

        P5 followup: addresses the actual root cause of the
        P5.3 firmware_update E2E block. The original handover
        (commit 26512e9) hypothesized a NimBLE / firmware
        MTU bug, but the logcat from
        `test-harness/runs/20260714-095903-firmware_update/`
        shows the proximate cause is the stale link key
        (`btm_sec: Encryption failure 6, disconnecting 77`
        with `HCI_ERR_KEY_MISSING`).
        """
        proc = self._run(
            "shell", "am", "start",
            "-n", f"{app_package}/.debug.DebugActionActivity",
            "-a", "de.aarondietz.beetmeister.debug.action.RUN",
            "--es", "action", "clear_ble_bond",
            "--es", "mac", mac,
            timeout=15.0,
        )
        return proc.returncode == 0

    def force_stop(self, package: str) -> None:
        """`adb shell am force-stop <package>`. Kills the app process.

        Idempotent; no-op if the package is not running.
        """
        self._run("shell", "am", "force-stop", package, timeout=15.0)

    def unlock_guard(self) -> None:
        """Wake + unlock the device if it's on the lockscreen.

        Ported from `scripts/dev/run-android-real-device-validation.ps1`'s
        `Assert-AndroidPreflightReady`. We DON'T fail on lockscreen
        (instrumentation launches its own activity), but we wake
        the device because `am instrument` against a sleeping
        device is flaky.
        """
        # Wake.
        self._run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        # Swipe up to dismiss keyguard. The exact swipe geometry
        # is device-specific; we use a generous 200..800 swipe
        # which works on most Android 10+ devices.
        self._run(
            "shell", "input", "swipe", "200", "1000", "200", "200", "200",
        )

    def launch_activity(self, package: str) -> None:
        """`adb shell am start -n <package>/.MainActivity` (best effort)."""
        self._run(
            "shell", "am", "start", "-n", f"{package}/.MainActivity",
            check=False,
        )

    def am_instrument(
        self,
        *,
        class_name: str,
        runner: str,
        extras: dict[str, str] | None = None,
        beet_run_e2e: bool = False,
        timeout: float = 900.0,
    ) -> AmInstrumentResult:
        """Run `adb shell am instrument -w` for a single test class.

        `runner` is the FQN of the instrumentation runner
        (`<app_package>/<test_runner_FQN>`). `extras` are passed
        via repeated `-e key value`. `beet_run_e2e=True` prepends
        `-e beetRunE2e true` (the @E2e gate arg).

        Returns an `AmInstrumentResult` with parsed pass/fail
        and per-test outcomes. The orchestrator decides what to
        do with the result.
        """
        extras = dict(extras or {})
        if beet_run_e2e:
            extras.setdefault("beetRunE2e", "true")

        cmd = ["shell", "am", "instrument", "-w"]
        for k, v in extras.items():
            cmd += ["-e", k, v]
        cmd += ["-e", "class", class_name]
        cmd += [f"{self._config.device.test_package}/{runner}"]

        start = time.monotonic()
        log.debug("am_instrument: cmd=%s timeout=%s", " ".join(cmd), timeout)
        proc = self._run(*cmd, timeout=timeout)
        duration = time.monotonic() - start
        result = AmInstrumentResult(
            class_name=class_name,
            returncode=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
            duration_s=duration,
            extras=extras,
            passed=False,
            test_results=[],
        )
        result.passed, result.test_results = _parse_instrumentation_output(
            proc.stdout,
        )
        return result

    def install_apks(
        self,
        *,
        rebuild: bool = True,
        extra_gradle_args: Iterable[str] = (),
    ) -> tuple[Path, Path]:
        """Install both APKs; rebuild first if `rebuild=True` (default).

        When `rebuild=True` (the P3 path): runs `gradlew
        assembleDebug assembleDebugAndroidTest` (cross-platform
        via the wrapper) + `adb install -r` both output APKs.

        When `rebuild=False` (the P4 dispatch path): skips the
        gradle build and just installs the already-built APKs.
        The P3 pipeline has already built them; the dispatch
        only needs to install after its destructive precondition
        (e.g. `adb uninstall` for fresh_install). This keeps
        the per-suite dispatch down to seconds of overhead
        instead of re-paying the 5-minute gradle build.

        Returns `(app_apk, test_apk)` paths.

        This is the orchestrator's APK builder of record per the
        plan: every suite that needs the APKs goes through this
        call so the build + install + smoke-gate invariant holds.
        """
        gradlew = self._config.device.gradlew
        app_dir = self._config.device.app_dir
        if not gradlew or not app_dir:
            raise RuntimeError(
                "config.toml: [device].gradlew + [device].app_dir are required for install_apks()"
            )
        gradlew_path = Path(gradlew)
        if not gradlew_path.exists():
            raise FileNotFoundError(gradlew_path)
        app_dir_path = Path(app_dir).resolve()

        if rebuild:
            # On Windows, the wrapper is `gradlew.bat`; on POSIX, `gradlew`.
            # We pick the one that exists; fall back to a `.bat` shim.
            if os.name == "nt":
                cmd: list[str] = [str(gradlew_path.with_name("gradlew.bat"))]
            else:
                cmd = [str(gradlew_path)]
            cmd += [
                ":app:assembleDebug",
                ":app:assembleDebugAndroidTest",
                *extra_gradle_args,
            ]
            log.debug("gradle build: cmd=%s", " ".join(cmd))
            proc = subprocess.run(
                cmd,
                cwd=str(app_dir_path),
                capture_output=True,
                text=True,
                check=False,
                timeout=float(self._config.orchestrator.gradle_build_timeout),
            )
            if proc.returncode != 0:
                tail = (proc.stderr or proc.stdout).strip().splitlines()[-30:]
                raise RuntimeError(
                    f"gradle build failed (rc={proc.returncode}):\n" + "\n".join(tail)
                )

        app_apk = app_dir_path / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
        test_apk = (
            app_dir_path
            / "app"
            / "build"
            / "outputs"
            / "apk"
            / "androidTest"
            / "debug"
            / "app-debug-androidTest.apk"
        )
        if not app_apk.exists():
            raise FileNotFoundError(f"app APK not found: {app_apk}")
        if not test_apk.exists():
            raise FileNotFoundError(f"androidTest APK not found: {test_apk}")
        self.install(app_apk)
        self.install(test_apk)
        self.grant_ble_permissions(self._config.device.app_package)
        self.unlock_guard()
        return app_apk, test_apk

    def pull_in_test_screenshots(
        self,
        remote_dir_name: str,
        dest: Path,
        *,
        pattern: str = "*.png",
    ) -> list[Path]:
        """Pull PNG screenshots written by the Kotlin `E2eScreenshotHelper`.

        The helper writes to
        `<externalFilesDir>/e2e_screenshots/<slug>/` on the device.
        `remote_dir_name` is the leaf dir name (e.g. `freshInstall`).
        Returns the list of locally-saved Paths (in remote order).
        """
        dest.mkdir(parents=True, exist_ok=True)
        remote_root = f"/sdcard/Android/data/{self._config.device.app_package}/files/e2e_screenshots/{remote_dir_name}"
        # `ls` first so we know what's there; `pull *.png` does not
        # have a recursive-friendly form on every adb version.
        ls = self._run("shell", "ls", "-1", remote_root)
        if ls.returncode != 0:
            return []
        pulled: list[Path] = []
        for line in ls.stdout.splitlines():
            name = line.strip()
            if not name or not name.endswith(".png"):
                continue
            target = dest / name
            self._run("pull", f"{remote_root}/{name}", str(target))
            if target.exists() and target.stat().st_size > 0:
                pulled.append(target)
        return pulled


# --- instrumentation-output parser -----------------------------------------


# Legacy AndroidX Test output (the format `MaintenanceUpdateInstrumentationTest`
# still uses on this project).
_OK_RE = re.compile(r"OK \((?P<n>\d+) tests?\)")
_SUCCESS_RE = re.compile(r"SUCCESS \((?P<n>\d+) tests?\)")
# Newer AndroidX Test output.
_TESTS_RUN_RE = re.compile(r"Tests run:\s*(?P<n>\d+),\s*Failures:\s*(?P<f>\d+)")
# Markers indicating at least one test failed.
_FAIL_RE = re.compile(r"FAILURES!!!")
# Per-test failure lines (legacy + newer formats).
_TEST_LINE_RE = re.compile(
    r"Error in (?P<name>[A-Za-z0-9_$]+)\((?P<cls>[A-Za-z0-9_.$]+)\):",
)
# Per-test status line emitted by the AndroidX Test runner for
# every test as it runs: `INSTRUMENTATION_STATUS: test=<name>(<class>)`.
# We collect these so the manifest can list all tests (passed +
# failed) by name, not just the failures.
_STATUS_TEST_RE = re.compile(
    r"INSTRUMENTATION_STATUS:\s*test=(?P<name>[A-Za-z0-9_$]+)"
    r"\((?P<cls>[A-Za-z0-9_.$]+)\)"
)


def _parse_instrumentation_output(
    stdout: str,
) -> tuple[bool, list[dict[str, object]]]:
    """Parse `am instrument` stdout into (passed, per-test results).

    Recognizes BOTH the legacy JUnit "OK (N tests)" / "FAILURES!!!"
    output (the format the smoke gate uses) AND the newer AndroidX
    "Tests run: N, Failures: M" output. The AndroidX Test runner
    emits `INSTRUMENTATION_STATUS: test=<name>(<class>)` for every
    test as it runs; we collect those names so the manifest can
    list every test by name (not just the failures). For each
    "test=" name we mark it passed unless we also see it in an
    "Error in <name>(<class>):" line.

    Returns `(passed, test_results)` where `test_results` is a
    list of `{"name", "class", "passed", "details"}` dicts in
    the order they appeared in the output.
    """
    if not stdout:
        return False, []
    # `am instrument` writes to its stdout via a remote adb
    # channel that uses `\r\n` line endings. Strip the `\r` so
    # the search sees plain `\n` line terminators.
    text = stdout.replace("\r\n", "\n").replace("\r", "\n")

    # Collect per-test status lines in order. Each becomes a
    # `{name, class, passed, details}` entry. Failed names come
    # from the "Error in <name>(<class>):" lines; if a name
    # appears in both lists, it failed.
    seen_order: list[tuple[str, str]] = []
    seen_index: dict[tuple[str, str], int] = {}
    failed: set[tuple[str, str]] = set()
    for m in _STATUS_TEST_RE.finditer(text):
        key = (m.group("name"), m.group("cls"))
        if key not in seen_index:
            seen_index[key] = len(seen_order)
            seen_order.append(key)
    for m in _TEST_LINE_RE.finditer(text):
        failed.add((m.group("name"), m.group("cls")))

    # Decide pass/fail from the summary lines.
    passed = False
    test_count: int | None = None
    fail_count: int | None = None
    if _OK_RE.search(text) or _SUCCESS_RE.search(text):
        passed = True
        # Parse the count for the manifest.
        m = _OK_RE.search(text) or _SUCCESS_RE.search(text)
        if m:
            test_count = int(m.group("n"))
    elif (m := _TESTS_RUN_RE.search(text)):
        test_count = int(m.group("n"))
        fail_count = int(m.group("f"))
        passed = fail_count == 0
    elif _FAIL_RE.search(text):
        passed = False
    else:
        # No OK line, no FAILURES line, no Tests run line.
        # Assume non-zero exit was already captured by the
        # caller. Treat as failed.
        return False, []

    test_results: list[dict[str, object]] = []
    for name, cls in seen_order:
        entry: dict[str, object] = {
            "name": name,
            "class": cls,
            "passed": (name, cls) not in failed,
            "details": "see instrumentation-e2e.txt" if (name, cls) in failed else "",
        }
        test_results.append(entry)
    # If we know the count but no per-test status lines were
    # emitted (some AndroidX Test versions), synthesize a stub
    # so the manifest still records the count.
    if test_count is not None and not test_results:
        test_results.append({
            "name": f"<{test_count} tests>",
            "class": "",
            "passed": passed,
            "details": f"summary: {test_count} tests"
                       + (f", {fail_count} failures" if fail_count is not None else ""),
        })
    return passed, test_results
