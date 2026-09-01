#!/usr/bin/env python3
"""Autonomous verifier for `features/onboarding.md` — first run through `LET'S GO!`.

First run shows the onboarding pitch; tapping the CTA writes the DataStore flag and lands on the
camera; a relaunch goes straight to the viewfinder on the back lens with no onboarding and no
permission rationale.

    python .claude/skills/verify-openloop/helpers/onboarding_loop.py

    VERIFY_SERIAL=emulator-5556   pick a device when more than one is online
    VERIFY_EVIDENCE_DIR=<dir>     where the XML/PNG/logcat evidence lands

The adb/uiautomator plumbing lives in `verify_common.py`, shared with the other loops here.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verify_common import (  # noqa: E402
    PACKAGE,
    adb_out,
    assert_absent,
    assert_contains,
    clear_logcat,
    dump_blob,
    dump_strings,
    dump_ui,
    ensure_installed,
    ensure_serial_allowed,
    evidence_dir,
    fail,
    find_exact,
    force_stop,
    grant_camera,
    require_online,
    resolve_serial,
    run_adb,
    save_screencap,
    start_activity,
    tap_node,
    wait_until,
)

DATASTORE_REL = "files/datastore/openloop_preferences.preferences_pb"

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


def reset_onboarding_store(serial: str) -> None:
    force_stop(serial)
    run_adb(serial, "shell", "run-as", PACKAGE, "rm", "-f", DATASTORE_REL, check=False)


def datastore_exists(serial: str) -> bool:
    out = adb_out(serial, "shell", "run-as", PACKAGE, "ls", DATASTORE_REL, check=False)
    return DATASTORE_REL.split("/")[-1] in out and "No such file" not in out


def main() -> int:
    serial = resolve_serial()
    ensure_serial_allowed(serial)
    require_online(serial)

    evidence = evidence_dir("onboarding")
    ensure_installed(serial)
    grant_camera(serial)
    reset_onboarding_store(serial)

    clear_logcat(serial)
    start_activity(serial)

    first_xml = ""
    first_nodes: list = []

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
    returning_nodes: list = []

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
