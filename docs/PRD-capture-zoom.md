# PRD — Pinch-to-Zoom During Live Capture

**Status:** Approved — owner signed off 2026-07-09 (`/goal Implement docs/PRD-capture-zoom.md`); implemented on `feature/capture-pinch-zoom`
**Owner:** Steven Gates
**Date:** 2026-07-09
**Proposed branch:** `feature/capture-pinch-zoom`

---

## 1. Problem statement

The capture screen has **no zoom at all**. Users cannot frame a shot tighter or wider without
physically moving — every competing camera app (stock Pixel/Samsung camera, Instagram, Snapchat)
supports pinch-to-zoom on the viewfinder, including while a recording is running. For a
boomerang app, mid-record zoom is also a creative tool (push-in loops).

Under the hood the gap is structural: `CameraManager.startCamera()` **discards the `Camera`
handle** returned by `bindToLifecycle()` (`camera/CameraManager.kt`, pre-#100), so the app holds no
`CameraControl` / `CameraInfo` — the only interfaces through which CameraX zoom is set and
observed. There is no touch handling on the `PreviewView` and no zoom UI in `CameraScreen.kt`.

**Explicitly out of the problem:** clips imported from the library. Import bypasses
`CameraManager` entirely (gallery → Photo Picker → editor), zoom is baked into recorded pixels at
capture time, and the downstream trim/editor/reverse pipeline never sees a "zoom" concept. This
feature touches the live capture path only.

### Why Samsung is called out

Samsung devices are OpenLoop's proven OEM pain point — every past Samsung failure was invisible
on Pixels and emulators and only surfaced on real Galaxy hardware:

| Incident                                                   | Device                        | Lesson                                                                        |
| ---------------------------------------------------------- | ----------------------------- | ----------------------------------------------------------------------------- |
| Reverse pass produced a valid zero-frame MP4, wedging save | Galaxy S23 (SM-S911U, API 33) | [023](lessons_learned/023-media-pipeline-stages-must-count-output-samples.md) |
| FGS type crash on 100% of Loopify saves on Android 14      | Galaxy A55 (SM-A556E, API 34) | [024](lessons_learned/024-fgs-type-constant-api-gating.md)                    |
| Preview reverse exceeded deadline at 720p+ on Exynos       | Galaxy S24 family (SM-S921B)  | Issue #63, `media/DeviceMediaHints.kt`                                        |

Zoom has its own Samsung-specific realities (see §5). The design below treats "works on Samsung"
as an acceptance criterion with its own validation lane, not an afterthought.

---

## 2. Decisions already made (owner, 2026-07-09)

| #   | Decision        | Choice                                                                                                                                                                                                                               |
| --- | --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| D1  | Control surface | **Pinch gesture + transient ratio chip** (e.g. `2.3x`) that fades ~1 s after the gesture ends. No slider, no preset buttons.                                                                                                         |
| D2  | Mid-record zoom | **Allowed** — pinch works before and during recording; zoom is baked into the clip.                                                                                                                                                  |
| D3  | Reset semantics | **Reset to 1x on every rebind** — returning to the camera screen and flipping front/back both start at 1x. This is CameraX's natural behavior (zoom state reverts when the camera closes), so no re-apply logic exists to get wrong. |
| D4  | Range           | **Honor the device-reported `ZoomState` range**, including sub-1x ultra-wide where the OEM exposes it. Never hardcode ratios.                                                                                                        |
| D5  | Imports         | **No zoom anywhere outside live capture** (restates the requirement).                                                                                                                                                                |

---

## 3. Success criteria

1. Pinch-out/pinch-in on the viewfinder changes zoom smoothly on **back and front** lenses, in
   both `ReadyToCapture` and `Recording` states.
2. The **recorded clip's framing matches the preview** at the moment of capture — zoom is in the
   encoded video, not preview-only. Verified on a Pixel **and** a Samsung device.
3. A pinch **never interrupts a recording**: no camera rebind, no `ERROR_SOURCE_INACTIVE`, no
   `Recorder RECORDING --> STOPPING` in logcat during/after a mid-record pinch (Lesson 012's
   signature).
4. Ratio chip appears on gesture start showing the live ratio (`1.0x` format), fades ~1 s after
   the gesture ends, and never blocks the shutter/flip/home touch targets or the countdown chip.
5. Zoom is clamped to `CameraInfo.getZoomState()` bounds — pinching past min/max holds at the
   bound without errors; devices reporting `minZoomRatio < 1.0` can pinch out below 1x.
6. Fresh 1x after leaving/re-entering the camera screen and after a lens flip (D3).
7. The import → editor flow is byte-for-byte untouched — no zoom UI, no behavior change.
8. Full Definition-of-Done gate passes (`docs/DEFINITION_OF_DONE.md`): green debug+release
   builds, 0 test failures, 0 new lint errors, app run on emulator with screenshot proof.

Non-criteria (explicit **non-goals**): zoom in gallery/editor/trim playback; preset buttons or a
slider (possible follow-up — also an accessibility win, see §8); tap-to-focus; volume-key zoom;
persisted zoom preference; Camera2 interop to reach lenses the OEM hides from the logical camera.

---

## 4. Design

### 4.1 API grounding (verified on developer.android.com this session)

From [CameraX camera output control](https://developer.android.com/media/camera/camerax/configuration):

- `bindToLifecycle()` returns a `Camera`; `camera.cameraControl` sets zoom,
  `camera.cameraInfo.getZoomState()` is a `LiveData<ZoomState>` carrying
  `zoomRatio` / `minZoomRatio` / `maxZoomRatio` / `linearZoom`.
- [`CameraControl.setZoomRatio()`](https://developer.android.com/reference/androidx/camera/core/CameraControl#setZoomRatio%28float%29)
  applies zoom to **all bound use cases** (Preview *and* VideoCapture — this is what bakes zoom
  into the recording). Out-of-range values return a **failed `ListenableFuture`** — they do not
  throw. A new call **cancels the previous outstanding future** (expected constantly during a
  pinch stream).
- Google's documented pinch pattern: a `ScaleGestureDetector` whose `onScale` multiplies the
  current `zoomRatio` by `detector.scaleFactor` and calls `setZoomRatio()`, attached via
  `PreviewView.setOnTouchListener`.
- **Zoom state reverts to default whenever the `Camera` closes.** OpenLoop unbinds on every
  screen leave (`releaseCamera()`, Lesson 022) and on lens flip (`toggleCamera()` → full
  `startCamera()` rebind) — which is exactly D3's reset-on-rebind for free.
- After the LifecycleOwner stops or the camera is released, `CameraControl` returns failed
  futures — late gesture events after `releaseCamera()` must be a no-op, not a crash.

### 4.2 `CameraManager` changes (`camera/CameraManager.kt`)

```kotlin
class CameraManager(private val context: Context) {
    private var camera: Camera? = null                       // NEW: retained from bindToLifecycle
    private val _zoomUi = MutableStateFlow<ZoomUi?>(null)    // NEW: null = camera not bound
    val zoomUi: StateFlow<ZoomUi?> = _zoomUi.asStateFlow()

    // In startCamera(), replace the discarded return:
    //   camera = cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
    // then observe camera.cameraInfo.zoomState (main thread) and mirror into _zoomUi.

    /** Clamps to the device-reported ZoomState range and applies. Safe no-op when unbound. */
    fun setZoomRatio(requested: Float) {
        val info = camera?.cameraInfo?.zoomState?.value ?: return
        val clamped = requested.coerceIn(info.minZoomRatio, info.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(clamped)
        // Do NOT await/verify the future per-event: each pinch tick cancels the prior request
        // by design (documented CameraX behavior); treating cancellation as failure would spam.
    }

    // releaseCamera(): also stop the zoomState observation, camera = null, _zoomUi.value = null.
}

/** Immutable UI snapshot of the zoom state (pure data, JVM-testable consumers). */
data class ZoomUi(val ratio: Float, val minRatio: Float, val maxRatio: Float)
```

Implementation constraints:

- **LiveData observation lifetime:** `CameraManager` is not a `LifecycleOwner`; observe
  `zoomState` with `observeForever` on the main executor and remove the observer in
  `releaseCamera()`/before every rebind. A leaked observer across rebinds is the failure mode to
  test for.
- **`releaseCamera()` ordering:** clear `camera` and `_zoomUi` in the same place `videoCapture`
  is cleared today. The existing "skip while `activeRecording != null`" guard already protects
  mid-record teardown (Lessons 012/022) — zoom adds no new lifecycle edge.
- **Clamp math is a pure function** — extract `fun clampZoom(requested: Float, min: Float, max: Float): Float`
  (or fold into `ZoomUi`) into a small pure file so it's JVM-tested without a camera, mirroring
  the `BoomerangSequence` extraction pattern (Lesson 018's testability rationale).

### 4.3 Gesture wiring (`ui/CameraScreen.kt`)

Follow the **documented CameraX pattern**: a `ScaleGestureDetector` attached with
`previewView.setOnTouchListener` inside the existing `remember { PreviewView(context) }` block.

```kotlin
val previewView = remember {
    PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        val detector = ScaleGestureDetector(context, object : SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val current = cameraManager.zoomUi.value ?: return true
                cameraManager.setZoomRatio(current.ratio * d.scaleFactor)
                return true
            }
        })
        setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }
}
```

Why the View-side listener instead of a Compose `pointerInput` overlay:

- It is the pattern Google documents for CameraX pinch-zoom, and it keeps a future tap-to-focus
  (`previewView.meteringPointFactory.createPoint(event.x, event.y)`) on the same listener.
- The Compose control overlays (shutter, flip, home, chips) sit **above** the `AndroidView` and
  consume their own touches — pinches on them never reach the `PreviewView`, so there is no
  gesture-priority fight to manage.
- A custom-`View` touch listener wants a `performClick()` call for accessibility (standard
  `ClickableViewAccessibility` lint contract) — handled above.

> **Implementation deviation (shipped, PR #100).** The design above did not survive hardware
> testing: on Fold-class devices both a Compose `pointerInput` overlay and
> `PreviewView.setOnTouchListener` stop receiving events at the second pointer down, so the
> pinch never starts. The shipped implementation wraps `PreviewView` in
> `camera/PinchZoomLayout.kt` — a `FrameLayout` whose `onInterceptTouchEvent` steals the stream
> when `pointerCount >= 2` (or a scale gesture is in progress) and feeds its own
> `ScaleGestureDetector`, with the `performClick()` accessibility contract implemented on the
> layout itself. `CameraScreen` hands it `PinchZoomCallbacks` wired into
> `CameraManager.onPinchZoomBegin / applyPinchZoom / onPinchZoomEnd`. Everything else in this
> section (detector math, clamp-through-`CameraManager`, overlays keeping their own touches)
> holds as written. Full pattern: `docs/lessons_learned/025-previewview-pinch-needs-parent-intercept.md`.

### 4.4 Zoom ratio chip (new composable in `ui/CameraScreen.kt`)

`ZoomRatioChip(visible: Boolean, text: () -> String)` — same hoisted, stateless shape and glass
styling (`OverlayWhite`/`OverlayScrim`/`OverlayWhiteBorder`, `RoundedCornerShape(percent = 50)`)
as `RecordingCountdownChip`, centered in the viewfinder per the approved mock.

- **Lesson 016 applies literally:** during a pinch the ratio updates every frame. `CameraScreen`
  collects `cameraManager.zoomUi` as a **raw `State` via `collectAsStateWithLifecycle()`**
  (Lesson 002) and **never reads `.value` at the screen root** — the read happens inside the
  chip's `text` lambda, so per-tick recomposition is confined to the chip (the pattern already
  used for the countdown chip and progress ring).
- Visibility: driven by a "last gesture end" timestamp — visible while a pinch is active and for
  ~1 s after, then fades (`AnimatedVisibility`/alpha animation). Visibility flips at gesture
  granularity (rare), so it may be ordinary low-frequency state.
- Format: `"%.1fx".format(ratio)` — `1.0x`, `2.3x`, `0.5x`.
- Semantics: `contentDescription = "Zoom level"`; the chip is informational, not a touch target.

### 4.5 What deliberately does NOT change

| Untouched                                         | Why                                                                                                                                                                                                                                                                                 |
| ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `OpenLoopUiState` / `OpenLoopNavHost`             | Zoom is transient camera control inside the two capture states — no new state, router `when` stays exhaustive and unchanged (Lesson 014).                                                                                                                                           |
| `OpenLoopViewModel`                               | Zoom never crosses the ViewModel: the gesture writes straight to `CameraManager`, the chip reads straight from it — same direct relationship `toggleCamera` already has. Keeps Context/camera types out of the VM (Lesson 004) and keeps the ~60 Hz gesture stream out of VM state. |
| `CameraScreenHost` single call site               | Zoom via `CameraControl` mutates capture requests on the existing session — **no unbind, no rebind, no remount** (Lessons 012/022 preserved by construction).                                                                                                                       |
| Recording pipeline, trim, editor, reverse, import | Zoom is applied pre-encode by the camera HAL; every downstream stage sees ordinary frames.                                                                                                                                                                                          |
| Quality/resolution config                         | `QualitySelector.from(Quality.HD)` unchanged.                                                                                                                                                                                                                                       |

---

## 5. Samsung / OEM considerations

1. **Range honesty (D4 is the mitigation).** Many Galaxy models expose only `[1.0 .. max]` to
   third-party apps through the default back logical camera — ultra-wide (sub-1x) is reserved
   for the stock camera on much of the fleet, while Pixels commonly report `~0.55x`. Because the
   UI derives *everything* from `ZoomState` and hardcodes nothing, Samsung's narrower range
   degrades gracefully: users simply can't pinch below 1x there. **Do not** add Camera2 interop
   or camera-ID enumeration to chase hidden Samsung lenses — out of scope (§3).
2. **Mid-record zoom is the risk surface.** On API 30+ CameraX applies zoom via
   `CONTROL_ZOOM_RATIO`; on API 26–29 (`minSdk 26`) it falls back to crop-region digital zoom —
   both per-capture-request, neither restarts the session. The Samsung-specific things to watch
   on hardware: recorded zoom not matching preview zoom, visible stepping/stutter on Exynos
   while ramping, and AE/AWB flicker during the ramp. These are observational QA items (§6.3) —
   **no speculative quirk code**. If a real Galaxy failure shows up, the mitigation belongs in a
   `DeviceMediaHints`-style hint gated on `isSamsungDevice()`, driven by an actual Crashlytics
   signature — the auto-triage pipeline (PRD-crashlytics-autotriage) is the safety net,
   exactly how the S23/A55 issues were caught and fixed.
3. **Emulators cannot validate zoom optics.** The virtual camera scene will scale, so gesture →
   `ZoomState` plumbing is emulator-testable, but encoded-output framing, smoothness, and OEM
   behavior are not — same blind spot that hid Lessons 012/023. Real-hardware checks are §6.3.

---

## 6. Test plan (per `docs/TEST_COVERAGE.md` pyramid)

### 6.1 JVM unit tests (new `ZoomMathTest` or similar)

- Clamp: below min → min; above max → max; in-range → unchanged; degenerate range (min == max)
  → that value. Includes a sub-1x min case (Pixel-style `0.55f`).
- Scale accumulation: `ratio × scaleFactor` clamped across a simulated pinch stream never
  escapes the range.
- Chip formatting: `1.0x`, `2.3x`, `0.5x`, max-ratio rounding.

### 6.2 Instrumented tests (`androidTest` — real fakes, no mockk; Lesson 017)

- `ZoomRatioChip` visible with correct text when shown; gone after the fade window (Compose test
  clock).
- Chip does not overlap/steal the shutter's touch target (existing semantics assertions extend).
- **Regression must stay green:** `CameraScreenTest.cameraScreenHost_keepsContentMounted_acrossCaptureTransition`
  — proves the zoom additions didn't reintroduce a remount seam.

### 6.3 On-device manual QA (attached to the PR as the checklist)

| Check                                                                                                            | Pixel 10 Pro Fold (physical)   | Samsung Galaxy (see §7 Q1)   |
| ---------------------------------------------------------------------------------------------------------------- | ------------------------------ | ---------------------------- |
| Pinch in/out at idle, back lens; chip tracks ratio                                                               | ☐                              | ☐                            |
| Sub-1x reachable iff `minZoomRatio < 1`                                                                          | ☐ (expect yes)                 | ☐ (expect no on most models) |
| Start recording at 2x → clip plays back framed at 2x                                                             | ☐                              | ☐                            |
| Pinch **during** recording → recording completes; logcat clean of `ERROR_SOURCE_INACTIVE` / premature `STOPPING` | ☐                              | ☐                            |
| Zoom ramp smooth, no AE/AWB flicker (observational)                                                              | ☐                              | ☐                            |
| Front lens pinch works; flip resets to 1x                                                                        | ☐                              | ☐                            |
| Leave to gallery → return → 1x                                                                                   | ☐                              | ☐                            |
| Import from library → editor: zero zoom UI, flow unchanged                                                       | ☐                              | ☐                            |

### 6.4 E2E automation

Extend the `run-e2e` lane with a zoom step only if a reliable two-finger gesture is available
(`adb shell input` can't multitouch; the sweep scripts drive via uiautomator bounds — a
two-pointer swipe helper or a debug-only test hook would be needed). **Recommendation:** ship
with 6.1–6.3 and file a follow-up issue for sweep integration rather than blocking the feature
on gesture-injection tooling.

---

## 7. Open questions (need owner input before/while building)

1. **Samsung hardware access.** Is there a physical Galaxy available for the §6.3 column? If
   not, proposed fallback: run the QA matrix on a **Firebase Test Lab** physical Galaxy
   (interactive session), plus staged-rollout monitoring via the existing Crashlytics
   auto-triage. Which do you want?
2. **Front-lens chip.** Front cameras typically report a small digital range (e.g. 1x–4x). Show
   the same chip there (proposed: yes, zero extra code) or suppress zoom on the front lens?
3. **Haptics.** Tick haptic when the pinch hits min/max (like stock cameras)? Proposed: no for
   v1 — keep the surface minimal.
4. **E2E follow-up issue** (§6.4): file it as part of this PR or skip until the feature proves
   itself?

---

## 8. Risks & mitigations

| Risk                                                           | Mitigation                                                                                                                              |
| -------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Pinch stream floods recomposition, jank on viewfinder          | Deferred reads (Lesson 016); chip-scoped recomposition; verified via Layout Inspector counts.                                           |
| Late gesture after `releaseCamera()` → NPE/failed-future crash | `setZoomRatio` no-ops when `camera == null`; futures intentionally un-awaited; failed futures are the documented post-release behavior. |
| Leaked `zoomState` observer across lens flips                  | Observer removed in `releaseCamera()` and before each rebind; unit-check via observer count on fake.                                    |
| OEM-specific mid-record zoom artifact ships unnoticed          | §6.3 two-device matrix pre-merge; Crashlytics auto-triage post-release; `DeviceMediaHints` pattern reserved for a *proven* quirk.       |
| Pinch-only zoom excludes some users (a11y, Issue #98 context)  | Known limitation, documented non-goal; preset buttons are the designed escape hatch as a follow-up.                                     |
| Gesture eats taps meant for future tap-to-focus                | Listener structured so `ACTION_UP` + `performClick()` leaves room for a metering-point branch later.                                    |

---

## 9. Implementation plan (single PR)

Small enough for one PR on `feature/capture-pinch-zoom`; slices are commit-sized, not PR-sized:

1. **`CameraManager` zoom plumbing** — retain `Camera`, `ZoomUi` StateFlow, clamped
   `setZoomRatio()`, observer lifecycle in `releaseCamera()`. Pure clamp math + JVM tests.
2. **Gesture + chip** — `ScaleGestureDetector` on `PreviewView`, `ZoomRatioChip` with deferred
   reads and fade, instrumented tests.
3. **Verification gate** — full `DEFINITION_OF_DONE.md`: debug+release builds, unit +
   instrumented suites, `:app:lintDebug` (0 new), emulator run + screenshot, §6.3 hardware
   matrix, honest "could not verify" list in the PR body.

New lesson file only if review/hardware surfaces a genuinely new pattern (e.g. a Samsung zoom
quirk) — not speculatively.

---

## 10. References

- [CameraX camera output control (zoom, CameraControl/CameraInfo, pinch pattern)](https://developer.android.com/media/camera/camerax/configuration)
- [`CameraControl` API reference](https://developer.android.com/reference/androidx/camera/core/CameraControl)
- [`ZoomState` API reference](https://developer.android.com/reference/androidx/camera/core/ZoomState)
- [CameraX video capture architecture](https://developer.android.com/media/camera/camerax/video-capture)
- Internal: [`PRD-mission-control.md`](PRD-mission-control.md) · [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md) ·
  Lessons [002](lessons_learned/002-lifecycle-aware-flow-collection.md),
  [004](lessons_learned/004-viewmodel-no-context-parameters.md),
  [012](lessons_learned/012-camera-bound-screen-single-call-site.md),
  [013](lessons_learned/013-media-start-failure-return-and-narrow-catch.md),
  [014](lessons_learned/014-state-router-when-exhaustive-no-else.md),
  [016](lessons_learned/016-compose-defer-high-frequency-state-reads.md),
  [017](lessons_learned/017-androidtest-no-mockk-and-sweep-meaningful-mock-returns.md),
  [022](lessons_learned/022-release-camera-when-preview-leaves-composition.md),
  [023](lessons_learned/023-media-pipeline-stages-must-count-output-samples.md),
  [024](lessons_learned/024-fgs-type-constant-api-gating.md)
