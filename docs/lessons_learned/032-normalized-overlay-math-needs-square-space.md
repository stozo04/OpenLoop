# Lesson 032 — Anchor overlays to a face frame, and make the two streams the same shape

## The rule that replaced three bugs

**Do not position a face overlay from a bounding box plus tuned offsets, a roll angle, and a mirror
flag.** That is three error sources stacked on magic numbers, and it does not generalise. Build a
coordinate frame out of the subject's own landmarks instead:

* **origin** — the eye midpoint.
* **up** — mouth midpoint → eye midpoint. Every human head has this axis, at any tilt, upside down,
  or mirrored.
* **right** — perpendicular to *up*. Derive it from *up*, never from the detector's "left eye" /
  "right eye" labels, which swap in a mirrored image.
* **unit** — the eye-to-mouth distance. This is the scale of the face.

A lens then declares only *(offset in units, width in units)*. Rotation falls out of the axes, scale
falls out of the unit, and mirroring stops mattering. In OpenLoop this deleted `rollDegrees`, both
box extents, the mirror flag, and every per-lens offset constant in one change — and it is the only
version that actually landed on a face.

Calibrate the unit numbers **by measuring a tracked face**, not from published head statistics.
Reasoning them from interpupillary distance made every lens ~20% oversized, because eye-to-mouth is
the larger span.

## The same frame builds a character, not just a prop

Once the frame exists, "put a hat on the user" and "replace the user with a vegetable that has their
eyes" are the same mechanism with the art drawn opaque and three extra quads:

* **source** each feature from the subject's real landmark — that is where blinks and smiles come
  from;
* **destination** each feature at a point *fixed in the face frame* — that is what stops the
  character inheriting the subject's proportions, camera perspective, or nose.

Fixed destinations are the whole difference between "broccoli character with human eyes" and "human
face inside a broccoli costume". Keep the cut-outs tight: surrounding skin is precisely what makes a
character read as a person in a costume.

One GL trap on the way: `discard` plus an elliptical `smoothstep` falloff is what makes a cut-out
melt into the art. A hard-edged quad reads as a rectangle of skin pasted on top no matter how well
it is positioned.

**Origin:** Camera lenses (`docs/PRD-camera-lenses.md`), 2026-08-08
**Applies to:** `camera/lens/LensAnchor.kt`, `camera/lens/LensSurfaceProcessor.kt` (shader)

## What went wrong

Anything drawn over a camera frame — a sticker, a face box, a warp radius — is naturally expressed
in **normalized coordinates**, `0f..1f` on each axis. That space is *not* isotropic: on a 1280×720
frame, `0.1` along x is 128 px and `0.1` along y is 72 px. So in normalized units:

* a "circle" is an ellipse,
* a square sticker renders squashed,
* rotating an offset changes its length.

`LensAnchor` had the conversion helpers, and they were **inverted**:

```kotlin
// WRONG
fun toSquareY(dy: Float, frameAspect: Float) = dy * frameAspect
```

The unit test caught it immediately (`halfHeight / halfWidth` came out `0.5625` instead of
`1.777`), but the same inversion was copy-pasted into the fragment shader, where nothing would have
caught it except somebody looking at a squashed broccoli on a real phone.

## Pattern

Derive the direction from pixels every time rather than guessing the sign:

> One y unit spans `height` px; one x unit spans `width` px. A y distance measured in x units is
> `dy * height / width` = `dy / frameAspect`, where `frameAspect = width / height`.

```kotlin
fun toSquareY(dy: Float, frameAspect: Float): Float = dy / frameAspect   // y-normalized → square
fun fromSquareY(sy: Float, frameAspect: Float): Float = sy * frameAspect // square → y-normalized
```

Rules:

1. **Rotate, measure distance, and compare radii only in square space.** Convert in, do the work,
   convert out.
2. **Keep the conversion in exactly one pure function pair** and JVM-test the round trip
   (`toSquareY` → `fromSquareY` is the identity) plus one asymmetric assertion that would fail if
   the two were swapped — a round-trip test alone passes happily on an inverted pair.
3. **The shader must cite the Kotlin helper it mirrors.** Any aspect maths duplicated in GLSL is
   untested by construction; a comment naming `LensAnchor.toSquareY` is what makes the duplication
   reviewable.

## Detection checklist

* `grep -n "frameAspect\|uFrameAspect\|aspect" app/src/main/java app/src/main/res` — every `*` or
  `/` by an aspect ratio should sit inside the helper pair or a line that names it.
* A test asserting only `f(g(x)) == x` for a conversion pair proves nothing about direction. Pair it
  with a concrete expectation (`halfHeight / halfWidth == artAspect * frameAspect`).
* Symptom on device: art correct on a square-ish preview but visibly squashed or stretched on 16:9;
  a radial effect that is an ellipse.

## The bigger trap: two different streams

Square space is only half of it. When you detect on `ImageAnalysis` and draw in a `CameraEffect`,
**those are two different streams off the same sensor, and normalized coordinates do not transfer
between them.** Measured on a Pixel 8 emulator with a single one-line-per-bind log:

```text
OpenLoopFaceTracker: Analysis buffer=1280x720 rotation=90 upright=720x1280
OpenLoopLens:        Lens output targets=3 size=1280x960 inputDet=-1.0 outputDet=-1.0
```

Three separate mismatches, none of them obvious from the APIs:

1. **Orientation.** ML Kit is handed `rotationDegrees` (it needs it, or detection quality drops)
   and answers in the *upright* image — portrait `720x1280`. The effect output is the camera
   buffer's own orientation — landscape `1280x960`. Drawing upright coordinates there is 90° out.
   Undo the rotation once, at the tracker boundary.
2. **Field of view — do not model it, remove it.** 16:9 and 4:3 streams do not cover the same
   rectangle of the sensor. A 16:9 `ImageAnalysis` against the 4:3 effect output put every lens
   visibly off sideways, and the "same width, different vertical crop" model did **not** correct
   it — how a given device derives one stream from the other is not something to guess at.

   **Match the analysis aspect ratio to the effect output's** (`RATIO_4_3_FALLBACK_AUTO_STRATEGY`
   here, since that is what CameraX picks for the shared effect stream). Two streams of the same
   shape cover the same sensor rectangle, so a landmark's normalized position means the same thing
   in both and no correction is needed at all. Keep a re-frame helper for the residual case, but
   the fix is making the question not arise.
3. **Mirroring.** Once the frame is built from *up* (above), this stops being a question — a
   mirrored image yields a mirrored frame and the overlay follows. Do not add a mirror flag, and
   in particular do not infer one from lens facing: front-camera mirroring may be applied by
   `PreviewView` *after* the effect, in which case it moves the composited overlay along with the
   face and needs no correction at all.
4. **Texture orientation.** `GLUtils.texImage2D` uploads a bitmap's first row at `v = 0`, so `v = 0`
   is the *top* of the art. Pairing the screen's top corners with `v = 1` renders every sticker
   upside down — invisible on near-symmetric art, obvious the moment a broccoli's stalk appears
   above the head.

**Corollary for anything measured then drawn:** carry the frame the measurement was taken in
(`sourceAspect` on the snapshot), not just the numbers. A bare `Float` pair silently means nothing
without its frame, and a scalar like "mouth width as a fraction of the frame" cannot survive a
quarter turn at all — carry the two endpoints and recompute the span in square space instead.

**Log the geometry once per bind.** Sizes, rotation, and the determinants are three lines that
turn "the sticker is in the wrong place" from a guessing game into arithmetic. This is what made
all three mismatches above findable on an emulator that cannot even show the detector a face.

## Reference

* Regression guards: `LensAnchorTest` — `sticker_keepsArtProportions_onANonSquareFrame`,
  `sticker_offsetOrbitsWithTheHead_keepingItsDistance`, `squareSpaceConversion_roundTrips`,
  `uprightToBuffer_*`, `reframe_*`.
* [`SurfaceOutput.updateTransformMatrix`](https://developer.android.com/reference/androidx/camera/core/SurfaceOutput)
* [ML Kit face detection — rotation](https://developers.google.com/ml-kit/vision/face-detection/android)
