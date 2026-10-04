---
name: android-screenshot-capture
description: Capture Android screenshots cross-platform (Windows, macOS, Linux) without CRLF byte corruption or scoped storage permission failures. Covers host Python runner and in-test Kotlin helper.
---

# Android Screenshot Capture

## Two Tiers

### Tier 1: Host Python Runner (Manual / Shell)
- Script: `scripts/dev/take-screenshot.py`
- Run: `python scripts/dev/take-screenshot.py [name]`
- Output: `artifacts/screenshots/<name>-<timestamp>.png`
- Raw binary mode: no CRLF break on Windows.

### Tier 2: In-Test Kotlin Helper (E2E)
- Class: `E2eScreenshotHelper`
- Call: `fixture.screenshots.captureStep("step_name")`
- Path: `/sdcard/Android/data/de.aarondietz.beetmeister/files/e2e_screenshots/<slug>/`
- Pull: `adb pull /sdcard/Android/data/de.aarondietz.beetmeister/files/e2e_screenshots/ artifacts/screenshots/`
- Git Bash only: prepend `MSYS_NO_PATHCONV=1`

## Hard Rules
1. Never pipe `adb exec-out ... > file.png` in Windows terminal (CRLF corrupts bytes). Use script.
2. Never write `/sdcard/*.png` direct (Android 10+ scoped storage denies). Use app external dir or `/data/local/tmp/`.
3. Wrap in-test writes in `runCatching` (screenshots never fail tests).
