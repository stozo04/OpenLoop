# OpenLoop verification map

This directory is the maintained source for verifying the user-facing behavior of OpenLoop. Read the index before driving the app, then use the matching feature file as the recipe.

## Baseline preconditions

- One emulator attached. Serial in `VERIFY_SERIAL` if more than one `adb devices` line is `device`.
- Debug APK from **this** checkout installed (`io.github.stozo04.openloop`).
- `pwsh .codex/skills/verify-openloop/helpers/control.ps1 doctor` passes.
- Camera permission granted (`control.ps1 grant-camera` or install `-g`).
- Never drive a physical phone unless `VERIFY_ALLOW_DEVICE=1`.
- Never drive `com.OpenLoop.app`.

## Driving conventions

- Start every recipe from the baseline state unless its preconditions say otherwise.
- `control.ps1 dump` then `control.ps1 tap -Label "..."` (matches text or content-desc).
- Treat labels as literal. Keep punctuation (`LET'S GO!`, `B&W`).
- Restore seeded clips after a delete recipe. Do not remove proof artifacts during cleanup.

## Proof and skip reporting

- Capture dump-before, the tap, dump-after.
- Save/export proof includes the file or gallery tile, not only a success toast.
- Record the feature ID and entry point with every artifact.
- Report an unreachable path with the attempted label and the dump that lacked it.
- Do not report a skipped entry point as verified through a different path.
- Onboarding has an autonomous proof: `python scripts/run-verification-loops.py --changed`.

## Feature entry contract

Each feature file starts with an H1 title and one paragraph describing the user-visible behavior. It then uses exactly four H2 sections in this order: `Sub-features`, `How to get to it (user POV)`, `Driving it with control.ps1`, `Gotchas`.

## Completeness gate (before claiming the map is current)

Follow the **global** Cursor rule `feature-map-completeness` (all projects). OpenLoop specifics:

1. **Inventory** from (in order): `app/src/main/res/values/strings.xml` section comments; tappable chrome in `CameraScreen` / `GalleryScreen` / `BoomerangEditorScreen` / `EditorBottomToolbar` / lens carousel; sealed `OpenLoopUiState` routes. Treat `docs/PRD-*.md` as **optional** extras — many features shipped before PRDs existed.
2. **Diff** each item → `mapped` | `folded into <file>` | `missing` | `out of scope (why)`. Use [INVENTORY.md](./INVENTORY.md) when auditing.
3. **Gate** — do not call this map complete while a shipped user-visible surface is still `missing`. Orchestrators ([edit-and-save](./edit-and-save.md)) require each named Edit surface to have its own file under **Edit (post-capture)** below.
4. Show the user the remaining `missing` / `out of scope` list in the reply.

## Features

- [Onboarding](./onboarding.md) — first launch, `LET'S GO!`, then camera.
- [Record a clip](./record-clip.md) — shutter start/stop, land on Trim.
- [Lenses](./lenses.md) — carousel, pick/clear, 1–2 face tracking, bake into capture.
- [Photo capture](./photo-capture.md) — Camera mode stills (skip Trim/editor).
- [Photo booth](./photo-booth.md) — arm booth, countdown ×3, strip.
- [Pinch zoom](./pinch-zoom.md) — viewfinder pinch + zoom chip (gesture-fragile).
- [Import a video](./import-video.md) — Photo Picker → Trim (then save into library).
- [Gallery](./gallery.md) — open from camera, play or empty state, back, selection delete.
- [Share and library](./share-and-library.md) — dual save (Gallery + Photos), share sheet, gallery SEND.

### Edit (post-capture)

- [Trim](./edit-trim.md) — in/out window, SAVE into editor, discard dialog.
- [Speed](./edit-speed.md) — Constant slider + Custom curve / presets.
- [Loop](./edit-loop.md) — direction chips, reverse / Loopifying, help.
- [Filter (Looks)](./edit-filter.md) — look chips, preview, memory gate.
- [Delete clip](./edit-delete.md) — toolbar Delete → discard session → camera.
- [Save boomerang](./edit-save.md) — export / Creating.. → share + library handoff.
- [Edit and save (full path)](./edit-and-save.md) — orchestrates the suite end-to-end (`run-e2e` scope).
