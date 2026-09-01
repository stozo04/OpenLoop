#!/usr/bin/env python3
"""Shared adb/uiautomator plumbing for the `*_loop.py` verifiers in this directory.

Extracted when the second real loop (`record_clip_loop.py`) landed and duplicated ~200 lines of
`onboarding_loop.py` verbatim — the point the create-verifier skill names for extracting, and not
before. Nothing feature-specific lives here: a loop owns its own strings, assertions and evidence.

Not a loop itself. `scripts/run-verification-loops.py` discovers `*_loop.py`, so this file is
never executed as a verifier; the loops import it as a sibling module.
"""
from __future__ import annotations

import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

PACKAGE = "io.github.stozo04.openloop"
ACTIVITY = f"{PACKAGE}/.MainActivity"
APK_REL = "app/build/outputs/apk/debug/app-debug.apk"


@dataclass(frozen=True)
class UiNode:
    text: str
    desc: str
    bounds: tuple[int, int, int, int] | None
    # Owning package. A dump is the whole screen, so the system status bar (its clock above all)
    # and the navigation bar come back alongside the app — filter on this before asserting that
    # some text is or is not on screen "in the app".
    pkg: str = ""
    # Selection/toggle state. A Compose `selectable`/`toggleable` reaches uiautomator as
    # checkable="true" on the wrapper node, with `checked` carrying whether it is the selected
    # one — the label leaf and the RadioButton-class leaf both report checked="false" whatever
    # is selected, so this pair is the only place a segmented control's state is readable.
    checkable: bool = False
    checked: bool = False


def fail(message: str) -> None:
    print(f"FAIL {message}")
    sys.exit(1)


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent.parent.parent


def run_adb(serial: str, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    cmd = ["adb", "-s", serial, *args]
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=check,
    )


def adb_out(serial: str, *args: str, check: bool = True) -> str:
    result = run_adb(serial, *args, check=check)
    return (result.stdout or "") + (result.stderr or "")


def resolve_serial() -> str:
    if not shutil.which("adb"):
        fail("adb not found on PATH")
    env_serial = os.environ.get("VERIFY_SERIAL", "").strip()
    if env_serial:
        return env_serial

    devices_out = subprocess.run(
        ["adb", "devices"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=True,
    ).stdout
    emulators = [
        line.split()[0]
        for line in devices_out.splitlines()
        if re.match(r"emulator-\d+\s+device$", line)
    ]
    if len(emulators) == 1:
        return emulators[0]
    if len(emulators) > 1:
        fail(f"multiple emulators: {', '.join(emulators)}; set VERIFY_SERIAL")

    physical = [
        line.split()[0]
        for line in devices_out.splitlines()
        if re.search(r"\s+device$", line) and not line.startswith("emulator-")
    ]
    if physical:
        if os.environ.get("VERIFY_ALLOW_DEVICE") == "1" and env_serial:
            return env_serial
        fail(
            f"physical device {physical[0]} attached; start an emulator or set "
            "VERIFY_ALLOW_DEVICE=1 and VERIFY_SERIAL"
        )
    fail("no emulator or device (adb devices)")


def ensure_serial_allowed(serial: str) -> None:
    if serial.startswith("emulator-"):
        return
    if os.environ.get("VERIFY_ALLOW_DEVICE") == "1":
        return
    fail(f"serial {serial} is not an emulator; set VERIFY_ALLOW_DEVICE=1 for a test phone")


def require_online(serial: str) -> None:
    state = adb_out(serial, "get-state").strip()
    if state != "device":
        fail(f"serial={serial} get-state={state!r}")


def evidence_dir(feature: str) -> Path:
    base = os.environ.get("VERIFY_EVIDENCE_DIR")
    if base:
        path = Path(base)
    else:
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        path = Path(tempfile.gettempdir()) / "openloop-verify" / stamp / feature
    path.mkdir(parents=True, exist_ok=True)
    return path


def decode_entities(value: str) -> str:
    if not value:
        return value
    return html.unescape(value)


def parse_bounds(raw: str) -> tuple[int, int, int, int] | None:
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw)
    if not match:
        return None
    return tuple(int(g) for g in match.groups())  # type: ignore[return-value]


def parse_nodes_regex(xml_text: str) -> list[UiNode]:
    nodes: list[UiNode] = []
    for match in re.finditer(r"<node[^>]*>", xml_text):
        fragment = match.group(0)
        text_m = re.search(r'text="([^"]*)"', fragment)
        desc_m = re.search(r'content-desc="([^"]*)"', fragment)
        bounds_m = re.search(r'bounds="(\[[^\]]+\]\[[^\]]+\])"', fragment)
        pkg_m = re.search(r'package="([^"]*)"', fragment)
        checkable_m = re.search(r'checkable="([^"]*)"', fragment)
        checked_m = re.search(r'checked="([^"]*)"', fragment)
        bounds = parse_bounds(bounds_m.group(1)) if bounds_m else None
        nodes.append(
            UiNode(
                text=decode_entities(text_m.group(1) if text_m else ""),
                desc=decode_entities(desc_m.group(1) if desc_m else ""),
                bounds=bounds,
                pkg=pkg_m.group(1) if pkg_m else "",
                checkable=bool(checkable_m) and checkable_m.group(1) == "true",
                checked=bool(checked_m) and checked_m.group(1) == "true",
            )
        )
    return nodes


def parse_nodes_etree(xml_text: str) -> list[UiNode]:
    nodes: list[UiNode] = []
    root = ET.fromstring(xml_text)
    for elem in root.iter("node"):
        bounds = parse_bounds(elem.attrib.get("bounds", ""))
        nodes.append(
            UiNode(
                text=decode_entities(elem.attrib.get("text", "")),
                desc=decode_entities(elem.attrib.get("content-desc", "")),
                bounds=bounds,
                pkg=elem.attrib.get("package", ""),
                checkable=elem.attrib.get("checkable") == "true",
                checked=elem.attrib.get("checked") == "true",
            )
        )
    return nodes


def parse_nodes(xml_text: str) -> list[UiNode]:
    try:
        return parse_nodes_etree(xml_text)
    except ET.ParseError:
        return parse_nodes_regex(xml_text)


def dump_ui(serial: str) -> tuple[str, list[UiNode]]:
    """Current hierarchy, or ("", []) when uiautomator could not produce one.

    The dump file is only read when THIS dump wrote it: uiautomator leaves the previous XML in
    place when it fails ("ERROR: null root node returned by UiTestAutomationBridge", seen on a
    busy camera preview), and reading that back would assert against a screen that is gone.
    """
    dump_result = run_adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    out = (dump_result.stdout or "") + (dump_result.stderr or "")
    if dump_result.returncode != 0 or "dumped to" not in out:
        return "", []
    result = run_adb(serial, "shell", "cat", "/sdcard/ui.xml", check=False)
    xml_text = (result.stdout or "").strip()
    marker = "<hierarchy"
    if marker in xml_text:
        xml_text = xml_text[xml_text.index(marker) :]
    if not xml_text.startswith(marker):
        return "", []
    return xml_text, parse_nodes(xml_text)


def app_nodes(nodes: list[UiNode]) -> list[UiNode]:
    """Only the nodes OpenLoop drew — no system status bar, no navigation bar."""
    return [node for node in nodes if node.pkg == PACKAGE]


def dump_strings(nodes: list[UiNode]) -> set[str]:
    values: set[str] = set()
    for node in nodes:
        if node.text:
            values.add(node.text)
        if node.desc:
            values.add(node.desc)
    return values


def dump_blob(nodes: list[UiNode]) -> str:
    parts: list[str] = []
    for node in nodes:
        if node.text:
            parts.append(node.text)
        if node.desc:
            parts.append(node.desc)
    return "\n".join(parts)


def find_exact(nodes: list[UiNode], label: str) -> UiNode | None:
    for node in nodes:
        if not node.bounds:
            continue
        if node.text == label or node.desc == label:
            return node
    return None


def center(node: UiNode) -> tuple[int, int]:
    if not node.bounds:
        fail("no bounds for tap target")
    x1, y1, x2, y2 = node.bounds
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_node(serial: str, node: UiNode) -> None:
    cx, cy = center(node)
    run_adb(serial, "shell", "input", "tap", str(cx), str(cy))


def save_screencap(serial: str, path: Path) -> None:
    proc = subprocess.run(
        ["adb", "-s", serial, "exec-out", "screencap", "-p"],
        capture_output=True,
        check=True,
    )
    path.write_bytes(proc.stdout)


def package_installed(serial: str) -> bool:
    out = adb_out(serial, "shell", "pm", "path", PACKAGE, check=False)
    return "package:" in out


def ensure_installed(serial: str) -> None:
    apk = repo_root() / APK_REL
    if not apk.is_file():
        if package_installed(serial):
            return
        fail(f"{PACKAGE} not installed and debug APK missing at {apk}")
    install = subprocess.run(
        ["adb", "-s", serial, "install", "-r", "-g", str(apk)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    combined = (install.stdout or "") + (install.stderr or "")
    if install.returncode != 0:
        fail(f"adb install failed: {combined.strip()}")
    if not package_installed(serial):
        fail(f"adb install reported success but {PACKAGE} is still missing")


def grant_camera(serial: str) -> None:
    run_adb(serial, "shell", "pm", "grant", PACKAGE, "android.permission.CAMERA")


def force_stop(serial: str) -> None:
    run_adb(serial, "shell", "am", "force-stop", PACKAGE)


def clear_logcat(serial: str) -> None:
    run_adb(serial, "logcat", "-c", check=False)


def start_activity(serial: str) -> None:
    run_adb(serial, "shell", "am", "start", "-n", ACTIVITY)


def wait_until(predicate, timeout_s: float, interval_s: float = 0.5) -> bool:
    import time

    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval_s)
    return False


def assert_contains(strings: set[str], blob: str, required: list[str], context: str) -> None:
    missing = [item for item in required if item not in strings and item not in blob]
    if missing:
        fail(f"{context}: missing {missing[0]!r} (also checked: {missing[1:]})")


def assert_absent(strings: set[str], blob: str, forbidden: list[str], context: str) -> None:
    for item in forbidden:
        if item in strings or item in blob:
            fail(f"{context}: must not contain {item!r}")
