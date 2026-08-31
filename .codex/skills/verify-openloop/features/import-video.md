# Import a video

Bring a clip from the phone's Photos / Files picker into OpenLoop. Import copies the video into the app session and opens **Trim** (same editor path as a fresh recording). It does **not** add a gallery tile by itself — saving the loop does that. Clips longer than 30 s or shorter than ~0.5 s get a length dialog instead of Trim.

## Sub-features

- `import-from-gallery` starts from the gallery chrome (`Import a video` content-desc on the action bar).
- `import-from-empty` starts from the empty gallery link (`…or import one`).
- `import-success` lands on Trim (`TRIM YOUR VIDEO` / `SAVE`) after a valid pick.
- `import-too-long` shows `That clip's a bit long` / `Got it` for >30 s sources.
- `import-too-short` shows `That clip's a bit short` / `Got it` for sub-minimum sources.
- `import-failed` shows snackbar `Couldn't import that video.` for unreadable URIs (hard to force on purpose).

## How to get to it (user POV)

- Open [Gallery](./gallery.md) from the camera.
- Tap **Import a video** (top action bar) or, when empty, **…or import one**.
- Pick a video in the system Photo Picker / document UI.
- Edit and save like any other clip ([edit-trim](./edit-trim.md) → … → [edit-save](./edit-save.md)) to land it in the in-app library and Photos.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Gallery open (or camera → Gallery).
- A fixture under ~30 s is available on the emulator (canonical: `google-pro-fold-video.mp4` when present; often gitignored). Without a fixture, stop after opening the picker and mark remaining steps **verified-unreachable**.
- Prefer the pixel-sweep drive when the fixture exists: `.claude/skills/run-e2e-pixel-sweep/scripts/drive-flow.ps1`.
- Evidence dir `import-video/` created.

- **Open gallery.** `control.ps1 tap -Label "Gallery"`.
- **Start import.** `control.ps1 tap -Label "Import a video"` (empty: `…or import one`). System picker appears — uiautomator may leave the app hierarchy.
- **Pick.** Use the picker UI or the scripted sweep to select the fixture. Wait for Trim (or a length dialog).
- **Success.** Dump: `TRIM YOUR VIDEO`, `SAVE`. Continue into editor/save only if proving the full import→library path.
- **Too long / too short.** If you have fixtures for those lengths, confirm dialog title + `Got it`, then dismiss. Otherwise leave unverified.
- **Proof.** `gallery.txt`, `after-import.txt` (Trim or dialog). Do not count "picker opened" as `import-success`.

## Gotchas

- Import is **not** [record-clip](./record-clip.md). Do not report a recording as import.
- Import does not write a gallery tile until [share-and-library](./share-and-library.md) save succeeds.
- Lenses never apply to imported pixels (capture-time only).
- System picker is outside OpenLoop — dumps inside the app will look empty while it is up. Use `adb shell uiautomator dump` against the picker package if needed, or rely on the pixel-sweep script.
- Length dialogs are product gates (issue #95 / slice 07), not crashes.
