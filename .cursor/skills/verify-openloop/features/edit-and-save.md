# Edit and save a loop

After a clip exists on Trim, the user can change the trim window, playback speed, loop direction, and look, then save. Save writes a `boom_*.mp4`, opens a share sheet, and the clip appears in Gallery. This is the `run-e2e` happy path.

## Sub-features

- `edit-trim` is on Trim (`tab_trim`, `trim_bar`) with a duration range label.
- `edit-speed` opens Speed (`tab_speed`) and changes `Playback speed` / `Current speed`.
- `edit-loop` opens Loop (`tab_loop`) and picks a direction chip (`Forward loop` is the fast, no-reverse choice).
- `edit-filter` opens Filter (`tab_filter`) and picks a non-Original look (e.g. `B&W`).
- `edit-save` taps `Save boomerang` (`editor_save`) and gets a share sheet or gallery file.

## How to get to it (user POV)

- Record a clip ([record-clip](./record-clip.md)) or import a video from Gallery.
- On Trim, use SAVE or the Speed / Loop / Filter tabs. SAVE on Trim advances into the tabbed editor when the window is long enough.
- Top-right confirm on the editor is `Save boomerang`.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Dump shows Trim (`TRIM YOUR VIDEO` / `trim_screen`) or the editor (`editor_screen`, `editor_bottom_toolbar`).
- Prefer the scripted pixel-sweep drive when the canonical file `google-pro-fold-video.mp4` is present: `.claude/skills/run-e2e-pixel-sweep/scripts/drive-flow.ps1`. That **is** this feature, with stronger proof.
- Otherwise follow `.claude/skills/run-e2e/SKILL.md` section 3 (one change per tab) using `control.ps1 tap` instead of raw `input tap` where a label exists.
- Evidence dir `edit-and-save/` created. Logcat capture started before save (see `run-e2e`).

- **Trim.** Dump contains `Trim` / `SAVE`. Changing handles is gesture-fragile; `run-e2e` documents three-button nav and slow drags. If the duration label does not change after a few tries, record that as a finding and continue.
- **Speed.** `control.ps1 tap -Label "Speed"`. Dump: `speed_tab_panel`, `Playback speed`. Tap the seek bar track (not a drag) near a tick. `Current speed` updates.
- **Loop.** `control.ps1 tap -Label "Loop"`. Dump: `loop_tab_panel`. Tap `Forward loop` (`direction_chip_*`). Reverse-needing directions can take up to 120 s (`Loopifying..` / `editor_loading_message`).
- **Filter.** `control.ps1 tap -Label "Filter"`. Dump: `filter_tab_panel`. Tap a look chip that is not Original.
- **Save.** `control.ps1 tap -Label "Save boomerang"`. Share sheet or `boom_*.mp4`. Dismiss with BACK. Open Gallery and confirm a new tile (`gallery_tile_*`).
- **Proof.** Dumps per tab, logcat scan via `.claude/skills/run-e2e/scripts/scan-logcat.ps1`. A CRASH row is a product failure, not a skill miss. Copy the `run-e2e` report path into the evidence dir.

## Gotchas

- Default loop direction is already `Forward then reverse` (Boomerang). Picking it again does not prove a change. Use `Forward loop` for a fast pass; use a reverse direction when you mean to exercise `VideoReverser`.
- Trim edge drags fire Android back gesture and `Discard this clip?`. Tap Keep. Enable three-button nav as in `run-e2e`.
- Speed ticks are labels; the control is the SeekBar `Playback speed`. Tap the track.
- `Preview unavailable` on Filter is a finding (often stale reverse error).
- Share sheet is success, not a failure to return to camera.
- Do not call Robolectric or `connectedDebugAndroidTest` this feature. Those are tests; this is the real APK.
