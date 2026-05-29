# KICKOFF — Boomerang Slice 03 (Direction tab)

> **Temporary session doc, NOT a numbered lesson** — same convention as the slice-02
> [`HANDOFF`](./HANDOFF-boomerang-slice-02-pr-24.md) and
> [`SECOND-REVIEW`](./SECOND-REVIEW-boomerang-slice-02-pr-24.md). It exists because CLAUDE.md makes
> every session read this whole folder at startup. **Delete it when slice 03 merges** — but first
> promote anything that survived the device pass into a real numbered lesson (the seam-per-ordering
> rule and the per-item-effects finding are the strongest candidates).

You're starting [`docs/active/boomerang-rollout/03-editor-direction-tab.md`](../active/boomerang-rollout/03-editor-direction-tab.md).
Read **that** (it's the PRD), then [`RESEARCH-reverse-video.md`](../active/boomerang-rollout/RESEARCH-reverse-video.md),
then this. This doc is only the things the slice-03 PRD does **not** tell you and that I learned the
hard way reviewing slice 02. Read order matters; don't skip the two above.

---


## 1. THE slice-03 landmine: per-item effects in a sequence (#1658)

This is the highest-risk item and slice 03 is exactly what surfaces it. Speed is applied **per
`EditedMediaItem`**. [androidx/media #1658](https://github.com/androidx/media/issues/1658) reported
that in an `EditedMediaItemSequence` **only the first item's effects are applied** (real in 1.4.1,
marked Closed — *unconfirmed in our 1.10.1*). If that bug is live, then with the `SpeedChangeEffect`
on every item:

- `FORWARD_THEN_REVERSE` → forward (1st) plays 2×, **reverse half (2nd) plays 1×**.
- `REVERSE_THEN_FORWARD` → reverse (1st) plays 2×, **forward half (2nd) plays 1×**.

So **one half of every two-part boomerang would play at the wrong speed, and which half flips with the
user's direction choice.** Maximally visible and confusing.

**Verify on device before building the UI:** render `F→R` and `R→F`, confirm **both halves play at
2×**. If broken, move speed to a **Composition-level** effect instead of per-item — but **verify that
API exists in Media3 1.10.1 first** (don't guess; cross-check the
[[reference-media3-1-10-1-transformer-api]] memory and the source tag). This matters even more in
slice 04 when speed becomes user-controlled.

---

## 2. The seam-drop location FLIPS per direction — `VideoProcessor` needs a real refactor, not an `if`

Slice 02's `VideoProcessor` hard-codes the 1-frame seam drop onto the **reversed** clip
(`reverseItem()` does `setStartPositionMs(seamOffsetMs)`), because in `FORWARD_THEN_REVERSE` the
reversed clip is always **second**. Slice 03 makes ordering user-selectable, and the seam rule is
actually: **drop the first frame of whichever clip is SECOND in the sequence** — because the seam
duplicate is "last frame of clip 1 == first frame of clip 2."

Walk it through (this is the trap):

| mode | sequence | drop the head of… | current slice-02 code does… |
|------|----------|-------------------|------------------------------|
| `FORWARD` | `[forward]` | nothing (no seam) | builds via `forwardItem` (no drop) ✓ |
| `REVERSE` | `[reversed]` | **nothing (no seam!)** | would build via `reverseItem` → **drops a real frame ✗** |
| `FORWARD_THEN_REVERSE` | `[forward, reversed]` | reversed (2nd) | drops reversed head ✓ |
| `REVERSE_THEN_FORWARD` | `[reversed, forward]` | **forward (2nd)** | drops reversed head → **wrong clip ✗** |

So a naïve "just reorder the list" gives you a lost frame on standalone `REVERSE` and a wrong-clip
drop + un-dropped real seam on `R→F`. **Fix:** make the seam offset a property of *sequence position*
(apply it to item[1] of a 2-item list, never to a lone item), not a property of "the reversed clip."
The slice-03 PRD's table says "drop 1 frame seam" for both two-clip modes but does **not** spell out
that the drop location moves — that omission is the bug waiting to happen.

---

## 3. Preview is a NEW problem — the slice-02 trick does not carry over

Slice 02's trim preview was a single clip with a manual `seekTo` poll-loop (a workaround for ExoPlayer
deduping a re-clipped same-URI `MediaItem`; see the slice-02 HANDOFF). Slice 03 must preview the
**concatenated boomerang** (e.g. `[forward, reversed]` looped). ExoPlayer **cannot reverse natively**
([ExoPlayer #2191](https://github.com/google/ExoPlayer/issues/2191)), which is why the reversed *file*
is fed as a playlist item.

Two viable paths (the PRD leaves it open — decide deliberately):
- **`ConcatenatingMediaSource2`** (ExoPlayer playlist of `[forwardClip, reversedFile]`, `REPEAT_MODE_ALL`).
  Simpler, but the preview is *not* the render path — speed effect + seam can look different in preview
  vs. the exported Transformer `Composition`. Preview/export fidelity drift is a real QA risk.
- **`CompositionPlayer`** (`androidx.media3.transformer.CompositionPlayer`, exists in 1.10.x) previews
  the *same* `Composition` the renderer exports → preview == export, no drift. Heavier, newer, and has
  setup quirks ([androidx/media #1983](https://github.com/androidx/media/issues/1983)).

My lean: if you want WYSIWYG, `CompositionPlayer` is worth the cost because it kills the drift class of
bugs outright. But prototype it early — if it fights you, `ConcatenatingMediaSource2` is the safe
fallback. Either way, **watch for the same-URI re-clip dedup gotcha** when you rebind the preview on a
direction change.

---

## 4. `ensureReversedSegment()` concurrency — guard it

The editor calls reverse **eagerly on entry** *and* **on direction change**, and the user can spam the
4 chips. `VideoReverser.reverse()` checks the cache at the top, but **two concurrent calls for the same
trim both miss the cache before the output file exists** → two full two-pass runs racing to write the
same output path. Serialize it: a single `Job` you cancel-and-replace, or a `Mutex`, or "ignore if a
run for this exact key is in flight." The PRD doesn't mention this; it will bite under fast chip-taps.

---

## 5. The "VideoProcessorTest (JVM)" in the PRD is probably wrong — it needs a device

Slice 03's testing plan asks for `VideoProcessorTest` as a **JVM** test with a fixture in
`src/test/resources/`. But `Media3VideoProcessor` drives a `Transformer` (Looper + MediaCodec +
OpenGL) — that does **not** run on a pure JVM. This is the same reason `VideoReverserTest` is
**instrumented** (Lesson 008/017). Expect to make the `VideoProcessor` correctness test **instrumented**
(`src/androidTest/`), and remember **androidTest can't use mockk** (Lesson 017) — hand-write fakes.
What *can* stay JVM: pure logic you extract out of the Transformer (e.g. a `fun sequenceFor(mode):
List<ClipSpec>` + seam math), mirroring how `MediaFormatUtils` was split out for testability. Extract
the ordering/seam decision into a pure function and unit-test *that* on the JVM; leave the actual
encode to an instrumented smoke test.

---

## 6. Lessons that specifically apply to slice 03

- **Lesson 014 (exhaustive `when`, no `else`):** adding `BoomerangEditor` means a new branch in
  `OpenRangNavHost` — route it, no `else`. Build will fail until you do; that's the feature.
- **Lesson 015 (state-routed `BackHandler`):** the editor can lose work. The PRD says back confirms
  "Discard changes?" only if `mode` changed from default. Gate the `BackHandler` on that condition —
  don't always-intercept, don't let back finish the Activity.
- **Lesson 016 (defer high-frequency reads):** the preview position / shimmer progress tick — pass as
  `() -> T` lambdas, don't read at the screen root next to the ExoPlayer `AndroidView`.
- **Lesson 002 / 004 / 001 / 003** all re-listed in the slice-03 acceptance criteria — they still hold.
- **DI:** the editor preview and `VideoProcessor` must share the **same `VideoReverser` instance**
  (wired in the ViewModel `Factory`) or the cache won't be shared and you'll reverse twice. The PRD
  acceptance list calls this out — honor it.

---

## 7. Inherited from slice 02 — still open, now affecting all 4 directions

Read [`SECOND-REVIEW-boomerang-slice-02-pr-24.md`](./SECOND-REVIEW-boomerang-slice-02-pr-24.md) in
full. The short version of what carries forward:
- **Rotation (W2):** I made the reverser strip `KEY_ROTATION` from the decoder format (deterministic
  "coded pixels + metadata hint"). Still needs the on-device portrait A/B. Now it affects every
  reverse-containing direction, not just the default.
- **W5 fixture / W4 TalkBack:** as documented there.
- **`ffmpeg` is NOT in the app.** If you see `ffmpeg` referenced, it was only ever a *dev-machine*
  tool for generating a test fixture (`app/src/androidTest/assets/trim_fixture.mp4`) — never an APK
  dependency. FFmpegKit (the in-app library) is dead and rejected (RESEARCH §2). The runtime reverser
  is the hand-rolled two-pass MediaCodec class (= the `video-backwards.txt` / sisik.eu approach).

---

## 8. One-line summary for the impatient

Verify slice 02's reverse on the Fold first; then the two things most likely to make slice 03 wrong
are **(a) the seam drop must follow sequence *position*, not "the reversed clip"** and **(b) confirm
both halves of `F→R` and `R→F` actually play at 2× (the #1658 per-item-effects risk).** Everything
else is in the PRD.
