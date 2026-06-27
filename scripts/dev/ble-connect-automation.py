#!/usr/bin/env python3
"""
BLE Connect Automation for BeetMeister

Uses ADB + uiautomator to automate the Android app's BLE scan-and-connect flow.
Steps:
  1. Launch/return to BeetMeister app
  2. Grant BT permissions if needed
  3. Enable Bluetooth if disabled
  4. Dump UI hierarchy, find and tap "Scan" button
  5. Wait for device "beetmeister-01" to appear in scan results
  6. Tap "Connect" button on the device card
  7. Wait for bonding + GATT connection to complete

Usage:
  python ble-connect-automation.py [--skip-connect] [--timeout 120]
"""

import subprocess
import sys
import time
import re
import os
import argparse
from xml.etree import ElementTree as ET

APP_PACKAGE = "de.aarondietz.beetmeister"
APP_ACTIVITY = ".MainActivity"
DEVICE_NAME = "beetmeister-01"
BOND_TIMEOUT = 40  # seconds to wait for bonding
SCAN_TIMEOUT = 30  # seconds to wait for scan results
POLL_INTERVAL = 1.0  # seconds between UI polls
TAP_DELAY = 0.5  # seconds between taps


def adb(cmd, check=True, timeout=30):
    """Run an adb command and return stdout."""
    full = ["adb"] + cmd
    proc = subprocess.run(full, capture_output=True, text=True, timeout=timeout)
    if check and proc.returncode != 0:
        print(f"  [WARN] adb {' '.join(cmd)} returned {proc.returncode}: {proc.stderr.strip()}")
    return proc.stdout.strip()


def adb_shell(cmd, check=False):
    """Run an adb shell command."""
    return adb(["shell"] + cmd, check=check)


def adb_tap(x, y):
    """Tap at screen coordinates."""
    adb_shell(["input", "tap", str(x), str(y)])


def get_ui_xml():
    """Dump the current UI hierarchy and return as parsed XML ElementTree."""
    tmp = "/sdcard/ui_dump.xml"
    adb_shell(["uiautomator", "dump", tmp])
    xml_str = adb(["shell", "cat", tmp])
    adb_shell(["rm", tmp], check=False)

    if not xml_str:
        return None

    # uiautomator dump output includes "UI hierarchy dumped to: ..." prefix
    # Find the XML start
    xml_start = xml_str.find("<?xml")
    if xml_start < 0:
        xml_start = xml_str.find("<hierarchy")
    if xml_start < 0:
        print(f"  [WARN] Could not find XML in dump output: {xml_str[:200]}")
        return None

    xml_str = xml_str[xml_start:]
    try:
        return ET.fromstring(xml_str)
    except ET.ParseError as e:
        print(f"  [WARN] XML parse error: {e}")
        return None


def find_clickable(root, text=None, content_desc=None, resource_id=None, class_name=None):
    """Find a clickable node by text, content-desc, resource-id, or class name.
    Returns list of (bounds_dict, node) tuples where bounds_dict has x1, y1, x2, y2 as ints."""
    results = []
    for node in root.iter("node"):
        attrs = node.attrib
        is_clickable = attrs.get("clickable") == "true"
        is_enabled = attrs.get("enabled") != "false"
        if not is_clickable or not is_enabled:
            continue

        match = True
        if text is not None and attrs.get("text", "") != text:
            match = False
        if content_desc is not None and attrs.get("content-desc", "") != content_desc:
            match = False
        if resource_id is not None:
            rid = attrs.get("resource-id", "")
            if not rid.endswith(resource_id):
                match = False
        if class_name is not None:
            cls = attrs.get("class", "")
            if not cls.endswith(class_name):
                match = False

        if match:
            bounds_str = attrs.get("bounds", "")
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str)
            if m:
                bounds = {"x1": int(m.group(1)), "y1": int(m.group(2)),
                          "x2": int(m.group(3)), "y2": int(m.group(4))}
                results.append((bounds, node))
    return results


def tap_center(bounds):
    """Tap the center of given bounds dict."""
    x = (bounds["x1"] + bounds["x2"]) // 2
    y = (bounds["y1"] + bounds["y2"]) // 2
    adb_tap(x, y)
    return x, y


def launch_app():
    """Launch/return to the BeetMeister app."""
    print("[*] Launching BeetMeister app...")
    adb_shell(["am", "start", "-n", f"{APP_PACKAGE}/{APP_ACTIVITY}"])
    time.sleep(3.0)


def grant_permissions():
    """Grant all required BLE permissions."""
    print("[*] Granting BLE permissions...")
    perms = [
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH",
        "android.permission.ACCESS_FINE_LOCATION",
    ]
    for p in perms:
        adb_shell(["pm", "grant", APP_PACKAGE, p], check=False)
    time.sleep(1.0)


def enable_bluetooth():
    """Enable Bluetooth if disabled."""
    result = adb_shell(["settings", "get", "global", "bluetooth_on"])
    if result != "1":
        print("[*] Enabling Bluetooth...")
        adb_shell(["svc", "bluetooth", "enable"])
        time.sleep(3.0)
        # Also try via am start for the system dialog
        adb_shell(["am", "start", "-a", "android.bluetooth.adapter.action.REQUEST_ENABLE"], check=False)
        time.sleep(2.0)


def wait_for_phase(phase_text, timeout):
    """Poll UI until a specific text appears (phase label like 'Scanning' or 'Connected')."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        root = get_ui_xml()
        if root is None:
            time.sleep(POLL_INTERVAL)
            continue
        for node in root.iter("node"):
            text = node.attrib.get("text", "")
            if phase_text.lower() in text.lower():
                print(f"  [OK] Found phase: '{text}'")
                return True
        time.sleep(POLL_INTERVAL)
    print(f"  [WARN] Phase '{phase_text}' not found within {timeout}s")
    return False


def tap_button(label, timeout=10):
    """Find and tap a button by text label."""
    print(f"[*] Looking for button: '{label}'...")
    deadline = time.time() + timeout
    while time.time() < deadline:
        root = get_ui_xml()
        if root is None:
            time.sleep(POLL_INTERVAL)
            continue

        # Search by text
        buttons = find_clickable(root, text=label)
        if not buttons:
            # Also search by content-desc
            buttons = find_clickable(root, content_desc=label)
        if not buttons:
            # Try case-insensitive substring match
            for node in root.iter("node"):
                t = node.attrib.get("text", "")
                cd = node.attrib.get("content-desc", "")
                if label.lower() in t.lower() or label.lower() in cd.lower():
                    bounds_str = node.attrib.get("bounds", "")
                    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str)
                    if m:
                        buttons = [({"x1": int(m.group(1)), "y1": int(m.group(2)),
                                     "x2": int(m.group(3)), "y2": int(m.group(4))}, node)]
                        break

        if buttons:
            x, y = tap_center(buttons[0][0])
            print(f"  [OK] Tapped '{label}' at ({x}, {y})")
            time.sleep(TAP_DELAY)
            return True

        time.sleep(POLL_INTERVAL)

    print(f"  [WARN] Button '{label}' not found within {timeout}s")
    return False


def wait_for_device_card(device_name, timeout=SCAN_TIMEOUT):
    """Wait for a device card with the given name to appear in scan results."""
    print(f"[*] Waiting for device '{device_name}' in scan results...")
    deadline = time.time() + timeout
    while time.time() < deadline:
        root = get_ui_xml()
        if root is None:
            time.sleep(POLL_INTERVAL)
            continue

        for node in root.iter("node"):
            text = node.attrib.get("text", "")
            content = node.attrib.get("content-desc", "")
            if device_name in text or device_name in content:
                # Found the device name - now find the Connect button nearby
                # The Connect button is typically a sibling or child
                print(f"  [OK] Found device: '{text or content}'")
                return True
        time.sleep(POLL_INTERVAL)

    print(f"  [WARN] Device '{device_name}' not found in scan results within {timeout}s")
    return False


def tap_connect_on_device(device_name):
    """Tap the Connect button on the device card for device_name."""
    print(f"[*] Tapping Connect on '{device_name}'...")
    root = get_ui_xml()
    if root is None:
        return False

    # Strategy: find the device name text node, then navigate to find Connect button
    # Connect button has text "Connect" or "Connecting..."
    connect_buttons = find_clickable(root, text="Connect")
    if not connect_buttons:
        connect_buttons = find_clickable(root, text="Connecting...")
    if not connect_buttons:
        # Try partial match
        for node in root.iter("node"):
            t = node.attrib.get("text", "")
            if "onnect" in t:
                bounds_str = node.attrib.get("bounds", "")
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str)
                if m:
                    connect_buttons = [({"x1": int(m.group(1)), "y1": int(m.group(2)),
                                         "x2": int(m.group(3)), "y2": int(m.group(4))}, node)]
                    break

    if connect_buttons:
        x, y = tap_center(connect_buttons[0][0])
        print(f"  [OK] Tapped Connect at ({x}, {y})")
        time.sleep(TAP_DELAY)
        return True

    # Fallback: try to find any "Button" class with Connect-like text
    for node in root.iter("node"):
        cls = node.attrib.get("class", "")
        text = node.attrib.get("text", "")
        if "Button" in cls and ("onnect" in text or "air" in text.lower()):
            bounds_str = node.attrib.get("bounds", "")
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str)
            if m:
                x, y = (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2
                adb_tap(x, y)
                print(f"  [OK] Fallback tap at ({x}, {y}) on '{text}'")
                time.sleep(TAP_DELAY)
                return True

    print("  [ERROR] Could not find Connect button")
    return False


def wait_for_bonding(timeout=BOND_TIMEOUT):
    """Wait for bonding to complete (UI shows 'Connected' or similar)."""
    print(f"[*] Waiting for bonding/connection (timeout={timeout}s)...")
    return wait_for_phase("Connected", timeout) or wait_for_phase("Overview", timeout) or wait_for_phase("Settings", timeout)


def main():
    parser = argparse.ArgumentParser(description="Automate BeetMeister BLE connect flow")
    parser.add_argument("--skip-connect", action="store_true", help="Only grant permissions and scan, don't connect")
    parser.add_argument("--timeout", type=int, default=120, help="Total timeout in seconds")
    parser.add_argument("--bond-timeout", type=int, default=50, help="Bonding timeout in seconds")
    args = parser.parse_args()

    overall_deadline = time.time() + args.timeout
    print("=" * 60)
    print("BeetMeister BLE Connect Automation")
    print("=" * 60)

    # Step 1: Grant permissions
    grant_permissions()

    # Step 2: Launch app
    launch_app()

    # Step 3: Enable Bluetooth if needed
    enable_bluetooth()

    # Step 4: Wait for app to settle, then look for and tap "Scan"
    time.sleep(2.0)
    if not tap_button("Scan", timeout=15):
        print("[*] Trying 'Retry' button instead...")
        tap_button("Retry", timeout=5)

    # Step 5: Wait for device to appear in scan results
    if not wait_for_device_card(DEVICE_NAME, timeout=30):
        print("[*] Device not found. Trying Scan again...")
        tap_button("Scan", timeout=5)
        if not wait_for_device_card(DEVICE_NAME, timeout=30):
            print("[ERROR] Could not find device. Aborting.")
            sys.exit(1)

    # Step 6: Connect
    if not args.skip_connect:
        if not tap_connect_on_device(DEVICE_NAME):
            sys.exit(1)

        # Step 7: Wait for bonding
        if wait_for_bonding(timeout=args.bond_timeout):
            print("\n[SUCCESS] Bonding completed!")
            sys.exit(0)
        else:
            print("\n[FAIL] Bonding did not complete within timeout")
            sys.exit(1)
    else:
        print("\n[DONE] Scan only mode. Device found.")
        sys.exit(0)


if __name__ == "__main__":
    main()
