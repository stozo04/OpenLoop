# PRD — Camera Lenses (Snapchat-style live effects)

**Status:** Approved (owner, 2026-08-08) — building
**Author:** Claude (research pass, 2026-08-08)
**Owner:** Steven Gates
**Related:** `docs/PRD-mission-control.md`, `docs/PRD-capture-zoom.md`, Lessons 012 / 016 / 022 / 025

---

## 1. Problem statement

The live camera screen (`ui/CameraScreen.kt`) has two controls: the shutter (center) and the
lens-flip button (`Alignment.CenterEnd`, 54.dp glass circle). The left side is empty.

The owner wants a **Lenses** button at `Alignment.CenterStart` — mirroring the flip button — that
opens a horizontal, Snapchat-style carousel of circular lens thumbnails along the bottom of the
viewfinder. The button is present in both camera-bound states (`ReadyToCapture` and `Recording`).
Requested starting set: **Big Mouth**, **Broccoli**, **3D Cartoon**.

Reference UI supplied by the owner: a circular thumbnail rail above a category tab row, with an
`✕` close affordance on the left and the active lens rendered larger/highlighted in the center.

**This PRD does not propose all three lenses for v1.** The three requested effects sit in three
different implementation tiers with wildly different costs — see §3. That is the core finding of
this research pass and the main thing needing a decision.

---

## 2. Hard constraints (owner, this session)

| # | Constraint | Consequence |
|---|---|---|
| C1 | **Free and open source. No paid licenses, no per-MAU billing.** | Kills Snap Camera Kit, DeepAR, Banuba, Perfect Corp. See §4. |
| C2 | 100 % on-device processing (existing product positioning) | Kills any SDK that streams lens content from a vendor CDN. |
| C3 | Apache 2.0 app — every dependency and shipped model must be license-compatible | ML Kit and MediaPipe (Apache 2.0) are fine; proprietary SDK binaries are not. |
| C4 | The lens must be visible in the **live preview** and land in the **saved video** | Effect must target `PREVIEW` *and* `VIDEO_CAPTURE`. |
| C5 | minSdk 26, CameraX 1.6.1, Compose, existing MVVM/state-machine architecture | No new architecture; lens state lives in the ViewModel like `EditorTabState`. |

---

## 3. What the three requested lenses actually require

Researched what each Snapchat lens does visually, then classified by rendering tier. This is the
load-bearing table.

| Lens | What it does | Tier | In-house cost |
|---|---|---|---|
| **Broccoli Head** | Replaces/covers the head with a broccoli graphic; eyes and mouth exaggerated on top | **Sticker** — a bitmap anchored to the face box, rotated by head roll | **Low.** Canvas draw. No GL, no shaders. |
| **Big Mouth** | Warps the actual face pixels — mouth/lips balloon outward, cartoon-distorted | **Warp** — per-frame mesh deformation of the camera texture | **Medium-high.** Custom `SurfaceProcessor`, EGL context, triangulated mesh, vertex shader. |
| **3D Cartoon** | Restyles the entire frame into a rendered-cartoon look (a learned style, not a filter) | **Neural** — on-device generative restyling at 30 fps | **Very high.** No off-the-shelf free Android model. Research project, not a feature. |

**Finding:** "3D Cartoon" is not buildable in-house at acceptable quality/perf without a licensed
SDK, which C1 forbids. It should be dropped from v1 and replaced.

**Finding:** a *sticker* lens validates the entire feature chain (button → carousel → face tracker →
camera effect → baked recording) using a **stable, no-GL API**. A *warp* lens does not add any new
plumbing — it only swaps the renderer. So the sticker goes first regardless of which lens ships
first in the UI.

---

## 4. Build vs. buy — buy is ruled out

| Option | On-device? | Cost | Lens availability | Verdict |
|---|---|---|---|---|
| **Snap Camera Kit** | Rendering is on-device; how lens *content* is delivered was **not verified** this session | No published price; requires a Snap developer account, app review, and agreement to the Camera Kit Terms | You get **Lens Studio** lenses you author + a curated community set. Snap's *own* branded lenses (the actual "Big Mouth") are not offered to partners. | ❌ **C1 / C3** — a proprietary binary SDK under Snap's own terms cannot ship in an Apache 2.0 app on unpriced terms. C2 not relied on. |
| **DeepAR** | Yes | Free tier ≈10 MAU **with watermark**; ≈$25/mo entry, scales with MAU | Bring-your-own via DeepAR Studio | ❌ C1 |
| **Banuba** | Yes | Enterprise quote, no free production tier | Bring-your-own | ❌ C1 |
| **ARCore Augmented Faces** | Yes | Free | Bring-your-own 3D asset | ⚠️ Renderer story is dead — Sceneform was archived Dec 2021 and Google says don't use it for new projects. Would mean adopting Filament or the community SceneView fork. Overkill for a sticker. |
| **In-house: ML Kit + CameraX effects** | Yes | Free, Apache 2.0 | Bring-your-own | ✅ **Recommended** |

> ⚠️ The DeepAR/Banuba figures come from vendor marketing comparisons, not a price sheet. They are
> directionally right (MAU-metered, paid) and that alone fails C1 — no further verification needed.

---

## 4b. Two kinds of lens — props and characters

A late but decisive requirement: **Broccoli is not a prop worn over a face, it is a character whose
face happens to be a broccoli.** The first build framed the subject's face with broccoli and left
the nose, cheeks and jaw on show — "a man in a costume". The reference lens hides the human head
entirely and animates only the eyes and mouth on the vegetable.

That splits the catalogue in two, and the renderer supports both from one mechanism:

| Kind | Art | The subject's face | Examples |
|---|---|---|---|
| **Prop** | drawn over the face | stays visible | Shades, Big Mouth (a warp, no art) |
| **Character** | drawn **opaque** over the whole head | hidden; only the eyes and mouth are lifted out and composited onto the art | Broccoli |

A character lens carries a `FeatureLayout` — where the eyes and mouth sit **on the character's
face**, in face units. Sources follow the subject's real landmarks (so blinks, smiles and head
turns carry through); destinations are fixed in the face frame, so the character keeps its own
proportions and never inherits the subject's perspective, nose, or jawline. Expression from the
human, geometry from the character.

The acceptance test is visual and blunt: *can you see a human nose, cheek, forehead or jaw?* If yes,
it is a prop pretending to be a character.

## 5. Recommended architecture

Three pieces, each already a supported Google API.

```
CameraX bindToLifecycle
  ├─ Preview ─────────┐
  ├─ VideoCapture ────┤──▶ LensEffect (CameraEffect, targets PREVIEW|VIDEO_CAPTURE)
  │                   │      └─ renders the active lens onto every frame
  └─ ImageAnalysis ───┴──▶ ML Kit FaceDetector ──▶ latest FaceSnapshot (box, euler Y/Z, contours)
```

### 5.1 Face tracking — ML Kit Face Detection (stable), not Face Mesh (beta)

| Candidate | Status | Data | Verdict |
|---|---|---|---|
| **ML Kit Face Detection** (`com.google.mlkit:face-detection`) | **Stable**, actively released | Bounding box, `headEulerAngleY/Z`, and in `CONTOUR_MODE` **133 points** including `UPPER_LIP_TOP/BOTTOM` and `LOWER_LIP_TOP/BOTTOM` | ✅ **Recommended.** Enough for the sticker *and* the mouth warp. |
| ML Kit Face Mesh | **Beta**, no SLA, last release Aug 2024 | 468 3D points | ❌ Beta + stale for a Production app. |
| MediaPipe Face Landmarker (`tasks-vision`) | Actively maintained, Apache 2.0 | 478 points, 52 blendshapes, facial transform matrix | ⏸ Adopt only if the warp lens needs a denser mesh than ML Kit contours give. Adds several MB (native libs + `.task` bundle). |

Starting with ML Kit face-detection means **one dependency, stable API, no beta risk, smallest APK
delta**. If Big Mouth later proves it needs a real mesh, that's the moment to add MediaPipe — and
then ML Kit comes out. Don't ship both.

Run it on an `ImageAnalysis` use case with `STRATEGY_KEEP_ONLY_LATEST`, `PERFORMANCE_MODE_FAST`,
single-face (`CONTOUR_MODE` is computed for the most prominent face only — correct for selfies).

### 5.2 Rendering — ONE custom `CameraEffect` + `SurfaceProcessor`

**Revised after the owner's "all in one PR" decision (§8).** The original plan used
`OverlayEffect` (Canvas, no GL) for stickers and deferred the warp. That split does not survive
shipping both together: **CameraX permits at most one effect per target**, so stickers and warp
cannot be two effects, and `OverlayEffect` is `final`-shaped around Canvas drawing with no hook for
a UV warp.

So: one hand-written `CameraEffect(PREVIEW or VIDEO_CAPTURE, executor, SurfaceProcessor)`, with a
single GL program whose **uniforms** select the behavior:

```glsl
// one fragment shader, three lenses
vec2 uv = warpStrength > 0.0 ? bulge(texCoord, warpCenter, warpRadius, warpStrength) : texCoord;
vec4 cam = texture2D(cameraTexture /* samplerExternalOES */, uv);
gl_FragColor = stickerEnabled ? over(texture2D(stickerTexture, stickerUv), cam) : cam;
```

* **Big Mouth** = `bulge()` — a radial UV displacement centred on the mouth. A fragment-shader
  warp, **not** a triangulated vertex mesh: same look, a fraction of the code.
* **Broccoli / sunglasses** = an alpha-blended textured quad positioned from the face box and head
  roll.
* **No lens** = `warpStrength = 0`, `stickerEnabled = false` → identity pass-through.

Lens art ships as **vector drawables rasterised once at startup**, not PNGs — smaller, resolution
independent, and unambiguously ours for an Apache 2.0 repo (§10 Q5 answered).

### 5.3 The effect is attached **always**, and switches internally

**This is the most important design decision in the PRD.** Effects attach at `bindToLifecycle`.
Attaching or removing one means a **rebind** — and a rebind mid-recording tears the camera out from
under the in-flight capture and finalizes it with `ERROR_SOURCE_INACTIVE`. That is exactly the
Lesson 012 / Issue #36 failure class this repo has already been bitten by twice.

So: `LensEffect` is attached on every bind, holds a `@Volatile var activeLens: Lens?`, and draws
nothing when it is `null`. Switching lenses — including **while recording** — is a field write, not
a rebind.

> Cost: an always-attached effect routes frames through a GPU copy even with no lens active.
> Phase 0 must measure this. If idle preview regresses measurably, fall back to attach-on-demand
> plus "lens locked once recording starts", and record that trade in this PRD.

### 5.4 The lens is baked into the recording

Targeting `PREVIEW | VIDEO_CAPTURE` means the lens is in the pixels the `Recorder` writes.

* ✅ Trim / reverse / speed / Looks all keep working untouched — they see an ordinary video file.
* ✅ WYSIWYG: the preview is the result.
* ❌ **The lens cannot be removed or changed in the editor.** Accepted trade for v1; re-applying at
  render time would mean re-running face tracking over the decoded clip inside the Media3 pipeline.

---

## 6. UI design

Matches the owner's reference screenshot and the existing camera-screen tokens.

### 6.1 Lens button

Mirror of the flip button, at `Alignment.CenterStart` of the same width-capped `Box` in
`CameraScreen.kt:281`: 54.dp circle, `OverlayWhite` fill, 1.dp `OverlayWhiteBorder`, 28.dp white
icon. Visible in `ReadyToCapture` **and** `Recording`. `contentDescription = "Lenses"`.

### 6.2 Lens tray

Replaces the bottom control row while open (the shutter stays — Snapchat keeps it, and the reference
screenshot shows capture remains reachable):

* Horizontal `LazyRow` of circular thumbnails, center-snapped, active item scaled up with a ring.
* Leading `✕` clears the lens and closes the tray.
* Tapping a thumbnail sets the lens immediately — the preview updates live.
* **No category tab row in v1.** The reference shows one, but with 1–3 lenses it's chrome around
  an empty room. Add it when there are enough lenses to need grouping.
* 48.dp minimum touch target (accessibility floor — see the `HomeButton` note at
  `CameraScreen.kt:337`), each thumbnail carrying the lens name as `contentDescription`.

### 6.3 State

`activeLens: StateFlow<Lens?>` and `lensTrayOpen: StateFlow<Boolean>` as sibling flows in
`OpenLoopViewModel` — same shape as `TrimState` / `EditorTabState`. **No new `OpenLoopUiState`
entries**; the tray is an overlay on the camera-bound states, not a route (Lesson 014's exhaustive
`when` stays untouched).

`BackHandler` while the tray is open closes the tray; the existing recording backstop
(`CameraScreen.kt:131`) keeps priority.

---

## 7. Success criteria

1. Lens button visible at `CenterStart` in both camera-bound states, 48.dp+ target, TalkBack-labeled.
2. Tapping it opens a thumbnail carousel; tapping a thumbnail applies the lens to the live preview
   within ~1 frame of a face being detected.
3. The saved `.mp4` contains the lens, and trim → editor → save still works end-to-end on it.
4. Switching or clearing the lens **mid-recording** does not finalize or corrupt the recording.
5. **Live preview** holds ≥24 fps with a lens active on a mid-tier device — measured separately in
   phase 0 (`adb shell dumpsys SurfaceFlinger --latency` / frame timing), because the pixel-sweep
   gate does not see the preview.
5b. **Recorded output** shows no dropped or frozen frames with a lens active — the existing
   pixel-sweep per-half fps and freeze/green/black scans cover this.
6. The sticker tracks the face through roll, yaw, and lens flip without visible offset, on both
   front and back cameras.
7. No face in frame → no artifacts; the preview is a clean pass-through.
8. `docs/DEFINITION_OF_DONE.md` gate fully green (build ×2, tests, lint, on-device screenshot).

---

## 8. Phased plan

**Owner decision (2026-08-08): everything lands in ONE PR**, iterated on the emulator until it
behaves as designed. The phases below are therefore build order within that PR, not separate PRs.

| Step | Deliverable | Gate |
|---|---|---|
| **0 — Spike** | On a running emulator: `Preview + VideoCapture + ImageAnalysis` binds; a custom `CameraEffect` renders to preview *and* recording; a face is actually detectable in the emulator camera (custom virtual-scene poster). | Blocks everything below. |
| **1 — Pure math + tests** | `LensAnchor.kt` — face box + euler + rotation + mirror → sticker matrix / warp centre. No Android types. JVM-tested first (R1 dies here). | `:app:testDebugUnitTest` green. |
| **2 — GL renderer** | `LensSurfaceProcessor` + shader; identity pass-through when no lens. | Preview unchanged with no lens active. |
| **3 — Tracker** | ML Kit `ImageAnalysis` → `FaceSnapshot` flow. | Face box logged live. |
| **4 — UI** | Lens button (`CenterStart`), carousel, ViewModel flows, `BackHandler`. | Compose tests green. |
| **5 — Lenses** | **Broccoli** (sticker) · **Big Mouth** (warp) · one more sticker (**Sunglasses**). | All three render and record. |
| **6 — Verify** | `docs/DEFINITION_OF_DONE.md` gate + emulator run + screenshot. | Green before PR. |

**3D Cartoon: dropped** (owner, §10 Q1). Reopens only if C1 changes.
If Sunglasses adds risk late, shipping two lenses is acceptable (owner's second selection).

---

## 9. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Coordinate-space hell** — ML Kit reports in analysis-image space; `OverlayEffect` draws in buffer space; front camera adds mirroring; device rotation adds another transform. Misalignment here is *the* likely bug. | Isolate the whole mapping in a **pure Kotlin function** (`LensAnchor.kt`) with no Android types in its signature, JVM-tested per-orientation/per-lens-facing — the house pattern from `ZoomUi.kt`, `BoomerangSequence.kt`, `TrimHandleMath.kt`. |
| R2 | Third stream: `Preview + VideoCapture + ImageAnalysis` is only guaranteed on `LEVEL_3` hardware. Below that, CameraX 1.5+ no longer throws but falls back to an OpenGL stream copy — extra latency and power. | Phase 0 measures it across the sweep lanes. If the fallback is too costly, drop `ImageAnalysis` and run detection off the effect's own frames instead. |
| R3 | Tracker latency → the sticker lags the face. | `STRATEGY_KEEP_ONLY_LATEST` + `PERFORMANCE_MODE_FAST`; if visible, interpolate the last two snapshots. Do not add prediction speculatively. |
| R4 | The always-attached effect costs battery on an idle camera. | Measured in phase 0; documented fallback in §5.3. |
| R5 | Lens is unremovable once baked (§5.4). | Explicit, accepted, stated in the PR. |
| R6 | APK growth from the ML Kit bundled model + lens art. | Prefer the **unbundled** (Play-services-delivered) face-detection variant if size matters; measure both in phase 0. |
| R7 | OEM breakage — this repo's history is Samsung encoder/surface bugs (Lessons 019/021/023/027). A new GL stage in the capture path is exactly that risk surface. | Run the Samsung RTL lane + `run-e2e-pixel-sweep` before the PR, not after. |

---

## 10. Decisions (all resolved)

| # | Question | Decision |
|---|---|---|
| 1 | Replace 3D Cartoon with what? | **Dropped**, replaced by a second sticker lens — **Shades**. Owner, 2026-08-08. Shipping two lenses stays acceptable if Shades ever needs to be cut. |
| 2 | Which lens ships first? | **All three, one PR.** Owner, 2026-08-08. This is what forced §5.2's single-effect design. |
| 3 | Lens switching while recording? | **Yes.** Free under §5.3, guarded by `OpenLoopViewModelTest."changing lenses mid-recording never touches the capture state"`. |
| 4 | Auto-flip to the front camera? | **No.** The flip button is 54.dp away and back-camera tracking works. One less branch. |
| 5 | Where does lens art come from? | **Mixed, and one item needs clearing.** Shades and the Big Mouth icon are vector drawables authored in-repo — Apache 2.0 covers every pixel. **Broccoli is a photograph**, because hand-drawn vector florets could not reach the quality bar. ⚠️ **Open:** the shipped `lens_broccoli*.png` came from an owner-supplied file of unknown provenance. A public-domain USDA alternative was prepared and is a drop-in replacement if the licence cannot be confirmed — see §12. |
| 6 | Does the lens persist across launches? | **No.** `activeLens` is plain ViewModel state — reset on every launch. |

## 10b. As built — what changed from the plan

* **`OverlayEffect` was not used.** Decision 2 put a warp lens in the same PR, and CameraX allows
  only one effect per target — so stickers and warp share one hand-written `SurfaceProcessor`
  (§5.2). Lesson 031 records the rule.
* **Big Mouth is a fragment-shader UV bulge, not a vertex mesh.** Same look, a fraction of the code;
  no triangulation and no mesh landmarks needed, which is also why ML Kit *landmarks* suffice and
  contour mode was never switched on.
* **The square-space conversion shipped inverted first** and was caught by `LensAnchorTest` before
  it ever reached a device — see Lesson 032.
* **The tracker's frame is not the renderer's frame.** One line of per-bind logging exposed three
  independent mismatches that no amount of reading the APIs would have: the analysis stream is
  upright `720x1280` while the lens output is `1280x960` (a quarter turn *and* a different field of
  view), and mirroring is not inferable from lens facing. All three are now handled by derivation
  rather than assumption — `LensAnchor.uprightToBuffer`, `LensAnchor.reframe`, and a determinant
  comparison on the output transform. Lesson 032 carries the detail; this was the single most
  valuable thing the emulator produced.
* **Phase 0 could not be completed as written.** What *was* verified on the emulator: the
  three-stream bind, the effect rendering to preview, and a full record → clean `Finalize` with a
  lens active. Face-relative placement is covered by `LensAnchorTest` (math) and
  `FaceTrackerNormalizationTest` (real ML Kit against a bundled public-domain still); **live
  mirroring, roll and steadiness still need one pass with a real face** — see §11.1.

### Getting a face in front of the emulator camera

Researched against Google's docs. The mechanism exists but is **not fully scriptable**:

* `adb emu virtualscene-image <wall|table> <png|jpg>` sets a custom image at runtime (the
  command-line equivalent of Extended Controls → Camera → *Virtual scene images* → Add image).
* **But both image surfaces are in the dining room, through the doorway *behind* the starting
  camera pose** ([ARCore emulator guide](https://developers.google.com/ar/develop/java/emulator)).
  The default view is the bookshelf-and-TV wall; the checkerboard TV is scene geometry, not a
  poster.
* Moving the camera is **host-window input, not adb**: hold `Shift` + `W/A/S/D/Q/E`, `Shift`+mouse
  to look.
* `adb emu automation record` / `play` replays device-state macros — so navigating to the poster
  **once** by hand and recording it makes every later run automatic.
* Fallback: `-camera-back webcam0` (a host webcam is present on this machine). Captures the owner's
  real camera, so it needs consent.

Dead ends, recorded so nobody repeats them: an AVD-level `Toren1BD.posters` override is ignored
(only the SDK copy is read); enlarging or re-positioning the posters did not bring them into view;
and replacing `resources/default.mp4` with a face video — even matching the stock 1080x1080 /
H.264 High / AAC format exactly — is not the TV source and **black-screens the entire scene**.

---

## 11. Test plan

Follows `docs/TEST_COVERAGE.md`.

* **JVM (pure math):** `LensAnchorTest` — 30 cases over placement, offset orbit, mirroring, aspect,
  the warp circle, the upright→buffer rotation, and re-framing between streams. This is where R1
  dies, and it caught three separate model errors before any of them reached a device. ✅
* **JVM (ViewModel):** lens selection, tap-to-toggle-off, tray open/close independence, and the
  mid-recording guard. ✅
* **Compose (`androidTest`, no mockk — Lesson 017):** `LensCarouselTest` — every registered lens
  gets a thumbnail, 48.dp touch-target floor, selection and close callbacks, name pill visibility.
  `CameraBackHandlerTest` — back closes the tray, and takes priority over the recording backstop. ✅
* **Instrumented (real ML Kit):** `FaceTrackerNormalizationTest` — detects a face in a bundled
  public-domain still, asserts the normalized geometry stays in-frame and dominates a head crop
  (catching a divide-by-the-wrong-dimension), that mouth landmarks resolve below the box centre,
  and that Broccoli anchors above the face. ✅
* **E2E:** extend `run-e2e` to select a lens before recording, and reuse the pixel-sweep per-half fps
  and freeze/green/black scans on the lens output. ⬜
* **OEM:** Samsung RTL lane before PR (R7). ⬜

### 11.1 Manual QA — must be done on hardware

Everything below is unverifiable on an emulator (§10b) and is the honest residual risk:

0. **Steadiness first.** Does the lens sit still on a still face, and does it flicker off when the
   detector blips or the subject moves fast? Nothing about this is observable without a real face,
   and it is the most likely "it looks cheap" complaint. Deliberately **no smoothing or
   hold-last-face was added**: both trade latency for steadiness, and tuning that blind risks
   making it worse than leaving it out. If it jitters or flickers, say so — an exponential filter
   on `FaceSnapshot` plus a short hold is a small, well-understood change once there is something
   to tune against.
1. Front camera, face centred: each lens lands **on** the face, not mirrored to the wrong side.
2. Tilt the head left and right: the sticker rolls **with** the head, not against it.
3. Back camera: same three checks — mirroring must differ between the two, and only in x.
4. Portrait and landscape holds: no 90° offset.
5. Big Mouth: the bulge sits on the mouth and scales with distance from the camera.
6. Record with a lens, then trim / speed / reverse / Looks: the baked lens survives the pipeline.
7. Switch lenses mid-recording: the clip finalizes normally.
8. Walk out of frame: preview returns to a clean pass-through with no ghost sticker.

---

## 11.2 ⚠️ Asset licensing — must be settled before the PR

`app/src/main/res/drawable-nodpi/lens_broccoli.png` and `lens_broccoli_art.png` are derived from an
owner-supplied photograph whose licence is **not established**. OpenLoop is public under Apache 2.0,
so a stock photo without redistribution rights cannot ship.

Two clean resolutions:

1. **Confirm the source licence** is CC0 / public domain / otherwise redistributable, and record it
   here plus in the repo's attribution notes.
2. **Swap in the public-domain fallback.** [`File:USDA 2026 Broccoli.png`](https://commons.wikimedia.org/wiki/File:USDA_2026_Broccoli.png)
   (Wikimedia Commons, public domain) was already processed through the same pipeline and works —
   slightly more illustrative, unambiguously free. The pipeline is: key the white background to
   alpha, autocrop, downscale; the art is the solid cut-out, since the character render composites
   the eyes and mouth over it.

An earlier candidate was rejected outright: an Unsplash+ image, which is both watermarked and on a
paid tier that does not permit this use.

## 12. References

* [CameraX overview](https://developer.android.com/media/camera/camerax) · [CameraX architecture](https://developer.android.com/media/camera/camerax/architecture) · [Video capture architecture](https://developer.android.com/media/camera/camerax/video-capture)
* [`CameraEffect`](https://developer.android.com/reference/androidx/camera/core/CameraEffect) · [`OverlayEffect`](https://developer.android.com/reference/kotlin/androidx/camera/effects/OverlayEffect) · [`UseCaseGroup`](https://developer.android.com/reference/androidx/camera/core/UseCaseGroup)
* [What's new in CameraX 1.4.0](https://android-developers.googleblog.com/2024/12/whats-new-in-camerax-140-and-jetpack-compose-support.html) — the `OverlayEffect` + ML Kit sample
* [Introducing CameraX 1.5](https://developer.android.com/blog/posts/introducing-camera-x-powerful-video-recording-and-pro-level-image-capture) · [Guaranteeing feature combinations](https://developer.android.com/blog/posts/beyond-single-features-guaranteeing-feature-combinations-with-camera-x-1-5)
* [Image analysis](https://developer.android.com/media/camera/camerax/analyze) · [Configuration options](https://developer.android.com/media/camera/camerax/configuration)
* [ML Kit face detection (Android)](https://developers.google.com/ml-kit/vision/face-detection/android) · [Face detection concepts](https://developers.google.com/ml-kit/vision/face-detection/face-detection-concepts) · [`FaceContour`](https://developers.google.com/android/reference/com/google/mlkit/vision/face/FaceContour)
* [ML Kit face mesh detection](https://developers.google.com/ml-kit/vision/face-mesh-detection) (beta) · [ML Kit release notes](https://developers.google.com/ml-kit/release-notes)
* [MediaPipe Face Landmarker (Android)](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android)
* [Snap Camera Kit](https://developers.snap.com/camera-kit/home) · [Camera Kit Terms](https://www.snap.com/terms/snap-camera-kit)
* [Sceneform Augmented Faces guide](https://developers.google.com/sceneform/develop/augmented-faces/developer-guide) (archived) · [SceneView fork](https://github.com/SceneView/sceneform-android/releases)
