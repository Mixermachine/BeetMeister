"""BeetMeister E2E harness CLI entry point.

Modes (mutually exclusive):
    --dry-run            P3 stop point. Walks the full pipeline
                         through the smoke gate and stops before
                         the per-suite E2E class dispatch. Proves
                         build + install + capture + smoke work
                         end-to-end on this machine.
    --dry-run-dispatch   P4 stop point. Smoke gate runs, then
                         the dispatch is COMPOSED (preconditions
                         + am instrument command are recorded in
                         dispatch-plan.json + logged) but NOTHING
                         is executed. Lets the user verify P4
                         wiring on a machine without hardware.
    (no flag)            Real run. P5 territory — may FAIL on
                         hardware until stabilization is done.

Usage:
    python test-harness/run.py --dry-run fresh_install
    python test-harness/run.py --dry-run-dispatch fresh_install
    python test-harness/run.py --dry-run-dispatch firmware_update
    python test-harness/run.py --dry-run-dispatch settings_update
    python test-harness/run.py --dry-run-dispatch combined_pairs
    python test-harness/run.py fresh_install
    python test-harness/run.py firmware_update
    python test-harness/run.py settings_update
    python test-harness/run.py combined_pairs
    python test-harness/run.py firmware_update_abort_disconnect
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

from harness import logging_setup as log_harness
from harness.orchestrator import Orchestrator  # noqa: E402  (path mangling above)
from harness.logging_setup import get_logger

log = get_logger()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="BeetMeister E2E test harness — cross-platform orchestrator"
    )
    parser.add_argument(
        "suite",
        choices=["fresh_install", "firmware_update", "settings_update", "combined_pairs", "firmware_update_abort_disconnect"],
        help="Test suite to run.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="P3 stop point: build + install + capture + smoke gate only; "
             "do not invoke the per-suite E2E class.",
    )
    parser.add_argument(
        "--dry-run-dispatch",
        action="store_true",
        help="P4 stop point: smoke gate runs, then dispatch is COMPOSED "
             "(preconditions + am instrument command recorded in "
             "dispatch-plan.json + logged) but NOTHING is executed. "
             "Use to verify P4 wiring on a machine without hardware.",
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

    orch = Orchestrator()
    result = orch.run(
        args.suite,
        dry_run=args.dry_run,
        dry_run_dispatch=args.dry_run_dispatch,
        skip_install=args.skip_install,
        skip_smoke=args.skip_smoke,
    )

    log.info("=== run.py ===")
    log.info("  run_dir     = %s", result.run_dir)
    log.info("  manifest    = %s", result.manifest_path)
    log.info("  passed      = %s", result.passed)
    if result.smoke is not None:
        log.info("  smoke class = %s", result.smoke.class_name)
        log.info("  smoke pass  = %s", result.smoke.passed)
        log.info("  smoke dur   = %.1fs", result.smoke.duration_s)
    if result.e2e is not None:
        log.info("  e2e class   = %s", result.e2e.class_name)
        log.info("  e2e pass    = %s", result.e2e.passed)
        log.info("  e2e dur     = %.1fs", result.e2e.duration_s)
    if result.dispatch_plan is not None:
        plan = result.dispatch_plan
        n_pre = len(plan.preconditions)
        n_executed = sum(1 for s in plan.preconditions if s.executed)
        executed_str = (
            f"am instrument {'executed' if plan.am_instrument and plan.am_instrument.executed else 'NOT executed'}"
            if plan.am_instrument else ""
        )
        log.info(
            "  dispatch    = %s (%d/%d preconditions executed%s)",
            plan.suite, n_executed, n_pre,
            (", " + executed_str) if executed_str else "",
        )
        log.info("  plan file   = %s", result.run_dir / "dispatch-plan.json")
    if result.fail_reason:
        log.info("  fail reason = %s", result.fail_reason)
    # Also print the log file path so operator can find the debug log.
    lf = log_harness.log_file_path()
    if lf:
        log.info("  log file    = %s", lf)
    return 0 if result.passed else 1


if __name__ == "__main__":
    sys.exit(main())
