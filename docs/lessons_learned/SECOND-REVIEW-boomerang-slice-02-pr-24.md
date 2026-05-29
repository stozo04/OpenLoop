# SECOND REVIEW — Boomerang Slice 02 (PR #24)

> **Temporary session doc, NOT a numbered lesson** — same convention as
> [`HANDOFF-boomerang-slice-02-pr-24.md`](./HANDOFF-boomerang-slice-02-pr-24.md). It lives here so the
> startup "read every file in `lessons_learned/`" mandate guarantees the next session sees it.
> **Delete it once PR #24 merges** — but first distill anything durable into a real numbered lesson
> (rotation-through-MediaCodec-surface is a strong candidate; see "Lesson seeds" at the bottom).

This captures a focused second review of three owner-flagged uncertainties (W2 rotation, W5 fixture,
W4 TalkBack) plus one bonus finding, what was **changed in code**, and — most importantly — **what is
still unverified and must be confirmed on the Fold**. The code changes were made to convert as much of
the risk as possible from "device-dependent gamble" into "deterministic + on-device confirmation."

---

## What changed in this pass (all on `feature/boomerang-slice-02-trim-and-default-save`)

| Area | File | Change | Why |
|------|------|--------|-----|
| **W2** | `media/VideoReverser.kt` | After reading `KEY_ROTATION`, **neutralize it on the decoder input format** (`setInteger(KEY_ROTATION, 0)`) in **both** passes; still re-stamp the hint on the muxer. | Removes the device-dependent **double-rotate** risk (see below). |
| **W5** | `app/src/androidTest/assets/trim_fixture.mp4` (new) | A real **1 s 320×240 H.264** clip that **fades black→white**. ⚠️ **Generation pending** — see command below; a tool outage blocked the `ffmpeg` run this session. | The test then **runs** (not skips) on every device, and the fade gives the reversal order-guard real signal (first frame dark, last frame bright). |
| **W5** | `media/VideoReverserTest.kt` | Fixed integer-division flatten edge: `(frameIndex * 219) / frameCount`. | A larger `frameCount` could collapse the synthetic ramp to a vacuous all-one-color clip (silent green). |
| **W4** | `ui/TrimScreen.kt` | Added `Modifier.semantics { hideFromAccessibility() }` to the full-width pointer overlay. | Makes the overlay explicitly a11y-transparent so TalkBack focus reaches the handles beneath it — and so the device pass validates the **shipping** config. |
| **Bonus** | `media/VideoProcessor.kt` | `frameDurationMs()` now uses `frameRateOrDefault()` instead of `getInteger(KEY_FRAME_RATE)`. | Same `ClassCastException`-on-Float trap `MediaFormatUtils` already guards; it was being silently masked → wrong seam offset. |

---

## W2 — Rotation: the part that is now deterministic, and the part that still needs the Fold

**Verified (live docs, not training data):**
- `MediaCodec` **auto-applies rotation in Surface-output mode**, but whether the
  decoder→encoder-**input**-surface path *bakes* it into pixels (vs. carrying it as a surface
  transform the encoder ignores) is **device-dependent** — Android's own guidance is "no way to know
  other than trying it." Source: the MediaCodec reference + community consensus ("manually rotate
  before encoding").
- Media3's per-item effects + GL transcode path normalizes orientation from the input hint — but the
  docs **only** document *manual* `ScaleAndRotateTransformation`; auto-application of input
  `KEY_ROTATION` is **not contractually documented**.

**What the code change buys us:** by stripping `KEY_ROTATION` from the decoder format, the reversed
file is now *guaranteed* to be "coded-orientation pixels + metadata-only hint" — structurally
identical to the raw source — **regardless of device**. That kills the double-rotate branch.

**STILL UNVERIFIED — must A/B on the Fold with a portrait recording:**
1. **Does Media3 1.10.1 actually read the rotation hint and render both halves upright?** (Assumption
   B.) If it does → correct & symmetric. If it does *not* auto-apply input rotation, **both** halves
   render sideways but symmetrically, and the *final* boomerang would need its own orientation fix
   (the Transformer output currently relies on Media3, not an explicit hint).
2. **Eyeball:** forward half and reversed half must have the **same** orientation; first frame of the
   reverse half ≈ last frame of the trimmed source, **right way up**.

If orientation is still wrong after this change, it is **no longer the reverser** — look at the
Transformer/Composition output orientation, and consider an explicit `ScaleAndRotateTransformation`
or setting an output orientation hint.

---

## ⚠️ Heads-up I'd want if I were picking this up cold

1. **The reversed half's 2× speed is NOT yet proven on device — check it in the same A/B pass.**
   [androidx/media #1658](https://github.com/androidx/media/issues/1658) reported that in an
   `EditedMediaItemSequence` **only the first item's effects were applied** (v1.4.1; marked Closed).
   Our `FORWARD_THEN_REVERSE` puts the reversed item **second**, and it carries the `SpeedChangeEffect`
   per-item. If that bug is present/regressed in 1.10.1, the **reverse half would play at 1× while the
   forward half plays at 2×**, with a speed jump at the seam. The current per-item approach matches
   the *documented intended* API, so I did **not** change it — but **watch the reverse half's speed**
   when you validate. If it's wrong, the fix is to apply speed once at the Composition level (verify
   that API exists in 1.10.1 first — do not guess).

2. **`connectedDebugAndroidTest` will now genuinely exercise `VideoReverser`.** Before this pass it
   could `assumeTrue`-skip (synthetic generator no-op = "green" that proved nothing). With the bundled
   fixture it should **run**. After your device run, **confirm the count**: `VideoReverserTest` should
   show 4 tests *run*, not *skipped/ignored*. A skip here is now a real signal that the asset didn't
   package, not an expected fallback.

3. **The bundled fixture is a flat-per-frame fade.** That's deliberate (deterministic luma ramp), but
   it means the test does **not** exercise spatial motion or a real-world bitrate. It proves
   *temporal reversal correctness*, not *visual quality*. Visual quality is still a manual-QA item.

4. **W4 traversal order is not asserted, only reachability.** I made the overlay a11y-transparent, but
   I could not run TalkBack here. In your 10-min pass also confirm **focus order** (start handle →
   end handle is sensible) and that **swipe-up/down adjusts** a focused handle (that fires
   `setProgress`; double-tap does not). If order is scrambled, add `isTraversalGroup = true` on the
   `TrimBar` `BoxWithConstraints` + `traversalIndex` on the thumbs — I left that out to avoid shipping
   an untested a11y reordering.

5. **`setProgress → commit` snapshot read is sound by construction** — same-thread, same-snapshot
   `mutableLongStateOf` writes are visible to the immediate next read, so the commit sees the updated
   value. No code change needed; the device pass is confirmatory only.

6. **Reverse latency:** unchanged by this pass. The handoff's honest estimate stands (~2–3 s
   cache-miss for a 3 s trim, dominated by pass-2 per-frame seeking). Report the **true** Fold number;
   don't tune the trim down to hit 1.5 s.

---

## Lesson seeds (promote to numbered lessons if they survive the device pass)

- **Reversing/transcoding through a MediaCodec decoder→encoder *input* Surface:** strip `KEY_ROTATION`
  from the decoder format and re-stamp it on the muxer, because Surface-mode auto-rotate + a muxer
  hint can double-rotate on some devices. (Confirm direction on the Fold before writing it as law.)
- **Reuse `frameRateOrDefault()` everywhere a frame rate is read** — `getInteger(KEY_FRAME_RATE)`
  throws on Float-typed values; a broad catch hides it as a silent default.
- **A test that can `assumeTrue`-skip its only fixture is not coverage** — bundle a real asset so the
  suite runs, not skips.
