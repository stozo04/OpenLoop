# GOAL — Four AR Lenses for OpenLoop

**Judge:** Steven Gates — **unavailable during this run.**
**Acting judge / standing authority:** Kayley (**Grok**) — her own session, reads the bus directly.
Authority over ties, kills, and locking the shortlist. **Not** over git. See `KAYLEY-PROMPT.md`.
**Builders:** Claude Code + Codex — peers, neither in charge.

This is the brief. It is the authority. `SWARM-PROMPT.md` tells you *how* to work together; this file
tells you *what* to deliver and what "done" means.

---

## Mission

Ship **four new AR lenses** in OpenLoop's live camera, each usable for **both photo and video**
capture, at a quality bar Steven would accept from Snapchat.

- **Lens 1 is decided:** Football. Not up for debate. See §1.
- **Lenses 2, 3, 4:** you two research trending lenses independently, pitch, argue with evidence,
  and converge on three. Agreement is the trigger to build (Steven, 2026-08-15: *"Once yall agree to
  the top 3 start building"*).

> **Scope — confirmed by Steven, 2026-08-15.** **Four new** lenses ship in this feature: the football
> plus the three you agree on. They are *added* to the existing catalogue (`Broccoli`, `Shades`,
> `Big Mouth`), giving **seven** in the carousel. Nothing gets removed.

## 0. Broccoli is the reference implementation

Steven, 2026-08-15: *"follow however Broccoli lens worked as this is a perfect working copy."*

`Lens.Broccoli` is the golden template. When you are unsure how a character lens should be shaped,
sized, encoded, anchored, or tested — **read what Broccoli does and copy it**, including:

- the enum entry's shape (`art` + `features`, `warp = null`) and its KDoc explaining *why* each
  number is what it is
- the asset pipeline: opaque cut-out, background keyed to alpha, autocropped, WebP q90 in
  `drawable-nodpi/` (lossy body, lossless alpha)
- the face-unit geometry, measured against the table in §4.1
- the tests it satisfies — all catalogue-driven, so they cover a new lens automatically (§4.3)

A new lens that diverges from this template needs a stated reason. "I did it differently" is not one.

---

## 1. Lens 1 — Football (pre-decided)

Source art: `football.jpg` at the repo root.

It must be a **character** lens — the exact pattern `Lens.Broccoli` uses, not a prop:

- `art` is **opaque** and swallows the whole head.
- `features: FeatureLayout` is **non-null**, so the subject's own eyes and mouth are lifted off their
  real landmarks and composited onto the ball. Expression comes from the human; geometry from the
  character.
- The ball tracks position, scale, and head roll like every other lens.

**Acceptance test (PRD `docs/PRD-camera-lenses.md` §4b), and it is blunt:** *can you see a human
nose, cheek, forehead or jaw?* If yes, it is a prop pretending to be a character — rejected. The
broccoli lens failed this on its first build and had to be redone; do not repeat it.

**Branding — owner decision, 2026-08-15:** the Wilson script and NFL shield **stay**. Steven's call:
the eyes-and-mouth composite covers the center of the ball where the marks sit. Do not spend a round
re-litigating it. Do the background key + autocrop + WebP q90 encode exactly as
`lens_broccoli_art.webp` was done (PRD §11.2 "Encoding") — lossy body, lossless alpha, so the cut-out
edge that makes the character read stays bit-exact.

---

## 2. Lenses 2–4 — jointly chosen

### 2.1 Read this before you research

The renderer supports exactly **three shapes** today, and adding a lens is designed to be *only* one
`Lens.kt` enum entry plus its art. Every pitch **must name its tier**:

| Tier          | What it is                                                 | Shape in `Lens.kt`           | Cost                                          |
| ------------- | ---------------------------------------------------------- | ---------------------------- | --------------------------------------------- |
| **Prop**      | Art drawn over a still-visible face                        | `art` set, `features = null` | Trivial — one entry + a drawable (`Shades`)   |
| **Character** | Opaque art over the whole head, eyes + mouth composited on | `art` set, `features` set    | Trivial — one entry + a drawable (`Broccoli`) |
| **Warp**      | Radial UV displacement of the camera pixels                | `warp` set, `art = null`     | Trivial — one entry, no art (`Big Mouth`)     |

A pitch that needs a capability the shader does not have (a second sticker, animation, a
non-radial warp, color grading, particles) is a **scope increase**: name the shader change, cost it,
and get **both agents' explicit ACK** before it enters the shortlist. Un-costed scope increases are
killed on sight.

**Auto-killed, no debate** — these fail the project's hard constraints (PRD §2, C1–C3):

- Neural / generative restyling ("3D Cartoon", anime filter, AI face swap) — no free on-device model,
  already dropped once by owner decision.
- Anything needing a **new dependency**: body/hand/pose tracking, selfie segmentation, background
  replacement, 3D mesh renderers (Filament/SceneView), MediaPipe.
- Anything needing a **paid or proprietary SDK** (Snap Camera Kit, DeepAR, Banuba) — Apache 2.0,
  free, 100 % on-device, no per-MAU billing.
- Multi-face lenses — the tracker is single-face by design (`CONTOUR_MODE` computes the most
  prominent face only).
- Anything requiring a licensed character, logo, or celebrity likeness.

### 2.2 Evidence bar

Assume your training data is stale. **Web-search.** Each pitched lens carries **≥3 concrete signals,
each with a URL and a date**, from at least two of:

- Snapchat / TikTok / Instagram effect pages showing use counts or rankings
- Dated trend coverage, creator posts, or platform "top effects" lists
- Repeated demand in reviews/forums ("I wish there was an X lens")
- A named closest alternative plus what people complain about in it

No signal → the idea dies. Weak signal → demoted, not argued into the list. Reject AI-SEO listicle
sludge; go to the primary page.

### 2.3 Selection rules for the final three

**The bar, in Steven's and Kayley's words (2026-08-15): catchy, funny, shareable. *Pretty is not the
job.*** Beauty lenses, glitter, and flower-crown style effects are **off the ballot** — they are not
what this app is for. If a candidate's pitch is "it looks nice", it is already dead. The question is
whether someone sends the clip to a friend.

1. **Tier diversity.** The final four must not all be the same tier. At least one warp and at least
   one character among the new entries, unless both agents ACK a written reason not to.
2. **No near-duplicate of a shipped lens.** Another face-covering vegetable, another mouth bulge, or
   another eyewear prop is a "no" — the catalogue should read as four distinct ideas.
3. **Readable at a glance.** A lens that only works if the viewer already knows the joke is weak.
4. **Face-unit sizable.** You must be able to state its geometry in face units before you build it
   (§4). If you cannot describe where it sits, you do not understand it yet.

### 2.4 Each shortlist entry must include

- Name + one-sentence description of what the viewer sees
- **Tier** (prop / character / warp) and why that tier is sufficient
- Art source and license status (in-repo vector, public domain, or owner-supplied)
- Proposed geometry in **face units** — `widthInUnits`, `artAspect`, `upInUnits`, and for a
  character the full `FeatureLayout`; for a warp, `radiusInUnits` + `strength`
- Evidence pack (≥3 dated sources)
- Biggest risk and its kill criterion ("we kill this if …")

---

## 3. Process

1. **Independent research.** Neither agent reads the other's shortlist first. Post findings to the
   bus as you go; write the trail to `swarm/collab/research-<you>.md`.
2. **Pitch 4–6 candidates each** on the bus, in the §2.4 shape.
3. **Cross-argue with evidence.** Kill clones, un-tiered fantasies, and anything failing §2.1. Steal
   the better half of the other agent's idea rather than defending yours.
4. **Converge** on exactly three in `swarm/collab/decisions.md`. **Both ACK explicitly.**
5. **Deadlocked?** Bounded: if two full rounds pass with evidence posted on both sides and no ACK,
   post a `question` addressed to `kayley` on the bus. **Kayley's answer is binding** and goes
   straight into `decisions.md`. She reads the bus herself — posting the question is all it takes.
6. **Kayley silent? You still do not stall.** Steven is away and there is no other escalation hatch.
   Apply this ladder, take the first rung that resolves it, and record which rung you used in
   `decisions.md`:
   1. **The pre-seeded ballot.** Steven and Kayley already left one in `decisions.md` for exactly
      this: **Pizza Face** (character), **Bug Eyes** (warp), **Cat Ears** (prop), backup **Pink
      Donut**. On a lens-choice deadlock, that ballot is the answer.
   2. **Smaller scope wins** — the option needing no new shader capability, no new dependency, no
      new license question.
   3. **Stronger evidence wins** — more dated primary sources.
   4. **Alphabetical.** Dumb on purpose. It ends the argument and nothing about it is arguable.

   A stalled swarm is worse than a decided one. Decide, record the rung, move.
7. **Then build.** No sign-off round needed — the agreement *is* the trigger. But post a `status`
   naming the three so Steven sees it in the viewer before the first file changes.

**Minimum two rounds.** A third only if it would actually change the list; do not perform rounds.

**PRD-first is honored by `decisions.md`.** The locked shortlist section is the PRD of record for
this work. At PR time, fold it into `docs/PRD-camera-lenses.md` as a new §13 — do not create a
second PRD.

---

## 4. Build rules

### 4.1 Geometry is measured, not guessed

Every size is in **face units**, where one unit = the subject's eye-to-mouth distance
(`LensAnchor.faceFrame`). The reference table lives in `Lens.kt`'s KDoc:

| Real measurement         | ≈ face units |
| ------------------------ | ------------ |
| head width, ear to ear   | 1.55         |
| eye line up to the crown | 1.25         |
| mouth width, at rest     | 0.8          |

That table exists because an earlier pass reasoned the numbers from published head statistics and
**every lens came out ~20 % oversized**. Re-measure against this table. Do not nudge a lens in
isolation.

### 4.2 Rules that cost real bugs

- **Lesson 031** — the `CameraEffect` is attached once per bind and switched by field write. Never
  re-attach; a rebind kills an in-flight recording (`ERROR_SOURCE_INACTIVE`).
- **Lesson 032** — the tracker's frame is not the renderer's frame: orientation, field of view,
  mirroring, and square-space are four independent traps. Derive, never assume.
- **Lesson 012 / 022** — one camera call site; release when the `PreviewView` leaves composition.
- **Lesson 014** — no new `OpenLoopUiState` entries. Lens state is sibling flows in the ViewModel.
- **Lesson 017** — no `mockk` in `androidTest`.
- **Lesson 008** — real temp dirs for `File`, one shared `TestDispatcher`.

Read the full core tier (008, 011–032) before touching code — `CLAUDE.md` requires it, and the
lens-specific ones above are not optional.

### 4.3 Tests you inherit for free

The suite is **catalogue-driven** — a new enum entry is covered the moment it exists:

- `LensCarouselTest` renders `Lens.entries` and asserts a thumbnail, a 48.dp touch target, and a
  name for every one.
- `LensAnchorTest` iterates `Lens.entries` (≈ lines 503, 524).
- `FaceTrackerNormalizationTest` asserts over `Lens.entries.mapNotNull { it.art }`.

So a badly-shaped new lens **fails existing tests**. Add new cases only where a lens introduces math
that does not already exist. Do not write a parallel per-lens suite.

---

## 5. Photo capture — verify, do not assume

Steven's requirement is *"take a picture or video"* with every lens.

`CameraScreen.kt:398` captures a photo via `viewModel.capturePhoto(previewView.bitmap)` — a snapshot
of the **preview** stream. The effect targets `PREVIEW or VIDEO_CAPTURE` (`CameraManager.kt:447`), so
the lens *should* already be baked into that bitmap for free.

**"Should" is not "does."** Capture one lensed photo and one lensed video on a device, open both, and
report what you actually saw. If the photo path drops the lens, that is a real work item in this
scope — not a footnote.

---

## 6. Definition of Done

**The repo's gate, not a lighter one:** `docs/DEFINITION_OF_DONE.md`.

| Gate          | Command                                    | Bar                                       |
| ------------- | ------------------------------------------ | ----------------------------------------- |
| Debug build   | `./gradlew :app:assembleDebug`             | `BUILD SUCCESSFUL`, **exit 0**, zero `e:` |
| Release build | `./gradlew :app:assembleRelease`           | same                                      |
| Unit tests    | `./gradlew :app:testDebugUnitTest`         | **0 failures**                            |
| Instrumented  | `./gradlew :app:connectedDebugAndroidTest` | 0 failures — on the **Pixel_8 AVD**       |
| Lint          | `./gradlew :app:lintDebug`                 | zero *new* errors                         |
| Run it        | launch on the emulator                     | screenshot captured                       |

Two things that bite here specifically:

- **Production zero-error rule.** Any failure you encounter — including one you did not cause — is
  fixed. A red baseline is never an excuse. **There is no escalation hatch this run** — Steven is
  AFK, so "escalate and stop" is not a move. Fix it, or record precisely why it cannot be fixed and
  keep every other part green. Decisions route to Kayley; broken builds do not.
- **Do not trust a `| tail`-masked exit code.** "Genuinely green" means `BUILD SUCCESSFUL` *and* exit
  0 *and* zero `e:` lines.
- **The physical Fold is usually locked** and fails Compose tests environmentally. Use the Pixel_8
  AVD.

---

## 7. What "Snapchat quality" means, concretely

Steven's bar, made testable:

1. **Character test** — no human nose, cheek, forehead, or jaw visible on a character lens.
2. **Sized right** — geometry derived from §4.1, not eyeballed.
3. **Steady** — it sits still on a still face and does not flicker off when the detector blips. There
   is deliberately **no smoothing or hold-last-face** in the code today, because tuning it blind
   risks making it worse. If it jitters, **say so** — an exponential filter on `FaceSnapshot` plus a
   short hold is a small, well-understood change once there is something to tune against.
4. **Tracks** — roll and yaw, front *and* back camera, portrait *and* landscape, no 90° offset, no
   mirrored-to-the-wrong-side.
5. **Clean exit** — walk out of frame and the preview is a clean pass-through with no ghost art.
6. **Both media** — the lens is in the saved `.mp4` *and* the saved photo, and a lensed clip still
   survives trim → speed → reverse → Looks → save.

---

## 8. Hardware QA is Steven's, and you must not fake it

**The emulator's virtual scene has no face.** Face lenses cannot be visually verified on an emulator.
You may verify the bind, the render path, the recording finalize, and every unit of math — you may
**not** claim you saw a lens land on a face.

Deliver a numbered manual-QA checklist for Steven mirroring `docs/PRD-camera-lenses.md` §11.1, and
state honestly which items you could not reach.

---

## 9. Deliverables

1. `swarm/collab/decisions.md` — the locked four, both ACKs, and every judge/tie-break ruling
2. `swarm/collab/research-claude.md` / `research-codex.md` — your evidence trails
3. `swarm/collab/LESSONS.md` — honest notes on what worked and what wasted time
4. Code: new `Lens.kt` entries + art (`drawable-nodpi/*.webp`) + carousel thumbnails
5. `docs/PRD-camera-lenses.md` §13 — the shortlist folded into the PRD of record
6. A manual-QA checklist for Steven, with the unverifiable items named

---

## 10. Non-goals

- No new dependencies, no new SDKs, no new architecture.
- No editing or removing a lens after capture — it is baked in (PRD §5.4), and that stands.
- No category tab row in the carousel (chrome around an empty room until there are far more lenses).
- No new `OpenLoopUiState` entries.
- No new branch. You are on `feature/AR-Lenses`.
- **No commits, no push, no PR** — Steven's standing ruling, 2026-08-15, recorded in `decisions.md`.
  Kayley's authority covers ties, kills, and locking the shortlist; it does **not** extend to git.
  Leave the work in the tree, green, and wait.
- **Hardware QA stays Steven's.** Nobody else can do it and nobody may claim it was done.
