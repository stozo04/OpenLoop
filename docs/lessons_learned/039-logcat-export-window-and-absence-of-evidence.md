# Lesson 039 — A logcat export is only as good as its window: check its span before reading anything into an absence

> Origin: the lens flick chase, 2026-08-26. Five commits added three touch-capture layers
> (`PinchZoomLayout` detector → Compose pointer probe → `Activity.dispatchTouchEvent` with a raw
> `VelocityTracker`) because "seven Fold logcats showed no viewfinder touch reaching the app." The
> touch chain was fine — the emulator proved it end to end (5/5 `Flick HIT`) — and the owner had
> been waving a **hand** at the camera, not flicking the screen. All three layers were deleted the
> same day (`docs/PRD-lens-hand-flick.md`).

## What went wrong

Each round of "here are the logs" was an Android Studio export (`.logcat` JSON), and each one was
read as a complete record of the session. It was not:

1. **The window was seconds long.** With a lens active ML Kit logs ~25 `ThickFaceDetector: Unknown
   landmark type` lines per frame — ~750 lines/s — so Studio's cycle buffer held **8–25 s**. The
   second export began 2 s *after* the owner's last gesture and ended with the activity going
   invisible as he opened the export dialog. Everything he did had already scrolled off.
2. **The live filter matched nothing the app logs.** The Studio filter was `Fling`; the app's tags
   were `OpenLoopFlick` / `OpenLoopLens` and its lines said `Flick HIT` / `Flick miss`. The owner
   could not have seen the gesture fire even when it did, and the export carries the filter string
   as metadata for anyone to read.
3. **An absence was read as a negative result.** "No viewfinder touch in the log" became "viewfinder
   touches do not reach the Activity on the Fold," and each new capture layer was placed one level
   deeper to catch a signal that was never emitted — because the gesture being performed was not a
   touch at all. The log had positive controls the whole time (button taps at the bottom of the
   screen logged perfectly); the honest reading of the same evidence was "no viewfinder touch
   happened in this window."

## Pattern

- **Print the window first.** Before drawing a conclusion from an export, print its first and last
  timestamp, its span, and how many lines carry the app's own tags. A window shorter than the
  session the user describes cannot support a claim about what did *not* happen in it.

  ```powershell
  $j = Get-Content 1.logcat -Raw | ConvertFrom-Json
  $m = $j.logcatMessages | Where-Object { $_.header.applicationId -like 'io.github.stozo04*' }
  "filter=$($j.metadata.filter) span=$($m[0].header.timestamp.seconds)..$($m[-1].header.timestamp.seconds) app lines=$($m.Count)"
  ```

- **Absence needs a positive control in the same window.** Point at a line you *know* the gesture
  path emits earlier than the missing one (a bind line, a button tap, a DOWN) and show it present.
  If the control is absent too, the window is wrong, not the code.
- **Establish what the user actually did before instrumenting.** A screen recording or the app's
  own saved clip settles "which gesture" in one look; three capture layers did not. Ask for it on
  the first round.
- **Capture hardware QA logs at the source, not in the IDE buffer.** For lens work:
  `adb logcat -s OpenLoopHand:* OpenLoopLens:* OpenLoopCameraManager:*` in a terminal — unbounded,
  tag-filtered, and immune to the face detector's spam. The Studio buffer is for reading, not for
  evidence.

## Detection checklist

- An export whose span is under a minute while a lens was active — the ML Kit spam has evicted it.
- `metadata.filter` in the export that is not one of the app's log tags — the reporter was looking
  at nothing.
- A commit message that says "instrument" or "probe" for the second time on the same question:
  stop and re-read the evidence's window before adding a third probe.
- A diagnosis that rests on a line *not* being present, with no line in the same window that
  proves the path was exercised.

## Reference

- [Logcat window in Android Studio](https://developer.android.com/studio/debug/logcat) — the cycle
  buffer and filter syntax (`tag:` matches a tag; a bare word matches message text).
- [`adb logcat` filter expressions](https://developer.android.com/tools/logcat#filteringOutput) —
  `-s <tag>:*` for a tag-only capture.
- `docs/PRD-lens-hand-flick.md` §1.1 (the emulator proof that the chain worked all along) and the
  Pixel_8 verification screenshots in that PR.
