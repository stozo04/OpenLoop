# E2E Fold Loop — State

- Iteration: 1 (last run 2026-06-04_160734)
- Source video: `C:\Users\gates\Personal\OpenRang\google-pro-fold-video.mp4` 2,602,743 bytes ·
  5.447 s · 1280×720 H.264 8-bit SDR · 30 fps (real spacing 33,222 µs) · AAC + data track
- AVD names: `Pixel_6` | `Pixel_8` | `Pixel_10_Pro_Fold` (all Android 17 / API 37, 16k x86_64)
- Last iteration result: P6 PASS · P8 PASS · Fold PASS — **on the post-fix APK; a mid-run code
  fix was needed, so this does not count as a clean sweep**
- Consecutive clean sweeps: 0

## Open bugs

- [BUG-2] Pass-1 subsampling (engages only for >30 fps sources) drops compressed samples before
  the decoder → broken P-frame chains → macroblock smear in the reversed half — root cause
  `VideoReverser.kt` `runDecodeEncodeLoop` (skip-at-input, ~:543-579); correct fix is
  skip-at-render (`releaseOutputBuffer(idx, false)`). Fix attempts 0/3 — **deliberately
  deferred**: not reachable with the loop's 30 fps source post-BUG-1-fix, and restructures the
  decode loop the verified S23 save depends on. Needs owner decision (fix w/ synthetic 60 fps
  clip vs standalone issue).

## Fixed this loop

- [BUG-1] At-cap (30 fps) sources subsampled to 15 fps + reversed-half macroblock corruption
  (timestamp-jitter floor comparison in `pass1SampleAction`) — commit `731b26b`, verified on
  Pixel 6 (pre/post evidence), Pixel 8, Fold.

## Notes for the harness (cost time in iter 1)

- Pull app-private files with `cmd /c "adb exec-out run-as io.github.stozo04.openloop cat <path> > out"`
  — `run-as … > /sdcard/…` writes 0 bytes; PowerShell pipes mangle binary.
- Fresh-install saves trigger the POST_NOTIFICATIONS dialog — dismiss it (tap Allow by coords).
- Fold AVD: `emu fold` locks the screen; after `emu unfold` run `wm dismiss-keyguard` +
  `input keyevent KEYCODE_WAKEUP`.
- After any reverser fix, `pm clear` the app before re-running — the trim-keyed reverse cache
  will otherwise serve the pre-fix artifact.
- Speed slider is continuous: tap ~35 px right of the "1x" tick label center (Pixel 6/8 scale).
- CHURN baseline (single clean run, Pixel 8): 45 `Created component [c2.*]`.

## Next iteration starts with

- Full 3-device sweep on commit `731b26b` (or later) with ZERO code changes — that confirms the
  clean sweep and ends the loop (announce + recommend PR).
- Carry forward: BUG-2 decision; recommend S23 re-verify (BUG-1 fix doubles pass-1 encode count
  for low-jitter at-cap sources on every device incl. Samsung).
