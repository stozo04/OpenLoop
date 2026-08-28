# OpenLoop verification map

This directory is the maintained source for verifying the user-facing behavior of OpenLoop. Read the index before driving the app, then use the matching feature file as the recipe.

## Baseline preconditions

- One emulator attached. Serial in `VERIFY_SERIAL` if more than one `adb devices` line is `device`.
- Debug APK from **this** checkout installed (`io.github.stozo04.openloop`).
- `pwsh .cursor/skills/verify-openloop/helpers/control.ps1 doctor` passes.
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

## Feature entry contract

Each feature file starts with an H1 title and one paragraph describing the user-visible behavior. It then uses exactly four H2 sections in this order: `Sub-features`, `How to get to it (user POV)`, `Driving it with control.ps1`, `Gotchas`.

## Features

- [Onboarding](./onboarding.md) — first launch, `LET'S GO!`, then camera.
- [Record a clip](./record-clip.md) — shutter start/stop, land on Trim.
- [Edit and save a loop](./edit-and-save.md) — Trim / Speed / Loop / Filter, then Save boomerang.
- [Gallery](./gallery.md) — open from camera, play or empty state, back.
- [Photo booth](./photo-booth.md) — arm booth, countdown ×3, strip.
