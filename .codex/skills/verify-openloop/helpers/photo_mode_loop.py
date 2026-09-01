#!/usr/bin/env python3
"""Autonomous verifier for the `photo-mode` sub-feature of `features/photo-capture.md`.

Proves the viewfinder carries the top-right CAMERA | VIDEO selector and that tapping a segment
actually swaps what the shutter does — video -> stills -> video. Every state is asserted two ways
out of the *same* dump:

  * the shutter's contentDescription (`Start recording` <-> `Take photo`) — the ability being
    switched, i.e. whether the user can record or take a picture right now, and
  * the tapped segment's `checked` flag — the only indicator of which mode is armed, which is the
    whole reason the single toggling icon became a segmented control (issue #126).

Neither alone is the claim: a highlight that moves while the shutter still records, or a shutter
that flips while the pill stays put, is a bug and this catches both. The two reads must land in
one dump, because a dump costs seconds and re-dumping between them is a race.

Nothing is captured — no still, no clip, no gallery write. The scope is the toggle.

    python .codex/skills/verify-openloop/helpers/photo_mode_loop.py

    VERIFY_SERIAL=emulator-5556   pick a device when more than one is online
    VERIFY_EVIDENCE_DIR=<dir>     where the XML/PNG evidence lands

Roughly 30 s on a healthy AVD; the cost is three uiautomator dumps plus the two taps.
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verify_common import (  # noqa: E402
    UiNode,
    app_nodes,
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
    save_screencap,
    start_activity,
    tap_node,
    wait_until,
)

# Labels from strings.xml: camera_mode_camera / camera_mode_video, and the two idle shutter
# content descriptions camera_take_photo / camera_start_recording.
CAMERA_LABEL = "Camera"
VIDEO_LABEL = "Video"
PHOTO_SHUTTER = "Take photo"
VIDEO_SHUTTER = "Start recording"


def snapshot(serial: str, evidence: Path, name: str, xml: str) -> Path:
    path = evidence / f"{name}.xml"
    path.write_text(xml, encoding="utf-8")
    save_screencap(serial, evidence / f"{name}.png")
    return path


def segment_checked(nodes: list[UiNode], label: str) -> bool | None:
    """Whether the selector segment carrying `label` is the selected one; None if there is none.

    uiautomator hands back a flat node list, so the segment is found by geometry: the smallest
    checkable node whose bounds contain the label's. Compose puts the `selectable` on a
    full-height wrapper *around* the visible pill, and only that wrapper reports `checked` — the
    label leaf and the RadioButton-class leaf inside it both read checked="false" in either mode.
    """
    label_node = find_exact(app_nodes(nodes), label)
    if not label_node or not label_node.bounds:
        return None
    lx1, ly1, lx2, ly2 = label_node.bounds
    best: tuple[int, bool] | None = None
    for node in app_nodes(nodes):
        if not node.checkable or not node.bounds:
            continue
        x1, y1, x2, y2 = node.bounds
        if x1 <= lx1 and y1 <= ly1 and x2 >= lx2 and y2 >= ly2:
            area = (x2 - x1) * (y2 - y1)
            if best is None or area < best[0]:
                best = (area, node.checked)
    return None if best is None else best[1]


def mode_state(nodes: list[UiNode]) -> tuple[str, bool | None, bool | None]:
    """One dump's answer to "which mode is the viewfinder in": shutter desc + both segment flags."""
    strings = dump_strings(app_nodes(nodes))
    shutter = ""
    if PHOTO_SHUTTER in strings:
        shutter = PHOTO_SHUTTER
    if VIDEO_SHUTTER in strings:
        # Both at once is a product bug, not a read error — report it as what it is.
        shutter = "both" if shutter else VIDEO_SHUTTER
    return shutter, segment_checked(nodes, CAMERA_LABEL), segment_checked(nodes, VIDEO_LABEL)


def matches(nodes: list[UiNode], want_photo: bool) -> bool:
    shutter, camera_on, video_on = mode_state(nodes)
    return (
        shutter == (PHOTO_SHUTTER if want_photo else VIDEO_SHUTTER)
        and camera_on is want_photo
        and video_on is not want_photo
    )


def describe(nodes: list[UiNode]) -> str:
    shutter, camera_on, video_on = mode_state(nodes)
    return f"shutter={shutter or '<none>'!r} Camera.checked={camera_on} Video.checked={video_on}"


def expected(want_photo: bool) -> str:
    return (
        f"shutter={(PHOTO_SHUTTER if want_photo else VIDEO_SHUTTER)!r} "
        f"Camera.checked={want_photo} Video.checked={not want_photo}"
    )


def wait_for_viewfinder(serial: str, evidence: Path, timeout_s: float = 90.0) -> tuple[str, list[UiNode]]:
    """Poll until the idle viewfinder is up in Video mode, walking onboarding like a user.

    Deliberately resets no stored state: this feature owns neither onboarding nor anyone else's
    in-progress capture. Capture mode itself is ViewModel state, so the force-stop in `main`
    already puts a fresh process back in VIDEO — the stills-mode tap here only covers a device
    someone else left in Camera mode without restarting the app.
    """
    seen: dict = {"xml": "", "nodes": []}

    def ready() -> bool:
        xml, nodes = dump_ui(serial)
        if not nodes:
            return False
        seen["xml"], seen["nodes"] = xml, nodes
        strings = dump_strings(app_nodes(nodes))
        if VIDEO_SHUTTER in strings:
            return True
        cta = find_exact(nodes, "LET'S GO!")
        if cta:  # first run on a fresh install — walk through it like a user
            tap_node(serial, cta)
            return False
        if PHOTO_SHUTTER in strings:  # stills mode left over from another recipe
            video = find_exact(app_nodes(nodes), VIDEO_LABEL)
            if video:
                tap_node(serial, video)
        return False

    if not wait_until(ready, timeout_s=timeout_s, interval_s=1.0):
        path = snapshot(serial, evidence, "viewfinder-not-idle", seen["xml"])
        fail(f"viewfinder: never reached an idle camera in Video mode; evidence={path}")
    return seen["xml"], seen["nodes"]


def select_mode(serial: str, xml: str, nodes: list[UiNode], label: str, evidence: Path, context: str) -> None:
    segment = find_exact(app_nodes(nodes), label)
    if not segment:
        path = snapshot(serial, evidence, f"{context}-no-segment", xml)
        fail(f"{context}: the capture-mode selector has no {label!r} segment to tap; evidence={path}")
    tap_node(serial, segment)


def wait_for_mode(
    serial: str, want_photo: bool, context: str, evidence: Path, timeout_s: float = 30.0
) -> tuple[str, list[UiNode]]:
    """Poll until ONE dump shows the whole target state, then save it as this step's evidence."""
    seen: dict = {"xml": "", "nodes": []}

    def arrived() -> bool:
        xml, nodes = dump_ui(serial)
        if not nodes:
            return False
        seen["xml"], seen["nodes"] = xml, nodes
        return matches(nodes, want_photo)

    if not wait_until(arrived, timeout_s=timeout_s, interval_s=1.0):
        path = snapshot(serial, evidence, f"{context}-mismatch", seen["xml"])
        fail(
            f"{context}: expected {expected(want_photo)}; last dump had {describe(seen['nodes'])}; "
            f"evidence={path}"
        )
    snapshot(serial, evidence, context, seen["xml"])
    return seen["xml"], seen["nodes"]


def main() -> int:
    serial = resolve_serial()
    ensure_serial_allowed(serial)
    require_online(serial)

    evidence = evidence_dir("photo-mode")
    ensure_installed(serial)
    grant_camera(serial)
    force_stop(serial)
    start_activity(serial)

    started = time.monotonic()
    xml, nodes = wait_for_viewfinder(serial, evidence)

    # The control exists at all. Checked separately from the state below so a selector that is
    # simply missing fails saying so, instead of timing out on a mode that can never arrive.
    for label in (CAMERA_LABEL, VIDEO_LABEL):
        if find_exact(app_nodes(nodes), label) is None:
            path = snapshot(serial, evidence, "no-selector", xml)
            fail(f"selector: no {label!r} segment on the viewfinder; evidence={path}")
        if segment_checked(nodes, label) is None:
            path = snapshot(serial, evidence, "not-selectable", xml)
            fail(
                f"selector: {label!r} is on screen but sits in no checkable segment, so nothing "
                f"tells the user which mode is armed; evidence={path}"
            )

    # Baseline asserted on the dump the idle wait already paid for.
    if not matches(nodes, want_photo=False):
        path = snapshot(serial, evidence, "video-baseline-mismatch", xml)
        fail(
            f"video-baseline: expected {expected(False)}; got {describe(nodes)}; evidence={path}"
        )
    snapshot(serial, evidence, "video-baseline", xml)

    # Video -> stills, then back. Each tap targets the segment as it was seen in the dump that
    # proved the *previous* state, so no extra dump is paid for between the two directions.
    select_mode(serial, xml, nodes, CAMERA_LABEL, evidence, "camera-mode")
    camera_xml, camera_nodes = wait_for_mode(serial, True, "camera-mode", evidence)

    select_mode(serial, camera_xml, camera_nodes, VIDEO_LABEL, evidence, "video-restored")
    wait_for_mode(serial, False, "video-restored", evidence)

    force_stop(serial)
    print(
        f"PASS serial={serial} selector=Camera|Video video->photo->video "
        f"took={int(time.monotonic() - started)}s evidence={evidence}"
    )
    return 0


if __name__ == "__main__":
    import subprocess

    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        cmd = " ".join(exc.cmd if isinstance(exc.cmd, list) else [str(exc.cmd)])
        fail(f"adb command failed ({cmd}): {(exc.stderr or exc.stdout or '').strip()}")
