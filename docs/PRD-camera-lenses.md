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

```text
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

So: `LensSurfaceProcessor` (the one `CameraEffect`) is attached on every bind, holds a `@Volatile var activeLens: Lens?`, and draws
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
`CameraScreen.kt`: 54.dp circle, `OverlayWhite` fill, 1.dp `OverlayWhiteBorder`, 28.dp white
icon. Visible in `ReadyToCapture` **and** `Recording`. `contentDescription` = `camera_drawer_open` ("Lenses and Photo Booth" since the drawer gained the booth tab — [PRD-photo-booth D2](PRD-photo-booth.md)).

### 6.2 Lens tray

Replaces the bottom control row while open (the shutter stays — Snapchat keeps it, and the reference
screenshot shows capture remains reachable):

* Horizontal `LazyRow` of circular thumbnails, center-snapped, active item scaled up with a ring.
* Leading `✕` clears the lens and closes the tray.
* Tapping a thumbnail sets the lens immediately — the preview updates live.
* **No category tab row in v1** *(superseded 2026-08-20 — the drawer now carries a `Photo Booth | Lenses` tab slider, [PRD-photo-booth D2](PRD-photo-booth.md))*. The reference shows one, but with 1–3 lenses it's chrome around
  an empty room. Add it when there are enough lenses to need grouping.
* 48.dp minimum touch target (accessibility floor — see the `HomeButton` note at
  `CameraScreen.kt`), each thumbnail carrying the lens name as `contentDescription`.

### 6.3 State

`activeLens: StateFlow<Lens?>` and `lensTrayOpen: StateFlow<Boolean>` as sibling flows in
`OpenLoopViewModel` — same shape as `TrimState` / `EditorTabState`. **No new `OpenLoopUiState`
entries**; the tray is an overlay on the camera-bound states, not a route (Lesson 014's exhaustive
`when` stays untouched).

`BackHandler` while the tray is open closes the tray; the existing recording backstop
(`CameraScreen.kt`) keeps priority.

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
| 5 | Where does lens art come from? | **Mixed, and one item needs clearing.** Shades and the Big Mouth icon are vector drawables authored in-repo — Apache 2.0 covers every pixel. **Broccoli is a photograph**, because hand-drawn vector florets could not reach the quality bar. The shipped `lens_broccoli*.webp` came from an owner-supplied file; **the owner confirmed the licence on 2026-08-09** (PR #118). A public-domain USDA alternative stays prepared as a drop-in replacement — see §11.2. |
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
  independent mismatches that no amount of reading the APIs would have: a quarter turn between the
  upright analysis image and the lens output, a **field-of-view difference when the two streams are
  different shapes**, and mirroring that is not inferable from lens facing. All three are handled by
  derivation rather than assumption — `LensAnchor.uprightToBuffer`, `LensAnchor.reframe`, and a
  determinant comparison on the output transform. Lesson 032 carries the detail; this was the single
  most valuable thing the emulator produced.

  **On the field-of-view leg, the fix was to remove the question rather than model it.** Pinning
  `ImageAnalysis` to `RATIO_4_3_FALLBACK_AUTO_STRATEGY` makes both streams 4:3, so a landmark's
  normalized position means the same thing in both. Re-measured 2026-08-09 on a Pixel 10 Pro Fold and
  a Pixel_8 AVD, the pin **held on both** (analysis `640x480`, output `1600x1200` / `1280x960` — all
  4:3), and `reframe` early-returns. It stays in the tree as the residual-case guard, because CameraX's
  strategy is a *fallback* one and a device that cannot serve 4:3 analysis will hand back another
  shape. Earlier notes here quoted a `720x1280` analysis stream; that measurement predates the pin —
  see the proof doc's correction section.
* **Phase 0 could not be completed as written.** What *was* verified on the emulator: the
  three-stream bind, the effect rendering to preview, and a full record → clean `Finalize` with a
  lens active. Face-relative placement is covered by `LensAnchorTest` (math) and
  `FaceTrackerNormalizationTest` (real ML Kit against a bundled public-domain still); **live
  mirroring, roll and steadiness still need one pass with a real face** — see §11.1.

### Getting a face in front of the emulator camera

Researched against Google's docs. **Fully scriptable — no manual step** (an earlier version of this
section said otherwise; corrected 2026-08-09):

* `adb emu virtualscene-image <wall|table> <png|jpg>` sets a custom image at runtime (the
  command-line equivalent of Extended Controls → Camera → *Virtual scene images* → Add image).
* **But both image surfaces are in the dining room, through the doorway *behind* the starting
  camera pose** ([ARCore emulator guide](https://developers.google.com/ar/develop/java/emulator)).
  The default view is the bookshelf-and-TV wall; the checkerboard TV is scene geometry, not a
  poster.
* Moving the camera by hand is host-window input, not adb (`Shift` + `W/A/S/D/Q/E`) — **but you do
  not have to.** The emulator ships a stock macro that walks exactly this route:
  `adb emu automation play "<sdk>/emulator/resources/macros/Walk_to_image_room"`. It needs the
  **full path**; a bare name returns `KO: Could not open file`. Recording your own macro (what the
  first run here did) is unnecessary.
* Use the repo's own `app/src/androidTest/assets/face_fixture.jpg` (public domain) as the poster, so
  the run reproduces from a clean checkout with no personal image.
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

Everything below needs a **real face on real hardware** and is the honest residual risk. The
2026-08-09 re-verification run closed the emulator-reachable parts of items 5–7 (bulge on the mouth,
record-with-lens → Trim with the effect baked in, and a mid-recording lens switch covered by
`OpenLoopViewModelTest`); it could not touch 0–4 or 8, because the hardware run had nobody in front
of the camera and the emulator shows a static poster. See `docs/e2e/2026-08-08-camera-lenses-proof.md`.

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

## 11.2 Asset licensing — settled

`app/src/main/res/drawable-nodpi/lens_broccoli.webp` and `lens_broccoli_art.webp` are derived from an
owner-supplied photograph. OpenLoop is public under Apache 2.0, so a stock photo without
redistribution rights cannot ship — this was raised as a merge blocker on PR #118 and is **closed**:

> **Owner confirmation, 2026-08-09: OpenLoop holds full use permission for the broccoli source
> photograph.** Full use, so there is **no attribution requirement** and no credit line to carry.
> Asked and answered explicitly rather than assumed, because "we're allowed to use it" and "we're
> allowed to redistribute it under Apache 2.0 with no credit" are different claims and only the
> second one lets this ship. Nothing on this asset is outstanding.

The prepared public-domain fallback below stays on record in case that ever needs revisiting.

Everything else in the feature is licence-clean by construction: Shades and the Big Mouth icon are
vector drawables authored in-repo, ML Kit is Apache 2.0, and the instrumented-test face fixture is
public domain.

**Prepared fallback, unused.** [`File:USDA 2026 Broccoli.png`](https://commons.wikimedia.org/wiki/File:USDA_2026_Broccoli.png)
(Wikimedia Commons, public domain) was already processed through the same pipeline and works —
slightly more illustrative, unambiguously free. The pipeline is: key the white background to alpha,
autocrop, downscale; the art is the solid cut-out, since the character render composites the eyes
and mouth over it.

An earlier candidate was rejected outright: an Unsplash+ image, which is both watermarked and on a
paid tier that does not permit this use.

### Encoding

Both files are **WebP**, not PNG. They are photographic, and a lossless format on photographic
content cost ~1.1 MB — the largest asset in the app. Re-encoded at quality 90 they are **170 KB**
combined, and libwebp stores the alpha channel losslessly even in lossy mode, so the cut-out edges
that make the character read are bit-identical to the PNG (verified: max alpha delta 0 on both
files). Android has decoded WebP since API 14 and lossy-with-alpha since 18, well under `minSdk 26`.
Resource names are extension-independent, so `R.drawable.lens_broccoli*` is unchanged.

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

---

## 13. 2026-08-15 expansion — four new lenses, seven total

The catalogue now contains the original **Broccoli**, **Shades**, and **Big Mouth**, plus four
evidence-selected additions: **Bug Eyes**, **Pizza Face**, **Football**, and **Dog**. The candidates,
sources, disagreements, kills, and final mutual ACK are preserved in
`swarm/collab/research-codex.md`, `research-claude.md`, and `decisions.md`; this section records the
result as built.

| Lens | Tier | Final data | Art |
|---|---|---|---|
| Bug Eyes | warp | Two circles centred exactly on `FaceSnapshot.leftEye` and `rightEye`; radius `0.36`, strength `0.75`, target `EYES` | Original in-repo carousel vector; no live sticker art |
| Pizza Face | character | width `3.6`, aspect `1.005`, up `-0.4`; features `0.50 / 0.30 / 0.75 / -0.50 / 1.15` | Original generated near-photo food source, keyed to 1019×1024 WebP plus 255×256 thumbnail |
| Football | character | width `4.7`, aspect `0.571`, up `0.10`; features `0.58 / 0.45 / 0.80 / -0.45 / 1.30` | Owner-provided `football.jpg`, keyed to 1024×585 WebP plus 256×146 thumbnail; known source clip at the left tip retained honestly |
| Dog | prop | width `2.90`, aspect `0.7345`, up `0.385`; no feature compositing or warp | Original 290×213 in-repo vector; same resource is reused for the carousel |

Feature tuples are `eyeSpacing / eyeUp / eyeWidth / mouthUp / mouthWidth`, in face units. Every
photographic asset is at or below the renderer's 1024-pixel long-side limit, uses lossless alpha,
has transparent corners, and has no baked cast shadow. Pizza's first five-circle vector draft was
rejected as clip-art and removed; the shipped carousel and live art both use the near-photo source.
Kayley accepted the replacement on 2026-08-15 after inspecting the encoded file.

### Generic two-eye warp

`WarpSpec` gained the anatomical `WarpTarget` values `MOUTH` and `EYES`, defaulting to `MOUTH`.
`LensAnchor.warps()` therefore produces one unchanged mouth circle for Big Mouth or two landmark-
centred eye circles for Bug Eyes. `LensSurfaceProcessor` binds two generic uniform sets and applies
the same shader function twice; an unused set is disabled with `WarpCircle.NONE`. No renderer,
tracker, UI, camera, or capture branch names a lens, and the default keeps Big Mouth's centre,
radius, and strength unchanged.

`LensAnchorTest.warps_eyeTargetUsesBothTrackedEyes` is the smallest regression check for the new
branch. It asserts two circles and exact landmark centres; the existing mouth-centred test protects
the old path.

### Final-tree verification

On 2026-08-15, one no-daemon invocation of `assembleDebug`, `assembleRelease`,
`testDebugUnitTest`, and `lintDebug` completed with `BUILD SUCCESSFUL` (107 tasks; 3 executed,
104 up-to-date after the preceding uncached execution). Debug lint had **zero errors**, 25 visible
warnings, and 11 baseline-filtered warnings; this is not represented as warning-clean. The unit
result XML totals **381 tests, 0 failures, 0 errors, 0 skipped**, and the release APK passes
`zipalign -c -P 16 4` with exit 0.

The Pixel_8 AVD walk-to-image-room run reached the stock dining-room portrait, which ML Kit detects
even though it is a painting rather than a real face. All seven carousel labels rendered. Football
rendered in the live preview, and the pulled saved JPEG plus frame 60 from the pulled 720×1280,
26.9-second MP4 both contain the composited lens with no UI chrome. Recording finalized cleanly;
logcat showed `targets=3`, 1280×960 lens output, 640×480 analysis, and no
`ERROR_SOURCE_INACTIVE`. Evidence is in `swarm/collab/evidence-claude/`.

Bug Eyes and Dog were then selected against the same detected portrait. The Bug Eyes comparison
shows two independently firing circles with the nose bridge unchanged; Dog shows both ears clear of
the detected eyes and the snout on the nose. These are useful bind/shader/placement checks, not look
approval: Kayley kept Bug Eyes' joke magnitude and Dog's human-face fit in Steven's hardware lane.

Pizza v2 was recaptured only after reinstalling and pulling the installed APK back from the device.
Its embedded `lens_pizza_art.webp` is byte-identical to the repo asset (337,878 bytes; SHA-1 prefix
`a53a010b5cbc`). Evidence `10-pizza-v2-VERIFIED-hash-checked-install.png` visibly shows the accepted
near-photo pizza, with the tracked eyes and mouth composited through it and the painted head covered.
The stale v1 capture remains explicitly named `09-REJECTED-...-DO-NOT-USE.png`; it is not shipping
evidence. Evidence `11-dod-run-screenshot-full.png` is the final full-screen Pixel_8 run proof.

That closes the bind/render/photo/video-bake path, including the explicit question of whether the
preview-bitmap photo path drops the lens: it does not. It does **not** turn the painting into
hardware QA. Kayley ruled that its oversized painted head cannot pass or fail character coverage or
the Bug Eyes visual-read gate; Football stays at the table-derived width `4.7` rather than being
inflated against the painting.

The Pixel_8 instrumented gate initially exposed two real failures in `LensCarouselTest`: once the
catalogue reached seven entries, the trailing `LazyRow` thumbnails were not composed until scrolled
into view. The test now scrolls to each catalogue entry before retaining the same displayed and
48 dp touch-target assertions; no production carousel code changed. The fresh result XML records
**102 tests, 0 failures, 0 errors, 1 skipped**.

Real-face quality remains a hardware gate owned by Steven: front/back mirroring, head roll,
portrait/landscape alignment, steadiness and flicker, fast movement, distance scaling, no-face
pass-through, and whether each joke actually looks good. A static emulator poster can prove bind,
render, capture, and saved-media paths; it cannot prove those human-facing behaviours.

---

## 14. 2026-08-16 — Twisted Tongue, and the framework it forced

**Status:** built, verified on the owner's hardware · **Guide:** [`twisted-tounge/GUIDE.md`](../twisted-tounge/GUIDE.md)
**Ships in:** 1.0.41 (versionCode 41)

The eighth lens, and the first sourced from a **third-party AR project** rather than designed here.
The owner supplied a DeepAR Studio project (`twisted-tounge/`); §4 already ruled DeepAR out as a
dependency on C1/C3, so the SDK and its assets could not ship. What shipped instead is a native
reimplementation in this repo's own renderer, with original art. The guide carries the full method,
the dead ends, and a playbook for the next one — that playbook is the durable deliverable, since the
owner has more effects of this kind queued.

### 14.1 What the reference effect does

Read out of `effect.json`'s scene graph and the `.mat` shader bindings, not from the preview image:

| Reference node | What it is | Shipped as |
|---|---|---|
| `L_eye_phy` / `R_eye_phy` + `pSphere5/6` on `matcap*.mat` | matcap-shaded eyeball spheres at ±3.08 units, each on `simplePendulumPhysics` | Opaque eyeball art on the `LEFT_EYE` / `RIGHT_EYE` anchors |
| `tongue_1..5` chain, four with `simplePendulumPhysics` | a bone chain that lags the head | One tongue layer with a single damped spring (`LensPhysics`) |
| `Group48969` / `group1group4` / `Group17997` on `skinsamplingmat.mat` + `colorSampling` | skin-toned mouth surround that samples the camera for its colour | **Dropped** — the mouth layer is lips-and-cavity, so it has no complexion to match |
| `morphbase2.fbx` → `dense_new`, `blendShapeWeights = [1.0]` | a static full-face morph | **Dropped** — a dense mesh warp is a different renderer |
| `nose1.fbx` | phong nose overlay | **Dropped** — invisible under the eyes and mouth |

The two drops are the honest scope line: this renderer composites textured quads and does one
radial UV bulge. It does not skin meshes, and nothing here pretends otherwise.

### 14.2 Framework changes

Three, all generic — **no renderer, tracker, UI or camera branch names a lens**, and the catalogue
rule ("adding a lens is one entry plus its art") survives:

1. **`Lens.art` is a `List<LensArt>`.** Layers draw in list order, so a lens controls its own
   stacking. Every previous lens is a one-element list.
2. **`LensPlacement` gained `anchor` (`FACE`/`LEFT_EYE`/`RIGHT_EYE`/`MOUTH`) and `rightInUnits`.**
   `FACE` is the default and reproduces the old centre-line behaviour exactly. This is what lets one
   lens track three landmarks at once — impossible with a single quad at any size.
3. **`LensPhysics`** — a pure damped-spring module. A layer opts in with a `WobbleSpec`;
   `LensAnchor.sticker` takes a `wobbleRadians` that rotates the art **about its anchor**, so a
   hanging part swings from where it is attached instead of spinning in place. Zero is bit-identical
   to the rigid placement.

### 14.3 Decisions

| # | Question | Decision |
|---|---|---|
| 1 | Ship the DeepAR SDK? | **No** — §4 C1/C3, decided 2026-08-08 and unchanged. Paid/MAU-metered, and a proprietary binary cannot ship in an Apache 2.0 app. |
| 2 | Ship the DeepAR *assets* (matcaps, normal maps, FBX meshes)? | **No.** This repo is public; we hold no redistribution rights. `twisted-tounge/**` is gitignored except the guide. Art is original vector, authored here. |
| 3 | Gate the tongue on mouth-open? | **No.** The reference does not either — its blendshape weight is statically `1.0` and there is no trigger in the graph. Openness detection would need ML Kit `CONTOUR_MODE`, which §5.1 rejected on per-frame cost. Faithful *and* cheap. |
| 4 | Bulge the eyes with the existing `WarpTarget.EYES`? | **No.** The eyeball art is opaque and covers the socket, so a warp underneath is invisible — one less moving part. |
| 5 | Wobble the eyeballs too? | **No.** The reference does, but the eyeball is centred *on* its anchor, so rotation about that anchor barely moves it; a visible jiggle would need a second mechanism (translation). The tongue carries the motion. Same primitive with a lever arm if it is ever wanted. |
| 6 | Roll as a physics drive? | **No.** Translation only. A roll term needs the part to hang in world-down rather than face-down — a different, larger model. Marked `ponytail:` in `LensAnchor.lateralShiftInUnits`. |
| 7 | Teeth as their own layer? | **Yes.** A lolling tongue passes *over* the lower lip but *under* the upper teeth — a three-way interleave one drawable cannot express. |
| 8 | Carousel thumbnail art | **Redrawn once.** The first version used the live art's cream eyeballs on transparency; the tray renders each thumbnail on a light glass chip, so it read as a pale blob (owner feedback, 2026-08-16). Every light shape now carries a heavy dark rim. Thumbnails are designed for the chip they sit on, not scaled down from the live art. |

### 14.4 Where this feature is actually verified

**The wobble is verified on the JVM, and that is not a compromise — it is the only place it can be.**
The emulator's virtual scene is a static poster. It can prove the lens binds, renders to preview,
composites onto a detected face, and bakes into a recording. It can never move a head, so it cannot
exercise one line of the spring.

* **`LensPhysicsTest`** — settles, is under-damped enough to actually flop, never exceeds its limit
  under an absurd drive, cannot explode on a long frame gap, and is stable at `MAX_STEP_SECONDS`
  for *every* spec in the catalogue. Properties, not expected numbers, so retuning the feel cannot
  silently break the safety guarantees.
* **`LensAnchorTest`** — anchors resolve to their landmarks, `FACE` is unchanged, a wobble of zero
  is bit-identical to rigid, the swing preserves the hanging distance, and the drive is dimensionless
  (the same head movement gives the same number at any distance). Plus Twisted Tongue's own claims:
  the eyeballs clear the nose bridge and stay inside the head, and the tongue's root stays behind the
  teeth **at the swing limit**, not just at rest.
* **`LensCarouselTest`** is catalogue-driven and covered the eighth entry with no edit.
* **Emulator (Pixel_8 AVD):** carousel entry renders, selection sets the active lens (name pill),
  no shader-compile or link failures, no `ERROR_SOURCE_INACTIVE`.
* **Owner hardware, 2026-08-16:** confirmed working on a real face — the one check that settles it.

### 14.5 Residual risk — unchanged and owner-owned

Everything in §11.1 still applies, plus one item this lens adds — call it **item 9**, continuing
that list's numbering:

**Does the tongue's swing look right on a real head?** Frequency, damping and drive are tuned
   from arithmetic (≈1.95 Hz, a quarter of critical damping) against a reference video, not against
   a moving face. It is the one number set here that a person has to judge. All three live together
   in one `WobbleSpec` in `Lens.kt` so retuning is a single edit — and `LensPhysicsTest` asserts the
   *properties*, so the feel can be changed freely without weakening the guarantees.

---

## 15. 2026-08-17 — Big Mouth and Bug Eyes removed

Owner decision: both warp lenses come out of the catalogue entirely. The sections above are left as
written — they are the dated record of how the feature was built, and rewriting them would erase the
reasoning rather than the lenses.

### 15.1 What went

* `Lens.BigMouth` and `Lens.BugEyes`, plus their carousel-only vectors `lens_big_mouth.xml` and
  `lens_bug_eyes.xml`.
* Manual check **5** in §11 ("Big Mouth: the bulge sits on the mouth") no longer has a subject; it is
  retired rather than renumbered, so the surviving check numbers still match the e2e proofs that cite
  them.
* The catalogue is now **seven** lenses: Broccoli, Shades, Pizza Face, Football, Dog, Twisted Tongue,
  Elvis.

Nothing outside the catalogue named either lens — that was §5's design goal and it held, so the
removal is one enum edit plus the test call sites that happened to pick Big Mouth as a stand-in.
Lens selection is session state (`MutableStateFlow<Lens?>(null)` in `OpenLoopViewModel`), never
persisted, so no user can hold a stale reference to a deleted entry across a launch.

### 15.2 The warp engine went with them

Big Mouth and Bug Eyes were the **only** users of the warp path, so §5's `WarpSpec` / `WarpTarget` /
`WarpCircle`, `LensAnchor.warps()`, `Lens.warp`, and the shader's two uniform sets and `applyWarp()`
are all deleted rather than left as unreachable code. §13's "Generic two-eye warp" subsection
describes machinery that no longer exists; it stays as the record of why it was built that way.

The camera fragment shader collapses to a single `texture2D`. Its flip into y-down screen space
existed **only** so the warp centres could be compared against `LensAnchor`'s coordinates — with the
warp gone the flip and its inverse cancel exactly, so removing both is a no-op on output, not a
behaviour change. `drawCamera` no longer needs the lens, the face, or the frame aspect, and
`uFrameAspect` survives only on the feature program, which has its own copy.

This is a separate commit from §15.1 on purpose: reverting it restores the engine without bringing
back the two lenses, if a future warp lens wants it.
