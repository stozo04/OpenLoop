# Edit and save a loop (full path)

End-to-end editor smoke: after a clip exists, exercise the major Edit surfaces, then export. Prefer the **per-surface** recipes when proving one area; use this file when the claim is the whole Trim → Speed → Loop → Filter → Save pipeline (same scope as `run-e2e` / pixel-sweep).

## Sub-features

- `edit-trim` → [edit-trim](./edit-trim.md)
- `edit-speed` (constant and/or custom curve) → [edit-speed](./edit-speed.md)
- `edit-loop` → [edit-loop](./edit-loop.md)
- `edit-filter` → [edit-filter](./edit-filter.md)
- `edit-delete` (optional abort path) → [edit-delete](./edit-delete.md)
- `edit-save` → [edit-save](./edit-save.md) then [share-and-library](./share-and-library.md)

## How to get to it (user POV)

- Record a clip ([record-clip](./record-clip.md)) or [import a video](./import-video.md).
- On Trim, adjust the window if needed, tap **SAVE**, then use **Speed** / **Loop** / **Filter**.
- Tap **Save boomerang**. Share or dismiss; confirm Gallery + Photos.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Dump shows Trim or the editor.
- Prefer `.claude/skills/run-e2e-pixel-sweep/scripts/drive-flow.ps1` when `google-pro-fold-video.mp4` is present.
- Otherwise `.claude/skills/run-e2e/SKILL.md` section 3 (one change per tab) via `control.ps1 tap`.
- Evidence dir `edit-and-save/` created. Logcat on before save.

- Drive each linked recipe in order (Trim → Speed → Loop → Filter → Save). Skip Delete on the happy path.
- For Loop, use `Forward loop` unless this run is meant to stress reverse.
- After Save, complete [share-and-library](./share-and-library.md) proof.
- **Proof.** Per-tab dumps under `edit-and-save/`, plus `run-e2e` / sweep report path. A CRASH row is a product failure.

## Gotchas

- This file is an **orchestrator**. Do not mark Trim “verified” here if you only opened Speed — use [edit-trim](./edit-trim.md).
- Default loop is already Boomerang; re-picking it proves nothing.
- Trim handle drags vs system back → `Keep` / three-button nav.
- Share sheet success details live under [share-and-library](./share-and-library.md).
- Not a substitute for unit / Robolectric / `connectedDebugAndroidTest`.
