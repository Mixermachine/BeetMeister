#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
VERSION_FILE = "config/protocol_versions.properties"
MAINTENANCE_OVERRIDE_MARKER = "MAINTENANCE_PROTOCOL_CHANGE_APPROVED"

RUNTIME_SURFACE_FILES = {
    "app/app/src/main/java/de/aarondietz/beetmeister/data/protocol/BeetJsonCodec.kt",
    "firmware/esp-idf/components/beet_firmware/include/beet_iface.h",
    "firmware/esp-idf/components/beet_firmware/src/beet_ble_codec.c",
    "firmware/esp-idf/components/beet_firmware/src/beet_iface_names.c",
}

MAINTENANCE_LINE_RE = re.compile(
    r"(maintenance_|maintenance_info|maintenance_status|maintenance_data|"
    r"maintenance_protocol_version|BEET_MAINTENANCE_|"
    r"\"begin_update\"|\"query_status\"|\"abort_update\"|\"finish_update\"|"
    r"asset_id|image_kind)"
)


def git(*args: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        check=check,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
    return completed.stdout.strip()


def resolve_base(base: str | None, head: str) -> str:
    if not base or re.fullmatch(r"0+", base):
        try:
            return git("rev-parse", f"{head}^")
        except subprocess.CalledProcessError:
            return head
    try:
        return git("merge-base", base, head)
    except subprocess.CalledProcessError:
        return base


def parse_properties(revision: str) -> dict[str, str]:
    content = git("show", f"{revision}:{VERSION_FILE}")
    result: dict[str, str] = {}
    for raw_line in content.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def collect_diff(base: str, head: str) -> list[tuple[str, str]]:
    diff_text = git("diff", "--unified=0", "--no-color", f"{base}..{head}")
    entries: list[tuple[str, str]] = []
    current_file: str | None = None
    for line in diff_text.splitlines():
        if line.startswith("diff --git "):
            parts = line.split()
            current_file = parts[3][2:] if len(parts) >= 4 and parts[3].startswith("b/") else None
            continue
        if current_file is None:
            continue
        if line.startswith(("+++", "---", "@@")):
            continue
        if line.startswith("+") or line.startswith("-"):
            entries.append((current_file, line[1:]))
    return entries


def maintenance_override_present(context: str) -> bool:
    return MAINTENANCE_OVERRIDE_MARKER in context


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--context", default="")
    args = parser.parse_args()

    head = args.head
    base = resolve_base(args.base, head)
    diff_entries = collect_diff(base, head)
    changed_files = sorted({path for path, _ in diff_entries})

    runtime_hits: list[str] = []
    maintenance_hits: list[str] = []
    for path, changed_line in diff_entries:
        if path.endswith((".md", ".txt", ".png", ".svg", ".jpg", ".jpeg", ".properties", ".yml", ".yaml")):
            continue
        normalized = changed_line.strip()
        if not normalized:
            continue
        if MAINTENANCE_LINE_RE.search(normalized):
            maintenance_hits.append(path)
            continue
        if path in RUNTIME_SURFACE_FILES:
            runtime_hits.append(path)

    runtime_changed = bool(runtime_hits)
    maintenance_changed = bool(maintenance_hits)

    head_props = parse_properties(head)
    base_props = parse_properties(base) if base != head else head_props

    runtime_bumped = (
        head_props.get("runtime_protocol_version") != base_props.get("runtime_protocol_version")
    )
    override_present = maintenance_override_present(args.context)

    failures: list[str] = []
    if runtime_changed and not runtime_bumped:
        failures.append(
            "Runtime protocol surface changed without a runtime_protocol_version bump."
        )
    if maintenance_changed and not override_present:
        failures.append(
            "Maintenance protocol wire change detected without explicit approval marker "
            f"{MAINTENANCE_OVERRIDE_MARKER}."
        )

    print(f"Protocol guard base={base} head={head}")
    if changed_files:
        print("Changed files:")
        for path in changed_files:
            print(f"  - {path}")
    else:
        print("Changed files: none")
    print(
        "runtime_protocol_version: "
        f"{base_props.get('runtime_protocol_version')} -> {head_props.get('runtime_protocol_version')}"
    )
    print(
        "maintenance_protocol_version: "
        f"{base_props.get('maintenance_protocol_version')} -> {head_props.get('maintenance_protocol_version')}"
    )
    if runtime_hits:
        print("Runtime protocol hits:")
        for path in sorted(set(runtime_hits)):
            print(f"  - {path}")
    if maintenance_hits:
        print("Maintenance protocol hits:")
        for path in sorted(set(maintenance_hits)):
            print(f"  - {path}")
    print(f"Maintenance override marker present: {'yes' if override_present else 'no'}")

    if failures:
        print("")
        for failure in failures:
            print(f"ERROR: {failure}")
        return 1

    print("Protocol guard passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
