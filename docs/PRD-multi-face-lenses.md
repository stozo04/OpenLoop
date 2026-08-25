# PRD — Multi-Face Lenses (the same lens on two faces)

**Status:** Approved — owner signed off 2026-08-25 (D1–D7 as proposed); implemented on `feature/multi-face-lenses`
**Owner:** Steven Gates
**Date:** 2026-08-25
**Proposed branch:** `feature/multi-face-lenses`
**Parent PRD:** [`PRD-camera-lenses.md`](PRD-camera-lenses.md) — this PRD changes one decision in it (§5.1 "single-face") and nothing else.

---

## 1. Problem statement

A lens only ever lands on **one** person. Two people in a selfie — a parent and a toddler, two
friends — get one broccoli and one bare face, and the bare face is whoever the tracker decided was
less prominent. That is the opposite of how a lens is used with a kid: the whole point is that
*both* of you are broccoli.

The single-face behavior is a deliberate, documented choice, not an accident:

- `PRD-camera-lenses.md` §5.1 — "single-face (… the most prominent face only — correct for
  selfies)". Correct for *solo* selfies; wrong for the two-person case that lenses invite.
- `FaceTracker.pickTrackedFace` locks onto one ML Kit tracking id and publishes **one**
  `FaceSnapshot?`; the lock exists so a bystander cannot steal the lens.
- `LensSurfaceProcessor` holds exactly one `face`, and its animation state — the wobble springs,
  the eased mouth openness, the previous-frame face the spring drive is measured from — is
  **per-processor**, i.e. it describes *the* subject. Two faces cannot share it: one spring driven
  by two heads would swing on the average of their motion, and one eased mouth would open both
  characters' mouths whenever either person talked.

So this is not "raise a limit". The tracker's lock, the renderer's draw loop, and the physics state
all have "one subject" baked in, and each needs the same change: keyed by face, not by processor.

**Explicitly out of the problem:** the photo-booth strip, imported clips, and the editor. Lenses are
burned into the recording at capture time by the one `CameraEffect` (Lesson 031); nothing downstream
knows a face exists, so nothing downstream changes.

---

## 2. Decisions (owner, 2026-08-25)

| #   | Decision                       | Choice                                                                                                                                                                       | Why                                                                                                                                                                                                                                                            |
| --- | ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D1  | Face cap                       | **Two** faces (`MAX_TRACKED_FACES = 2`), a single constant.                                                                                                                  | The stated need. A third face is a group photo, not a selfie; per-face cost in the detector and two extra draw passes per face per output are cheap at 2 and unbounded at "all". Raising it later is a one-line change *because* every piece is keyed by face. |
| D2  | Which two, when there are more | **Locked slots + largest fills the gap.** A face already tracked keeps its slot for as long as ML Kit keeps its tracking id; a free slot goes to the largest untracked face. | Keeps the existing "the lens does not hop between people" guarantee. Re-ranking every frame by size would flicker the lens between two similar-sized faces.                                                                                                    |
| D3  | Drop-out hold                  | **Per face**, same `HOLD_MS = 350`. Each tracked face rides out its own blinks and blurred frames independently.                                                             | One person turning away must not blink the other person's lens off.                                                                                                                                                                                            |
| D4  | Same lens for everyone         | **Yes** — the active lens draws on every tracked face, stickers and character features alike. No per-face lens selection.                                                    | The request. Per-face lenses would need a picker that knows which face is which; out of scope.                                                                                                                                                                 |
| D5  | Physics and mouth easing       | **Per face, keyed by tracking id.** A face that leaves takes its springs with it; a new face starts at rest.                                                                 | Otherwise one spring is driven by two heads (§1). Stays dimensionless and once-per-frame exactly as today (`stepWobbles` doc, points 1–3).                                                                                                                     |
| D6  | Draw order                     | Tracked faces draw in slot order (slot 0 first).                                                                                                                             | Two faces' art rarely overlaps; when it does, the older lock paints underneath. Deterministic and identical on preview and recording — good enough; not worth a z-sort on face size.                                                                           |
| D7  | `MIN_FACE_SIZE`                | **Unchanged at 0.15.**                                                                                                                                                       | It gates *bystanders*; a second person leaning into a selfie is well above 15 % of the frame width. Lowering it is a separate tuning question (§7 Q2).                                                                                                         |

---

## 3. Success criteria

1. Two people in frame with a lens selected both get the lens — stickers (shades, broccoli) *and*
   character features (each person's own eyes and mouth on their own vegetable).
2. The recording matches the preview: both faces lensed in the saved clip, not preview-only.
3. One person leaving, turning to profile, or blinking does not disturb the other person's lens
   (no flicker, no jump, no swap).
4. A third person entering does not steal a slot from either tracked face.
5. Solo selfies behave **exactly** as before — same lock, same hold, same physics. This is a
   superset, not a rewrite.
6. Wobble springs and mouth-driven layers (Twisted Tongue) animate independently per face.
7. No new mid-recording rebind (Lesson 031 detection checklist stays clean).
8. `LensAnchorTest`, `LensPhysicsTest` and the new roster/motion tests pass on the JVM; the
   instrumented `FaceTrackerNormalizationTest` still passes.

---

## 4. Design

### 4.1 `FaceSnapshot` gains an identity

```kotlin
data class FaceSnapshot(
    // …existing landmarks, sourceAspect, mouthOpenness…
    /** ML Kit tracking id — the key every per-face state hangs off. */
    val trackingId: Int = NO_TRACKING_ID,
)
```

`uprightToBuffer` / `mapPoints` and `reframe` carry it through untouched, like `mouthOpenness`
(it is an identity, not a coordinate — Lesson 032's corollary in reverse). Default keeps the
existing test constructors compiling.

### 4.2 `FaceTracker` publishes a roster, not a face

```kotlin
class FaceTracker(private val onFaces: (List<FaceSnapshot>) -> Unit) : ImageAnalysis.Analyzer
```

The slot logic moves out of the analyzer into a **pure, JVM-testable** `FaceRoster`
(`camera/lens/FaceRoster.kt`), in the same spirit as `ZoomUi` / `TrimHandleMath`:

- input: this frame's detections as `(trackingId, area)`, the current slot ids, `maxFaces`;
- output: the ordered slot ids for this frame — kept ids stay in place, gaps filled by largest
  area, never more than `maxFaces`.

`FaceRoster` also owns the per-face hold: a map `trackingId → (lastSnapshot, lastSeenMs)`; a
face missing this frame but seen within `HOLD_MS` is republished from the map; older than that it
is dropped and its slot freed. A held face without a slot cannot take one. `FaceTracker` becomes
the ML Kit glue in front of it. ML Kit does the detection of several faces already —
`enableTracking()` and `LANDMARK_MODE_ALL` are per face, so the only detector change is that we
stop throwing the others away. Faces whose landmarks are missing (profile views) are skipped
exactly as today, per face.

**ID churn (found in review).** When ML Kit loses a face for a frame or two it often re-detects it
under a *new* tracking id. The old id is still held, so naively the same person would wear two
lenses for up to `HOLD_MS` — and with both slots full the new id would be locked out until the
hold expired. The single-face tracker never had this problem because a fresh detection always won
outright. So: the **nearest** fresh face with no slot within one face-unit (eye-to-mouth distance,
square space) of a held-but-unseen slot holder is the same person. A third person entering
anywhere else during someone's blink still cannot take a slot.

**And it is published under the original id (second review finding, PR #145).** The first fix
handed the slot to the new id, which put one lens on the face but reset that person's `LensMotion`
state — springs and eased mouth are keyed by `trackingId` — on every relabel. So `FaceRoster`
re-keys the adopted snapshot to the holder's id and keeps an alias (new → original) that folds
every later sighting of the new id onto the same entry, until the holder expires. Nothing
downstream ever sees the label change. Lesson 037 patterns 2–3 and 5.

### 4.3 `LensSurfaceProcessor` draws per face

- `setFace(FaceSnapshot?)` → `setFaces(List<FaceSnapshot>)`. Still one `@Volatile` write of an
  immutable list; the GL thread reads it once per frame. `CameraManager`'s `setFace(null)` fallback
  becomes `setFaces(emptyList())`.
- The per-processor animation state (`wobbleStates`, `wobbleAngles`, `previousFace`,
  `easedOpenness`) becomes a per-face `FaceMotion` record held in a
  `HashMap<Int /*trackingId*/, FaceMotion>`, stepped **once per frame before the output loop**
  (unchanged rule). Ids not in this frame's list are evicted so a departed face's spring cannot be
  inherited by a newcomer that happens to reuse the id. A lens change resets every face's springs
  and keeps its eased mouth (the mouth describes the subject, not the lens), as today.
- Stepping moves into a pure `LensMotion` class (`camera/lens/LensMotion.kt`) so the per-face
  bookkeeping is JVM-tested; `LensSurfaceProcessor` keeps only GL. `dtSeconds` is computed once per
  frame and shared by every face.
- Draw loop, per output: camera pass once, then for each face in roster order → reframe → face
  frame → stickers (with that face's wobble angles and eased openness) → features. Sticker textures
  are already keyed by drawable, so two broccoli share one upload.

### 4.4 What deliberately does NOT change

- ML Kit options (`FAST`, landmarks not contours, `MIN_FACE_SIZE`), the analysis aspect strategy,
  the one-effect-per-bind rule, the geometry logs.
- `LensAnchor` math — it is already per-face; it just gets called twice.
- `Lens.kt` — no lens declares anything about face count.
- The lens carousel / UI — nothing to pick.

---

## 5. Cost

Detector: ML Kit already detects every face in the frame; landmarks are computed per face, so the
second face roughly doubles landmark work only (detection is shared). At `PERFORMANCE_MODE_FAST`
this is well inside a preview frame on the S23/A55 class. Renderer: two extra sticker draws and up
to three extra feature draws per output per frame — trivially GPU-bound quads. Per frame the
tracker allocates a snapshot and a sighting per face plus the published roster list — the same
order as the one snapshot per frame it always allocated.

---

## 6. Test plan (per `docs/TEST_COVERAGE.md`)

### 6.1 JVM unit tests

- `FaceRosterTest` — locked ids keep their slot; a gap is filled by the largest untracked face;
  a larger newcomer does not evict a locked face; cap respected; empty input frees every slot;
  ordering is stable frame to frame. Stateful: a blink is held per face; a held face without a
  slot cannot take one; a third person cannot take a slot during someone's blink; an id-churned
  face keeps its slot **and its original id** (solo and two-face), the new id stays folded onto it
  on later frames, the alias dies with the face, adoption takes the nearest candidate; the hold
  expires.
- `LensMotionTest` — two faces step independent springs (moving face A never swings B); a face
  that leaves is evicted and a returning id starts from rest; mouth easing per face; lens change
  resets springs and keeps the mouth; a timestamp gap is clamped so no spring can blow up; **and
  one test through the real `FaceRoster` → `LensMotion` boundary**: a detector relabel keeps the
  person's springs and mouth continuous.
- `FaceSnapshotTest` — `uprightToBuffer` and `reframe` carry `trackingId` through, the way
  `LensAnchorTest` already guards `mouthOpenness`.

### 6.2 Instrumented

- `FaceTrackerNormalizationTest` unchanged in intent; updated to the roster API if it touches it.

### 6.3 Manual QA (attached to the PR)

Two people, front camera, each lens in the catalogue: both lensed in preview and in the saved
clip; one person leaves → other unaffected; third person enters → ignored; solo selfie identical to
`main`.

---

## 7. Open questions — resolved at sign-off (2026-08-25)

1. **Cap of two** (D1) — confirmed. `MAX_TRACKED_FACES` is the one constant to change.
2. **`MIN_FACE_SIZE`** (D7) — stays at 0.15 for this PR; tune on device if a toddler at arm's
   length drops out.
3. **Slot behavior** (D2) — "locked slots + largest fills the gap" confirmed over "always the
   two largest".

---

## 8. Implementation plan (single PR)

1. `FaceSnapshot.trackingId` + carry-through in `LensAnchor` + tests.
2. `FaceRoster` (pure) + `FaceRosterTest`.
3. `FaceTracker` → roster + per-face hold.
4. `LensMotion` (pure) + `LensMotionTest`; `LensSurfaceProcessor` per-face draw; `CameraManager`
   call-site.
5. Review pass (adversarial): id-churn ghost lens found and fixed; hold logic moved into
   `FaceRoster` so it is JVM-tested.
6. Docs: this PRD, `docs/README.md` PRD table, `CLAUDE.md` source-layout map (two new
   load-bearing files), lesson if the review surfaces one. (`PRD-camera-lenses.md` §5.1 still
   reads "single-face"; a superseded-by note pointing here is a follow-up — see the PR.)
7. Definition of Done gate.

## 9. References

- [ML Kit face detection — Android](https://developers.google.com/ml-kit/vision/face-detection/android) (tracking ids, multiple faces, `setMinFaceSize`)
- Lessons [031](lessons_learned/031-camera-effect-attach-once-switch-by-uniform.md), [032](lessons_learned/032-normalized-overlay-math-needs-square-space.md)
