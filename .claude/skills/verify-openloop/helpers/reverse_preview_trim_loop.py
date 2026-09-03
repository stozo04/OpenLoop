#!/usr/bin/env python3
"""Installed-APK verifier for issue #170: reverse after a non-keyframe trim start.

Records a fresh clip through the real camera UI, moves the trim start away from zero, enters the
default Forward-then-reverse editor, and requires reverse preview to finish within 30 seconds.
The run is rejected as vacuous unless the trim start landed BETWEEN sync samples, which is the
condition #170 wedged on. Do not lengthen the deadline or weaken the assertions to turn it green.

    python .claude/skills/verify-openloop/helpers/reverse_preview_trim_loop.py

    VERIFY_SERIAL=emulator-5556   pick a device when more than one is online
    VERIFY_EVIDENCE_DIR=<dir>     where XML/PNG/logcat evidence lands
"""
from __future__ import annotations

import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from record_clip_loop import (  # noqa: E402
    Chip,
    Scratch,
    shutter,
    snapshot,
    wait_for_camera_idle,
    wait_for_trim,
)
from verify_common import (  # noqa: E402
    PACKAGE,
    adb_out,
    center,
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
    swipe,
    tap_node,
    wait_until,
)

TRIM_RANGE_RE = re.compile(r"([0-9]+(?:\.[0-9]+)?) seconds to ([0-9]+(?:\.[0-9]+)?) seconds")
REVERSE_START_RE = re.compile(r"viewModel\.ensureReversed\.start")
REVERSE_OK_RE = re.compile(
    r"viewModel\.ensureReversed\.ok \| .*?file=([^\s]+) bytes=(\d+)",
)
# Pass 1 logs where SEEK_TO_PREVIOUS_SYNC actually landed. sync < trimStart means the decoder had to
# preroll — the exact path #170 broke. sync == trimStart means the drag happened to land on a
# keyframe, so a green run would prove nothing.
PREROLL_RE = re.compile(r"pass1\.preroll \| sync=(\d+)us trimStart=(\d+)us")
REVERSE_FAILURE_RE = re.compile(
    r"viewModel\.ensureReversed\.(?:timeout|fail)|reverse\.(?:failed|exhausted)",
)
LOADING_LABELS = ["Trimming..", "Loopifying.."]
FAILURE_LABELS = ["Couldn't loop that clip", "TRY AGAIN", "SEND DEBUG REPORT"]
REVERSE_DEADLINE_S = 30.0
SCRATCH_ROOTS = ("files/scratch", "cache/scratch")


def app_files(serial: str) -> set[str]:
    out = adb_out(
        serial,
        "shell",
        "run-as",
        PACKAGE,
        "find",
        *SCRATCH_ROOTS,
        "-type",
        "f",
        check=False,
    )
    return {
        line.strip()
        for line in out.splitlines()
        if line.strip().startswith(SCRATCH_ROOTS)
    }


def remove_run_files(serial: str, baseline: set[str]) -> None:
    """Remove only scratch/cache files created by this verifier, never prior user media."""
    for path in sorted(app_files(serial) - baseline):
        run_adb(serial, "shell", "run-as", PACKAGE, "rm", "-f", path, check=False)


def reverse_logcat(serial: str) -> str:
    return adb_out(
        serial,
        "logcat",
        "-d",
        "-v",
        "time",
        "-s",
        "OpenLoopReverse:*",
        "VideoReverser:*",
        "OpenLoopViewModel:*",
    )


def record_fixture(serial: str, evidence: Path, scratch: Scratch) -> str:
    _, nodes = wait_for_camera_idle(serial, evidence, "reverse-camera")
    chip = Chip()
    tap_node(serial, shutter(serial, nodes, "Start recording", "reverse-record", evidence))

    seen = {"xml": "", "nodes": []}

    def recorded_two_seconds() -> bool:
        seen["xml"], seen["nodes"] = dump_ui(serial)
        if "Stop recording" not in dump_strings(seen["nodes"]):
            return False
        seconds = chip.observe(seen["nodes"], "reverse-record")
        return seconds is not None and seconds >= 2

    if not wait_until(recorded_two_seconds, timeout_s=90.0, interval_s=1.0):
        path = snapshot(serial, evidence, "recording-stalled", seen["xml"])
        fail(f"record: countdown never reached 2s; evidence={path}")
    snapshot(serial, evidence, "recording", seen["xml"])
    tap_node(
        serial,
        shutter(serial, seen["nodes"], "Stop recording", "reverse-record", evidence),
    )

    trim_xml = wait_for_trim(serial, chip, "reverse-stop", timeout_s=90.0)
    if not trim_xml:
        xml, _ = dump_ui(serial)
        path = snapshot(serial, evidence, "trim-missing", xml)
        fail(f"record: stop did not open Trim; evidence={path}")
    snapshot(serial, evidence, "trim-before", trim_xml)

    clips = scratch.new(serial)
    if len(clips) != 1:
        fail(f"record: expected one new scratch clip, found {clips}")
    clip = clips[0]
    source_path = f"files/scratch/{clip}"
    source_receipt = adb_out(
        serial, "shell", "run-as", PACKAGE, "ls", "-l", source_path, check=False
    ) + adb_out(
        serial, "shell", "run-as", PACKAGE, "sha256sum", source_path, check=False
    )
    (evidence / "source.txt").write_text(source_receipt, encoding="utf-8")
    return clip


def move_trim_start(serial: str, evidence: Path) -> float:
    xml, nodes = dump_ui(serial)
    start = find_exact(nodes, "Trim start")
    end = find_exact(nodes, "Trim end")
    if not start or not end:
        path = snapshot(serial, evidence, "trim-handles-missing", xml)
        fail(f"trim: start/end handles missing; evidence={path}")

    start_x, start_y = center(start)
    end_x, _ = center(end)
    target_x = start_x + max(80, (end_x - start_x) // 5)
    swipe(serial, start_x, start_y, target_x, start_y, duration_ms=1000)

    seen = {"xml": "", "start_s": 0.0}

    def committed_nonzero_start() -> bool:
        seen["xml"], current = dump_ui(serial)
        for node in current:
            for value in (node.desc, node.text):
                match = TRIM_RANGE_RE.fullmatch(value)
                if match:
                    seen["start_s"] = float(match.group(1))
                    return seen["start_s"] >= 0.10
        return False

    if not wait_until(committed_nonzero_start, timeout_s=20.0, interval_s=0.5):
        path = snapshot(serial, evidence, "trim-not-committed", seen["xml"])
        fail(f"trim: start remained zero after drag; evidence={path}")
    snapshot(serial, evidence, "trim-after", seen["xml"])
    return seen["start_s"]


def start_reverse(serial: str, evidence: Path) -> None:
    xml, nodes = dump_ui(serial)
    save = find_exact(nodes, "SAVE")
    if not save:
        path = snapshot(serial, evidence, "save-missing", xml)
        fail(f"trim: SAVE button missing; evidence={path}")
    
    clear_logcat(serial)
    tap_node(serial, save)

    seen = {"xml": "", "nodes": []}

    def editor_open() -> bool:
        seen["xml"], seen["nodes"] = dump_ui(serial)
        return find_exact(seen["nodes"], "Loop") is not None

    if not wait_until(editor_open, timeout_s=20.0, interval_s=0.5):
        path = snapshot(serial, evidence, "editor-not-open", seen["xml"])
        fail(f"editor: did not open after SAVE; evidence={path}")
    xml, nodes = seen["xml"], seen["nodes"]
    loop = find_exact(nodes, "Loop")
    if not loop:
        path = snapshot(serial, evidence, "loop-missing", xml)
        fail(f"editor: Loop control missing; evidence={path}")
    tap_node(serial, loop)

    started = {"logcat": ""}

    def reverse_started() -> bool:
        started["logcat"] = reverse_logcat(serial)
        return bool(REVERSE_START_RE.search(started["logcat"]))

    if wait_until(reverse_started, timeout_s=5.0, interval_s=0.5):
        return

    xml, nodes = dump_ui(serial)
    direction = find_exact(nodes, "Forward then reverse")
    if not direction:
        path = snapshot(serial, evidence, "direction-missing", xml)
        fail(f"editor: Forward then reverse control missing; evidence={path}")
    tap_node(serial, direction)
    if not wait_until(reverse_started, timeout_s=10.0, interval_s=0.5):
        (evidence / "reverse-logcat.txt").write_text(started["logcat"], encoding="utf-8")
        fail(f"editor: reverse never started; evidence={evidence / 'reverse-logcat.txt'}")


def require_reverse_success(serial: str, evidence: Path) -> tuple[str, int]:
    outcome = {"logcat": ""}

    def terminal() -> bool:
        outcome["logcat"] = reverse_logcat(serial)
        return bool(REVERSE_OK_RE.search(outcome["logcat"])) or bool(
            REVERSE_FAILURE_RE.search(outcome["logcat"])
        )

    finished = wait_until(terminal, timeout_s=REVERSE_DEADLINE_S, interval_s=0.5)
    (evidence / "reverse-logcat.txt").write_text(outcome["logcat"], encoding="utf-8")
    xml, nodes = dump_ui(serial)
    snapshot(serial, evidence, "reverse-after", xml)
    (evidence / "media-resources.txt").write_text(
        adb_out(serial, "shell", "dumpsys", "media.resource_manager", check=False),
        encoding="utf-8",
    )

    strings, blob = dump_strings(nodes), dump_blob(nodes)
    if REVERSE_FAILURE_RE.search(outcome["logcat"]):
        fail(f"reverse: product reported failure; evidence={evidence / 'reverse-logcat.txt'}")
    if not finished:
        fail(
            f"reverse: no success within {int(REVERSE_DEADLINE_S)}s; "
            f"evidence={evidence / 'reverse-logcat.txt'}"
        )

    match = REVERSE_OK_RE.search(outcome["logcat"])
    if not match or int(match.group(2)) <= 0:
        fail(f"reverse: success receipt has no nonempty output; evidence={evidence / 'reverse-logcat.txt'}")
    for forbidden in LOADING_LABELS + FAILURE_LABELS:
        if forbidden in strings or forbidden in blob:
            fail(f"reverse: {forbidden!r} remains visible after success; evidence={evidence / 'reverse-after.xml'}")

    output_name, output_bytes = match.group(1), int(match.group(2))
    found = adb_out(
        serial,
        "shell",
        "run-as",
        PACKAGE,
        "find",
        "cache/scratch/reversed",
        "-name",
        output_name,
        "-type",
        "f",
        check=False,
    )
    if output_name not in found:
        fail(f"reverse: logged output {output_name} is absent from cache/scratch/reversed")

    preroll = PREROLL_RE.search(outcome["logcat"])
    if not preroll:
        fail(
            "reverse: no pass1.preroll receipt, so the trim start's keyframe alignment is unknown; "
            f"evidence={evidence / 'reverse-logcat.txt'}"
        )
    sync_us, trim_start_us = int(preroll.group(1)), int(preroll.group(2))
    if sync_us >= trim_start_us:
        fail(
            f"reverse: trim start {trim_start_us}us IS a sync sample, so the #170 decoder-preroll "
            f"path was never exercised and this run proves nothing; "
            f"evidence={evidence / 'reverse-logcat.txt'}"
        )
    return output_name, output_bytes


def main() -> int:
    serial = resolve_serial()
    ensure_serial_allowed(serial)
    require_online(serial)

    evidence = evidence_dir("reverse-preview-trim")
    ensure_installed(serial)
    grant_camera(serial)
    force_stop(serial)
    baseline = app_files(serial)
    scratch = Scratch(serial)
    started = time.monotonic()

    try:
        start_activity(serial)
        source = record_fixture(serial, evidence, scratch)
        trim_start_s = move_trim_start(serial, evidence)
        start_reverse(serial, evidence)
        output, output_bytes = require_reverse_success(serial, evidence)
    finally:
        force_stop(serial)
        remove_run_files(serial, baseline)
        # `run-as` cleanup can briefly materialize the package process on some emulator images.
        # Leave the app definitively stopped after removing only this run's files.
        force_stop(serial)

    print(
        f"PASS serial={serial} source={source} trim-start={trim_start_s:.2f}s "
        f"reverse={output} bytes={output_bytes} took={int(time.monotonic() - started)}s "
        f"evidence={evidence}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        cmd = " ".join(exc.cmd if isinstance(exc.cmd, list) else [str(exc.cmd)])
        fail(f"adb command failed ({cmd}): {(exc.stderr or exc.stdout or '').strip()}")
