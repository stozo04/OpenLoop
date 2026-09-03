# Pinch zoom

Pinch on the live viewfinder to zoom in or out (including mid-recording). A zoom chip shows the live ratio (e.g. `2.3x`). Zoom is bake-into-pixels at capture time; imported clips have no zoom control.

## Sub-features

- `zoom-idle` — pinch on idle camera; `zoom_chip` / content-desc `Zoom level, …` appears or updates.
- `zoom-while-recording` — pinch between `Start recording` and `Stop recording`; chip updates; Trim still opens on stop.
- `zoom-photo` — pinch in Camera stills mode before `Take photo` (optional).

## How to get to it (user POV)

- On the viewfinder, pinch with two fingers. Release; the chip shows the ratio.
- Record or shoot; Zoom is in the saved media.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Viewfinder up.
- Evidence dir `pinch-zoom/` created.
- `control.ps1` has **no** pinch helper today — this feature is gesture-fragile on emulators.

- **Baseline.** Dump idle camera. Note whether `zoom_chip` is already visible.
- **Pinch.** Prefer a manual pinch on the emulator window, or `adb shell` multitouch only if you already have a known-good script for this AVD. Do not invent random coordinates and call it proof.
- **Confirm.** Dump or screenshot shows zoom chip / TalkBack desc `Zoom level,`.
- **While recording (optional).** Start recording, pinch, stop → Trim.
- **Proof.** `before.txt`, `after-zoom.txt`. If pinch cannot be delivered, mark **verified-unreachable** with the AVD name — do not fake it via Camera2 API.

## Gotchas

- Parent `PinchZoomLayout` owns the gesture (Lesson 025) — Compose overlays must not steal it.
- Samsung / Fold density differences matter; pixel-perfect scripts break across AVDs.
- Zoom is capture-path only — not in the editor, not on imports ([import-video](./import-video.md)).
