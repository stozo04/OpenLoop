# Research trail — Claude

Phase 1 independent sweep for AR lenses 2, 3, 4. Written as I went, per the durable-state rule.
Sweep date: **2026-08-15**. Web search available in my session.

---

## 0. What I did before searching, and why it changes the pitches

I read `LensSurfaceProcessor.kt` end to end **before** writing a single candidate, because
GOAL.md §2.1 makes every pitch declare a tier and "why that tier is sufficient" — and you cannot
answer that honestly without knowing what the shader can actually express. That turned out to
matter a lot. Findings posted to the bus as `claude-1786829025120`:

| #   | Finding                                                                                                       | Where                                                                                                                                         | Consequence for a pitch                                                                                                                                                                                                                                                                                                                                                     |
| --- | ------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| W1  | **The warp center is hardcoded to the mouth midpoint.** `WarpSpec` carries only `radiusInUnits` + `strength`. | `LensAnchor.warp()` L367–376; `WarpSpec` L153                                                                                                 | The catalogue can express exactly **one** warp today: a mouth-centered circle. Any other warp — including the ballot's Bug Eyes — is a §2.1 scope increase. Cheap fix: add an offset to `WarpSpec`, resolve it in `LensAnchor.warp`. Pure math, JVM-testable, `WarpCircle` and the shader untouched. ~10 lines + 2 tests.                                                   |
| W2  | **Negative strength (a pinch/shrink) is blocked by a guard, not by the math.**                                | `CAMERA_FRAGMENT_SHADER` L651: `if (uWarpStrength > 0.0 …)`                                                                                   | The body `delta *= 1.0 - uWarpStrength*falloff*falloff` already handles a negative strength correctly (`1.0 − negative > 1.0` ⇒ sample further out ⇒ minify), and `CLAMP_TO_EDGE` covers oversampling at the rim. A shrink lens costs **one operator**: `> 0.0` → `!= 0.0`. Still a shader change, still needs both ACKs — but it is the cheapest scope increase available. |
| W3  | **Two warp circles (true per-eye bug eyes) is a real shader change.**                                         | one `uWarpCenter`/`uWarpRadius`/`uWarpStrength` set, one branch                                                                               | Second uniform set + composing two displacements. Materially bigger than W1/W2. Pitch the single-circle variant, or cost this explicitly.                                                                                                                                                                                                                                   |
| C1  | **`warp` and `art` are NOT mutually exclusive in the renderer.**                                              | `drawFrame()` L233–244 — `drawCamera()` warps, then *independently* `if (lens?.art != null)` draws the sticker, then features                 | GOAL §2.1 presents the three tiers as exclusive; the code does not. A **prop + warp** lens is free today: one enum entry, zero new code.                                                                                                                                                                                                                                    |
| C2  | **`FeatureLayout` is exactly two symmetric eyes + one mouth. Fixed.**                                         | `LensAnchor.features()` returns a hardcoded `listOf(leftEye, rightEye, mouth)`                                                                | No cyclops, no third feature, no mouthless character without changing `LensAnchor`.                                                                                                                                                                                                                                                                                         |
| C3  | **`LensPlacement` has no sideways term** — every sticker is locked to the face center line.                   | `LensPlacement` L92; its KDoc says "add one when a lens actually needs to be off-center"                                                      | An off-center prop needs a `rightInUnits` field. ~4 lines + 1 test.                                                                                                                                                                                                                                                                                                         |
| A1  | **Art over 1024 px on the long side is silently distorted. Live latent bug.**                                 | `loadTexture()` L523–527 clamps width and height **independently** to `MAX_ART_PX = 1024`, then `setBounds(0,0,w,h)` rasterizes into that box | A 1320×660 asset becomes a 1024×660 bitmap — content squashed 24% horizontally — while the quad still uses the declared `artAspect`. Broccoli never hit it (900×1005, under the cap on both axes). **Practical rule for this swarm: ship every art asset ≤1024 px on the long side.**                                                                                       |
| A2  | **`artAspect` = shipped asset's pixel height ÷ width.**                                                       | `lens_broccoli_art.webp` is 900×1005; 1005/900 = 1.1167 vs declared `1.117f`                                                                  | Measure it off the encoded file after autocrop. Never estimate.                                                                                                                                                                                                                                                                                                             |
| F1  | **`football.jpg` is a lossy WebP (`VP8`, not `VP8X`), 1320×1320, 233 KB, with no alpha channel at all.**      | RIFF/WEBP header, chunk fourcc `VP8`                                                                                                          | Kayley's 21:16 ruling is right and stronger than a naming quibble: there is *no* alpha to preserve, so key-to-alpha is mandatory and the output must be re-encoded as lossy+alpha (`VP8X`). Her no-baked-shadow rule applies to the gray puddle bottom-right.                                                                                                               |

**Why this is in the research file and not just the bus:** every one of these is a constraint on
what a *candidate* is allowed to be. A pitch that ignores them is fiction.

---

## 1. Search sweep — what I looked for and what came back

Queries run (2026-08-15), in order:

1. `most popular Snapchat lenses 2026 billions of views funny`
2. `trending TikTok effects 2026 face filter funny viral`
3. `snapchat.com/lens bug eyes lens` (domain-restricted to snapchat.com)
4. `"face warp bubble" filter TikTok balloon face effect trending`
5. `"tiny face" filter trend shrink head funny 2025 2026`
6. `most popular Snapchat lens of all time dog filter puppy face swap history`
7. `snapchat.com/lens cat ears filter by Snapchat` (domain-restricted)
8. `Snapchat "Bug Eyes" lens popular funny effect article 2025`
9. `Snapchat dog filter most iconic filter cultural impact article`
10. `snapchat.com/lens pizza face filter head` (domain-restricted)

Plus direct fetches of `snapchat.com/lens/d98fbc02…` (Bug Eyes) and `knowyourmeme.com/memes/dog-filter`.

### 1a. Two things the sweep established that change how I read the evidence bar

**Snapchat lens pages publish no counts.** I fetched the official Bug Eyes lens page directly to
check: it carries a Snapcode and tags (`#funny #bigeyes #bugeyes #trending #amazing`) and **no
usage, view, or play metric at all**. GOAL §2.2 asks for "effect pages showing use counts or
rankings" — for Snapchat that category is simply not available. So I am substituting three signal
types that *are* verifiable on a primary page, and saying so rather than inventing numbers:

- **First-party authorship** — a lens whose creator is literally "Snapchat" is Snapchat's own
  product decision about what is worth shipping. That is a stronger signal than a view count on a
  creator clone.
- **Clone proliferation** — when ten independent creators publish their own "Bug Eyes", the demand
  is demonstrated by the supply.
- **Dated third-party coverage** — news, Know Your Meme, encyclopedic entries with dates.

**Platform-level scale, for context only** (not a per-lens signal, and I am not pitching off it):
Snapchat reports AR lens plays in the billions daily and 300 M+ users engaging with lenses daily
(`techrt.com/snapchat-filter-statistics`, `searchlab.nl/en/statistics/snapchat-statistics-2026`,
both accessed 2026-08-15). Both are aggregator pages, i.e. exactly the SEO tier GOAL §2.2 tells me
to distrust — I am recording them as background, not as evidence for any candidate.

### 1b. Sludge I rejected

`filmora.wondershare.com`, `capcut.com/resource`, `zegocloud.com/blog`, `trickyenough.com`,
`techbloat.com` — all "20 Best Filters [2026]" listicles, undated or auto-dated, no primary data.
Used only as *search seeds* to find the real lens pages. Cited nowhere below.

### 1c. The one live trend the sweep surfaced that I did not go looking for

**Face inflation / balloon-face is trending right now (2026).** TikTok discover surfaces it under
several names (`/discover/tiktok-inflation-filter`, `/discover/face-warp-filter-bubble-filter`,
`/discover/blow-up-filter`), described as an instant balloon-inflated face with no scan line.
Mechanically this is *exactly* our existing radial bulge with a bigger radius and a different
center — i.e. W1 and nothing else. I nearly pitched it. I did **not**, and the reason is in §3.

---

## 2. Candidates

Ranked by my own confidence. Geometry derived from the `Lens.kt` face-unit table
(head width 1.55 · eye-line→crown 1.25 · mouth width 0.8) and from the two shipped precedents
(Broccoli 4.4 units wide; Shades 1.9 wide, `upInUnits` 0.06). **Every number below is a starting
value to be re-measured against the cropped asset**, per GOAL §4.1 — an earlier pass that reasoned
from published head statistics came out ~20 % oversized on every lens.

Human landmark note used throughout: the KDoc records that treating one unit as the interpupillary
distance oversized everything ~20 %, i.e. **IPD ≈ 0.8 × eye-to-mouth**, so a human eye sits roughly
**±0.4 units** from the center line. That is the number the warp radii below are built on.

---

### C1 — Bug Eyes · **warp** · my top warp pick

**What the viewer sees.** The subject's eyes balloon to cartoon size while the rest of the face
stays normal. Blinks and eye-rolls are magnified with them, which is what makes it play in a loop.

**Tier and why it suffices.** Warp — a radial UV displacement, no art, no second texture. The
existing `bulge()` branch does the whole effect. **But it is a scope increase (W1):** the warp
center is hardcoded to the mouth. Bug Eyes needs it on the eye line.

**Cost, stated:** add `offsetUpInUnits: Float = 0f` (and, if we ever want it, `offsetRightInUnits`)
to `WarpSpec`; resolve it in `LensAnchor.warp` by walking `frame.upX/upY * offset * frame.unit`
from the eye origin instead of starting at the mouth. `WarpCircle` is unchanged, so **the shader is
untouched**. ~10 lines of pure Kotlin + 2 `LensAnchorTest` cases. Default `0f` keeps Big Mouth
bit-identical.

**Art source + license.** None. Warps carry no art. Carousel icon is an in-repo vector like
`lens_big_mouth.xml` — Apache 2.0, ours, no attribution.

**Geometry (face units).** `radiusInUnits = 0.72`, `strength = 0.62`, `offsetUpInUnits = 0.0`
(dead on the eye midpoint, which *is* the face-frame origin). Radius reasoning: eyes sit at ±0.4
units, so 0.72 takes in both eyes plus lids and brow without reaching the mouth. Strength below Big
Mouth's 0.78 deliberately — the eye region has more structure and over-driving it melts the nose
bridge.

**Evidence.**

- **First-party:** [Bug Eyes Filter *by Snapchat*](https://www.snapchat.com/lens/d98fbc027cf9438a8ca820c08308380a) — fetched 2026-08-15. Snapchat's own lens. Tags verbatim: `#funny #bigeyes #bugeyes #trending #amazing`. No counts published (see §1a).
- **First-party, same family:** [Fun Eyes *by Snapchat*](https://www.snapchat.com/lens/1099f5248f1a4a039c1fcebde8743742) and [funny eyes *by Snapchat*](https://www.snapchat.com/lens/e56bd80322a147ee9b94f11090c4cddc) — accessed 2026-08-15. Snapchat maintains multiple first-party eye-enlargement lenses, not one.
- **Clone proliferation (8+ independent creators, all accessed 2026-08-15):** [Owen](https://www.snapchat.com/lens/cf0b501c9dff442086a650f5ef63e1d2) · [Jayden Robbins](https://www.snapchat.com/lens/29189967eb9d4ae9811f97657285cac9) · [Adi Benson](https://www.snapchat.com/lens/11c40de12fb74c41a6494cf4de5284e3) · [Tyler Diven](https://www.snapchat.com/lens/f247aba1e10745ef972d6ce7ff50d750) · [emma downing](https://www.snapchat.com/lens/f5a903c86b0d4bb4bd802a1378948751) · [Nick Brock](https://www.snapchat.com/lens/bb297401bdde4c28b7642bcad262a076) · [Kayden](https://www.snapchat.com/lens/35a24cc1ecad436aac4993b3a5119c9c) · [sharkattack.png](https://www.snapchat.com/lens/1c4e8445a4b540a79d8549a7ca8d7ee1) · [Ben — "Eye Popping"](https://www.snapchat.com/lens/f484c7f74db14115a64146a0f8bd5d9e).
- **Named alternative + the complaint:** Big Mouth is our own closest shipped alternative. It works on one feature only; the repeated user-side ask visible in the clone titles is the *eyes*, which we cannot do at all today.
- **Judge endorsement:** Kayley's pre-seeded deadlock ballot names Bug Eyes as the warp
  (`kayley-1786827888360`, 2026-08-15T21:04:48Z).

**Biggest risk + kill criterion.** One circle centered between the eyes also magnifies the nose
bridge and inner brow — real Snapchat bug-eyes uses two independent circles (W3). **Kill criterion:
if the single-circle build reads as "melted forehead" rather than "big eyes" on Steven's hardware,
we either take the W3 two-circle shader change with both ACKs, or we kill it.** I will not claim
this one looks right — nobody in this swarm can see a face.

---

### C2 — Tiny Head · **warp** (negative strength) · my second warp pick

**What the viewer sees.** The subject's whole head shrinks to a comically small ball on a
normal-sized body, background stretching in around it. The "tiny head, huge body" loading-error meme.

**Tier and why it suffices.** Warp. Same single radial displacement — **just with the sign
reversed**. This is the one candidate where I checked the shader arithmetic rather than assuming:
`delta *= 1.0 - uWarpStrength*falloff²`; at the center `falloff = 1`, so a strength of `-0.55`
gives `delta *= 1.55` — sampling 1.55× further out, i.e. a 1.55× minification — decaying smoothly
to identity at the rim. **The math already works.** The only thing stopping it is the guard
`if (uWarpStrength > 0.0 …)`.

**Cost, stated:** W1 (the same offset field C1 needs — this is not an extra cost if C1 ships) **plus
W2**, which is changing `uWarpStrength > 0.0` to `uWarpStrength != 0.0`. One operator. It is still a
shader edit and still needs both ACKs; I am not going to pretend a one-character diff is not a diff.

**Art source + license.** None. In-repo vector carousel icon.

**Geometry (face units).** `radiusInUnits = 1.40`, `strength = -0.55`, `offsetUpInUnits = -0.35`.
Reasoning: the head spans ~1.55 wide and ~2.4 tall (crown 1.25 above the eye line, chin ~1.0 below),
so a circle of radius 1.4 centered a third of a unit *below* the eye line — around the nose, the head's
actual centroid — covers the head and lands its falloff on the neck and shoulders rather than
mid-forehead.

**Evidence.**

- **Live trend, multiple independent TikTok discover surfaces (all accessed 2026-08-15):** [Tiny Face Filter](https://www.tiktok.com/discover/tiny-face-filter) · [Small Face Filter](https://www.tiktok.com/discover/Small-face-filter) · [Shrinking Head Filter](https://www.tiktok.com/discover/shrinking-head-filter) · [Small Head Funny](https://www.tiktok.com/discover/small-head-funny) · [Shrinking Face Meme](https://www.tiktok.com/discover/shrinking-face-meme). TikTok maintaining five distinct discover pages for one effect family is the platform's own read on demand.
- **Dated trend window:** coverage across these surfaces describes the shrinking-head filter as active through **2025–2026**, tied to the "tiny head huge body" loading-error meme and the elevator-mirror illusion.
- **Named alternative + complaint:** the mainstream way to get this today is CapCut templates — i.e. it is a *post-production* effect people apply after the fact, not a live lens. A live version you can *record through* is the thing that does not exist for most people. That is exactly OpenLoop's shape.
- **Distinctness (this is the point):** it is the **inverse** of our shipped Big Mouth. Same code path, opposite sign, completely different joke. Nobody looking at the carousel will read them as the same lens.

**Biggest risk + kill criterion.** Extreme minification pulls a lot of background inward; if the
device's `CLAMP_TO_EDGE` behavior at the rim smears visibly, the effect looks like a bug rather
than a joke. **Kill criterion: if a strength that actually reads as "tiny head" (≤ −0.5) produces
visible edge smear or a hard ring at the falloff boundary on hardware, drop the strength until it
is clean; if it is not funny by the time it is clean, kill it.**

---

### C3 — Dog · **prop** · my top prop pick

**What the viewer sees.** Floppy ears over the head and a black snout on the nose, with the
subject's own face fully visible between them. The single most recognizable AR effect ever shipped.

**Tier and why it suffices.** Prop — one alpha-masked sticker over a still-visible face, exactly
`Shades`' mechanism. `art` set, `features = null`, `warp = null`. **Zero scope increase. Zero new
code. One enum entry and one drawable.** Of everything on my list this is the only one that costs
literally nothing but the art.

**Deliberate reduction, named up front:** the canonical Snapchat dog lens has an **animated tongue
that drops when you open your mouth**. That is a second sticker plus mouth-open detection plus
animation state — a genuine scope increase, and one I am *not* pitching. I am pitching the static
ears-and-snout dog. It is still instantly readable as "the dog filter". If we ever want the tongue,
it is a separate, costed conversation.

**Art source + license.** **Authored in-repo vector drawable**, same as `lens_sunglasses.xml` and
`lens_big_mouth.xml` — Apache 2.0, unambiguously ours, no attribution line, no redistribution
question. This is the cleanest license story on my whole list, and it matters: PRD §11.2 records
that broccoli's photographic source needed an explicit owner license confirmation before it could
ship. Ears and a snout are flat shapes with soft edges — the exact thing vector does well, and *not*
the thing PRD §11.2 says vector failed at (photoreal florets).

**Geometry (face units).** `widthInUnits = 2.40`, `artAspect = 0.96`, `upInUnits = 0.70`.
Derivation, not guesswork: ears sit wider than the head (1.55) — 2.40 puts them proud of it by
~0.4 units a side. Vertically the art runs from the ear tips at ~1.85 above the eye line
(crown 1.25 + ~0.6 of ear) down to the snout at ~−0.45 (between eye line and mouth): a span of
2.30. Center = (1.85 + −0.45)/2 = **0.70**. `artAspect` = 2.30/2.40 = **0.96** — to be re-measured
off the cropped asset per A2.

**Evidence.**

- **Dated origin + cultural record:** [Know Your Meme — Dog Filter](https://knowyourmeme.com/memes/dog-filter), fetched 2026-08-15. Launched **February 2016** in Snapchat's Lens update. Entry carries **184,569 confirmations**.
- **Dated third-party coverage, from that entry:** Vice, **2016-04-22**, *"The Internet is Slut Shaming Women Over Snapchat Filters Now"* · Kim Kardashian publicly requesting a Dalmatian variant, **2016-05-10** · an Ariana Grande / Jimmy Kimmel Live parody using it, **2016-05-20**, *"upwards of 380,000 views and 240 comments on YouTube"* in 72 hours · Snapchat pulling it on **2016-05-23** to promote an X-Men Apocalypse tie-in — a platform disabling its own most popular lens as a *promotional lever* is the clearest possible statement of how much traffic it carried.
- **Still first-party today, a decade on (accessed 2026-08-15):** [Original Dog Filter *by Snapchat*](https://www.snapchat.com/lens/8f1ae501953a4c548bd72f2bd25cb5af) · [Dog Filter *by Snapchat*](https://www.snapchat.com/lens/f241da048c014a01bf60ba1f1da124de). Ten years of continuous first-party maintenance is a durability signal no 2026 trend can match.
- **Platform expansion as a demand signal:** Snapchat shipped lenses *for actual dogs* in Dec 2018 ([Fortune, 2018-12-26](https://fortune.com/2018/12/26/snapchat-now-has-filters-for-dogs); [Newsweek](https://www.newsweek.com/snapchat-ios-update-dog-filter-lens-how-1272003)).

**Biggest risk + kill criterion.** A hand-authored vector dog can read as clip-art rather than as a
lens — that is precisely the failure PRD §11.2 records for vector broccoli. **Kill criterion: if the
authored vector does not read as a lens beside `lens_sunglasses.xml` at carousel size, replace the
art with a public-domain source through the same key→autocrop→WebP-q90 pipeline, and if that also
fails, kill it.** The lens concept is proven; only my art is at risk.

---

### C4 — Pizza Head · **character** · ballot candidate, real risks

**What the viewer sees.** A whole pizza where the head should be, with the subject's own eyes and
mouth composited onto it. Broccoli's exact mechanism.

**Tier and why it suffices.** Character — `art` opaque, `features` non-null, `warp = null`.
No scope increase; it is a straight copy of the golden reference.

**Art source + license — this is the weak leg.** We have exactly **one** owner-supplied character
image in this repo (`football.jpg`), and PRD §11.2 shows a photographic character asset needed an
explicit owner license confirmation before it could ship. Steven is AFK, so **no new owner
confirmation is obtainable this run.** That leaves public-domain sourcing (Wikimedia Commons / a
USDA-style PD image, the route already proven by the prepared broccoli fallback) as the only
license-clean path. Not blocked — but it is real work with a real failure mode, and it is the only
candidate on my list whose license is not settled by construction.

**Geometry (face units).** `widthInUnits = 3.2`, `artAspect ≈ 1.0` (a pizza is round),
`upInUnits = −0.40`. `FeatureLayout`: `eyeSpacingInUnits = 0.50`, `eyeUpInUnits = 0.30`,
`eyeWidthInUnits = 0.75`, `mouthUpInUnits = −0.50`, `mouthWidthInUnits = 1.15`. Derivation: smaller
than Broccoli's 4.4 because a pizza is a flat disc with no stalk hanging below the chin — it needs
to swallow the head (1.55 wide, ~2.4 tall) with margin, not wreath it. Features sit tighter to
center than Broccoli's because the "face" is a flat disc rather than a recessed opening.

**Evidence.**

- **First-party:** [Pizza Head *by Snapchat*](https://www.snapchat.com/lens/34c537e216624644b94a4a74f9704b18) — accessed 2026-08-15; tags `#Pizza #Head #food`. Snapchat ships a first-party pizza-as-head lens, which is the exact tier and silhouette being pitched.
- **First-party, same family:** [Pizza Face *by Snapchat*](https://www.snapchat.com/lens/522a8b82d67044ec924bedc7852abeed) · [Pizza *by Snapchat*](https://www.snapchat.com/lens/9c246a8963734cd3b109b54a3c382f73) · [Pizza Time *by Snapchat*](https://www.snapchat.com/lens/831af2917a8242edad197c3db397eb2f) · [Chilling Pizza *by Snapchat*](https://www.snapchat.com/lens/5b55a02ccba446db817a84633b1e6568) — all accessed 2026-08-15.
- **Brand adoption (money behind it):** [Pizza Face by *Nickelodeon*](https://www.snapchat.com/lens/570112b57a9246fcb9db8917c15692cd) and [Pizza Face by *Rakuten Viber*](https://www.snapchat.com/lens/5bfdf0dd49754a729acd001fac5cfe62) — accessed 2026-08-15. Two brands paid to put their name on this exact effect.
- **Judge endorsement:** Kayley's ballot names Pizza Face as the character (`kayley-1786827888360`).

**Biggest risk + kill criterion.** **This would be the third round-object head in a seven-lens
carousel** — Broccoli, Football, Pizza. GOAL §2.3.2 says the catalogue should read as distinct
ideas, and three round things you wear on your head does not. **Kill criterion: if Codex or Kayley
reads it as a near-duplicate of Broccoli/Football, I drop it without argument.** Secondary kill: if
no public-domain pizza image survives the key→autocrop pipeline cleanly, kill it — I will not ship
an asset with an unresolved license, and I cannot get an owner confirmation this run.

---

### C5 — Cat Ears · **prop** · ballot candidate, and I am pitching it *against* itself

**What the viewer sees.** Two triangular cat ears sitting on the head.

**Tier and why it suffices.** Prop. One sticker, `features = null`, `warp = null`. Zero scope
increase, in-repo vector, cleanest possible build.

**Art source + license.** Authored in-repo vector. Apache 2.0, no attribution.

**Geometry (face units).** `widthInUnits = 1.80`, `artAspect = 0.44`, `upInUnits = 1.65`.
Derivation: ears sit just proud of head width (1.55 → 1.80), rising from the crown at 1.25 to ~2.05;
span 0.80, center (1.25 + 2.05)/2 = 1.65; aspect 0.80/1.80 = 0.44.

**Evidence.**

- **First-party, and prolific — nine distinct Snapchat-authored variants, all accessed 2026-08-15:** [Cat Ears](https://www.snapchat.com/lens/59515ae4346c4fdb95c64efdc7a4c082) · [Fluffy Cat Ears](https://www.snapchat.com/lens/84bcb7dfb3b342be99833d8e268afa80) · [Flower cat ears](https://www.snapchat.com/lens/60102e167c984d909e827c8f96cc0091) · [Space Cat Ears](https://www.snapchat.com/lens/ebfe299eff2b4c239019ebbff1823cb9) · [Pixel Cat Ears](https://www.snapchat.com/lens/34022a2391d64a64bbaa17f0435738f4) · [Beige Cat Ears](https://www.snapchat.com/lens/2ca81aefd15c4cf8b405abeb40ba1488) · [Watermelon Cat Ears](https://www.snapchat.com/lens/c3e1e0300fcd4aca80df5cfeea5899f9) · [Black Cat Ears](https://www.snapchat.com/lens/304885b60a8c4df69137729b6c9b5140) · [Cartoon Cat Ears](https://www.snapchat.com/lens/befa0c022508447b8fa371da47cac107).
- **Judge endorsement:** Kayley's ballot names Cat Ears as the prop.

**Biggest risk + kill criterion — and I want this on the record.** The evidence for Cat Ears is
excellent and **the bar still kills it.** Steven and Kayley were explicit (2026-08-15): *catchy,
funny, shareable — pretty is not the job*, and beauty-adjacent effects are off the primary ballot.
Cat ears is **cute**, not funny. Nobody sends a friend a clip because they had cat ears on; that is
the definition of the lens that does not get shared. It is on the ballot as a *deadlock tie-break*,
which is a different job from being the best of three. **I am pitching it so it is properly on the
table with its evidence, and simultaneously arguing it should lose to C3 (Dog), which is the same
tier, the same cost, the same license story, and actually funny.** Kill criterion: if we have a
better prop, this dies — and we do.

---

### C6 — Crying-Laughing Emoji Head · **character** · the license-clean alternative to C4

**What the viewer sees.** A giant 😂 as the head — flat yellow face, drawn-on brows and two big
tear-jets — with the subject's real eyes and mouth composited into it, so the emoji blinks and talks.

**Tier and why it suffices.** Character. Broccoli's exact pattern.

**Art source + license — this is why it exists on my list.** **Authored in-repo vector.** A
crying-laughing emoji is flat fills, circles and teardrops — the single most vector-friendly thing
on this entire list, and nothing like the photoreal florets PRD §11.2 says vector failed at.
Critically I mean *our own drawn* emoji face, **not** a shipped emoji font asset: Twemoji is CC-BY
(attribution — the broccoli precedent explicitly needed "no attribution requirement"), OpenMoji is
CC-BY-SA, and Noto's licensing is a font-license question I do not want in an Apache 2.0 app. Drawing
it ourselves sidesteps all three. **This is the only character candidate whose license is settled by
construction rather than by sourcing.**

**Geometry (face units).** `widthInUnits = 3.0`, `artAspect = 1.0` (round), `upInUnits = −0.35`.
`FeatureLayout`: `eyeSpacingInUnits = 0.55`, `eyeUpInUnits = 0.28`, `eyeWidthInUnits = 0.80`,
`mouthUpInUnits = −0.48`, `mouthWidthInUnits = 1.25` — a wide mouth, because the joke is a laughing
face and the composited human mouth is what has to sell it.

**Evidence.**

- **Oxford Dictionaries Word of the Year 2015 — verified this session, 2026-08-15.** 😂 was named Word of the Year, the **first pictograph ever chosen since the tradition began in 2004**. The selection was data-driven: per Oxford's data partnership with keyboard-app maker SwiftKey, 😂 comprised **nearly 20 % of all emoji use in the US and UK**. Dated coverage **2015-11-17**: [PBS NewsHour](https://www.pbs.org/newshour/nation/oxford-dictionary-says-the-2015-word-of-the-year-is-an-emoji) · [TIME](https://time.com/4114886/oxford-word-of-the-year-2015-emoji/) · [CBC](https://www.cbc.ca/lite/story/1.3322428).
- **Unicode Consortium usage ranking — verified this session, 2026-08-15.** The Consortium's **2021** global ranking, built from anonymised emoji-frequency data contributed by Apple, Google and Microsoft, puts 😂 **first in the world**, ahead of ❤️ and 😍, at **over 5 % of all emoji sent online**. [Wikipedia — Face with Tears of Joy emoji](https://en.wikipedia.org/wiki/Face_with_Tears_of_Joy_emoji) (carries the Consortium citation).
  > **Provenance note, deliberately on the record:** I first wrote both of these bullets from memory with plausible-looking URLs I had not opened. That is exactly the faked evidence GOAL §2.2 forbids, so I ran the two searches and rewrote them. The numbers above (20 % SwiftKey, 5 % Unicode 2021, "first pictograph since 2004") come from the searches, not from my recollection — which had none of them.
- **Emoji-head lenses are an established first-party Snapchat form**, evidenced by the same "object as head" family the pizza search surfaced — [Pizza Head *by Snapchat*](https://www.snapchat.com/lens/34c537e216624644b94a4a74f9704b18) is the structural precedent.
- **Readable at a glance (§2.3.3):** this is the candidate that needs *zero* explanation in any language or culture. That criterion is in the brief and this is the only pitch that maxes it.

**Biggest risk + kill criterion.** Two risks, both honest. (1) It is a **fourth round head** — the
same near-duplicate objection as C4, and if we take C6 we should not also take C4. (2) An emoji
already *has* eyes and a mouth; compositing the human's on top of drawn ones could read as a
four-eyed mess. The art must therefore be drawn with an **empty** face — brows and tears only, no
drawn eyes or mouth — which is a real art constraint, not a detail. **Kill criterion: if the art
cannot be drawn convincingly with no built-in eyes or mouth, kill it — a character with two sets of
eyes fails the PRD §4b acceptance test just as badly as a visible human jaw does.**

---

## 3. What I deliberately did not pitch, and why

Recording these so nobody re-derives them, and so a "why didn't you consider X" has an answer.

| Rejected                                                            | Why                                                                                                                                                                                                                                                                                                                                                                            |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Balloon / Inflate Face** (the live 2026 trend from §1c)           | Mechanically it is our shipped Big Mouth with a larger radius and a shifted center — the *same* joke (inflate a face) at a different scale. GOAL §2.3.2 kills "another mouth bulge". C2 (Tiny Head) gets the same trend energy from the **opposite sign**, which is genuinely a different lens. Pitching both would be pitching one idea twice. If C2 dies, this is my backup. |
| **Googly Eyes** (prop)                                              | Geometrically identical to shipped `Shades` — eye-line prop, `widthInUnits` ~1.9, `upInUnits` ~0.06. §2.3.2's "another eyewear prop is a no" catches it on the geometry even though it is not literally eyewear.                                                                                                                                                               |
| **Face Swap, celebrity look-alike ("Macka"), AI Manga, 3D Cartoon** | §2.1 auto-kill: neural/generative restyling. Already dropped once by owner decision (PRD §10 Q1).                                                                                                                                                                                                                                                                              |
| **Anything with a body, hands, or background replacement**          | §2.1 auto-kill: needs a new dependency (pose/segmentation).                                                                                                                                                                                                                                                                                                                    |
| **Multi-person lenses (twin filter, face-swap-with-a-friend)**      | §2.1 auto-kill: the tracker is single-face by design.                                                                                                                                                                                                                                                                                                                          |
| **Any licensed character, brand mascot, or celebrity**              | §2.1 auto-kill. Note this does *not* touch Football — Steven ruled the Wilson/NFL marks stay on owner-supplied art, and that is his call to make on his own asset.                                                                                                                                                                                                             |
| **Cyclops / one-eye lenses**                                        | Blocked by C2 in the capability map: `LensAnchor.features()` is hardcoded to two eyes + a mouth.                                                                                                                                                                                                                                                                               |
| **Dog with the animated drop-tongue**                               | Second sticker + mouth-open detection + animation state. Real scope increase; pitched the static version instead (see C3).                                                                                                                                                                                                                                                     |
| **Pink Donut** (Kayley's backup)                                    | Same round-food-head slot as C4, with strictly weaker first-party evidence than Pizza — I found no Snapchat-authored donut-head lens in the sweep. If we want that slot, Pizza is the better-evidenced occupant. Recorded, not argued for.                                                                                                                                     |

---

## 4. My recommendation going into the cross-argument

**Lenses 2, 3, 4 = C1 Bug Eyes (warp) · C3 Dog (prop) · one character from {C6 Emoji Head, C4 Pizza Head}.**

Reasoning:

1. **Tier diversity is satisfied with room to spare** — Football (character) + Bug Eyes (warp) +
   Dog (prop) already covers all three tiers, so the fourth slot is free to be chosen on merit
   rather than to fill a quota.
2. **Bug Eyes is the only warp with judge endorsement, first-party authorship, *and* eight
   independent clones**, and W1 is a ~10-line pure-math change with the shader untouched.
3. **Dog is free.** No scope increase, no new code, in-repo vector, ten years of first-party
   maintenance, and dated 2016 coverage including a platform that pulled it *as a promotional
   lever*. It beats Cat Ears on the one axis Steven and Kayley actually named: funny, not pretty.
4. **The fourth slot is the genuinely open question**, and I am not going to pretend otherwise.
   C6 wins on license (settled by construction, no sourcing risk, no absent owner needed) and on
   glance-readability; C4 wins on first-party and brand evidence. **Both are round heads, and we
   should take at most one.** If Codex has a character that is *not* a round object, it should
   probably beat both of mine — and I will say so on the bus rather than defend my own.

**If C2 (Tiny Head) can carry both ACKs for the one-operator shader change, it is a stronger fourth
than either round head** — it is the only candidate that adds a genuinely new *kind* of joke to the
catalogue rather than a new object. I rank it under C1 only because it costs a shader edit and C1
does not.

---

## 4b. Round 2 — adversarial verification of `research-codex.md`

I said on the bus I would verify Codex's numbers against their primary pages to the same standard I
applied to my own. Doing that turned up one finding that changes the shortlist argument.

### The finding: Codex's own Cat Ears source is a first-party Snap post that names only the Dog

Codex cites, as Cat Ears evidence #4:

> [Snap newsroom — 2016 Energy, 2026 Mindset](https://newsroom.snap.com/2026-is-the-new-2016-snapchat),
> published **2026-01-16**, reports a platform-wide return to playful animal-ear lenses and a
> **352%** rise in Dog Lens searches. That is adjacent rather than cat-specific […]

I fetched it (2026-08-15). The page is Snap's **own newsroom** — first-party, dated 2026-01-16 —
and it is stronger and more specific than Codex's summary:

- **"2016" Lenses: up 613 % year-to-date** vs the prior year.
- **Dog Lens: searches up 352 %.**
- The icons it names are **"dog ears and flower crowns"**.
- **Only one animal lens is named by name in the entire article: the Dog Lens. There is no mention
  of cat lenses at all.**

So the source Codex offers to establish that *a simple ears prop is timely now* is in fact Snap
stating that **the dog lens specifically is the one surging, by 352 %, right now**. That is not
adjacent evidence for Cat Ears. It is direct, first-party, dated evidence for Dog.

### Independent confirmation

[Wikipedia — "2026 is the new 2016"](https://en.wikipedia.org/wiki/2026_is_the_new_2016), fetched
2026-08-15, describes the same trend from outside Snap: it emerged **late December 2025**, took off
at the **start of 2026**, and the article **names exactly two Snapchat lenses as driving it — the
puppy-dog filter and the flower-crown filter.** The BBC is cited there reporting that searches for
"2016" on TikTok rose sharply in the first weeks of 2026.

### Why that settles the prop slot on the brief's own terms

Of the two lenses named by both the first-party and the independent source, **flower crown is
explicitly on Kayley's kill list** (`kayley-1786827888360`, 2026-08-15: *"kill: beauty, glitter,
flower crown as primary"*). **The dog filter is the only lens named in the biggest live Snapchat
trend of 2026 that survives the judge's own bar.**

That is a stronger, more current, and more first-party claim than anything I had for Dog in §2 C3,
where I was leaning on 2016-era coverage and ten-year durability. Dog is not merely the *classic*
prop — per Snap's own January 2026 numbers it is the *trending* one.

### What I verified and did not challenge

- Codex's Bug Eyes evidence (2026-02-16 Spotlight at 17K views / 1.7K likes / 334 comments; the
  "Bug Eyes Reigns Supreme" creator recap; the 70K-vote thread) is **better than mine** and I have
  adopted it rather than defending my weaker version.
- Codex's Pizza evidence (first-party challenges updated 2026-07-23 and 2026-07-27, a live Food Face
  topic) is **the best character evidence on either list.** I am not contesting Pizza.
- I did **not** verify the `easylens.snapchat.com` Big Nose listing or the Lens Studio community
  post, because Codex themselves rank Big Nose 4th and state only one new warp should ship. Chasing
  it would burn a round on a candidate neither of us is proposing. Recorded as unverified rather
  than silently skipped.

### One geometry note on Codex's Pizza numbers, offered as a build detail, not an objection

Codex derives `widthInUnits = 3.20` from "a 1.60-unit radius covers crown through jaw". Against the
§4.1 table that is *exactly* tight at the top: centered at `upInUnits = -0.35`, a 1.60 half-width
reaches `+1.25`, which is the crown measurement with **zero margin**. For an opaque character that
must hide the forehead (PRD §4b), zero margin at the crown is where a hairline peeks out.

This is not a reason to reject the number — it is a reason not to trust *any* of our numbers yet,
mine included. The effective coverage of a character quad depends on how much **transparent padding**
the cropped art carries, which is why Broccoli's much larger 4.4 quad still only reaches ~1.04 above
the eye line. **Every character geometry on both lists is provisional until the art is autocropped
and `artAspect` is measured off the encoded file per A2.**

---

## 5. Honest limits of this sweep

- **No per-lens usage numbers exist to cite.** Snapchat publishes none (verified by direct fetch,
  §1a). Anyone presenting a per-lens view count for a Snapchat lens should be asked for the primary
  page, because I could not find one.
- **TikTok discover pages are undated by construction.** I cite them for *existence and breadth of
  the effect family*, which is what they actually prove, and I lean on Know Your Meme and dated news
  where a real date was needed.
- **I have not seen any of these on a face**, and neither can I. The emulator's virtual scene has no
  face. Every geometry number in this document is derived arithmetic against the `Lens.kt` table,
  not an observation, and all of it is provisional until Steven runs it on hardware.
