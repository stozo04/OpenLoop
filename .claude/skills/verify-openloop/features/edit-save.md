# Edit — Save boomerang

Commits the current trim, speed, loop direction, and look into an exported loop. Triggers the Loopify / export pipeline (`Creating..`, optional FGS notification), then hands off to dual library write + share ([share-and-library](./share-and-library.md)).

## Sub-features

- `save-tap` — top-right `Save boomerang` (`editor_save`).
- `save-processing` — processing UI / notification (`Creating..`, `processing_screen`, or `Creating your loop…`).
- `save-success` — share sheet and/or return with snackbar path; new `boom_*.mp4` / Gallery tile (prove via [share-and-library](./share-and-library.md)).
- `save-failure` — snackbar `Couldn't save your loop…` with optional `Send debug report` (product failure if hit on a healthy AVD).

## How to get to it (user POV)

- Finish enough editing that the preview is valid (trim window long enough; reverse directions finished or forward-only).
- Tap the check / **Save boomerang** control.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Editor showing `Save boomerang`.
- Prefer at least one intentional edit from [edit-trim](./edit-trim.md) / [edit-speed](./edit-speed.md) / [edit-loop](./edit-loop.md) / [edit-filter](./edit-filter.md) before save when proving those features; bare save after Trim→SAVE is still a valid export smoke.
- Start logcat before tap (`.claude/skills/run-e2e/scripts/scan-logcat.ps1` afterward).
- Evidence dir `edit-save/` created.
- Count Gallery tiles before save.

- **Save.** `control.ps1 tap -Label "Save boomerang"`.
- **Wait.** Allow export to finish (seconds to tens of seconds). Dump may show processing, then share sheet.
- **Aftermath.** Follow [share-and-library](./share-and-library.md): sheet, `Saved to Photos`, Gallery tile++.
- **Proof.** `before-save.txt`, `after-save.txt`, logcat scan path, gallery count note. A CRASH row is a product failure.

## Gotchas

- Share sheet **is** success — dismissing with BACK is normal.
- Save without changing tabs still exports defaults (often Boomerang direction + default speed).
- Failure snackbar + debug report is a support path, not the happy path.
- Orchestrated multi-tab run: [edit-and-save](./edit-and-save.md). Pixel sweep: `.claude/skills/run-e2e-pixel-sweep/`.
