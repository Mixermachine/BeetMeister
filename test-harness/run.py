"""BeetMeister E2E harness CLI entry point.

P3 usage:
    python test-harness/run.py --dry-run fresh_install
        Walks the full pipeline through the smoke gate
        (MaintenanceUpdateInstrumentationTest) and stops before
        the per-suite E2E class dispatch. Proves build + install
        + capture + smoke work end-to-end on this machine.

P4 usage (NOT in P3):
    python test-harness/run.py fresh_install
    python test-harness/run.py firmware_update
    python test-harness/run.py settings_update
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Allow `python test-harness/run.py` to find the harness package
# without an editable install. The harness lives at
# test-harness/harness/, so its parent is `test-harness/`.
_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from harness.orchestrator import Orchestrator  # noqa: E402  (path mangling above)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="BeetMeister E2E test harness — cross-platform orchestrator"
    )
    parser.add_argument(
        "suite",
        choices=["fresh_install", "firmware_update", "settings_update"],
        help="Test suite to run. P3 only supports --dry-run; "
             "real dispatch arrives in P4.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="P3 stop point: build + install + capture + smoke gate only; "
             "do not invoke the per-suite E2E class.",
    )
    parser.add_argument(
        "--skip-install",
        action="store_true",
        help="Skip gradle build + adb install (use already-installed APKs).",
    )
    parser.add_argument(
        "--skip-smoke",
        action="store_true",
        help="Skip the pure-UI smoke gate. NOT RECOMMENDED in P3; "
             "the dry-run exit criterion depends on the gate actually running.",
    )
    args = parser.parse_args(argv)

    if not args.dry_run and args.suite in ("firmware_update", "settings_update"):
        parser.error(
            f"non-dry-run {args.suite} arrives in P4. "
            f"Use --dry-run for the P3 stop point."
        )

    orch = Orchestrator()
    result = orch.run(
        args.suite,
        dry_run=args.dry_run,
        skip_install=args.skip_install,
        skip_smoke=args.skip_smoke,
    )

    print()
    print(f"=== run.py ===")
    print(f"  run_dir     = {result.run_dir}")
    print(f"  manifest    = {result.manifest_path}")
    print(f"  passed      = {result.passed}")
    if result.smoke is not None:
        print(f"  smoke class = {result.smoke.class_name}")
        print(f"  smoke pass  = {result.smoke.passed}")
        print(f"  smoke dur   = {result.smoke.duration_s:.1f}s")
    if result.e2e is not None:
        print(f"  e2e class   = {result.e2e.class_name}")
        print(f"  e2e pass    = {result.e2e.passed}")
        print(f"  e2e dur     = {result.e2e.duration_s:.1f}s")
    if result.fail_reason:
        print(f"  fail reason = {result.fail_reason}")
    print()
    return 0 if result.passed else 1


if __name__ == "__main__":
    sys.exit(main())
