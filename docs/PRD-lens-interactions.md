# PRD — Lens Interactions (flick a lens, it spins)

**Status:** Draft — awaiting owner sign-off
**Owner:** Steven Gates
**Date:** 2026-08-26
**Proposed branch:** `feature/lens-interactions`
**Parent PRDs:** [`PRD-camera-lenses.md`](PRD-camera-lenses.md), [`PRD-multi-face-lenses.md`](PRD-multi-face-lenses.md)
**Related lessons:** 025, 031, 032, 034, 035, 037

---

## 1. Problem statement

Lenses track the face and animate themselves (wobble springs, mouth-open reveal), but the user
cannot **touch** them. The owner's ask: with the Football lens on your face and the front camera up,
flick the ball with a finger and it **spins** — a harder flick spins it faster, and friction brings
it back to rest. The spin must appear in the live preview *and* be baked into the recording (and
into a photo-mode capture), exactly like every other lens behavior.

The owner's second ruling shapes the design more than the first: this is not a football feature.
If flicking works, more interactions follow — stretching a lens, throwing it and having it come
back. So v1 ships **one interaction (flick → spin) on top of a small interaction pipeline** whose
pieces are reusable: gesture capture → coordinate mapping → hit-test → per-face interaction state →
physics stepped once per frame. Each future interaction should be a new gesture verb plus a new
per-layer spec, not a new architecture.

## 2. Constraints (inherited, unchanged)

| #  | Constraint                                                            | Consequence                                                                                          |
| -- | --------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| C1 | One `CameraEffect`, attached every bind, never rebound (Lesson 031)   | The spin is renderer state read per frame — a field write away from the touch, never a rebind.       |
| C2 | The lens is baked into the recording (parent PRD §5.4)                | The spin is part of [`LensMotion`]'s per-frame step, so it lands in the `.mp4` by construction.      |
| C3 | Physics is pure and JVM-tested (`LensPhysics`, parent PRD §14.4)      | Spin math is pure Kotlin; the emulator proves bind/render/record, the JVM proves the physics.        |
| C4 | Nothing outside `Lens.kt` names an individual lens                    | Spin is a per-layer opt-in spec in the catalogue; the renderer, tracker and UI stay lens-agnostic.   |
| C5 | Multi-face: all per-face state keyed by canonical tracking id (D5/037) | A flick spins the face it landed on; the other face's lens is untouched.                             |
| C6 | Touch over the `PreviewView` goes through the parent intercept (025)  | The flick detector lives in [`PinchZoomLayout`]'s stream, not a Compose overlay or a touch listener. |

## 3. Design

```text
finger flick (view px, px/s)
  └─ PinchZoomLayout ── GestureDetector.onFling(e1, e2, vx, vy)   [single finger only; pinch untouched]
       └─ CameraScreen callback → CameraManager.flickLens(...)
            └─ LensTouchMath (pure): view point + velocity → effect-output normalized space
                 └─ LensSurfaceProcessor: @Volatile pending-flick handoff to the GL thread
                      └─ LensMotion.step (once per frame, before the output loop):
                           ├─ LensHitTest (pure): which face, which spin-capable layer?
                           ├─ LensPhysics.spinImpulse (pure): torque → angular velocity
                           └─ LensPhysics.spinStep (pure): friction decay, per frame
                                └─ LensAnchor.sticker(..., spinRadians): rotate the art
                                     └─ drawn into preview AND recording
```

### 3.1 Gesture capture — in `PinchZoomLayout`, nowhere else

Lesson 025 is blunt: gestures over the `PreviewView` that are wired through a Compose overlay or a
view touch listener fail silently on real hardware. `PinchZoomLayout` already owns the touch stream
(it is the touch target when no child consumes `ACTION_DOWN`), so it gains a `GestureDetector`
whose `onFling` fires the new callback with **the down event's position** (`e1` — Lesson 035: the
down position is the honest hit-test point, everything later is slop-shifted) and the fling
velocity vector.

Interplay, stated explicitly:

* **Pinch wins.** A second finger marks `pinchInStream`; a stream that ever became a pinch never
  reports a fling. The existing pinch-zoom path is untouched.
* **Taps stay taps.** `onFling` requires the platform's minimum fling velocity; a completed
  single-finger tap still routes through `performClick()` exactly as today
  (`ClickableViewAccessibility` contract unchanged).
* **Below-threshold drags do nothing.** No drag-to-rotate in v1 — flick is the whole verb.

### 3.2 Coordinate mapping — the R1 of this feature

The touch is in **view** pixels. The sticker quads live in **effect-output normalized** space
(the 4:3 buffer the processor draws into — measured `1280x960` on the Pixel 8 class). Between them
sit exactly three transforms, all applied by `PreviewView` when it displays the processed stream:
the buffer→display rotation, the `FILL_CENTER` scale-and-crop, and front-camera mirroring (which
Lesson 032 measured being applied by the view layer *after* the effect).

Google's documented pattern is a `Matrix` built from the crop rect and rotation degrees
([Transform output](https://developer.android.com/media/camera/camerax/transform-output)); the
CameraX view artifact also carries transform accessors on `PreviewView`. **Which source of truth
each factor comes from is an implementation-time verification item** (web-search the current API
docs again at build time, per `CLAUDE.md`), because this is precisely the class of question the
parent PRD's R1 warned about. What is *not* negotiable is the shape of the mitigation, which is the
house pattern that already killed R1 once:

1. **All mapping math in one pure file** — `camera/lens/LensTouchMath.kt`: inputs are view size,
   buffer size, rotation degrees, mirror flag, and the touch point/velocity; output is the point
   and velocity in output-normalized space. No Android types. JVM tests include the asymmetric
   assertions Lesson 032 demands (a round-trip test alone passes on an inverted pair).
2. **One log line per flick** — mapped point, nearest face origin, hit/miss. On the emulator's
   static poster face this turns the whole mapping into arithmetic: inject a swipe at the detected
   face's known screen position and read whether the mapped point lands on the face frame origin.
3. **Velocity maps through the same transform as position** (direction matters: a mirrored view
   must flip the spin direction too), then converts to **face units per second** by dividing by
   `FaceFrame.unit` — dimensionless, so the same flick reads the same at any distance, matching
   how every other drive signal in `LensPhysics` works.

### 3.3 Hit-test — on the GL thread, against the freshest quads

The flick crosses to the GL thread through a `@Volatile`/atomic pending-flick handoff (the same
shape as `setLens`/`setFaces`: a write from the UI thread, a read at the top of the next frame — a
flick landing one frame late is invisible, and a lock would stall the camera). At step time,
`LensHitTest` (pure) walks the roster in slot order and asks, for each face's spin-capable layers
**topmost first**: does the mapped point fall inside this layer's current `StickerQuad`
(point-in-rotated-quad, computed in square space per Lesson 032)?

* **Tolerance is in face units, sanity-checked in dp** (Lesson 035's arithmetic): the Football
  quad is 5.6 units wide — hundreds of dp on screen — so the quad itself is a generous target and
  needs no inflation. A future small layer (an eyeball) must re-run the dp check before trusting
  the raw quad as its target.
* **First hit wins.** One flick spins one layer on one face. A miss is silently dropped.
* The hit consumes the pending flick either way — flicks never queue up across frames.

### 3.4 Spin physics — `LensPhysics`, same contract as the spring

Two new pure functions, tested the way the wobble is (properties, not magic numbers):

```kotlin
/** How a layer spins when flicked. All values dimensionless / per-second, like WobbleSpec. */
data class SpinSpec(
    val gain: Float,                     // flick torque → angular velocity multiplier
    val frictionHalfLifeSeconds: Float,  // time for the spin to lose half its speed
    val maxAngularVelocity: Float,       // hard cap, rad/s — a detector glitch or absurd fling
)                                        // can never make the art unwatchable

/** A layer's live spin: accumulated angle and signed angular velocity. */
data class Spin(val angleRadians: Float, val velocity: Float)
```

* **Impulse** (`spinImpulse`): the flick applies torque as if the finger grabbed the art where it
  touched — `ω += gain * cross(r, v) / max(|r|, R_MIN)²`, with `r` the vector from the quad center
  to the touch point and `v` the flick velocity, both in face units (square space). Flick the top
  of the ball rightward and it spins clockwise; flick the bottom rightward and it spins the other
  way; flick harder and it spins faster. `R_MIN` (~0.3 units) keeps a dead-center flick from
  dividing by zero — it spins in the flick's cross direction at a sane rate. The result is clamped
  to `maxAngularVelocity`. A flick landing on an already-spinning layer **adds** its impulse, so
  repeated flicks pump it up (to the cap) or brake it.
* **Decay** (`spinStep`): exponential half-life on the velocity — the same frame-rate-independent
  form as `LensPhysics.ease`, with the same `MAX_STEP_SECONDS` clamp so a dropped frame cannot
  teleport the angle. The angle accumulates; below a small velocity floor the spin snaps to done
  (velocity zero) so the art comes to a true rest instead of creeping forever. The **angle eases
  back to zero after the spin dies** over a few hundred ms, so the lens returns to its tracked
  orientation rather than freezing at an arbitrary tilt.
* **State lives in `LensMotion.FaceMotion`**, keyed per layer per face, stepped once per frame
  before the output loop (the double-step rule in `LensMotion`'s header applies verbatim), evicted
  with the face, cleared on lens change. Recording and preview therefore see the identical spin —
  landing it in the saved video costs zero extra code.

### 3.5 Rendering — one added rotation

`LensAnchor.sticker` gains a `spinRadians` parameter that adds to the quad's final
`rotationRadians` — a rotation **about the art's own center** (unlike the wobble, which swings
about the anchor; a spinning ball twirls in place, a hanging tongue swings from its root — the two
compose). Zero is bit-identical to today, the same guarantee the wobble parameter made.

### 3.6 Catalogue — the Football opts in

```kotlin
// Lens.Football's art layer gains:
spin = SpinSpec(
    gain = …,                        // tuned so a comfortable flick ≈ 1–2 rev/s
    frictionHalfLifeSeconds = 0.6f,  // visibly slows within a second, settles in ~2–3
    maxAngularVelocity = 25f,        // ~4 rev/s — fast enough to be funny, slow enough to read
)
```

`LensPlacement` gains `spin: SpinSpec? = null` beside `wobble` and `mouthOpen` — the established
opt-in pattern. Every other lens is untouched; any lens can adopt spin later by catalogue edit
alone (C4 holds). The starting numbers above are arithmetic guesses; like the wobble's
(§14.5 of the parent PRD), the *feel* is owner-tuned on hardware while `LensPhysicsTest` pins the
safety properties so retuning cannot weaken them.

### 3.7 What deliberately does NOT change

* No rebind, no new effect, no new use case (C1). The flick path never touches capture state —
  flicking mid-recording is as safe as switching lenses mid-recording, and gets the same guard
  test.
* `FaceTracker`, `FaceRoster`, ML Kit options, the analysis aspect pin — untouched.
* The features (the subject's composited eyes and mouth on a character) **do not spin** — see D2.
* No persistence, no settings, no UI chrome. The interaction is discoverable by doing.

## 4. Decisions proposed (owner to confirm at sign-off)

| #  | Question                          | Proposal                                                                                                                                                                                                                                                                                          |
| -- | --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D1 | Spin axis (owner delegated)       | **In-plane** — the ball twirls flat like a fidget spinner, direction set by where and which way you flick (the torque model). A 3D spiral or end-over-end tumble needs texture animation or a mesh this renderer deliberately does not have (parent PRD §14.1's honest scope line). Recorded as-is. |
| D2 | Do character features spin too?   | **No.** Your real eyes and mouth stay put while the ball whirls behind them — that contrast *is* the joke, it keeps the subject recognizable mid-spin, and it falls out of the architecture for free (features are drawn from the face frame, not the art quad).                                    |
| D3 | Which lenses get spin in v1?      | **Football only.** One lens proves the pipeline; the catalogue pattern makes each further lens a one-line opt-in once the feel is tuned.                                                                                                                                                            |
| D4 | Flick during recording?           | **Allowed** — it is the headline use ("flick mid-loop, the boomerang plays the spin forever"). Guarded by the same never-touches-capture-state test as lens switching.                                                                                                                              |
| D5 | Drag-to-rotate / hold?            | **Not in v1.** Flick is the verb. A drag gesture is a natural later interaction (stretch), and lands in the same pipeline.                                                                                                                                                                          |
| D6 | Accessibility                     | The spin is decorative (no information or function depends on it), so no alternative input path is required; the tap/`performClick` contract and all existing targets are unchanged. Revisit if an interaction ever gates a *capability*.                                                           |

## 5. The framework contract (what "stretch" and "throw" reuse)

A future interaction adds, at most: **(a)** a gesture verb in `PinchZoomLayout` (drag, long-press),
**(b)** a spec on `LensPlacement`, **(c)** a state record + step in `LensMotion`/`LensPhysics`,
**(d)** a transform in `LensAnchor.sticker` (scale for stretch, translation for throw). The
capture pipeline (§3.1), the mapping (§3.2), the handoff and hit-test (§3.3), and the
once-per-frame/per-face/recordable guarantees are shared and already paid for. "Throw and come
back" is explicitly a later PRD — it needs a departure-and-return path model — but nothing in this
design blocks it, and the hit-test + impulse handoff are its first two pieces.

## 6. Success criteria

1. Front camera, Football on, flick the ball: it spins in the direction flicked; a harder flick
   spins faster; it slows and settles back to normal tracking within a few seconds.
2. The spin is in the **saved video** when flicked mid-recording, and in a photo-mode capture
   taken mid-spin. Preview and recording show the identical motion.
3. Flicking mid-recording never finalizes, corrupts, or rebinds (Lesson 031 checklist stays
   clean; the ViewModel guard test extends to the flick path).
4. Pinch-zoom, tap, shutter, flip, tray, and booth behave exactly as before — a pinch is never
   misread as a flick, a flick never zooms.
5. Two faces: a flick on face A's ball spins only face A's; face B is undisturbed (C5).
6. A flick that misses every spin-capable layer does nothing — no crash, no stray spin, no log
   spam beyond the one diagnostic line.
7. No-lens and non-spin-lens behavior is bit-identical to today (`spinRadians = 0` path).
8. Full `docs/DEFINITION_OF_DONE.md` gate, including the pre-PR sweep.

## 7. Test plan (per `docs/TEST_COVERAGE.md`)

* **JVM — `LensPhysicsTest` additions:** impulse direction follows the cross product (top-right
  flick vs bottom-right flick spin opposite ways); harder flick → faster spin; the cap holds under
  an absurd velocity; friction halves the speed in its half-life at any frame rate; a
  `MAX_STEP_SECONDS` gap cannot jump the angle; the settle eases the angle to exactly zero; a
  dead-center flick spins at a finite rate.
* **JVM — `LensTouchMathTest`:** view→output mapping per rotation quadrant and mirror state, with
  asymmetric expectations (Lesson 032 — a round trip proves nothing about direction); velocity
  maps through the same transform as position; the mapped velocity is dimensionless in face units.
* **JVM — `LensHitTestTest`:** point-in-rotated-quad in square space on a non-square frame;
  topmost layer wins; slot order picks the right face when quads overlap; a miss consumes the
  flick.
* **JVM — `LensMotionTest` additions:** spin is per face (flicking A never spins B); state evicts
  with the face and survives id churn through the roster (the Lesson 037 boundary test gains a
  spin assertion); lens change clears it.
* **JVM — ViewModel:** the flick path never touches capture state (sibling of the lens-switch
  guard).
* **Instrumented / emulator:** poster face + `adb`-injected swipe across the detected face — the
  spin is one of the few lens behaviors the static poster **can** exercise, unlike the wobble.
  Verify: spin visible in preview, present in a recorded clip's frames, absent after settle.
  Real-hardware fling delivery over the `PreviewView` must be spot-checked on the Fold
  (Lesson 025: the emulator's input path is not the OEM's).
* **Hardware QA (owner):** feel of gain/friction/cap; flick reliability at arm's length; pinch vs
  flick disambiguation with real fingers; recording a flick mid-boomerang and watching the loop.

## 8. Risks

| #  | Risk                                                                                             | Mitigation                                                                                                                                                            |
| -- | ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| R1 | Coordinate mapping is wrong on some rotation/mirror combination — flicks miss or spin backwards | The §3.2 triple: pure math + asymmetric JVM tests + per-flick geometry log; emulator poster turns verification into arithmetic before hardware ever sees it.           |
| R2 | Fling events fail to arrive over `PreviewView` on some OEM (the Lesson 025 class)               | Detector sits in `PinchZoomLayout`'s already-proven stream — the one wiring that survived the Fold. Hardware spot-check stays in the plan.                             |
| R3 | The flick fights the pinch or steals taps                                                        | Pinch interception runs first and marks the stream; fling threshold separates taps; explicit instrumented cases for both.                                              |
| R4 | Spin state + wobble + mouth-open compose into something ugly                                     | Football has no wobble and no mouth-open layer — v1 composes with nothing. The composition rule (spin about center, after wobble) is still defined and JVM-tested now. |
| R5 | The feel is wrong (too twitchy, too slow)                                                        | All three numbers live in one `SpinSpec` in `Lens.kt`; properties are pinned by test, feel is a one-line owner tune (the §14.5 pattern).                               |

## 9. Open questions

None blocking beyond the D1–D6 confirmations. One forward-looking note: if the owner wants spin on
a **multi-layer** lens later (e.g. Twisted Tongue's eyeballs), the hit-test tolerance re-check in
§3.3 becomes load-bearing — small quads need the Lesson 035 dp arithmetic before they are trusted
as touch targets.

## 10. References

* [Transform output — CameraX](https://developer.android.com/media/camera/camerax/transform-output) — the documented crop-rect + rotation `Matrix` pattern
* [ML Kit Analyzer — `COORDINATE_SYSTEM_VIEW_REFERENCED`](https://developer.android.com/media/camera/camerax/mlkitanalyzer) — CameraX's own view-referenced coordinate story
* [Detect common gestures — `GestureDetector.onFling`](https://developer.android.com/develop/ui/views/touch-and-input/gestures/detector)
* [Manage touch events in a ViewGroup](https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup) — the Lesson 025 foundation
* Lessons [025](lessons_learned/025-previewview-pinch-needs-parent-intercept.md) ·
  [031](lessons_learned/031-camera-effect-attach-once-switch-by-uniform.md) ·
  [032](lessons_learned/032-normalized-overlay-math-needs-square-space.md) ·
  [034](lessons_learned/034-pointerinput-key-freezes-its-lambda.md) ·
  [035](lessons_learned/035-drag-hit-test-belongs-on-the-down-position.md) ·
  [037](lessons_learned/037-per-identity-hold-must-survive-id-churn.md)
