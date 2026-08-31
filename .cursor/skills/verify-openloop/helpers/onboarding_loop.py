#!/usr/bin/env python3
from __future__ import annotations

import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

PACKAGE = "io.github.stozo04.openloop"
ACTIVITY = f"{PACKAGE}/.MainActivity"
DATASTORE_REL = "files/datastore/openloop_preferences.preferences_pb"
APK_REL = "app/build/outputs/apk/debug/app-debug.apk"

ONBOARDING_MUST_HAVE = [
    "Free. Forever.",
    "No Subscriptions · No Ads",
    "Open source · 100% on your phone",
    "LET'S GO!",
    "Looping demo of a boomerang video",
]
CAMERA_MUST_HAVE = ["Start recording", "Video", "Flip Camera"]
CAMERA_MUST_NOT_HAVE = ["LET'S GO!"]
FACING_PROOF_RE = re.compile(r"Camera bound \(lens=back\)")
FACING_FRONT_RE = re.compile(r"Camera bound \(lens=front\)")


@dataclass(frozen=True)
class UiNode:
    text: str
    desc: str
    bounds: tuple[int, int, int, int] | None


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


def evidence_dir() -> Path:
    base = os.environ.get("VERIFY_EVIDENCE_DIR")
    if base:
        path = Path(base)
    else:
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        path = Path(tempfile.gettempdir()) / "openloop-verify" / stamp / "onboarding"
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
        bounds = parse_bounds(bounds_m.group(1)) if bounds_m else None
        nodes.append(
            UiNode(
                text=decode_entities(text_m.group(1) if text_m else ""),
                desc=decode_entities(desc_m.group(1) if desc_m else ""),
                bounds=bounds,
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
            )
        )
    return nodes


def parse_nodes(xml_text: str) -> list[UiNode]:
    try:
        return parse_nodes_etree(xml_text)
    except ET.ParseError:
        return parse_nodes_regex(xml_text)


def dump_ui(serial: str) -> tuple[str, list[UiNode]]:
    dump_result = run_adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    if dump_result.returncode != 0:
        return "", []
    result = run_adb(serial, "shell", "cat", "/sdcard/ui.xml", check=False)
    xml_text = (result.stdout or "").strip()
    marker = "<hierarchy"
    if marker in xml_text:
        xml_text = xml_text[xml_text.index(marker) :]
    if not xml_text.startswith(marker):
        return "", []
    return xml_text, parse_nodes(xml_text)


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


def tap_node(serial: str, node: UiNode) -> None:
    if not node.bounds:
        fail("no bounds for tap target")
    x1, y1, x2, y2 = node.bounds
    cx = (x1 + x2) // 2
    cy = (y1 + y2) // 2
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


def reset_onboarding_store(serial: str) -> None:
    force_stop(serial)
    run_adb(
        serial,
        "shell",
        "run-as",
        PACKAGE,
        "rm",
        "-f",
        DATASTORE_REL,
        check=False,
    )


def datastore_exists(serial: str) -> bool:
    out = adb_out(
        serial,
        "shell",
        "run-as",
        PACKAGE,
        "ls",
        DATASTORE_REL,
        check=False,
    )
    return DATASTORE_REL.split("/")[-1] in out and "No such file" not in out


def clear_logcat(serial: str) -> None:
    run_adb(serial, "logcat", "-c", check=False)


def start_activity(serial: str) -> None:
    run_adb(serial, "shell", "am", "start", "-n", ACTIVITY)


def wait_until(
    predicate,
    timeout_s: float,
    interval_s: float = 0.5,
) -> bool:
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


def fail(message: str) -> None:
    print(f"FAIL {message}")
    sys.exit(1)


def main() -> int:
    serial = resolve_serial()
    ensure_serial_allowed(serial)

    state = adb_out(serial, "get-state").strip()
    if state != "device":
        fail(f"serial={serial} get-state={state!r}")

    evidence = evidence_dir()
    ensure_installed(serial)
    grant_camera(serial)
    reset_onboarding_store(serial)

    clear_logcat(serial)
    start_activity(serial)

    first_xml = ""
    first_nodes: list[UiNode] = []

    def poll_first_run() -> bool:
        nonlocal first_xml, first_nodes
        first_xml, first_nodes = dump_ui(serial)
        strings = dump_strings(first_nodes)
        blob = dump_blob(first_nodes)
        return all(item in strings or item in blob for item in ONBOARDING_MUST_HAVE)

    if not wait_until(poll_first_run, timeout_s=30.0):
        path = evidence / "first-run.xml"
        path.write_text(first_xml, encoding="utf-8")
        save_screencap(serial, evidence / "first-run.png")
        strings = dump_strings(first_nodes)
        blob = dump_blob(first_nodes)
        missing = [
            item
            for item in ONBOARDING_MUST_HAVE
            if item not in strings and item not in blob
        ]
        fail(
            f"first-run onboarding timeout; missing {missing[0]!r}; evidence={evidence / 'first-run.xml'}"
        )

    (evidence / "first-run.xml").write_text(first_xml, encoding="utf-8")
    save_screencap(serial, evidence / "first-run.png")
    strings = dump_strings(first_nodes)
    blob = dump_blob(first_nodes)
    assert_contains(strings, blob, ONBOARDING_MUST_HAVE, "first-run")

    cta = find_exact(first_nodes, "LET'S GO!")
    if not cta:
        fail(f"first-run: no tap target for LET'S GO!; evidence={evidence / 'first-run.xml'}")
    tap_node(serial, cta)

    after_xml = ""

    def poll_after_cta() -> bool:
        nonlocal after_xml
        after_xml, nodes = dump_ui(serial)
        strings = dump_strings(nodes)
        blob = dump_blob(nodes)
        return "LET'S GO!" not in strings and "LET'S GO!" not in blob and datastore_exists(
            serial
        )

    if not wait_until(poll_after_cta, timeout_s=20.0):
        (evidence / "after-cta.xml").write_text(after_xml, encoding="utf-8")
        _, nodes = dump_ui(serial)
        strings = dump_strings(nodes)
        blob = dump_blob(nodes)
        if "LET'S GO!" in strings or "LET'S GO!" in blob:
            fail(f"after-cta: LET'S GO! still visible; evidence={evidence / 'after-cta.xml'}")
        fail(f"after-cta: datastore file missing; evidence={evidence / 'after-cta.xml'}")

    (evidence / "after-cta.xml").write_text(after_xml, encoding="utf-8")

    force_stop(serial)
    clear_logcat(serial)
    start_activity(serial)

    returning_xml = ""
    returning_nodes: list[UiNode] = []

    def poll_returning() -> bool:
        nonlocal returning_xml, returning_nodes
        returning_xml, returning_nodes = dump_ui(serial)
        strings = dump_strings(returning_nodes)
        blob = dump_blob(returning_nodes)
        if "Grant Permission" in strings or "Grant Permission" in blob:
            fail("returning launch shows Grant Permission — CAMERA was not granted")
        return "Start recording" in strings or "Start recording" in blob

    if not wait_until(poll_returning, timeout_s=30.0):
        path = evidence / "returning.xml"
        path.write_text(returning_xml, encoding="utf-8")
        save_screencap(serial, evidence / "returning.png")
        strings = dump_strings(returning_nodes)
        blob = dump_blob(returning_nodes)
        if "Grant Permission" in strings or "Grant Permission" in blob:
            fail("returning launch shows Grant Permission — CAMERA was not granted")
        fail(
            f"returning launch timeout; missing Start recording; evidence={evidence / 'returning.xml'}"
        )

    (evidence / "returning.xml").write_text(returning_xml, encoding="utf-8")
    save_screencap(serial, evidence / "returning.png")
    strings = dump_strings(returning_nodes)
    blob = dump_blob(returning_nodes)
    assert_contains(strings, blob, CAMERA_MUST_HAVE, "returning")
    assert_absent(strings, blob, CAMERA_MUST_NOT_HAVE, "returning")

    logcat = ""

    def poll_back_camera() -> bool:
        nonlocal logcat
        logcat = adb_out(serial, "logcat", "-d")
        return bool(FACING_PROOF_RE.search(logcat))

    wait_until(poll_back_camera, timeout_s=30.0)
    (evidence / "returning-logcat.txt").write_text(logcat, encoding="utf-8")
    if FACING_FRONT_RE.search(logcat) and not FACING_PROOF_RE.search(logcat):
        fail("returning logcat shows Camera bound (lens=front) but not lens=back")
    if not FACING_PROOF_RE.search(logcat):
        fail(
            "returning logcat missing Camera bound (lens=back); "
            f"see {evidence / 'returning-logcat.txt'}"
        )

    force_stop(serial)
    print(
        f"PASS serial={serial} first-run=onboarding returning=video+back evidence={evidence}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        cmd = " ".join(exc.cmd if isinstance(exc.cmd, list) else [str(exc.cmd)])
        fail(f"adb command failed ({cmd}): {(exc.stderr or exc.stdout or '').strip()}")
