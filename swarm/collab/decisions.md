# Decisions — AR Lens Swarm

The PRD of record for this work. Folded into `docs/PRD-camera-lenses.md` §13 at PR time.
Nothing here is final until **both agents have ACKed the same section**.

---

## Locked

### Lens 1 — Football (pre-decided by Steven, 2026-08-15)

Character lens, `Broccoli` pattern: opaque art over the whole head, `FeatureLayout` non-null so the
subject's eyes and mouth are composited onto the ball. Source: `football.jpg` at repo root.

**Wilson / NFL branding stays** — Steven, 2026-08-15: the eyes-and-mouth composite covers the centre
of the ball. Not up for re-litigation.

**Asset facts, measured 2026-08-15 (Claude).** `football.jpg` is a **lossy WebP** (RIFF/WEBP, chunk
fourcc `VP8 `, not `VP8X`), **1320×1320**, 233 KB, **with no alpha channel at all**. It is not a JPEG
despite the name. Because there is no alpha, the key-to-alpha step is mandatory rather than optional,
and the output must be re-encoded as lossy **`VP8X`** (lossy body, lossless alpha). Kayley's
no-baked-shadow ruling applies to the soft gray puddle at bottom-right.

| Field | Value |
|---|---|
| Tier | Character |
| `widthInUnits` | **4.7** |
| `artAspect` | **0.571** — measured off the encoded 1024×585 asset per A2, not estimated |
| `upInUnits` | **0.10** |
| `FeatureLayout` | `eyeSpacing 0.58 · eyeUp 0.45 · eyeWidth 0.80 · mouthUp −0.45 · mouthWidth 1.30` |
| Coverage | +1.40 above the eye line / −1.20 below, and checked **at the sides**: at the ear (±0.775) the ellipse narrows to ±1.23 and still clears crown and chin |
| Shipped art | `lens_football_art.webp` 1024×585 (187 KB) · thumb `lens_football.webp` 256×146 |
| Owner | **Claude** |
| ACK claude | ✅ |
| ACK codex | ✅ |

### Scope

Four **new** lenses added to the existing catalogue (`Broccoli`, `Shades`, `Big Mouth`) → seven
total. Nothing removed. **Locked by Kayley 2026-08-15 while Steven AFK.**

---

## Shortlist — lenses 2, 3, 4 — **LOCKED 2026-08-15**

Locked by Kayley (see *Kayley lock* below) and ACKed by both agents.

Convergence path: both agents researched independently and posted candidates
(`claude-1786829654458`, `codex-1786829091998`), cross-argued with evidence over two rounds, and
converged. **No deadlock occurred; the §3-step-6 ladder and the pre-seeded ballot were not used.**
Codex moved off Cat Ears and Claude moved off Emoji Head and Tiny Head — each on the other's
evidence, not on a tie-break.

### Lens 2 — Bug Eyes

- **What the viewer sees:** the subject's eyes balloon to cartoon size while the rest of the face
  stays normal; blinks and eye-rolls magnify with them, which is what makes it play in a loop.
- **Tier:** warp. No art, no second texture — a radial UV displacement in the existing camera pass.
- **Why the tier suffices, and the scope increase it needs:** the warp centre is currently
  **hardcoded to the mouth midpoint** (`LensAnchor.warp`; `WarpSpec` carries only `radiusInUnits` +
  `strength`). Bug Eyes needs it on the eyes — a GOAL §2.1 scope increase, costed and ACKed below.
- **Art source + licence:** no render art. Carousel icon is an **original in-repo vector**, Apache 2.0.
- **Geometry (face units):** **two circles centred on the real `FaceSnapshot.leftEye` / `rightEye`
  landmarks**, `radiusInUnits = 0.36`, `strength = 0.75`.
  - *Why landmarks, not an offset:* Claude first proposed circles at a fixed ±0.40 units from the
    centre line. Codex countered with the real eye landmarks, which already exist on `FaceSnapshot`.
    **Claude withdrew the offset version.** The ±0.40 was derived from the interpupillary ratio —
    precisely the reasoning the `Lens.kt` KDoc records as having made every lens ~20 % oversized.
    Landmarks need no calibration, no mirror flag and no `offsetUp` field, and they track yaw free.
- **Evidence:**
  - [Bug Eyes Lens *by Snapchat*](https://www.snapchat.com/lens/d98fbc027cf9438a8ca820c08308380a) — first-party, accessed 2026-08-15, tagged `#funny #bigeyes #bugeyes #trending`.
  - Spotlight clip on the exact lens, posted **2026-02-16**: **17K views, 1.7K likes, 334 comments**.
  - [Snapchat — Popular Lens](https://www.snapchat.com/topic/popular-lens), accessed 2026-08-15 — carries a 2025 creator recap titled *"Bug Eyes Reigns Supreme"*, naming it that creator's most-used lens.
  - [r/youseeingthisshit — "Them eyes"](https://www.reddit.com/r/youseeingthisshit/comments/1dotmk9/them_eyes/), **2024-06-26**, **70K+ votes** — the exaggerated-eye gag is instantly legible without explanation.
  - Same-family first-party lenses: [Fun Eyes](https://www.snapchat.com/lens/1099f5248f1a4a039c1fcebde8743742) · [funny eyes](https://www.snapchat.com/lens/e56bd80322a147ee9b94f11090c4cddc). Nine independent creator clones catalogued in `research-claude.md` §2 C1.
- **Kill criterion:** if two per-eye circles cannot read as two enlarged eyes without distorting the
  nose bridge, the lens dies — we do **not** rescue it with a third capability.
- **Owner:** **Codex** · **ACK claude ✅ / ACK codex ✅**

### Lens 3 — Dog

- **What the viewer sees:** floppy ears over the head and a black snout on the nose, with the
  subject's own face fully visible between them.
- **Tier:** prop. `art` set, `features = null`, `warp = null` — the shipped `Shades` mechanism.
- **Why the tier suffices:** **zero scope increase, zero new code.** One enum entry, one drawable.
- **Deliberate reduction, on the record:** the canonical Snapchat dog lens has an **animated tongue
  that drops when the mouth opens**. That is a second sticker + mouth-open detection + animation
  state — a real scope increase, **not in scope**. We ship the static ears-and-snout dog.
- **Art source + licence:** **original in-repo vector**, authored here, Apache 2.0 — no attribution,
  no redistribution question, no scraped Snap asset.
- **Geometry (face units) — AS SHIPPED:** `widthInUnits = 2.90`, `artAspect = 0.7345`,
  `upInUnits = 0.385`. Viewport `290×213`, which is exactly 100 viewport units per face unit at this
  width, so every coordinate in the vector reads as anatomy. **Keep the viewport and `artAspect` in
  sync if either changes.**
  - *An earlier draft in this document said 2.40 / 0.96 / 0.70 and was superseded during the build.*
    That first draft put the ear inner edges at **0.34 units** — and an eye sits at about **0.40**,
    so it sat both ears squarely on top of both eyes. The schematic previewer
    (`swarm/tools/preview_lens.py`) caught it before any device run; the ears moved out to **0.59**,
    clearing each eye by 0.19 units while still overlapping the head edge at 0.775, and the whole art
    was re-derived around that constraint. **The live render later confirmed the clearance.**
  - Ear tips reach 1.45 above the eye line (clearing the 1.25 crown); the snout lands on the
    subject's own nose and stops clear of the mouth at −1.0.
- **Evidence:**
  - **This is the trending prop, not merely the classic one.** [Snap Newsroom — *2016 Energy, 2026 Mindset*](https://newsroom.snap.com/2026-is-the-new-2016-snapchat), **published 2026-01-16, first-party**: **"2016" Lens searches up 613 %** year-to-date and **Dog Lens searches up 352 %**. Fetched and verified by Claude 2026-08-15. **Only one animal lens is named in the entire article — the Dog Lens. No cat lens is mentioned.**
  - [Wikipedia — *"2026 is the new 2016"*](https://en.wikipedia.org/wiki/2026_is_the_new_2016), fetched 2026-08-15 — independent confirmation: the trend emerged **late Dec 2025**, took off at the **start of 2026**, and names **exactly two** Snapchat lenses as driving it: **the puppy-dog filter and the flower-crown filter**. Cites the BBC on "2016" TikTok searches rising sharply in early 2026. (Flower crown is on Kayley's kill list, leaving Dog as the only lens in the trend that clears the bar.)
  - [Know Your Meme — Dog Filter](https://knowyourmeme.com/memes/dog-filter), fetched 2026-08-15: launched **February 2016**, **184,569 confirmations**. Dated coverage from that entry — Vice **2016-04-22**; Kim Kardashian requesting a Dalmatian variant **2016-05-10**; an Ariana Grande / Jimmy Kimmel parody **2016-05-20** at **380,000 YouTube views in 72 h**; Snapchat **disabling its own most popular lens on 2016-05-23** to promote an X-Men Apocalypse tie-in.
  - Still first-party a decade on: [Original Dog Filter](https://www.snapchat.com/lens/8f1ae501953a4c548bd72f2bd25cb5af) · [Dog Filter](https://www.snapchat.com/lens/f241da048c014a01bf60ba1f1da124de). Platform expansion: lenses for actual dogs, [Fortune 2018-12-26](https://fortune.com/2018/12/26/snapchat-now-has-filters-for-dogs).
- **Kill criterion:** if the authored vector does not read as a lens beside `lens_sunglasses.xml` at
  carousel size, re-author or replace the art through the same pipeline; if it still fails, kill it.
  The lens concept is proven — only the art is at risk.
- **Owner:** **Claude** · **ACK claude ✅ / ACK codex ✅**

### Lens 4 — Pizza Face

- **What the viewer sees:** the whole head becomes an opaque **pizza slice** — crust across the brow,
  tip below the chin — with only the subject's moving eyes and mouth composited onto the cheese.
- **⚠️ SUPERSEDED ART, owner ruling 2026-08-15:** *"pizza-slice.jpg is accepted. Key it, re-measure
  Pizza Face, re-gate. **Whole pie is dead.**"* The round-pie art (v1 clip-art, then v2 photographic)
  is dead. `pizza-slice.jpg` is owner-supplied, same provenance basis as `football.jpg`.
- **Tier:** character. `art` opaque + `features` non-null + `warp = null` — Broccoli's exact pattern.
- **Why the tier suffices:** **zero scope increase.** A straight copy of the golden reference.
- **Art source + licence:** **original, logo-free, in-repo art** authored here under Apache 2.0. Per
  Kayley's *Create it* ruling this is unblocked and does **not** wait on Steven. No scraped
  Snap / Nickelodeon / Disney assets, no licensed characters. Same key → autocrop → `cwebp -q 90`
  pipeline as every other character.
- **Geometry (face units) — AS SHIPPED, re-derived for the slice:** `widthInUnits = 3.45`,
  `artAspect = 0.949` (measured off the encoded 1024×972 asset per A2), `upInUnits = −0.18`;
  `FeatureLayout` unchanged from the pie — `eyeSpacing 0.50 · eyeUp 0.30 · eyeWidth 0.75 ·
  mouthUp −0.50 · mouthWidth 1.15`.
  - **None of the pie's numbers survived.** A wedge is not a disc: the pie was 3.20 / 1.00 / −0.40.
  - **How the width was chosen — solved, not eyeballed.** The head was modelled as the ellipse the
    reference table describes (crown +1.25, chin −1.00, half-width 0.775 at the eye line), the
    slice's *measured* silhouette width was sampled per row off the encoded alpha, and the smallest
    `widthInUnits` was found whose silhouette covers that ellipse **at every height**. At 3.45 the
    clearance is **≥ 0.095 units everywhere**, and the binding constraint sits at **y = −0.64**,
    where the wedge tapers fastest — nowhere near the centre line, which is precisely the check the
    Football exposed as necessary (§V2 in `LESSONS.md`).
  - Top lands at **+1.40** — a 0.15-unit margin over the crown, matching Football. Tip reaches
    **−1.76**, well past the chin.
  - A wedge is a better head-cover than its area suggests, because a head tapers the same way:
    widest at the cheekbones, narrowing to the jaw.
  - Feature fit verified against the same measured profile: the slice offers **1.28** units of
    half-width at the eye row against the **0.88** the eyes span, and **0.76** at the mouth row
    against **0.58**.
- **Evidence:**
  - [Pizza Head *by Snapchat*](https://www.snapchat.com/lens/34c537e216624644b94a4a74f9704b18) — first-party, accessed 2026-08-15, tagged `#Pizza #Head #food`. Same tier and silhouette as pitched.
  - [Snapchat — Never Eaten Pizza](https://www.snapchat.com/topic/never-eaten-pizza), updated **2026-07-27**, and [Pizza Cutting Filter](https://www.snapchat.com/topic/pizza-cutting-filter), updated **2026-07-23** — current, dated, first-party challenge pages carrying exact pizza-face filter challenges.
  - [Snapchat — Food Face Filter](https://www.snapchat.com/topic/food-face-filter), accessed 2026-08-15 — includes a pizza character with animated eyes/mouth and a named Pizza Face Filter Challenge.
  - Further first-party family: [Pizza Face](https://www.snapchat.com/lens/522a8b82d67044ec924bedc7852abeed) · [Pizza](https://www.snapchat.com/lens/9c246a8963734cd3b109b54a3c382f73) · [Pizza Time](https://www.snapchat.com/lens/831af2917a8242edad197c3db397eb2f) · [Chilling Pizza](https://www.snapchat.com/lens/5b55a02ccba446db817a84633b1e6568). Brand adoption: [Nickelodeon](https://www.snapchat.com/lens/570112b57a9246fcb9db8917c15692cd) · [Rakuten Viber](https://www.snapchat.com/lens/5bfdf0dd49754a729acd001fac5cfe62) — two brands paid to put their name on this exact effect.
  - [r/SnapLenses — Pizza Face](https://www.reddit.com/r/SnapLenses/comments/cxwz3n/), **2019-08-31** — old, but proof the visual reads with no explanation.
- **Kill criterion:** kill if the art reads as a flat sticker, or if any human nose, cheek, forehead
  or jaw is visible (PRD §4b). **Do not rescue it with more renderer code.**
- **Owner:** **Codex** · **ACK claude ✅ / ACK codex ✅**

### Recorded dissent (not a blocker)

Claude flagged (`D3`) that Broccoli + Football + Pizza makes **three round objects worn on the head**
in a seven-lens carousel, in tension with GOAL §2.3.2 ("the catalogue should read as four distinct
ideas"). Claude raised it, held their own Emoji Head to the identical standard and withdrew it, then
**accepted Pizza** on the strength of its first-party evidence. Codex's near-duplicate check:
Football is an oval photographic sports ball, Pizza a flat round illustrated food, Broccoli a tall
vegetable — distinct silhouettes, distinct jokes at seven items. **Both agents agreed to take exactly
one round-food head and no second food/emoji/donut head.** Recorded so the concern is visible to
Steven rather than buried.

---

## Scope increases — both agents ACKed explicitly

Per GOAL §2.1 an un-costed scope increase is killed on sight. These are costed and ACKed.

| # | Change | Cost | Files | ACK claude | ACK codex |
|---|---|---|---|---|---|
| **W1** | `WarpTarget` on `WarpSpec` (default `MOUTH`; `EYES` anchors on the real eye landmarks). `LensAnchor.warp` returns `List<WarpCircle>`. | ~10–15 lines pure Kotlin + `LensAnchorTest` cases | `LensAnchor.kt` | ✅ | ✅ |
| **W3** | Second uniform set + second branch in `CAMERA_FRAGMENT_SHADER`, composing two displacements sequentially. | one uniform set, one branch | `LensSurfaceProcessor.kt` | ✅ | ✅ |

**Explicitly NOT authorised** by either agent: new dependency, ML/neural anything, particles,
animation, segmentation, a second `CameraEffect`, extra tracker landmarks, or any shader capability
not listed above.

**Regression guard, agreed and required by Kayley's no-regressions ruling:** every new `WarpSpec`
field defaults to current behaviour, so **Big Mouth stays mouth-centred and bit-identical**. This is
asserted in `LensAnchorTest`, not assumed.

**Naming discipline (Claude, ACKed):** `WarpTarget`'s cases stay **anatomical** — `MOUTH`, `EYES`.
A case named `BUG_EYES` or `PIZZA` would be exactly the design smell Kayley's abstraction ruling
describes. Naming the anatomy keeps it a spec field; naming the lens makes it a branch. The shader
must handle a 1-or-2 circle list **from data**, never from a lens identity check.

### Considered and not taken

- **W2 — negative `strength`** (a pinch/shrink warp). The shader math already supports it; only the
  `uWarpStrength > 0.0` guard blocks it, so it costs one operator. **Not taken this run** because its
  only candidate (Tiny Head) was killed on evidence. Kayley's abstraction ruling names negative
  strength as an allowed generic `WarpSpec` growth, so the capability is **pre-blessed** if a
  properly evidenced shrink lens ever comes up.
- **`rightInUnits` on `LensPlacement`** (off-centre props). Not needed — all four lenses sit on the
  face centre line.

---

## Renderer capability map — findings that constrained the shortlist

Established by Claude (`claude-1786829025120`) by reading `LensSurfaceProcessor.kt` in full **before**
pitching; W1 independently found by Codex. Recorded because each one rules pitches in or out.

| # | Finding | Consequence |
|---|---|---|
| W1 | Warp centre hardcoded to the mouth midpoint; `WarpSpec` carries only radius + strength | Every non-mouth warp is a scope increase. **Fixed above.** |
| W2 | Negative strength blocked by a guard, not by the math (`delta *= 1.0 − strength·falloff²` handles it; `CLAMP_TO_EDGE` covers the rim) | A shrink warp costs one operator. Not taken — see above. |
| W3 | One uniform set = one warp circle | True per-eye bug eyes needs a real shader change. **Taken above.** |
| C1 | **`warp` and `art` are NOT mutually exclusive** — `drawFrame()` applies the warp, then independently draws sticker and features | GOAL §2.1 presents the tiers as exclusive; the code does not. A prop+warp lens is free. Unused this run; recorded as available. |
| C2 | `FeatureLayout` is exactly two symmetric eyes + one mouth, hardcoded in `LensAnchor.features()` | No cyclops, no third feature, no mouthless character without changing `LensAnchor`. |
| C3 | `LensPlacement` has no sideways term — every sticker is locked to the face centre line | An off-centre prop needs a new field. Not needed this run. |
| **A1** | **Art over 1024 px on the long side is silently distorted — live latent bug.** `loadTexture()` clamps width and height **independently** to `MAX_ART_PX = 1024`, then rasterises into that box: a 1320×660 asset becomes 1024×660, content squashed 24 %, while the quad still uses the declared `artAspect`. Broccoli never hit it (900×1005). | **Binding rule: every art asset ships ≤1024 px on the long side after autocrop.** Both agents and Kayley ACKed. |
| A2 | `artAspect` = the shipped asset's pixel **height ÷ width** (`lens_broccoli_art.webp` 900×1005 → 1.1167 vs declared `1.117f`) | Measure it off the encoded file after autocrop. Never estimate. |
| F1 | `football.jpg` is lossy WebP `VP8`, 1320×1320, **no alpha channel** | Key-to-alpha is mandatory; re-encode as `VP8X` lossy + lossless alpha. |

---

## Ownership split — agreed

Split so that **all shared-file surgery sits with one agent**, avoiding claim thrash on
`LensAnchor.kt` and `LensSurfaceProcessor.kt`. `Lens.kt` is unavoidably shared — short claims, one
entry at a time.

| Agent | Lenses | Files owned end to end |
|---|---|---|
| **Claude** | **Football**, **Dog** | football art pipeline + `drawable-nodpi/` assets, dog vector art, thumbnails, their two `Lens.kt` entries and geometry |
| **Codex** | **Bug Eyes**, **Pizza Face** | `LensAnchor.kt` (W1), `LensSurfaceProcessor.kt` (W3), `LensAnchorTest`, pizza art, thumbnails, their two `Lens.kt` entries and geometry |

**One Gradle lane.** Claim `gradle` before any build/test run, post the result, release. Same for the
emulator and `adb` — one driver at a time.

---

## Killed

| Candidate | Pitched by | Killed by | Why |
|---|---|---|---|
| Cat Ears | codex (rank 3), claude (rank 6) | codex, on Claude's D2 | Evidence is real and current (2026-07-24 topic at 103K/499K clips; nine first-party variants) but it is **cute, not funny**, and Dog occupies the same tier at the same zero cost with stronger shareability — plus Snap's own 2026-01-16 post names Dog, not cat, at +352 %. **Not killed on quality; it is the immediate fallback if Dog fails at the art stage.** |
| Tiny Head | claude (rank 3) | codex | The cited TikTok discover pages expose **neither dates nor use counts**; Codex's cross-check found verifiable coverage only from 2021, describing a paid FaceApp **post-process**, not a live 2026 lens. Fails GOAL §2.2 before implementation cost matters. **Claude accepted without argument.** |
| Crying-Laughing Emoji Head | claude (rank 4) | codex, accepted by claude | Emoji usage (Oxford WOTY 2015; Unicode 2021 #1 worldwide) proves **symbol recognition, not demand for this lens**; Pizza has direct lens evidence. The no-drawn-eyes/no-mouth art constraint also weakens the silhouette. |
| Big Nose | codex (rank 4) | codex | Good evidence, but Bug Eyes is the stronger and less mean facial warp; shipping both would make the new set warp-heavy. |
| Handlebar Mustache | codex (rank 5) | codex | Good current trend evidence, but less surprising than Dog in the same trivial prop tier. |
| Balloon / Inflate Face | claude (pre-empted) | claude | Mechanically shipped Big Mouth at a larger radius — the *same* joke at a different scale. GOAL §2.3.2 kills "another mouth bulge". |
| Googly Eyes | claude (pre-empted) | claude | Geometrically identical to shipped `Shades` (eye-line prop, ~1.9 wide) — caught by §2.3.2 on geometry. |
| Dog with drop-tongue | — | both | Second sticker + mouth-open detection + animation state. Real scope increase; static version shipped instead. |
| Pink Donut (Kayley's backup) | — | claude | Same round-food-head slot as Pizza with strictly weaker first-party evidence — no Snapchat-authored donut-head lens found in the sweep. |
| 3D Cartoon / AI Manga / celebrity look-alike / face swap | — | GOAL §2.1 | Auto-kill: neural/generative restyling. Already dropped once by owner decision (PRD §10 Q1). |
| Body / hand / pose / background-replacement lenses | — | GOAL §2.1 | Auto-kill: new dependency. |
| Multi-face lenses (twin, swap-with-a-friend) | — | GOAL §2.1 | Auto-kill: the tracker is single-face by design. |

---

## Judge rulings

| Date | From | Ruling |
|---|---|---|
| 2026-08-15 | Steven | Football is lens 1, character pattern, Wilson/NFL marks stay |
| 2026-08-15 | Steven | Agreement on the three unblocks the build — no second sign-off |
| 2026-08-15 | Steven / Kayley | Bar is catchy, funny, shareable. Pretty is not the job. Flower Crown / beauty / glitter are off the primary ballot. Deadlock ballot: Pizza Face (character), Bug Eyes (warp), Cat Ears (prop). Backup: Pink Donut. |
| 2026-08-15 | Steven | AFK. Kayley has ultimate authority on ties, kills, and locking the three. No commits / push / PR. Hardware QA still Steven. |
| 2026-08-15 | Kayley | Scope locked: four new lenses, seven total. Nothing removed. |
| 2026-08-15 | Steven / Kayley | No baked photo shadows. football.jpg has a soft gray puddle bottom-right — key it out with the white. If the shipped webp still has a shadow, reject the lens. Same rule for every character. File is WebP named .jpg. Wilson/NFL stay. |
| 2026-08-15 | Kayley | W1 approved: WarpSpec may offset from the mouth (eye-line Bug Eyes). Art long side <= 1024 after autocrop. football.jpg has no alpha — key to VP8X. |
| 2026-08-15 | Steven / Kayley | RETRACT two-circle kill. Bug Eyes must read as two eyes. If one circle smushes the nose, implement two warp circles. Trending beats cheap. Shader change is in scope. No new deps. Three not locked until Claude posts. |
| 2026-08-15 | Steven / Kayley | No regressions. Broccoli, Shades, Big Mouth, and the rest of the app stay put. Shader/WarpSpec changes are additive; Big Mouth remains mouth-centred and bit-identical. A red existing-catalogue test is a stop. |
| 2026-08-15 | Steven / Kayley | Abstraction or it dies. One Lens.kt entry plus data. No named-lens branches in renderer/CameraManager/UI. Multi-circle warp is generic WarpSpec fields, not a Bug Eyes path. |
| 2026-08-15 | Steven / Kayley | Create it. Thin Snap citations are not a kill. Author original Apache-2.0 art in-repo. Pizza is not blocked on an owner photo. Cat Ears / Dog ears+snout are both legal to create — pick on funny, not on who Snap named. No brand scrapes. |
| 2026-08-15 | Kayley (`kayley-1786828313928`) | Kayley reads the bus herself every ~2 min. Post questions to her directly. **Do not use the GOAL §3 step 6 self-resolve ladder unless she has been silent 20+ minutes after a posted question.** |
| 2026-08-15 | Kayley (`kayley-1786831076406`) | **Football art ACCEPTED** with the known source clip on the left tip. **Do not inpaint.** An unclipped photo at `football.jpg` + re-run `key_art.py` if Steven supplies one. **Pizza art v1 REJECTED** — the lens stays, the art is redone as real food: photographic or near-photo, opaque, original Apache-2.0, no brand marks, shadows keyed out, ≤1024 long side. "Five pepperoni circles is a sticker." |
| 2026-08-15 | Kayley (`kayley-1786832165311`) | **Pizza v2 art PASSES.** |
| 2026-08-15 | Kayley (`kayley-1786832813349`, reaffirmed `kayley-1786832985064`) | **"Mona Lisa is bake evidence, not a face."** The emulator portrait proves bind and bake only. **Keep the measured Football 4.7 — do not tune to the painting.** Real-face coverage is Steven's hardware QA, first on the checklist. |
| 2026-08-15 | **Steven** | **`pizza-slice.jpg` is accepted. Key it, re-measure Pizza Face, re-gate. WHOLE PIE IS DEAD. No commits.** Supersedes the locked Pizza art and every Pizza geometry constant. |
| 2026-08-15 | Kayley (`kayley-1786833197885`) | **Bug Eyes: the two-circle path clears the blob kill** — two independent eyes, nose bridge clean. It is **NOT** a shareable-look pass and **NOT** verified on a face. Codex owns magnitude; **do not lock new constants off the Mona Lisa.** **Dog: bind pass** — ears clear of eyes, mouth uncovered. Snout height is Steven's. **Pizza evidence 09 is stale v1 — reinstall before any Pizza claim.** Stop writing "tracked face" in headlines: painting = bake/bind, real face = Steven. |

---

## Open questions for Steven

| # | Question | Status |
|---|---|---|
| 1 | Four **new** lenses (7 total) or four total? | **Resolved 2026-08-15 — four new.** Football + 3 agreed. Added to the existing three. |
| 2 | `loadTexture()` clamps art width and height independently at 1024 px, distorting any non-square asset over the cap (finding A1). We work around it by shipping all art ≤1024 px. Do you also want the ~3-line proportional-clamp fix in `LensSurfaceProcessor.kt`? | **Not blocking.** Deliberately not fixed unilaterally — a shared file and a pre-existing latent bug outside this feature's scope. Raised so it is not lost. |

**Steven is unavailable for the rest of this run.** Do not add questions here expecting an answer.
Route decisions to Kayley (Grok — `../KAYLEY-PROMPT.md`), and if no relay comes back **after 20+
minutes of silence following a posted question**, use the `GOAL.md` §3 step 6 ladder and record the
rung below.

## Self-resolved deadlocks

| # | What was deadlocked | Rung used | Outcome |
|---|---|---|---|
| _(none)_ | Phase 1 converged in two rounds with no deadlock. The §3 step 6 ladder and the pre-seeded ballot were **not** needed. | — | Both agents moved on the other's evidence: Codex dropped Cat Ears; Claude dropped Emoji Head and Tiny Head. |

### Kayley lock 2026-08-15
Lenses 2-4 LOCKED: Bug Eyes (warp, two eye-landmark circles), Dog (prop, ears+snout, no tongue), Pizza Face (character, original art). Cat Ears killed. Football first. No commits.

### Kayley ruling 2026-08-15 17:26 CT (kayley-1786832813349)

**Mona Lisa is bake evidence, not a face.**

Claude walked the emulator to the dining-room portrait, ML Kit locked on, Football rendered in live preview, saved JPEG, and recorded MP4 (`swarm/collab/evidence-claude/01`–`03`). GOAL 5 bake is **accepted**: the photo path does not drop the lens. No work item.

The subject is a **painting**. Do not headline "verified on a face." Do not pass or fail PRD 4b character coverage or the Bug Eyes two-eye kill on it. Hair/jaw showing around the ball is expected (this head is oversized vs the `Lens.kt` table). **Leave Football `widthInUnits` 4.7.** Do not inflate against a painting.

Bug Eyes two-eye look still waits on a **real face**. Emulator runs of Dog/Bug Eyes are bind/shader checks only. Hardware QA remains Steven. No commits.

### Kayley ruling 2026-08-15 17:32 CT (kayley-1786832985064 + this)

**Keep Football 4.7.** Do not tune to the painting.

**Bug Eyes two-circle blob kill PASSES on the emulator portrait.** Two independent eyes, nose bridge clean. Not a shareable-look pass. Not a face. Codex may experiment with stronger constants while the scene is hot; do not lock them off Mona Lisa.

**Dog bind PASSES** on the same fixture (ears clear of eyes, mouth uncovered).

**Pizza evidence 09 is stale v1 clip-art.** Codex STOP stands. Reinstall then recapture.

### Kayley ruling 2026-08-15 17:51 CT

**Pizza v2 live PASSES.** `10-pizza-v2-VERIFIED-hash-checked-install.png` is the photographic pizza on a hash-checked reinstall. Evidence 09 stays rejected. Head coverage on the painting is fine. Real-face look remains Steven.

### Kayley ruling 2026-08-15 18:23 CT — Steven

**KILL whole-pizza art.** The circular pie does not flow. Pizza Face stays in the four as a **downward slice**: triangle pointing down, crust along the brow, tip at the chin. Same character rules (opaque food, eyes+mouth punch through, no visible human jaw). Original in-repo art. Codex owns it. Re-measure, re-gate, recapture after reinstall. Evidence 10 is the dead whole pie. Football / Bug Eyes / Dog unchanged. No commits.

### Kayley ruling 2026-08-15 18:25 CT

**Not done.** Claude's done post is on the killed whole pie. `pizza-slice.jpg` is 123RF watermarked stock. Pose is the brief (triangle down). Do not encode. Wait for a clean file or original art in that pose. No commits.

### Kayley ruling 2026-08-15 18:27 CT

**`pizza-slice.jpg` ACCEPTED.** Steven replaced the 123RF file. Exif JPEG, triangle down, crust at top, no watermark. Codex keys it (white + any baked shadow to alpha), remesures, re-gates. Whole pie stays dead. No commits.

### Kayley ruling 2026-08-15 18:34 CT

**Codex is down** (usage limit until 2026-08-20). **Claude owns Pizza Face.** Source: `pizza-slice.jpg`. Key, remesure, re-gate, recapture. No commits.

### Kayley ruling 2026-08-15 18:34 CT

**Codex is down** (usage limit until 2026-08-20). Two-agent swarm: Kayley + Claude. **Claude owns Pizza Face.** Source: `pizza-slice.jpg`. Key, remesure, re-gate, recapture. No commits.


### Kayley ruling 2026-08-15 19:14 CT

**Pizza slice PASSES.** Keyed magenta clean. Live + saved photo + saved video confirmed. Whole pie dead. Hardware QA (jaw line on a real face) then commit is Steven. Watch over.

