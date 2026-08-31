# Edit — Trim

Sets the in/out window on the captured or imported clip before (or while) editing. Trim is both the first post-capture screen (`TRIM YOUR VIDEO`) and a bottom-toolbar tab that returns to the filmstrip. Handles and the duration range label (`trim_range_label`) show the active window.

## Sub-features

- `trim-screen` — land on Trim after [record-clip](./record-clip.md) or [import-video](./import-video.md) (`trim_screen`, `TRIM YOUR VIDEO`, `SAVE`).
- `trim-handles` — drag start/end (`Trim start` / `Trim end`) so the range label changes.
- `trim-save-advance` — tap `SAVE` (`trim_save`) when the window is long enough; advances into the tabbed editor.
- `trim-tab-return` — from Speed/Loop/Filter, tap toolbar `Trim` to show the filmstrip again (`trim_bar` / `trim_filmstrip_controls`).
- `trim-discard` — back / discard control opens `Discard this clip?` (`Discard` / `Keep`).

## How to get to it (user POV)

- Record or import a clip → Trim is automatic.
- Or from the editor toolbar, tap **Trim**.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Dump shows Trim or the editor with bottom toolbar.
- Evidence dir `edit-trim/` created.
- Prefer three-button nav (edge drags otherwise fire predictive back — see `run-e2e`).

- **Confirm Trim.** Dump: `TRIM YOUR VIDEO` or `Trim`, `SAVE`, duration-ish label.
- **Handles.** Slow-drag start/end. If the range label does not change after a few tries, record a finding and continue — gesture is fragile on emulators.
- **Advance.** `control.ps1 tap -Label "SAVE"`. Dump moves toward editor chrome (`Speed` / `Loop` / `Filter` / `Save boomerang`) when the window is valid.
- **Return via tab.** From another tab: `control.ps1 tap -Label "Trim"`. Filmstrip visible again.
- **Discard (optional).** Tap discard / back → `Discard this clip?`. Prefer `Keep` unless this run intends to abort. Confirm path uses `Discard` (`discard_confirm`) → camera.
- **Proof.** `trim.txt`, optional `after-handle.txt`, `after-save.txt`.

## Gotchas

- Too-short window after handles can block SAVE or show product dialogs — do not call that a crash.
- Trim edge drags vs system back: tap `Keep` if `Discard this clip?` appears unexpectedly.
- Trim alone does not write Gallery / Photos — that is [edit-save](./edit-save.md) + [share-and-library](./share-and-library.md).
- Full multi-tab smoke: [edit-and-save](./edit-and-save.md).
