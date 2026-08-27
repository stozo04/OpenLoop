# PRD — Hand-driven lens flick (wave your hand at the ball, it spins)

**Status:** Approved (owner ruling 2026-08-26: *"It should be 'hand velocity near sticker' that triggers the spinning effect"*, restated after the Fold recording) — building
**Owner:** Steven Gates
**Date:** 2026-08-26
**Branch:** the current flick-to-spin branch (continues that work)
**Parent PRDs:** [`PRD-lens-interactions.md`](PRD-lens-interactions.md) (the touch verb this replaces),
[`PRD-camera-lenses.md`](PRD-camera-lenses.md), [`PRD-multi-face-lenses.md`](PRD-multi-face-lenses.md)
**Related lessons:** 011, 031, 032, 037

---

## 1. Problem statement

The owner's ruling (2026-08-26, verbatim): *"It should be 'hand velocity near sticker' that
triggers the spinning effect."* The picture: front camera up, Football on your head, you bring a
hand into frame, wave it past the ball, and the ball spins one or two revolutions and lands square —
the spin baked into the recording like every other lens behavior.

The approved flick-to-spin PRD built the same spin behind a **finger fling on the touchscreen**. Its
own §5 backlog already named the hand-in-the-image version as the next step and the reason it was
deferred: ML Kit face detection sees no hands, so a hand tracker is a new dependency. That
dependency's one hard blocker for this app — Google Play's 16 KB page-size requirement (Lesson
011) — was measured this session and is clear (§3.1). This PRD adopts the hand tracker and swaps
the verb.

**What carries over unchanged** — and this is the reason the feature is small, not a re-architecture:
the hit-test (`LensHitTest`), the torque model and landing physics (`LensPhysics.spinImpulse` /
`spinStep`), the per-face state (`LensMotion`), the rotation in `LensAnchor.sticker`, the
features-hide-while-spinning rule, and the recording guarantee. A hand enters the pipeline at
`LensMotion.flick(...)`, exactly where a finger did.

**What gets simpler.** Hand landmarks arrive in the **analysis image** — the same space the face
landmarks live in — so `LensAnchor.uprightToBuffer` + `reframe` already map them onto the sticker
quads. The whole view→buffer transform (`LensTouchMath`, the mirror flag, the `FILL_CENTER` crop
inverse) and its R1 risk class disappear, along with the three touch-capture layers that the Fold
never demonstrably delivered a viewfinder fling through (`MainActivity.dispatchTouchEvent`,
`PinchZoomLayout`'s detector, the Compose pointer probe).

### 1.1 What is already proven (emulator, 2026-08-26)

The downstream pipeline the hand path inherits was exercised end-to-end this session on the
Pixel_8 AVD (API 37, poster face in the virtual scene, Football active, `adb shell input swipe`
across the ball): **5/5 swipes on the ball → `Flick HIT`, the control swipe above it → `Flick miss`,
every mapped point matching the `FILL_CENTER` arithmetic exactly; screenshots show the ball ~180°
inverted with the features hidden ~0.25 s after the swipe and upright with the features back at
+1.75 s.** No crash, no ANR. So hit-test → impulse → friction → whole-revolution landing →
feature-hide → render → land all work; the hand tracker only has to produce a contact point and a
velocity. The Fold's failure to spin from a finger remains unexplained — no captured log contains a
viewfinder touch at all (§7's capture note) — and D1 makes that question moot.

### 1.2 What the build proved (2026-08-26)

* **Owner, Pixel 10 Pro Fold:** *"It totally freaking works!!! just tested it on my Fold!!!"* —
  the hand wave spins the ball on hardware, first try.
* **Emulator (Pixel_8, API 37):** the Mona Lisa fixture plus the owner's own hand (cropped from
  the Fold recording) on the virtual-scene wall. With Football active: `Hand tracking on` →
  `Hand detected in 640x480 …` 90–140 ms later, every run. All 21 landmarks logged and drawn onto
  the preview screenshot under the "landmarks are in the un-rotated buffer" hypothesis: every
  point lands on the poster hand — fingertips on fingertips, wrist at the palm base. The
  coordinate contract in `HandTracker.publish` is therefore measured, not assumed. (MediaPipe
  read the palm-forward hand as its mirror image, so "index" was the pinky; irrelevant here —
  contact is any landmark and velocity is the palm centroid.)
* **No lens / a `NONE` lens:** `Hand tracking off`, no detector instantiated (D5).
* **Release build (R8) on the emulator:** the first release APK crashed inside
  `HandLandmarker.createFromOptions` → `Graph.<clinit>` with Flogger's
  `IllegalStateException: no caller found on the stack for: sn1` — Flogger finds its caller by
  matching class *names* on the stack, which obfuscation breaks. Fixed by keeping
  `com.google.common.flogger.**` (plus the two `-dontwarn` proto classes R8 listed); re-verified:
  `Hand tracking on` → `Hand detected` on the release APK with identical landmarks. Belt and
  braces: `HandTracker.open` now survives `MediaPipeException` and `LinkageError` (native lib
  missing for an ABI, a static initializer that throws) by turning the hand verb off, logging, and
  reporting a Crashlytics non-fatal — the lens never takes the camera with it.
* **Process stability:** the app's PID changed several times during the emulator session with no
  crash record anywhere; the events buffer showed `am_kill … stop <pkg>` + `next-top-activity`
  restarts — Android Studio deploying while the owner built, not the app.

## 2. Constraints (inherited)

| #   | Constraint                                                        | Consequence                                                                                          |
| --- | ----------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| C1  | One `CameraEffect`, attached once per bind (Lesson 031)           | Unchanged. The hand is renderer state read per frame, like faces.                                    |
| C2  | Lens is baked into the recording                                  | Unchanged — the spin is in `LensMotion.step`, so preview and `.mp4` see the same motion.             |
| C3  | Physics and geometry pure + JVM-tested                            | The hand→impulse decision is a pure function; the tracker is thin ML glue like `FaceTracker`.        |
| C4  | Nothing outside `Lens.kt` names a lens                            | Spin stays a per-layer `SpinSpec` opt-in; the hand tracker is lens-agnostic.                         |
| C5  | Multi-face: per-face state keyed by tracking id (Lesson 037)      | A hand spins the ball it touches; the other face's ball is untouched.                                |
| C6  | Every dependency Apache-2.0-compatible; 100 % on-device           | MediaPipe (Apache 2.0) and its hand model qualify; inference is on-device.                           |
| C7  | Play 16 KB page-size rule for native libs (Lesson 011)            | **Measured clear** for `tasks-core 1.0.0` — §3.1. The release sweep's `zipalign -P 16` re-proves it. |
| C8  | `Preview + VideoCapture + ImageAnalysis` is the binding ceiling   | No second `ImageAnalysis`. One analyzer feeds both detectors (§3.2).                                 |

## 3. Design

```text
ImageAnalysis (640x480 YUV, KEEP_ONLY_LATEST, unchanged) ── FaceTracker.analyze(imageProxy)
   ├─ ML Kit face detection → FaceSnapshot roster → LensSurfaceProcessor.setFaces   (unchanged)
   └─ HandTracker.submit(imageProxy)             [only while the active lens has a spin-capable layer]
        └─ imageProxy.toBitmap() → BitmapImageBuilder → HandLandmarker.detectAsync(img, rotation, tsMs)
             └─ result listener: 21 NormalizedLandmarks — already in the un-rotated BUFFER space
                  (verified §1.2; the rotation option only orients the model's view)
                  → HandSnapshot → LensSurfaceProcessor.setHand(snapshot)   (@Volatile latest-wins, like faces)

LensSurfaceProcessor.onFrame (GL thread, before motion.step — the same slot the touch flick used):
   HandFlick.evaluate(previous, current, quads) — pure, JVM-tested
      ├─ velocity  = Δ palm centroid / Δ timestamp, converted to face units/s in square space
      ├─ contact   = a landmark inside a spin-capable StickerQuad (LensHitTest.contains, reused)
      ├─ trigger   = contact && speed ≥ spec.minHandSpeed && armed
      └─ impulse   → LensMotion.flick(lens, face, layer, lever = contact − quad center, velocity)
   armed := false on trigger; re-armed when no landmark touches that quad, or after 400 ms
```

### 3.1 The dependency — MediaPipe Hand Landmarker, and the 16 KB proof

`com.google.mediapipe:tasks-vision:1.0.0` (Google Maven, released 2026-07-28) → `tasks-core:1.0.0`,
which carries the one native library. Measured this session with the NDK's `llvm-objdump -p` on the
downloaded AAR — every `PT_LOAD` segment on every ABI is `align 2**14` (16 KB):

| ABI           | `libmediapipe_tasks_jni.so` | `PT_LOAD` align           |
| ------------- | --------------------------- | ------------------------- |
| `arm64-v8a`   | 11.0 MB                     | `2**14`, `2**14`, `2**14` |
| `armeabi-v7a` | 7.7 MB                      | `2**14` ×3                |
| `x86`         | 15.6 MB                     | `2**14` ×3                |
| `x86_64`      | 13.7 MB                     | `2**14` ×3                |

MediaPipe's own release notes confirm the fix landed in `0.10.26` (2025-07-10: "All the latest
Android packages from Google Maven are now supporting the Android 16kb page size"). The app already
packages natives uncompressed (`useLegacyPackaging = false`, Lesson 011), so the sweep's
`zipalign -c -P 16` on the release APK is the second, mechanical proof.

Model: `hand_landmarker.task` (float16), **7.8 MB**, Apache 2.0, shipped in `src/main/assets`.
APK delta on arm64 ≈ **19 MB** (11.0 native + 7.8 model) on top of today's 46.4 MB release APK;
Play's per-ABI delivery from the bundle keeps other ABIs out of a user's download.

Rejected alternatives, briefly:

* **ML Kit Pose Detection** (has wrist/index/pinky landmarks): **beta, no SLA** — the same ground
  the parent PRD rejected Face Mesh on — and ~30 MB. Dead on precedent.
* **No-ML motion energy** (luma frame-differencing beside the ball): zero dependency, ~100 lines,
  but every head turn and every passer-by moves pixels next to the ball. The false-trigger rate
  makes it the flimsier algorithm, and ponytail's rule for that case is the one correct on edge
  cases, not the one with less code.

### 3.2 Feeding two detectors from one `ImageAnalysis` (C8)

`FaceTracker.analyze` stays the sole analyzer. It already hands ML Kit the `android.media.Image`
via `InputImage.fromMediaImage` and closes the proxy on completion. The hand path must not extend
the proxy's lifetime past that, so it takes a **copy**: `ImageProxy.toBitmap()` (present on CameraX
1.6.1 — verified by `javap` against the `camera-core-1.6.1` jar) converts the YUV frame to an
`ARGB_8888` bitmap; `BitmapImageBuilder` wraps it; `detectAsync(image, ImageProcessingOptions
.builder().setRotationDegrees(rotation).build(), timestampMs)` runs off-thread and returns
immediately (verified `HandLandmarker` signature). No rotated-bitmap allocation: the rotation goes
in as an option, and the landmarks come back in the upright image — the frame `FaceTracker`
already normalizes against.

**Per-frame memory (measured 2026-08-26, Pixel_8 AVD, 640×480):** every analyzed frame costs
three 1.2 MB buffers — the `toBitmap()` copy (ours), and the bitmap **plus** a direct `ByteBuffer`
that MediaPipe allocates to echo the input image back in the result (`HandLandmarker$1` →
`AndroidPacketGetter.getBitmapFromRgba`, read from the bytecode). MediaPipe copies our bitmap
into its packet synchronously inside `detectAsync` and never closes either `MPImage`, so
`HandTracker` recycles both with `use` — native heap alloc went from ~107 MB to ~91 MB (PSS
89–110 → 80–82 MB) on the AVD, and on the Fold, where the GC runs every ~4 s rather than 4×/s,
that is 25–67 frames × 2.4 MB no longer held between collections. The GC *cadence* did not move
(126 vs 123 GCs per 30 s): it is driven by MediaPipe's direct buffer, which is dropped on its
floor, not ours — a lower hand frame rate is the only lever left, and the pause it buys back is
~1–5 ms every few seconds. Zero-copy (`OUTPUT_IMAGE_FORMAT_RGBA_8888` → `ByteBufferImageBuilder`
on plane 0) stays off the table while faces need the stream in YUV; CameraX's
convert-into-an-existing-bitmap path is `@RestrictTo`.

* **Timestamps** — `imageInfo.timestamp` (ns → ms), monotonic per MediaPipe's LIVE_STREAM contract.
  Velocity is computed from `HandLandmarkerResult.timestampMs()` deltas, never arrival time.
* **Front camera** — nothing special. The analysis image is un-mirrored for faces and hands alike;
  `PreviewView` mirrors after the effect for both. The touch path's mirror flag had to exist
  because a finger has no anatomy; a hand does.
* **Lifecycle** — the `HandLandmarker` is created when a lens with a spin-capable layer becomes
  active and closed when one without does (or on camera release). Broccoli, Shades, no-lens:
  zero hand-tracking cost, zero behavior change.
* **Build-time verification items** (the sample app does *not* drop in — it configures
  `OUTPUT_IMAGE_FORMAT_RGBA_8888` and copies planes; OpenLoop's stream is YUV): the cost of
  `toBitmap()` at 640×480 on the analyzer thread (expected ~1–3 ms; measure); CPU vs GPU delegate
  (start on CPU — the GL thread is already the renderer's; measure inference on the Fold, target
  ≤ 30 ms). Resolved at build time: the `.task` asset needs no `noCompress` entry — Google's own
  hand-landmarker sample ships it as a plain asset and `setModelAssetPath` reads it through
  `AssetManager`; and the AARs carry **no consumer ProGuard rules**, so `proguard-rules.pro` keeps
  `com.google.mediapipe.**` and `com.google.protobuf.**` whole for the R8 release build.
* **Coordinate contract, stated once** (`HandTracker.publish`): MediaPipe returns landmarks in the
  image **as submitted** — the un-rotated buffer — and uses the rotation option only to orient
  the model's view, so a snapshot is already in the renderer's space. **Measured on the emulator
  (§1.2)**, not assumed: the 21-point overlay sits on the hand.

### 3.3 The gesture — `HandFlick` (pure)

Inputs per frame: the previous and current `HandSnapshot` (21 points in buffer-normalized space +
timestamp), the roster's spin-capable quads (rebuilt with the current spin angle, as the touch
hit-test did), each face's `FaceFrame`. Output: at most one impulse.

* **Contact** — the first landmark (fingertips first: 8, 12, 16, 20, 4, then the rest) inside a quad,
  tested with `LensHitTest.contains` unchanged, faces in reverse slot order and layers topmost first
  — identical precedence to the touch path. The ball is 5.6 face units wide, so contact is generous.
* **Velocity** — the palm centroid (mean of landmarks 0, 5, 9, 13, 17; stable while fingers flutter)
  differenced against the previous snapshot, divided by Δt, mapped to square space and divided by
  `FaceFrame.unit` — the same dimensionless currency `spinImpulse` already takes. A missing previous
  snapshot (hand just appeared) means no velocity and no trigger.
* **Trigger** — contact **and** speed ≥ `SpinSpec.minHandSpeed` **and** the quad is armed. Starting
  value 3 face units/s: a face unit is eye-line-to-mouth (~6–7 cm), so that is ~20 cm/s — a wave
  clears it easily, a hand adjusting hair does not. Owner-tuned on hardware like every SpinSpec number.
* **Impulse** — `LensMotion.flick(lens, trackingId, layer, lever = contact − quad center, velocity)`.
  Lever from the point of contact, velocity from the palm — the finger's torque model verbatim.
* **Re-arm** — one impulse per contact. The quad re-arms when no landmark is inside it, or 400 ms
  after the impulse, whichever comes first. A hand held waving *inside* the ball therefore pumps
  the spin at most every 400 ms (to the cap) instead of every frame; a hand that passes through
  gives exactly one impulse.
* **Log lines** — `OpenLoopHand: Hand tracking on/off`, `Hand detected in WxH wrist=… indexTip=…`
  / `Hand lost` (once per appearance — the geometry line, paired with `OpenLoopLens`'s output
  line the way the face tracker's is), and `OpenLoopLens: Hand HIT face= layer= speed= lever= v=`
  once per impulse. Nothing per frame: a hand resting on the ball is silent by design. The
  debugging currency for hardware QA (§7).

### 3.4 Catalogue, physics, rendering — unchanged except one field

`SpinSpec` gains `minHandSpeed: Float`. Football's `gain = 1.8`, `frictionHalfLifeSeconds = 0.6`,
`maxAngularVelocity = 25` stand: a 7 units/s wave at a 2-unit lever gives ω ≈ 6.3 rad/s → ≈ 0.9
revolution at the current tuning, a hard wave ≈ 2, the cap ≈ 3.5. `LensPhysicsTest` keeps pinning
the properties; the feel is retuned in `Lens.kt` alone.

## 4. Decisions (proposed — sign-off ratifies)

| #   | Question                                   | Proposal                                                                                                                                                                                                                                                                                                                                                                                 |
| --- | ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D1  | **Hand replaces touch**                    | Owner statement 2026-08-26 (§1). Supersedes `PRD-lens-interactions.md` §3.1–3.2 and the "drag" verbs in its §5 contract. The three touch-capture layers, `ViewFlick`/`LensTouchMath` (+ test), `CameraManager.flickLens` and the debounce are **deleted** in the same PR — three layers of capture that never showed a viewfinder fling on the Fold are not worth keeping as a fallback. |
| D2  | **Two ML vendors**                         | Contradicts the parent PRD's "don't ship both" — that rule was about two *face* trackers. ML Kit stays for faces (every `LensAnchor` number is calibrated on its landmarks); MediaPipe is hands-only. Consolidating faces onto MediaPipe Face Landmarker is a backlog item, not this PR.                                                                                                 |
| D3  | Which lenses spin                          | **Football, Broccoli and Pizza Face** — the head-sized characters (owner, 2026-08-26, after the Fold test; Football alone proved the pipeline first). Shades, Dog, Twisted Tongue and Elvis are `NONE`: props and parts worn on the real face have nothing to spin.                                                                                                                      |
| D4  | Sustained wave inside the ball             | One impulse per contact; re-arm on exit or 400 ms.                                                                                                                                                                                                                                                                                                                                       |
| D5  | Tracker lifecycle                          | Exists only while a spin-capable lens is active. Other lenses pay nothing.                                                                                                                                                                                                                                                                                                               |
| D6  | How many hands                             | `numHands = 1` in v1. Two faces, one hand: it spins whichever ball it touches.                                                                                                                                                                                                                                                                                                           |
| D7  | Rear camera                                | Works identically — a hand in frame is a hand in frame. Nothing is facing-specific any more.                                                                                                                                                                                                                                                                                             |
| D8  | Where the threshold lives                  | `SpinSpec.minHandSpeed`, per lens, JVM-pinned, owner-tuned — the house pattern for feel numbers.                                                                                                                                                                                                                                                                                         |
| D9  | New lenses                                 | **Every lens answers the interaction question on purpose.** `Lens.interaction` is a required constructor parameter (`NONE` / `SPIN`, no default) — a new entry does not compile until it is answered — and `LensPhysicsTest` pins the answer to the layers (a `SPIN` lens carries a `SpinSpec`, a `NONE` lens carries none). Owner rule, 2026-08-26.                                     |

## 5. What "stretch" and "throw" reuse now

The pipeline contract from the touch PRD holds with one substitution: the gesture source is a
`HandSnapshot` stream instead of a fling. A future interaction adds a verb in `HandFlick`'s
sibling (grab = fingertips pinched inside a quad; drag = grab + palm displacement), a spec on
`LensPlacement`, a state + step in `LensMotion`/`LensPhysics`, a transform in `LensAnchor.sticker`.
The Shades pull-down from the old backlog is now a drag verb away, with no new dependency.

## 6. Success criteria

1. Front camera, Football on: wave a hand past the ball — it spins in the direction of the wave,
   ~1 revolution on a comfortable wave, ~2 on a fast one, lands on a whole revolution, features snap
   back on landing. A hand held still touching the ball does nothing.
2. The spin is in the saved video and in a photo-mode capture taken mid-spin.
3. Broccoli / Shades / no lens: bit-identical behavior, no hand tracker instantiated (log proves it).
4. Two faces: the hand spins only the ball it touched.
5. Pinch-zoom, tap, shutter, flip, tray, booth: unchanged (the touch layers are gone, not rerouted).
6. Release APK passes `zipalign -c -P 16` with every `.so` `(OK)` — including the new one.
7. Frame rate: no visible preview hitch with the hand tracker running; measured inference on the
   Fold recorded in the PR.
8. Full `docs/DEFINITION_OF_DONE.md` gate, including the pre-PR sweep.

## 7. Test plan (per `docs/TEST_COVERAGE.md`)

* **JVM — `HandFlickTest`:** velocity from timestamp deltas at any frame rate; below-threshold contact
  never triggers; above-threshold contact triggers exactly once until re-arm; re-arm on exit and on
  timeout; fingertip precedence; lever comes from the contact point, velocity from the palm; a hand
  with no previous snapshot never triggers; non-finite input is dropped.
* **JVM — landmark-trace replay:** a hand-wave trace captured once on the Fold (one debug log line
  per `HandSnapshot`, exported to a JSON fixture) replayed through `HandFlick` against a synthetic
  Football quad asserts the exact impulse count and direction. This is the regression test for the
  feel numbers — the emulator cannot supply a hand.
* **JVM — `LensPhysicsTest` / `LensMotionTest`:** unchanged except the `minHandSpeed` property.
* **Emulator (poster face + the owner's hand on the wall image) — done, §1.2:** bind with
  Football active and the tracker instantiated — `Hand tracking on`, `Hand detected`, all 21
  landmarks overlaid on the preview and landing on the hand, no crash, no ANR, lens still on the
  face. A static hand has no velocity, so it proves the plumbing and the space, not the gesture —
  the gesture is the owner's Fold test and `HandFlickTest`.
* **Hardware QA (owner, Fold):** the feel; false triggers from hair-adjusting and head turns; a hand
  covering the face (ML Kit's 350 ms hold rides the occlusion — verify); rear camera; recording
  mid-wave. Capture logs from a terminal, not Studio's buffer (ML Kit's `ThickFaceDetector` spam
  evicts it in seconds): `adb logcat -s OpenLoopHand:* OpenLoopLens:* OpenLoopCameraManager:*`.

## 8. Risks

| #   | Risk                                                                    | Mitigation                                                                                                                                                                                                                                                                                                                                                                 |
| --- | ----------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| R1  | Hand inference too slow on mid-range phones → laggy or missed waves     | KEEP_ONLY_LATEST drops frames gracefully; velocity uses real timestamps so it stays correct at any rate; CPU/GPU delegate is a one-line switch; measure.                                                                                                                                                                                                                   |
| R2  | Landmark jitter → phantom velocity spikes                               | Palm centroid (5-point mean), the speed threshold, and `maxAngularVelocity` cap it three ways.                                                                                                                                                                                                                                                                             |
| R3  | Hand over the face drops ML Kit's face → ball vanishes mid-wave         | 350 ms roster hold; if QA shows it's not enough, raise the hold only while a hand is in frame.                                                                                                                                                                                                                                                                             |
| R4  | +19 MB on arm64                                                         | Owner call, stated up front (§3.1). Model could move to Play Asset Delivery later; not v1.                                                                                                                                                                                                                                                                                 |
| R5  | `toBitmap()` cost on the analyzer thread starves face detection         | `HandTracker.submit` skips the conversion while MediaPipe is still busy with the previous frame (it would ignore the new one anyway — its documented LIVE_STREAM contract), so conversions run at inference rate, not camera rate. Next lever: a fixed every-other-frame cadence, or `ByteBufferImageBuilder` on the Y plane only (the model wants RGB, so a last resort). |
| R6  | Per-frame bitmaps churn the GC / bloat native heap on mid-range phones  | Both bitmaps recycled the moment MediaPipe is done with them (§3.2, measured). The remaining churn is MediaPipe's own direct buffer; the R5 gate already drops the frames it would have ignored.                                                                                                                                                                           |

## 9. Open questions (none block sign-off)

* Does the owner want the touch fling kept as a **rear-camera fallback** (D1 says delete)?
* Play Asset Delivery for the model if APK size becomes a store-listing concern (R4).

## 10. References

* [Hand landmarks detection guide for Android](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker/android) — options, LIVE_STREAM, result shape
* [MediaPipe Android setup](https://developers.google.com/edge/mediapipe/solutions/setup_android) — `tasks-vision` dependency, minSdk 24
* [MediaPipe releases](https://github.com/google-ai-edge/mediapipe/releases) — `v1.0.0` (2026-07-28); `v0.10.26` 16 KB note
* [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) — the Play requirement (Lesson 011)
* [CameraX image analysis](https://developer.android.com/media/camera/camerax/analyze) — one analyzer, close the proxy
* [ML Kit pose detection](https://developers.google.com/ml-kit/vision/pose-detection) — beta, no SLA (rejected)
* Lessons [011](lessons_learned/011-16kb-uncompressed-native-libs.md) ·
  [031](lessons_learned/031-camera-effect-attach-once-switch-by-uniform.md) ·
  [032](lessons_learned/032-normalized-overlay-math-needs-square-space.md) ·
  [037](lessons_learned/037-per-identity-hold-must-survive-id-churn.md)
