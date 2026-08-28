# Record a clip

From the camera viewfinder the user taps the shutter to start a video (up to 30 s) and taps again to stop. A clip that is long enough leaves the camera and opens Trim. A tap that is too short stays on camera with snackbar `That was quick! Record a little longer to make a loop.`

## Sub-features

- `record-start` begins recording (`Start recording` → `Stop recording`, progress ring).
- `record-stop` ends recording and opens Trim (`trim_screen` / `TRIM YOUR VIDEO`).
- `record-too-short` stays on camera and shows the snackbar.
- `record-flip` uses `Flip Camera` before recording (optional; prove desc exists).

## How to get to it (user POV)

- Finish onboarding if shown (`LET'S GO!`).
- Grant camera if the rationale is on screen (`Grant Permission`).
- Confirm capture mode is **Video** (`capture_mode_selector`, label `Video`) not Camera/stills and not Photo Booth.
- Tap the large shutter at the bottom center.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Viewfinder is up (`Start recording` or `Flip Camera` / `Gallery` in the dump).
- Mode is Video. If dump shows `Take photo` as the shutter desc, tap `Video` on `capture_mode_selector`.
- Photo booth is off (no `Start photo booth`, no `Turn off photo booth`).
- Evidence dir `record-clip/` created.

- **Confirm idle camera.** `control.ps1 dump` → `Start recording`, `Gallery`, `Flip Camera`, `Lenses` or `Lenses and Photo Booth`.
- **Start.** `control.ps1 tap -Label "Start recording"`. Wait ~3–4 s. Dump shows `Stop recording` and a timer such as `6s / 30s` (testTag `progress_ring` is not in the uiautomator dump).
- **Stop.** `control.ps1 tap -Label "Stop recording"`. Wait for Trim. Dump shows `TRIM YOUR VIDEO` and `SAVE` (also tab labels `Trim` / `Speed` / `Loop` / `Filter`). testTags `trim_screen` / `trim_save` are not in the dump.
- **Too-short (optional).** From idle camera, start and stop immediately. Dump still has `Start recording` and text `That was quick!`.
- **Proof.** `dump-before.txt` (idle), `dump-after-start.txt`, `dump-after-stop.txt` (aliases: `idle.txt`, `recording.txt`, `trim.txt`). Trim on screen is the resulting state. Do not call this feature saved — that is [edit-and-save](./edit-and-save.md).

## Gotchas

- Shutter desc changes with mode: `Start recording` / `Stop recording` / `Take photo` / `Start photo booth`. Tap the wrong one and you are in stills or booth.
- `run-e2e` used raw coordinates `540,2155` on 1080×2400. Prefer the desc; coordinates break on fold / density.
- Import (`Import a video` / gallery empty `…or import one`) is a different entry into Trim. Do not count import as `record-clip`.
- Permission rationale can sit on top of the shutter. Dump first.
- Front/back flip (`Flip Camera`) does not by itself prove a recording.
