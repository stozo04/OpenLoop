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

## Autonomous check

Run it from the repository root:

```powershell
python .codex/skills/verify-openloop/helpers/record_clip_loop.py
```

`python scripts/run-verification-loops.py --changed` runs it alongside every other loop. Both take
`VERIFY_SERIAL` when more than one emulator is online and `VERIFY_EVIDENCE_DIR` for the artifacts.

It installs the current debug APK, grants CAMERA, and drives all three outcomes of a shutter tap:
a sub-400 ms double tap (stays on camera, `That was quick!` snackbar, no clip), a few seconds
(Trim opens, scratch clip on disk, `Capture finalized (Nms)` in logcat), and a recording left to
run out (the 30 s cap finalizes it with no stop tap). Every countdown sample is asserted as
`<seconds>s / 30s` — never a `mm:ss` clock. Both clips are thrown away through the Discard dialog,
so nothing is saved and the gallery is untouched.

Expect roughly **75 seconds** on a healthy Pixel_8_API34 AVD, where the cap produces a 31.2 s clip.
The elapsed counter accumulates 33 ms per tick instead of reading a clock, so a device that cannot
hold the cadence overruns: the same AVD under host memory pressure took 219 s and produced a
**143 s** clip for the same 30 s cap. The cap is therefore asserted as "no stop tap, no error
finalize, Trim opened, clip ≥ 25 s" — never a wall-clock ceiling, and never how high the chip had
climbed at the last dump, which measures the polling interval rather than the product.

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
- Import (`Import a video` / gallery empty `…or import one`) is a different entry into Trim — see [import-video](./import-video.md). Do not count import as `record-clip`.
- Active [lenses](./lenses.md) bake into the recording; prove lens UI separately if that is the claim.
- Permission rationale can sit on top of the shutter. Dump first.
- The too-short snackbar covers the shutter row: while it is up, `Start recording` / `Flip Camera` are not in the dump at all. Prove "still on camera" with the mode selector, then re-check the shutter once it clears.
- A uiautomator dump takes seconds and the snackbar lasts four, so polling with dumps loses that race. Wait on logcat (`Video burst recording failed` / `below the 400ms minimum`), then dump once.
- Under host memory pressure the AVD's own system ANRs (`System UI isn't responding`, package `android`, in the dump) and every recipe here fails at the first step. That is the host, not the app — free memory and cold-boot the AVD (`-no-snapshot-load`); `adb reboot` restores the same broken state.
- Front/back flip (`Flip Camera`) does not by itself prove a recording.
