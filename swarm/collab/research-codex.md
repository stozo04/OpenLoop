# Codex independent research — AR lens shortlist

Research run: 2026-08-15. I did not read Claude's shortlist before locking these five pitches.
The filter-page numbers below are snapshots, not lifetime-use claims unless the source says so.

## Ranking

1. **Pizza Face** — strongest character gag and the cleanest use of the proven Broccoli path.
2. **Bug Eyes** — strongest distinct warp; current creator evidence is unusually direct.
3. **Cat Ears** — lowest-risk prop and part of the 2026 return of playful 2016-era animal lenses.
4. **Big Nose** — very strong funny/shareable evidence, but only one new facial warp should ship.
5. **Handlebar Mustache** — trivial and current, but visually less surprising than Cat Ears.

## Candidate 1 — Pizza Face

- **What the viewer sees:** their whole head becomes an opaque round pepperoni pizza; only their
  moving eyes and mouth are composited onto the toppings.
- **Tier:** character. Broccoli's existing `art + FeatureLayout` path is sufficient; no renderer
  change.
- **Art source + licence:** new original, logo-free pizza art authored in-repo and shipped under
  Apache-2.0. Opaque cut-out, background and shadow keyed to alpha, autocropped, WebP q90.
- **Geometry (face units):** `widthInUnits = 3.20`, `artAspect = 1.00`,
  `upInUnits = -0.35`; `FeatureLayout(eyeSpacingInUnits = 0.45,
  eyeUpInUnits = 0.25, eyeWidthInUnits = 0.66, mouthUpInUnits = -0.55,
  mouthWidthInUnits = 1.15)`. A 1.60-unit radius covers crown through jaw against the §4.1
  table while leaving room for toppings around the lifted features.
- **Evidence:**
  1. [Snapchat — Never Eaten Pizza](https://www.snapchat.com/topic/never-eaten-pizza), updated
     **2026-07-27**, labels the topic as millions of trending videos and includes Brooke C/Sley
     Royal's exact pizza-slice face-filter challenge.
  2. [Snapchat — Pizza Cutting Filter](https://www.snapchat.com/topic/pizza-cutting-filter), updated
     **2026-07-23**, includes Aju's 1.2K-view giant-pepperoni-face filter challenge.
  3. [Snapchat — Food Face Filter](https://www.snapchat.com/topic/food-face-filter), accessed
     **2026-08-15**, includes both a pizza character with animated eyes/mouth and Jaycee Olson's
     exact Pizza Face Filter Challenge.
  4. [r/SnapLenses — Pizza Face](https://www.reddit.com/r/SnapLenses/comments/cxwz3n/), posted
     **2019-08-31**, is a direct community post for the same lens idea; old, but useful proof that
     the visual reads without explanation.
- **Biggest risk / kill criterion:** another food character could feel too close to Broccoli. Kill
  it if the art reads as a flat sticker or exposes any human nose, cheek, forehead, or jaw; do not
  try to rescue it with more renderer code.

## Candidate 2 — Bug Eyes

- **What the viewer sees:** one circular bulge around the eye line makes both eyes comically huge
  while the mouth and outer face stay recognizable.
- **Tier:** warp. The existing radial UV shader is sufficient. The only code cost is letting
  `WarpSpec` choose `EYES` instead of its currently hard-coded `MOUTH` anchor; no shader change,
  dependency, second effect, or tracking capability. Both agents must ACK that small model/math
  extension before build.
- **Art source + licence:** no render art. A new original in-repo carousel icon is Apache-2.0.
- **Geometry (face units):** `radiusInUnits = 0.88`, `strength = 0.92`, centred on the eye midpoint.
  The radius spans both eyes without swallowing the whole head.
- **Evidence:**
  1. [Snapchat — Bug Eyes Lens](https://www.snapchat.com/lens/d98fbc027cf9438a8ca820c08308380a),
     accessed **2026-08-15**, is a live Lens Explorer result tagged `funny`, `bigeyes`, `bugeyes`,
     and `trending`.
  2. [Demitra Kalog's Bug Eyes Spotlight](https://www.snapchat.com/%40kayliemandende/spotlight/W7_EDlXWTBiXAEEniNoMPwAAYemp1bW5mdmd4AZxnCFubAZxnCFuAAAAAAQ),
     posted **2026-02-16**, shows **17K views, 1.7K likes, and 334 comments** on an exact Bug Eyes
     Lens clip.
  3. [Snapchat — Popular Lens](https://www.snapchat.com/topic/popular-lens), accessed
     **2026-08-15**, includes a 2025 creator recap titled "Bug Eyes Reigns Supreme" and names it
     that creator's most-used lens.
  4. [r/youseeingthisshit — Them eyes](https://www.reddit.com/r/youseeingthisshit/comments/1dotmk9/them_eyes/),
     posted **2024-06-26**, earned **70K+ votes** around a big-eye filter on a horse; the reactions
     are direct evidence that the exaggerated-eye gag is instantly legible and shareable.
- **Biggest risk / kill criterion:** a single eye-line bulge may enlarge the bridge of the nose as
  well as the eyes. Kill it if a static fixture render does not read as two bug eyes, or if it needs
  two simultaneous warp circles / a second GL pass.

## Candidate 3 — Cat Ears

- **What the viewer sees:** oversized black-and-pink cat ears sit above the crown and roll with the
  head; the face stays visible.
- **Tier:** prop. One transparent art quad is sufficient; no animation, nose sticker, particles, or
  beauty pass.
- **Art source + licence:** new original vector authored in-repo, Apache-2.0; rasterized and shipped
  as WebP q90 with lossless alpha.
- **Geometry (face units):** `widthInUnits = 2.30`, `artAspect = 0.55`,
  `upInUnits = 1.45`. This puts the lower ear edges around 0.82 units above the eye line and the
  tips above the 1.25-unit crown measurement.
- **Evidence:**
  1. [Snapchat — Cat Ears Lens](https://www.snapchat.com/lens/59515ae4346c4fdb95c64efdc7a4c082),
     accessed **2026-08-15**, is a current first-party lens.
  2. [Snapchat — Cat Ears Filter](https://www.snapchat.com/topic/cat-ears-filter), updated
     **2026-07-24**, labels the topic as millions of trending videos and shows individual cat-filter
     clips at **103K** and **499K** views.
  3. [Snapchat — Popular Lenses](https://www.snapchat.com/lens), accessed **2026-08-15**, lists
     `Neko Mimi` in its current top-lenses rail — the closest ears-only alternative.
  4. [Snap newsroom — 2016 Energy, 2026 Mindset](https://newsroom.snap.com/2026-is-the-new-2016-snapchat),
     published **2026-01-16**, reports a platform-wide return to playful animal-ear lenses and a
     **352%** rise in Dog Lens searches. That is adjacent rather than cat-specific, but it explains
     why a simple ears prop is timely now.
- **Biggest risk / kill criterion:** plain ears can drift into "pretty" instead of funny. Kill it
  if the oversized silhouette is not obvious in a small carousel thumbnail, or if anyone proposes
  adding beautification, sparkles, animation, or a second sticker to compensate.

## Candidate 4 — Big Nose

- **What the viewer sees:** the middle of the face balloons into one absurd round nose while the
  eyes and mouth stay readable.
- **Tier:** warp. Same existing radial shader. It shares the proposed `WarpSpec` anchor selector
  with Bug Eyes, using `NOSE` (the midpoint halfway from the eye line to mouth line); no new tracker
  landmarks or shader capability.
- **Art source + licence:** no render art. New original in-repo carousel icon, Apache-2.0.
- **Geometry (face units):** `radiusInUnits = 0.58`, `strength = 0.88`, centred halfway between
  eye midpoint and mouth midpoint.
- **Evidence:**
  1. [Snapchat — Big Nose Lens](https://www.snapchat.com/lens/84a8b5ff13b6488a85d756b68ec1e806),
     accessed **2026-08-15**, is a live first-party lens specifically tagged `big`, `round`, and
     `nose`.
  2. [Easy Lens — Big Nose](https://easylens.snapchat.com/lens/06a3ed8f-2dee-7c66-8000-9805e48025cd),
     accessed **2026-08-15**, reports **35,117 views** for a face-deformation lens whose only
     feature is a big-nose deformation.
  3. [Lens Studio Community — Big Nose](https://support.lensstudio.snapchat.com/hc/en-us/community/posts/4413520242708-Big-Nose),
     posted **2021-12-01**, reports **100K viewers in one hour** for a creator's big-nose lens.
  4. [Daily Dot — Whoville Nose trend](https://dailydot.com/grinch-whoville-nose-tiktok-trend-explained),
     published **2025-07-03**, documents a cross-platform nose-distortion trend as a shared visual
     joke. It is the closest named alternative; our round bulge is deliberately simpler.
- **Biggest risk / kill criterion:** facial-feature ridicule can feel mean rather than playful, and
  the approximate nose point may drift on extreme yaw. Kill it if the derived centre leaves the
  nose in the existing fixture/yaw math, or if Bug Eyes wins — shipping both would make the new
  catalogue warp-heavy.

## Candidate 5 — Handlebar Mustache

- **What the viewer sees:** one oversized curled mustache sits across the upper lip and follows head
  roll.
- **Tier:** prop. One transparent art quad; no hair segmentation or face deformation.
- **Art source + licence:** new original black vector authored in-repo, Apache-2.0; WebP q90 with
  lossless alpha.
- **Geometry (face units):** `widthInUnits = 1.55`, `artAspect = 0.34`,
  `upInUnits = -0.72`, placing the centre just above the mouth line.
- **Evidence:**
  1. [Snapchat — Mustache Filter](https://www.snapchat.com/topic/mustache-filter), updated
     **2026-07-28**, labels the topic as millions of trending videos and shows mustache-filter
     clips at **57K, 91K, and 23K** views.
  2. [Snapchat — Funny Mustaches](https://www.snapchat.com/topic/funny-mustaches), updated
     **2026-07-20**, carries a current 31K-view filter demonstration.
  3. [r/pranks — filter prank](https://www.reddit.com/r/pranks/comments/1m784ys/), posted
     **2025-07-23**, earned **5,325 votes** for a clip whose joke lands on a thick fake mustache.
  4. [Le Monde — the mustache's under-30 comeback](https://www.lemonde.fr/en/campus/article/2025/03/03/the-mustache-s-comeback-among-under-30s-it-gives-you-a-light-and-comic-dandy-style_6738762_11.html),
     published **2025-03-03**, reports nearly **3 billion** TikTok views for `#Mustache` and calls
     out its comic, retro appeal.
- **Biggest risk / kill criterion:** it is readable but not surprising. Kill it if Cat Ears remains
  available, or if the art must cover beard/jaw regions rather than staying a single lip-anchored
  quad.

## Codex round-1 position

Lock **Pizza Face + Bug Eyes + Cat Ears** unless Claude produces materially stronger current
evidence or finds a renderer/geometry flaw. This set is character + warp + prop, requires no new
dependency, and matches Kayley's pre-seeded ballot. I explicitly ACK the minimal `WarpSpec` anchor
selector needed for Bug Eyes.

**Judge correction, 2026-08-15:** Kayley first killed a two-circle warp, then retracted that kill on
Steven's quality override. One eye-line circle is accepted only if a fixture render visibly reads as
two enlarged eyes rather than a nose-bridge blob. If it fails, two warp circles (second uniform set +
second branch) are in scope and both agents must ACK the implementation. No dependency, ML,
particles, or unrelated shader capability is authorized.
