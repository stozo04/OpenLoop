# E2E proof — Trim filmstrip decodes at tile size (Issue #149)

**Change:** `media/TrimFrameExtractor.kt` hands `MediaMetadataRetriever.getScaledFrameAtTime` the
smallest box whose fitted frame still covers a filmstrip tile (`filmstripDecodeBox`), instead of
`getFrameAtTime` at source size. Play's technical quality requirements put background bitmap
memory at 200 MB (P90, enforced Feb 2027 — `docs/play-store/README.md`); a 4K import on Trim was
over that line on its own.

| Run              | Date       | Device                                                            |
| ---------------- | ---------- | ----------------------------------------------------------------- |
| Before / after   | 2026-08-27 | Pixel_8 AVD, API 37, 4 GB — debug APK of `f2ff89a` vs this branch |

## The measurement

Fixture: a 3840×2160 H.264 clip, 6 s @ 30 fps, generated with
`ffmpeg -f lavfi -i testsrc2=size=3840x2160:rate=30 -t 6 -c:v libx264 -pix_fmt yuv420p`, pushed to
`/sdcard/Download/` and media-scanned. Flow: `pm clear` → onboarding → Gallery → *Import a video* →
photo picker → *Done* → Trim, then 15 s for the filmstrip to settle, then
`adb shell dumpsys meminfo io.github.stozo04.openloop`. Same script, same emulator, both APKs.

| `dumpsys meminfo` on Trim (KB)  | Before (`f2ff89a`) | After       | After + forced GC |
| ------------------------------- | ------------------ | ----------- | ----------------- |
| **Bitmap (malloced)**           | **259,224**        | 33,623      | **1,222**         |
| Native Heap (PSS)               | 264,456            | 103,504     | 38,836            |
| Java Heap (PSS)                 | 69,176             | 69,872      | 68,828            |
| TOTAL PSS                       | 442,422            | 278,966     | 213,631           |

- **Before:** ~253 MB of bitmaps — 8 filmstrip tiles × 3840×2160×4 B (the retriever returns
  `ARGB_8888`, which the numbers settle: RGB_565 would have been half). Over the 200 MB line with
  one screen.
- **After:** the 33 MB that remained is exactly one 4K ARGB frame and it is garbage, not state —
  a forced GC (`run-as io.github.stozo04.openloop kill -10 <pid>`, twice) took it to **1.2 MB**,
  which is the 8 tiles at 262×147 px (~154 KB each). Java heap is unchanged, so nothing moved
  from native to Java.
- Frame count on this AVD is 8 (track width ÷ 48 dp); the 14-tile worst case in the issue is a
  wide track, and scales the same way: ~2 MB, not ~465 MB.

## Screenshot

| File                                        | What it shows                                                                                                                                                    |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `2026-08-27-trim-filmstrip-4k-import.png`   | Trim on the after build with the 4K import: 8 filmstrip tiles rendered crisp at tile size (the `testsrc2` colour bars are legible), handles at 00:00.0 – 00:06.0 |

## What this run did not prove

- **The API 26 path** (`getFrameAtTime` + shrink + `recycle`) — `minSdk` only; there is no API 26
  AVD on this machine. It is pinned by `TrimFrameExtractorTest` on the JVM (box requested, full
  frame recycled, an in-box frame kept as-is).
- **Play's own number.** Android vitals P90 is the compliance figure; this is one device, one
  clip. The checklist row in `docs/play-store/README.md` keeps the monthly vitals check.
- The other two retriever callers are unchanged and out of scope: `extractThumbnail` (compressed to
  JPEG and dropped) and the editor's `extractRepresentativeFrame` (one retained frame, ~33 MB worst
  case on 4K — under the line, a candidate follow-up).
