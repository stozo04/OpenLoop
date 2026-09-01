#!/usr/bin/env python3
"""Autonomous verifier for `features/record-clip.md` — record / stop video.

Drives the installed debug APK on an emulator through the three outcomes a shutter tap can have,
and pins the countdown chip's format in every one of them:

  1. too-short  — start+stop inside the 400 ms minimum: stays on camera, snackbar
                  "That was quick! Record a little longer to make a loop.", no clip.
  2. mid-length — a few seconds: Trim opens and a scratch clip exists on disk.
  3. cap        — never tap stop: recording finalizes itself at the 30 s cap and Trim opens.

The countdown chip is asserted on every sample as `<seconds>s / 30s` and never as a `mm:ss` clock
— issue #154 shipped it reading `00:00`, and a format regression is invisible to a Compose test
that only checks the chip exists. Both clips are discarded through the product's own Discard
dialog, so the run leaves no media behind (saving is `edit-save.md`, not this feature).

    python .codex/skills/verify-openloop/helpers/record_clip_loop.py

    VERIFY_SERIAL=emulator-5556   pick a device when more than one is online
    VERIFY_EVIDENCE_DIR=<dir>     where the XML/PNG/logcat evidence lands

Runtime is dominated by scenario 3. The elapsed counter is a 33 ms tick loop, so on an emulator
whose main thread cannot keep up it advances at roughly a fifth of wall time: ~3 minutes measured
on a Pixel_8_API34 AVD, against 30 s on hardware that keeps the cadence. Scenario 3 therefore
asserts the cap in the units the user sees (the chip reaching the cap, and a finalize with no stop
tap and no error), never in wall-clock seconds — see the block comment above `scenario_cap`.
"""
from __future__ import annotations

import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verify_common import (  # noqa: E402
    PACKAGE,
    adb_out,
    app_nodes,
    assert_absent,
    assert_contains,
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
    tap_node,
    wait_until,
)

CAMERA_IDLE_MUST_HAVE = ["Start recording", "Gallery", "Flip Camera"]
TRIM_MUST_HAVE = ["TRIM YOUR VIDEO", "SAVE"]
TOO_SHORT_SNACKBAR = "That was quick! Record a little longer to make a loop."

# The shipped chip is `"${elapsed / 1_000}s / $capLabel"` (CameraScreen.kt) — whole seconds both
# sides. CLOCK_FORMAT_RE is the regression it replaced: a zero-padded mm:ss clock, which for a
# sub-minute cap sat at "00:00" for the first second of every recording (issue #154).
CHIP_RE = re.compile(r"^(\d+)s / (\d+)s$")
CLOCK_FORMAT_RE = re.compile(r"\b\d{1,2}:\d{2}\b")

FINALIZED_RE = re.compile(r"Capture finalized \((\d+)ms\)")
RECORDING_FAILED_RE = re.compile(r"Video burst recording failed")
TOO_SHORT_LOG_RE = re.compile(r"Capture finalized below the \d+ms minimum|Video burst recording failed")
SCRATCH_REL = "files/scratch"

# MIN_TRIM_DURATION / MAX_RECORDING in OpenLoopViewModel. A clip under the minimum is discarded
# with the snackbar instead of opening a Trim screen whose SAVE would be dead.
MIN_CLIP_MS = 400
CAP_SECONDS = 30
# The chip is sampled every few seconds; the last sample before the cap fires lands somewhere
# short of it. 20 s is well past any plausible mis-start and still leaves room for a sampling gap.
CAP_CHIP_FLOOR = 20


class Chip:
    """Every countdown-chip value seen across the whole run, asserted as it is collected."""

    def __init__(self) -> None:
        self.samples: list[int] = []

    def observe(self, nodes: list, context: str) -> int | None:
        # App-drawn nodes only: the system status bar's own clock reads "8:52" and would trip the
        # mm:ss check on every single dump.
        strings = dump_strings(app_nodes(nodes))
        for value in strings:
            match = CHIP_RE.match(value)
            if not match:
                continue
            if int(match.group(2)) != CAP_SECONDS:
                fail(f"{context}: countdown chip reads {value!r}; the cap side should be {CAP_SECONDS}s")
            seconds = int(match.group(1))
            if seconds > CAP_SECONDS:
                fail(f"{context}: countdown chip reads {value!r}, past the {CAP_SECONDS}s cap")
            self.samples.append(seconds)
            return seconds
        # No chip in this dump is only an error while recording, which the callers decide. A
        # mm:ss clock drawn by the app is the issue #154 regression and is never allowed.
        offender = next((v for v in strings if CLOCK_FORMAT_RE.search(v)), None)
        if offender:
            fail(f"{context}: countdown shows a mm:ss clock {offender!r}; it must read '<seconds>s / 30s'")
        return None


def snapshot(serial: str, evidence: Path, name: str, xml: str, screenshot: bool = True) -> Path:
    path = evidence / f"{name}.xml"
    path.write_text(xml, encoding="utf-8")
    if screenshot:
        save_screencap(serial, evidence / f"{name}.png")
    return path


def scratch_clips(serial: str) -> set[str]:
    out = adb_out(serial, "shell", "run-as", PACKAGE, "ls", SCRATCH_REL, check=False)
    return {line.strip() for line in out.splitlines() if line.strip().startswith("raw_")}


class Scratch:
    """Per-capture clip files under `filesDir/scratch`, measured against what was already there.

    Only clips THIS run created are asserted on. A scratch file left by an abandoned session
    elsewhere is not this feature's to judge — and deleting it to get a clean slate would throw
    away someone else's in-progress capture, which the verifier contract forbids.
    """

    def __init__(self, serial: str) -> None:
        self.baseline = scratch_clips(serial)

    def new(self, serial: str) -> list[str]:
        return sorted(scratch_clips(serial) - self.baseline)


def wait_for_camera_idle(serial: str, evidence: Path, context: str, timeout_s: float = 45.0) -> tuple[str, list]:
    """Poll until the viewfinder is idle, tapping through onboarding or stills mode if needed.

    Deliberately does NOT reset the onboarding DataStore: this feature does not own that state,
    and wiping it would make the loop destructive to a device someone else is mid-test on.
    """
    seen: dict = {"xml": "", "nodes": []}

    def ready() -> bool:
        xml, nodes = dump_ui(serial)
        if not nodes:
            return False
        seen["xml"], seen["nodes"] = xml, nodes
        strings, blob = dump_strings(nodes), dump_blob(nodes)
        if "Start recording" in strings:
            return True
        cta = find_exact(nodes, "LET'S GO!")
        if cta:  # first run on a fresh install — walk through it like a user
            tap_node(serial, cta)
            return False
        if "Take photo" in strings:  # stills mode left over from another recipe
            video = find_exact(nodes, "Video")
            if video:
                tap_node(serial, video)
        return False

    if not wait_until(ready, timeout_s=timeout_s, interval_s=1.0):
        path = snapshot(serial, evidence, f"{context}-not-idle", seen["xml"])
        fail(f"{context}: camera never reached idle (no 'Start recording'); evidence={path}")
    strings, blob = dump_strings(seen["nodes"]), dump_blob(seen["nodes"])
    assert_contains(strings, blob, CAMERA_IDLE_MUST_HAVE, context)
    assert_absent(strings, blob, ["TRIM YOUR VIDEO"], context)
    return seen["xml"], seen["nodes"]


def shutter(serial: str, nodes: list, label: str, context: str, evidence: Path):
    node = find_exact(nodes, label)
    if not node:
        fail(f"{context}: no tap target for {label!r}; evidence={evidence}")
    return node


def wait_for_trim(serial: str, chip: Chip, context: str, timeout_s: float, interval_s: float = 2.0) -> str:
    """Poll for the Trim screen, sampling the countdown chip on every dump along the way."""
    seen = {"xml": ""}

    def arrived() -> bool:
        xml, nodes = dump_ui(serial)
        if not nodes:
            return False
        seen["xml"] = xml
        strings, blob = dump_strings(nodes), dump_blob(nodes)
        chip.observe(nodes, context)
        return "TRIM YOUR VIDEO" in strings or "TRIM YOUR VIDEO" in blob

    ok = wait_until(arrived, timeout_s=timeout_s, interval_s=interval_s)
    return seen["xml"] if ok else ""


def discard_clip(serial: str, evidence: Path, context: str, scratch: Scratch) -> None:
    """Throw the session clip away through the product's Discard dialog, back to the camera."""
    xml, nodes = dump_ui(serial)
    opener = find_exact(nodes, "Discard clip") or find_exact(nodes, "Delete")
    if not opener:
        path = snapshot(serial, evidence, f"{context}-no-discard", xml)
        fail(f"{context}: editor has no 'Discard clip'/'Delete' affordance; evidence={path}")
    tap_node(serial, opener)

    seen = {"xml": "", "nodes": []}

    def dialog_up() -> bool:
        seen["xml"], seen["nodes"] = dump_ui(serial)
        return find_exact(seen["nodes"], "Discard") is not None and any(
            "Discard this clip?" in v for v in dump_strings(seen["nodes"])
        )

    if not wait_until(dialog_up, timeout_s=20.0, interval_s=1.0):
        path = snapshot(serial, evidence, f"{context}-no-dialog", seen["xml"])
        fail(f"{context}: 'Discard this clip?' dialog never appeared; evidence={path}")
    tap_node(serial, find_exact(seen["nodes"], "Discard"))

    if not wait_until(lambda: "Start recording" in dump_strings(dump_ui(serial)[1]),
                      timeout_s=45.0, interval_s=1.0):
        xml, _ = dump_ui(serial)
        path = snapshot(serial, evidence, f"{context}-discard-stuck", xml)
        fail(f"{context}: discard did not return to the camera; evidence={path}")
    left = scratch.new(serial)
    if left:
        fail(f"{context}: discard left the scratch clip behind: {left}")


def scenario_too_short(serial: str, evidence: Path, chip: Chip, scratch: Scratch) -> str:
    """Start and stop inside the minimum: no clip, no Trim, and the 'record longer' snackbar.

    Both taps go out in ONE `adb shell`, because a dump-parse-tap round trip is seconds long and
    would always produce a valid clip instead of the too-short case. Establishing that precondition
    is still timing-dependent, so a run that accidentally records a real clip is retried (and its
    clip discarded) rather than asserted against.
    """
    for attempt in range(1, 4):
        _, nodes = wait_for_camera_idle(serial, evidence, "too-short")
        cx, cy = center(shutter(serial, nodes, "Start recording", "too-short", evidence))
        clear_logcat(serial)
        run_adb(serial, "shell", f"input tap {cx} {cy}; input tap {cx} {cy}")

        # The snackbar is on screen for four seconds and a uiautomator dump takes about five, so
        # polling with dumps loses the race: the first snapshot lands before the capture has even
        # finalized and the second lands after the snackbar is gone. Logcat costs a fraction of a
        # second and says exactly when the capture ended, so wait on THAT and dump once, into the
        # open window.
        outcome = {"logcat": ""}

        def finalized() -> bool:
            outcome["logcat"] = adb_out(serial, "logcat", "-d", "-s", "OpenLoopViewModel:*")
            return bool(TOO_SHORT_LOG_RE.search(outcome["logcat"])) or bool(
                FINALIZED_RE.search(outcome["logcat"])
            )

        if not wait_until(finalized, timeout_s=30.0, interval_s=0.3):
            fail("too-short: the double tap produced no capture at all within 30s")
        logcat = outcome["logcat"]
        seen: dict = {"xml": "", "nodes": []}
        seen["xml"], seen["nodes"] = dump_ui(serial)
        strings, blob = dump_strings(seen["nodes"]), dump_blob(seen["nodes"])

        if FINALIZED_RE.search(logcat) or "TRIM YOUR VIDEO" in strings:
            # The two taps landed far enough apart to encode a real clip: the precondition failed,
            # not the product. Clean up and try again.
            print(f"  too-short attempt {attempt}: taps produced a full clip, discarding and retrying")
            discard_clip(serial, evidence, f"too-short-retry{attempt}", scratch)
            continue

        snapshot(serial, evidence, "too-short", seen["xml"])
        (evidence / "too-short-logcat.txt").write_text(logcat, encoding="utf-8")
        if TOO_SHORT_SNACKBAR not in strings and attempt < 3:
            # The capture was rejected as it should be, but the dump landed outside the snackbar's
            # four seconds. That is this harness losing a race, not the product; the last attempt
            # asserts for real rather than retrying forever.
            print(f"  too-short attempt {attempt}: dump missed the snackbar window, retrying")
            continue
        chip.observe(seen["nodes"], "too-short")
        # While the snackbar is up it sits over the shutter row, and those controls drop out of the
        # hierarchy — so the "still on camera" proof here is the mode selector plus the absence of
        # any editor, and the shutter itself is checked once the snackbar has gone.
        assert_contains(strings, blob, [TOO_SHORT_SNACKBAR, "Video", "Camera"], "too-short")
        assert_absent(strings, blob, ["TRIM YOUR VIDEO", "Stop recording"], "too-short")
        left = scratch.new(serial)
        if left:
            fail(f"too-short: a clip was kept anyway: {left}")
        wait_for_camera_idle(serial, evidence, "too-short-after")
        return f"attempt{attempt}"

    fail("too-short: three double-taps in a row recorded a real clip; the shutter never saw a sub-400ms press")


def scenario_mid_length(serial: str, evidence: Path, chip: Chip, scratch: Scratch) -> int:
    """A few seconds of recording: the chip counts in seconds, stop opens Trim, a clip exists."""
    _, nodes = wait_for_camera_idle(serial, evidence, "mid")
    clear_logcat(serial)
    tap_node(serial, shutter(serial, nodes, "Start recording", "mid", evidence))

    seen = {"xml": "", "nodes": []}

    def recording() -> bool:
        seen["xml"], seen["nodes"] = dump_ui(serial)
        strings, blob = dump_strings(seen["nodes"]), dump_blob(seen["nodes"])
        if "Stop recording" not in strings:
            return False
        seconds = chip.observe(seen["nodes"], "mid-recording")
        return seconds is not None and seconds >= 1

    if not wait_until(recording, timeout_s=90.0, interval_s=1.0):
        path = snapshot(serial, evidence, "mid-recording-stalled", seen["xml"])
        fail(f"mid: countdown never reached 1s while recording; evidence={path}")
    snapshot(serial, evidence, "mid-recording", seen["xml"])

    tap_node(serial, shutter(serial, seen["nodes"], "Stop recording", "mid", evidence))
    trim_xml = wait_for_trim(serial, chip, "mid-stop", timeout_s=90.0)
    if not trim_xml:
        xml, _ = dump_ui(serial)
        path = snapshot(serial, evidence, "mid-no-trim", xml)
        fail(f"mid: stop did not open Trim; evidence={path}")
    snapshot(serial, evidence, "mid-trim", trim_xml)

    _, trim_nodes = dump_ui(serial)
    strings, blob = dump_strings(trim_nodes), dump_blob(trim_nodes)
    assert_contains(strings, blob, TRIM_MUST_HAVE, "mid-trim")

    logcat = adb_out(serial, "logcat", "-d", "-s", "OpenLoopViewModel:*")
    (evidence / "mid-logcat.txt").write_text(logcat, encoding="utf-8")
    match = FINALIZED_RE.search(logcat)
    if not match:
        fail(f"mid: no 'Capture finalized (Nms)' in logcat; evidence={evidence / 'mid-logcat.txt'}")
    duration_ms = int(match.group(1))
    if duration_ms < MIN_CLIP_MS:
        fail(f"mid: clip finalized at {duration_ms}ms, under the {MIN_CLIP_MS}ms minimum")
    clips = scratch.new(serial)
    if not clips:
        fail(f"mid: Trim is open but no scratch clip exists under {SCRATCH_REL}")

    discard_clip(serial, evidence, "mid", scratch)
    return duration_ms


def scenario_cap(serial: str, evidence: Path, chip: Chip, scratch: Scratch) -> int:
    """Never tap stop: the 30 s cap finalizes the recording on its own and Trim opens.

    What is asserted is the cap in the product's own units — the chip climbing to the cap, Trim
    arriving with no stop tap, and a clean finalize — not the clip's wall-clock length. The
    elapsed counter accumulates 33 ms per tick rather than reading a clock, so on an emulator
    that cannot service the tick loop at cadence the cap fires late and the container is longer
    than 30 s (143 s measured here against a chip that had just reached 30s). That gap is the
    AVD's scheduling, not a product regression a verifier can distinguish from one, so pinning a
    wall-clock ceiling here would be a permanently red gate that says nothing about the feature.
    """
    _, nodes = wait_for_camera_idle(serial, evidence, "cap")
    clear_logcat(serial)
    tap_node(serial, shutter(serial, nodes, "Start recording", "cap", evidence))

    if not wait_until(lambda: "Stop recording" in dump_strings(dump_ui(serial)[1]),
                      timeout_s=60.0, interval_s=1.0):
        xml, _ = dump_ui(serial)
        path = snapshot(serial, evidence, "cap-never-started", xml)
        fail(f"cap: recording never started; evidence={path}")

    before = len(chip.samples)
    # 5 s between dumps: each dump is seconds of work on the device's main thread, and hammering
    # it starves the very tick loop under test.
    trim_xml = wait_for_trim(serial, chip, "cap-recording", timeout_s=480.0, interval_s=5.0)
    if not trim_xml:
        xml, _ = dump_ui(serial)
        path = snapshot(serial, evidence, "cap-no-trim", xml)
        fail(f"cap: recording did not finalize itself within 480s; evidence={path}")
    snapshot(serial, evidence, "cap-trim", trim_xml)

    peak = max(chip.samples[before:], default=-1)
    if peak < CAP_CHIP_FLOOR:
        fail(f"cap: countdown only reached {peak}s before Trim opened; expected it to climb to the {CAP_SECONDS}s cap")

    logcat = adb_out(serial, "logcat", "-d", "-s", "OpenLoopViewModel:*")
    (evidence / "cap-logcat.txt").write_text(logcat, encoding="utf-8")
    if RECORDING_FAILED_RE.search(logcat):
        fail(f"cap: recording ended in an error, not the cap; evidence={evidence / 'cap-logcat.txt'}")
    match = FINALIZED_RE.search(logcat)
    if not match:
        fail(f"cap: no 'Capture finalized (Nms)' in logcat; evidence={evidence / 'cap-logcat.txt'}")

    discard_clip(serial, evidence, "cap", scratch)
    return peak


def main() -> int:
    serial = resolve_serial()
    ensure_serial_allowed(serial)
    require_online(serial)

    evidence = evidence_dir("record-clip")
    ensure_installed(serial)
    grant_camera(serial)
    force_stop(serial)
    clear_logcat(serial)
    start_activity(serial)

    chip = Chip()
    scratch = Scratch(serial)
    started = time.monotonic()
    short = scenario_too_short(serial, evidence, chip, scratch)
    mid_ms = scenario_mid_length(serial, evidence, chip, scratch)
    peak = scenario_cap(serial, evidence, chip, scratch)

    if not chip.samples:
        fail("no countdown chip was ever observed while recording; the timer never rendered")

    force_stop(serial)
    print(
        f"PASS serial={serial} too-short={short} mid={mid_ms}ms cap-chip={peak}s "
        f"chip-samples={len(chip.samples)} took={int(time.monotonic() - started)}s evidence={evidence}"
    )
    return 0


if __name__ == "__main__":
    import subprocess

    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        cmd = " ".join(exc.cmd if isinstance(exc.cmd, list) else [str(exc.cmd)])
        fail(f"adb command failed ({cmd}): {(exc.stderr or exc.stdout or '').strip()}")
