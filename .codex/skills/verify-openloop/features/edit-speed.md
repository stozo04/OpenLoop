# Edit — Speed

Controls how fast the loop plays. **Constant** is a single multiplier slider (`0.25×`–`3.0×`, default often `2.0×`). **Custom** opens a drawable speed curve with presets so speed can change across the loop (ramps into the reverse turn, etc.).

## Sub-features

- `speed-open` — toolbar `Speed` → `speed_tab_panel`.
- `speed-constant` — mode `Constant` (`speed_mode_constant`); change `Playback speed` / SeekBar (`speed_slider`); `Current speed` / `speed_current_pill` updates.
- `speed-curve-mode` — tap `Custom` (`speed_mode_curve`); first visit may show intro (`speed_curve_intro`, dismiss `Got it`).
- `speed-curve-preset` — open Presets (`speed_curve_presets`) and pick one (`Ease In`, `Ease Out`, `Slow–Fast–Slow`, `Accelerate into Reverse`).
- `speed-curve-edit` (optional) — Add point / Remove / Reset; graph `speed_curve_graph`. Point drags are gesture-fragile.
- `speed-back-to-constant` — tap `Constant` to leave the curve editor.

## How to get to it (user POV)

- From Trim or any editor tab, tap **Speed** in the bottom toolbar.
- Toggle **Constant** vs **Custom** at the top of the panel.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Editor or Trim is up (after [record-clip](./record-clip.md) / [import-video](./import-video.md)).
- Evidence dir `edit-speed/` created.

- **Open.** `control.ps1 tap -Label "Speed"`. Dump: `Slow down or speed up the video` / `Playback speed` / `speed_tab_panel`.
- **Constant.** Ensure `Constant` is selected. Tap the SeekBar track near a tick (not a drag of a fake thumb). Dump: `Current speed` text changed.
- **Custom.** `control.ps1 tap -Label "Custom"`. Dismiss intro if present (`Got it`). Dump: `Speed curve` / presets / `Current:`.
- **Preset.** Tap `Presets` if needed, then e.g. `Ease In`. Graph / current readout updates.
- **Leave.** `control.ps1 tap -Label "Constant"`.
- **Proof.** `speed-constant.txt`, `speed-curve.txt`. Preset pick is enough smoke for Custom; do not require freehand curve drawing.

## Gotchas

- Speed tick labels are not the control — the SeekBar content-desc is `Playback speed`.
- Custom intro is one-shot (DataStore); later runs may skip it.
- Curve point drag / add-point geometry breaks across densities — mark unreachable rather than thrash coordinates.
- Speed composes with Looks; it does not rebuild the reverse cache by itself.
- Part of the editor suite — full path: [edit-and-save](./edit-and-save.md).
