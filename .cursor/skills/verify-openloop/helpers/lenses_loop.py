#!/usr/bin/env python3
"""Autonomous verifier for the `lenses-open` / `lenses-pick` / `lenses-clear` sub-features of
`features/lenses.md`.

Proves the lens drawer opens, that a named catalogue lens can be reached and worn, and that
tapping it again takes it off. Every state is asserted two ways out of the *same* dump:

  * the active-lens **name pill**, which `LensCarousel` renders only while a lens is on — the
    thing the user reads to know what they are wearing, and the only on-screen text that names it;
  * the thumbnail's own **selected** flag, which is what the tray announces to TalkBack.

Neither alone is the claim. A pill that appears while the thumb still reports unselected is a
broken accessibility contract; a thumb that highlights while no pill appears means the ViewModel
never took the lens. Both reads come from one dump, because a dump costs seconds and re-dumping
between them is a race.

The lens it drives defaults to the catalogue's newest entry, **Vampire**. That is the point: a new
lens is one enum entry plus its art, and the failure this catches is that entry not reaching the
tray at all — a missing drawable, a thumbnail that never composes, a name that never renders. Set
`VERIFY_LENS` to drive a different one.

Nothing is recorded — no clip, no still, no gallery write. Whether the lens lands *on a face* is
not in scope and cannot be: the emulator's virtual scene is a static poster, so face tracking,
roll and steadiness stay owner-owned hardware checks (`docs/PRD-camera-lenses.md` §11.1).

    python .cursor/skills/verify-openloop/helpers/lenses_loop.py

    VERIFY_SERIAL=emulator-5556   pick a device when more than one is online
    VERIFY_LENS="Elvis"           drive a different catalogue entry
    VERIFY_EVIDENCE_DIR=<dir>     where the XML/PNG evidence lands

Roughly 45 s on a healthy AVD; the cost is the carousel scroll plus four uiautomator dumps.
"""
from __future__ import annotations

import os
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
    find_all,
    find_exact,
    force_stop,
    grant_camera,
    require_online,
    resolve_serial,
    save_screencap,
    start_activity,
    swipe,
    tap_node,
    wait_until,
)

# Labels from strings.xml (camera_drawer_open / camera_lenses / camera_start_recording) and from
# Lens.displayName, which LensCarousel uses as each thumbnail's contentDescription.
DRAWER_BUTTON = "Lenses and Photo Booth"
LENSES_TAB = "Lenses"
# The tray's leading clear-and-close control. It exists only inside LensCarousel and sits in the
# same row as the thumbnails, so it doubles as proof the carousel is up and as the y to swipe on.
CLOSE_LENSES = "Close lenses"
VIDEO_SHUTTER = "Start recording"
LENS = os.environ.get("VERIFY_LENS", "").strip() or "Vampire"

# The carousel is a LazyRow: entries past the fold are not composed until scrolled to, which is
# exactly how LensCarouselTest started failing when the catalogue reached seven (PRD §13).
MAX_SCROLLS = 8


def snapshot(serial: str, evidence: Path, name: str, xml: str) -> Path:
    path = evidence / f"{name}.xml"
    path.write_text(xml, encoding="utf-8")
    save_screencap(serial, evidence / f"{name}.png")
    return path


def thumb_is_on(node: UiNode) -> bool:
    """Whether a lens thumbnail reports itself as the worn one.

    `LensCarousel` sets Compose's `selected` semantics, but Compose only maps that to
    AccessibilityNodeInfo.isSelected for `Role.Tab`; every other role is announced as
    isChecked + isCheckable. The thumbnails have no Role, so a worn lens dumps as
    checked="true" selected="false" (verified on the Pixel 8 API 37 AVD). Read both, so
    this stays true if the carousel ever takes a Tab role.
    """
    return node.selected or node.checked


def lens_state(nodes: list[UiNode]) -> tuple[bool, bool]:
    """One dump's answer to "is LENS on": (name pill showing, thumbnail reporting selected)."""
    app = app_nodes(nodes)
    # The pill is a Text; the thumbnails carry the same string as a contentDescription. Only a
    # node whose *text* is the name can be the pill, or every thumb would look like one.
    pill = any(node.text == LENS for node in app)
    thumb = any(thumb_is_on(node) for node in find_all(app, LENS))
    return pill, thumb


def describe(nodes: list[UiNode]) -> str:
    pill, thumb = lens_state(nodes)
    return f"pill={pill} thumb.selected={thumb}"


def wait_for_viewfinder(serial: str, evidence: Path, timeout_s: float = 90.0) -> tuple[str, list[UiNode]]:
    """Poll until the idle viewfinder is up, walking onboarding like a user.

    Resets no stored state: this feature owns neither onboarding nor anyone else's media. Lens
    selection is plain ViewModel state (PRD §10 decision 6, never persisted), so the force-stop in
    `main` already guarantees a fresh process starts with no lens on.
    """
    seen: dict = {"xml": "", "nodes": []}

    def ready() -> bool:
        xml, nodes = dump_ui(serial)
        if not nodes:
            return False
        seen["xml"], seen["nodes"] = xml, nodes
        if VIDEO_SHUTTER in dump_strings(app_nodes(nodes)):
            return True
        cta = find_exact(nodes, "LET'S GO!")
        if cta:  # first run on a fresh install — walk through it like a user
            tap_node(serial, cta)
        return False

    if not wait_until(ready, timeout_s=timeout_s, interval_s=1.0):
        path = snapshot(serial, evidence, "viewfinder-not-idle", seen["xml"])
        fail(f"viewfinder: never reached an idle camera; evidence={path}")
    return seen["xml"], seen["nodes"]


def open_drawer(serial: str, xml: str, nodes: list[UiNode], evidence: Path) -> tuple[str, list[UiNode]]:
    button = find_exact(app_nodes(nodes), DRAWER_BUTTON)
    if not button:
        path = snapshot(serial, evidence, "no-drawer-button", xml)
        fail(f"drawer: no {DRAWER_BUTTON!r} control on the viewfinder; evidence={path}")
    tap_node(serial, button)

    seen: dict = {"xml": "", "nodes": []}

    def open_on_lenses() -> bool:
        got_xml, got_nodes = dump_ui(serial)
        if not got_nodes:
            return False
        seen["xml"], seen["nodes"] = got_xml, got_nodes
        # The Lenses tab is selected by default, so its label plus the carousel's own ✕ is the
        # signal the carousel — not the Photo Booth tab — is what came up.
        app = app_nodes(got_nodes)
        return find_exact(app, LENSES_TAB) is not None and find_exact(app, CLOSE_LENSES) is not None

    if not wait_until(open_on_lenses, timeout_s=20.0, interval_s=0.5):
        path = snapshot(serial, evidence, "drawer-not-open", seen["xml"])
        fail(f"drawer: never showed the {LENSES_TAB!r} tab after tapping the button; evidence={path}")
    return seen["xml"], seen["nodes"]


def scroll_to_lens(serial: str, xml: str, nodes: list[UiNode], evidence: Path) -> tuple[str, list[UiNode], UiNode]:
    """Swipe the carousel until LENS composes, then hand back the dump that found it."""
    for attempt in range(MAX_SCROLLS + 1):
        thumb = find_exact(app_nodes(nodes), LENS)
        if thumb:
            return xml, nodes, thumb
        if attempt == MAX_SCROLLS:
            break
        # Swipe along the ✕'s row: it is laid out beside the LazyRow, so its y centre is the
        # thumbnails' y centre, and the gesture cannot land on the shutter or the preview instead.
        anchor = find_exact(app_nodes(nodes), CLOSE_LENSES)
        if not anchor or not anchor.bounds:
            break
        _, y1, x2, y2 = anchor.bounds
        row_y = (y1 + y2) // 2
        screen = max((node.bounds[2] for node in nodes if node.bounds), default=x2)
        swipe(serial, int(screen * 0.85), row_y, int(screen * 0.15), row_y)
        xml, nodes = dump_ui(serial)

    path = snapshot(serial, evidence, "lens-not-in-carousel", xml)
    fail(
        f"carousel: {LENS!r} never appeared after {MAX_SCROLLS} scrolls — it is in the catalogue "
        f"but not in the tray; evidence={path}"
    )


def wait_for_lens(serial: str, worn: bool, context: str, evidence: Path, timeout_s: float = 20.0) -> tuple[str, list[UiNode]]:
    """Poll until ONE dump shows both reads agreeing, then save it as this step's evidence."""
    seen: dict = {"xml": "", "nodes": []}

    def arrived() -> bool:
        xml, nodes = dump_ui(serial)
        if not nodes:
            return False
        seen["xml"], seen["nodes"] = xml, nodes
        return lens_state(nodes) == (worn, worn)

    if not wait_until(arrived, timeout_s=timeout_s, interval_s=0.5):
        path = snapshot(serial, evidence, f"{context}-mismatch", seen["xml"])
        fail(
            f"{context}: expected pill={worn} thumb.selected={worn} for {LENS!r}; last dump had "
            f"{describe(seen['nodes'])}; evidence={path}"
        )
    snapshot(serial, evidence, context, seen["xml"])
    return seen["xml"], seen["nodes"]


def main() -> int:
    serial = resolve_serial()
    ensure_serial_allowed(serial)
    require_online(serial)

    evidence = evidence_dir("lenses")
    ensure_installed(serial)
    grant_camera(serial)
    force_stop(serial)
    start_activity(serial)

    started = time.monotonic()
    xml, nodes = wait_for_viewfinder(serial, evidence)
    xml, nodes = open_drawer(serial, xml, nodes, evidence)
    snapshot(serial, evidence, "drawer-open", xml)

    xml, nodes, thumb = scroll_to_lens(serial, xml, nodes, evidence)

    # Nothing may be worn before the tap, or "it turned on" proves nothing.
    if lens_state(nodes) != (False, False):
        path = snapshot(serial, evidence, "lens-already-on", xml)
        fail(f"baseline: {LENS!r} reads as already worn ({describe(nodes)}); evidence={path}")
    snapshot(serial, evidence, "baseline", xml)

    # Wear it, then tap the same thumb again to take it off — the tray has no separate "None".
    tap_node(serial, thumb)
    worn_xml, worn_nodes = wait_for_lens(serial, True, "lens-worn", evidence)

    candidates = find_all(app_nodes(worn_nodes), LENS)
    again = next((node for node in candidates if thumb_is_on(node)), None)
    if not again:
        path = snapshot(serial, evidence, "thumb-gone", worn_xml)
        fail(f"clear: {LENS!r} left the tray once selected, so it cannot be tapped off; evidence={path}")
    tap_node(serial, again)
    wait_for_lens(serial, False, "lens-cleared", evidence)

    force_stop(serial)
    print(
        f"PASS serial={serial} lens={LENS} drawer=open pick->clear "
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
