# E2E Fold Loop — State

- Iteration: 2 (last run 2026-06-04_162455) — **LOOP COMPLETE: clean sweep confirmed**
- Source video: `C:\Users\gates\Personal\OpenLoop\google-pro-fold-video.mp4` 2,602,743 bytes ·
  5.447 s · 1280×720 H.264 8-bit SDR · 30 fps (real spacing 33,222 µs) · AAC + data track
- AVD names: `Pixel_6` | `Pixel_8` | `Pixel_10_Pro_Fold` (all Android 17 / API 37, 16k x86_64)
- Last iteration result: P6 PASS · P8 PASS · Fold PASS — zero code changes (iter-2 report:
  `2026-06-04_162455-iter-2.md`)
- Consecutive clean sweeps: 1

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

## Loop exit (2026-06-04)

- Iteration 2 confirmed the clean sweep — goal met, loop ended. Next step: open a PR from
  `feature/e2e-fold-loop-fixes` (full Definition-of-Done gate applies at PR time).
- Carry forward to PR/issues: BUG-2 decision; S23 re-verify recommendation (BUG-1 fix increases
  pass-1 encode count for low-jitter at-cap sources on every device incl. Samsung).
