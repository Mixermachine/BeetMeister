"""
Bounded adb logcat reader for BeetMeister.

Mirrors artifacts/stage8/serial_reader.py so it has the same hard timeouts.
Use this instead of `adb logcat` directly when calling it from an agent that
would otherwise block on an open stream.

Examples:
    # capture all logcat for up to 30s
    python logcat_reader.py RZCY51LB7BD --max-seconds 30

    # focus on the BeetMeister BLE tags
    python logcat_reader.py RZCY51LB7BD -s MaintenanceUpdatePanel BeetAppViewModel BeetRepository BeetGattSession

    # dump the existing buffer and exit
    python logcat_reader.py RZCY51LB7BD -d --idle-exit 3

    # clear the buffer before reading
    python logcat_reader.py RZCY51LB7BD -c --max-seconds 15

    # forward adb logcat -s style filters verbatim
    python logcat_reader.py RZCY51LB7BD -- -s BeetGattSession:V *:S
"""

import argparse
import shlex
import subprocess
import sys
import time


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Read adb logcat with hard timeouts. Always exits on its own.",
    )
    parser.add_argument("serial", nargs="?", default=None,
                        help="Target device serial (passed via adb -s). Omit to use the only attached device.")
    parser.add_argument("-c", "--clear", action="store_true",
                        help="Run `adb logcat -c` first to clear the buffer.")
    parser.add_argument("-d", "--dump", action="store_true",
                        help="Use `adb logcat -d` (dump buffer and exit). "
                             "Recommended with --idle-exit so the call returns promptly.")
    parser.add_argument("--format", default="threadtime",
                        help="adb logcat -v format (default: threadtime).")
    parser.add_argument("--max-seconds", type=float, default=30.0,
                        help="Exit after this many seconds even if data is still arriving. 0 = run until Ctrl-C.")
    parser.add_argument("--idle-exit", type=float, default=0.0,
                        help="Exit after this many seconds of zero received data. 0 = disabled.")
    parser.add_argument("--read-timeout", type=float, default=0.2,
                        help="Per-read timeout in seconds. Kept small so idle-exit can fire promptly.")
    parser.add_argument("--adb", default="adb",
                        help="Path to the adb executable (default: adb from PATH).")
    return parser


def main() -> int:
    args, extra = build_parser().parse_known_args()

    adb_base = [args.adb]
    if args.serial:
        adb_base += ["-s", args.serial]

    if args.clear:
        clear_cmd = adb_base + ["logcat", "-c"]
        print(" ".join(shlex.quote(p) for p in clear_cmd), file=sys.stderr, flush=True)
        clear_proc = subprocess.run(clear_cmd, capture_output=True, text=True)
        if clear_proc.returncode != 0:
            print(f"adb logcat -c failed (rc={clear_proc.returncode}): "
                  f"{clear_proc.stderr.strip() or clear_proc.stdout.strip()}",
                  file=sys.stderr, flush=True)
            return clear_proc.returncode

    cmd = adb_base + ["logcat", "-v", args.format]
    if args.dump:
        cmd.append("-d")
    cmd.extend(extra)
    print(" ".join(shlex.quote(p) for p in cmd), file=sys.stderr, flush=True)

    start = time.monotonic()
    last_data = start
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                bufsize=0)
    except FileNotFoundError:
        print(f"adb executable not found: {args.adb}", file=sys.stderr, flush=True)
        return 2

    exit_code = 0
    try:
        while True:
            if args.max_seconds and (time.monotonic() - start) >= args.max_seconds:
                break
            try:
                chunk = proc.stdout.read(4096)  # type: ignore[union-attr]
            except ValueError:
                # stdout was closed (e.g. process exited and we drained the buffer).
                break
            if chunk:
                sys.stdout.buffer.write(chunk)
                sys.stdout.flush()
                last_data = time.monotonic()
                continue
            if proc.poll() is not None:
                # adb logcat exited on its own (e.g. -d finished, device disconnected, or filter rejected).
                break
            if args.idle_exit and (time.monotonic() - last_data) >= args.idle_exit:
                break
    except KeyboardInterrupt:
        pass
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=2.0)
            except subprocess.TimeoutExpired:
                proc.kill()
                try:
                    proc.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    pass
        exit_code = proc.returncode if proc.returncode is not None else 0

    elapsed = time.monotonic() - start
    print(f"# logcat_reader: exit after {elapsed:.1f}s, rc={exit_code}",
          file=sys.stderr, flush=True)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
