# Porting a third-party AR effect into OpenLoop

**Worked example: DeepAR "Twisted Tongue" → `Lens.TwistedTongue`**

Written 2026-08-16, alongside the change that shipped it. The point of this document is not the
tongue — it is the **method**, because more effects like this are queued. Read §1 and §8 if you read
nothing else.

> **Where things live.** The vendor project this was written from lived in `twisted-tounge/` as
> **reference material only**, gitignored (§7); it was deleted from the repo on 2026-08-31, so the
> vendor paths below are a record of how it was handled, not a place to look. The shipped design
> decisions are in [`PRD-camera-lenses.md` §14](../PRD-camera-lenses.md); this guide is the how.

---

## 1. The 20-minute triage — do this before anything else

Three questions, in this order. Answering them out of order wastes the most time.

### Q1. Can the vendor's code ship? (Usually: no.)

For OpenLoop the answer was already on file. `docs/PRD-camera-lenses.md` §4 evaluated DeepAR in
August and rejected it on two hard constraints:

* **C1** — free and open source, no paid licenses, no per-MAU billing. DeepAR is MAU-metered.
* **C3** — the app is Apache 2.0; a proprietary binary SDK cannot ship inside it.

So "just use their SDK" was never available, and neither was "just use their assets" (§7). **Check
the PRD's constraint table first.** If a prior decision already covers the vendor, you are
reverse-engineering the *design*, and you should say so in one line and move on rather than
re-litigating it.

### Q2. What does the effect actually do?

Not what it looks like — what it *is*, mechanically. Two files answer this in about ten minutes
(§2). Do this before writing anything, because the answer decides Q3.

### Q3. Which parts can this renderer express?

OpenLoop's `LensSurfaceProcessor` composites **textured quads onto tracked landmarks** and applies
**radial UV bulges**. That is the whole vocabulary. It does not skin meshes, run bone chains, or
shade with matcaps.

So the honest triage is a table with a **Dropped** column, written before any code:

| Reference feature                                 | Renderer can express it?                           |
| ------------------------------------------------- | -------------------------------------------------- |
| eyeball spheres, matcap-shaded                    | ✅ as opaque art on an eye anchor                   |
| tongue on a 4-joint pendulum chain                | ✅ approximately — one damped spring                |
| skin-toned mouth surround that samples the camera | ❌ drop — and see §4.3 for why it stopped mattering |
| dense face-mesh morph at weight 1.0               | ❌ drop — different renderer entirely               |
| phong nose overlay                                | ❌ drop — invisible under the other layers          |

**A port is a lossy translation and the losses belong in writing.** Two of the five went in the bin,
and the lens is still recognisably the effect. Deciding that up front is much cheaper than
discovering it three days into building a mesh skinner.

---

## 2. Cracking a `.deeparproj`

A DeepAR Studio export contains two things that look equally promising and are not:

```text
twisted-tongue/
├── twisted-tongue.deepar         ← 3.6 MB COMPILED bundle. Ignore it.
├── twisted-tongue.jpg            ← the preview still. Look at it LAST.
└── twisted-tongue.deeparproj/
    ├── effect.json               ← THE SCENE GRAPH. Start here.
    ├── project.dprm              ← studio version + feature flags
    └── resources/                ← meshes, textures, .mat materials, .sc shaders
```

**Ignore the compiled `.deepar`.** It opens with magic bytes `DA01`, is not a zip, and is almost
entirely packed float data. There is nothing in it that the readable project does not state more
clearly. Confirmed rather than assumed:

```bash
python -c "print(open('twisted-tongue.deepar','rb').read(8))"   # b' \x00\x00\x00DA01'
python -c "import zipfile; print(zipfile.is_zipfile('twisted-tongue.deepar'))"   # False
```

### 2.1 Dump the scene graph first

`effect.json` is plain JSON: a tree of `name` / `localPosition` / `localScale` / `components`. Eighty
kilobytes is too much to read, and you do not need to — you need the tree:

```bash
python -c "
import json
d=json.load(open('effect.json',encoding='utf-8'))
def fmt(v): return '('+', '.join(f'{v[k]:.4g}' for k in 'xyzw' if k in v)+')' if isinstance(v,dict) else v
def walk(n,i=0):
    pad='  '*i
    print(f'{pad}- {n.get(\"name\",\"?\")}  pos={fmt(n.get(\"localPosition\",{}))} scale={fmt(n.get(\"localScale\",{}))}')
    for c in n.get('components',[]):
        extra=[f'{k}={json.dumps(c[k])[:200]}' for k in ('mesh','material','meshDriver','blendShapeWeights') if k in c]
        print(f'{pad}    * {c.get(\"type\")}  '+' | '.join(extra))
    for c in n.get('children',[]): walk(c,i+1)
walk(d)
"
```

That one command produced the entire §1 Q3 table. The load-bearing lines were:

```text
- tongue_1   pos=[0.0, -4.665, 0.207]      * simplePendulumPhysics
  - tongue_2 pos=[0.0, -0.470, 0.897]      * simplePendulumPhysics
    - tongue_3 ...                          * simplePendulumPhysics
      - tongue_4 ...                        * simplePendulumPhysics
- L_eye_phy  pos=[3.081, 2.869, 0.181]     * simplePendulumPhysics
- R_eye_phy  pos=[-3.081, 2.869, 0.181]    * simplePendulumPhysics
- dense_new  ... blendShapeWeights=[1.0]
```

Three facts fell straight out, none of them visible in the preview image:

1. The tongue is a **chain**, and four of its five joints carry physics. The effect's whole feel is
   secondary motion, not the art.
2. The eyeballs are at **symmetric ±3.081**, so they are placed on the eyes, not on the face center.
3. `blendShapeWeights = [1.0]` is a **constant**. There is no animation and no trigger anywhere in
   the graph — which answered "does the tongue come out only when the mouth opens?" with **no**,
   definitively, in one line. That saved building mouth-open detection the effect never had.

> Point 3 is the single highest-value thing in this document. A preview image cannot tell you
> whether something is triggered or static. The scene graph can, and it takes one grep.

### 2.2 Read the materials, then the shaders

`.mat` files are small JSON, and they name the textures and the shader:

```text
// skinsamplingmat.mat
"shader": "head.dprx",
"s_texColor":     { "texture": "CameraTexture.tex" },   ← reads the LIVE CAMERA
"s_texMap":       { "texture": "samplingmask1.png" },
"s_texAlphaMap":  { "texture": "alpha_mask1.png"  }
```

A material sampling `CameraTexture.tex` is the tell for "this surface tints itself from the camera".
The shader confirms exactly how — `resources/head/Fragment.sc` is 30 readable lines of bgfx GLSL:

```glsl
vec4 skin = mix(v_color0, cameraSmooth, map.a);          // sampled skin + camera
vec4 outp = mix(skin, diffuse*color*multiplyFactor.x, diffuse.a*color.a);
```

That is a skin-tone matcher: blend a drawn surface toward the camera's own color so the prosthetic
matches the subject's complexion. Genuinely clever, and a real capability this renderer lacks — see
§4.3 for how it stopped being needed rather than being reimplemented.

**Read the preview image last.** Looking at `twisted-tongue.jpg` first primes you to build what you
*see* (a picture) instead of what the effect *is* (a rig). The image is for checking your work.

---

## 3. The translation table

The reusable part. DeepAR concept on the left, OpenLoop primitive on the right:

| DeepAR / Studio                       | OpenLoop equivalent                                            | Notes                                                      |
| ------------------------------------- | -------------------------------------------------------------- | ---------------------------------------------------------- |
| Node `localPosition` in the face rig  | `LensPlacement.upInUnits` / `rightInUnits`                     | **Do not convert the numbers.** See §4.1.                  |
| Node parented to a face bone          | `LensPlacement.anchor` (`LEFT_EYE`/`RIGHT_EYE`/`MOUTH`/`FACE`) |                                                            |
| Several nodes under one effect        | `Lens.art: List<LensArt>`, drawn in list order                 |                                                            |
| `simplePendulumPhysics`               | `LensPhysics.WobbleSpec` on a layer                            | One spring per hanging part, not per joint                 |
| `meshRenderer` + `matcap*.mat`        | opaque art (a vector drawable)                                 | Matcap shading → bake the highlight into a radial gradient |
| Material sampling `CameraTexture.tex` | `FeatureLayout` (character lenses) — or design it out          | §4.3                                                       |
| `blendShapeWeights` on a morph mesh   | usually nothing; check if it is a constant first               | §2.1 point 3                                               |
| A `.armesh` / `.fbx` silhouette       | the alpha channel of a vector drawable                         |                                                            |
| Effect draw order                     | list order in `Lens.art`                                       |                                                            |

### The one rule that matters most

**Re-derive every number in face units. Never convert the vendor's.**

DeepAR's `±3.081` is in its own rig's arbitrary scale. Converting it requires knowing that rig's unit
— which the project does not state — so any conversion is a guess wearing a decimal point.

What the number *tells* you is only this: **the eyeballs are symmetric about the center line and
anchored to the eyes.** That is the design intent, and it is all you need. The magnitude comes from
this repo's own anatomy table in `Lens.kt`:

```text
head width, ear to ear   1.55 units      (so half-width 0.775)
eye line up to the crown 1.25 units
mouth width, at rest     0.80 units
eye off the center line  0.40 units
```

So the eyeball got sized by arithmetic against *that* table, not by scaling `3.081`:

```text
ball diameter          0.62 units
inner edge   0.40 - 0.31 = 0.09   → an 0.18 gap at the nose bridge  ✓ matches the reference look
outer edge   0.40 + 0.31 = 0.71   → inside the 0.775 head edge      ✓ stays on the face
```

Both checks are pure arithmetic, so both are unit tests
(`LensAnchorTest.twistedTongue_eyeballsCoverTheEyeButStayOnTheHead`) and neither needed a face.

---

## 4. What got built, and why

### 4.1 Three framework changes, all generic

The catalogue's own rule is that **nothing outside `Lens.kt` names an individual lens**. Every
change below holds that line — the renderer gained capabilities, not special cases.

1. **`Lens.art` became a `List<LensArt>`.** Forced, not chosen: an eyeball has to sit on each *eye*
   and a tongue has to hang from the *mouth*, and no single quad tracks three landmarks at any size.
   Every earlier lens is a one-element list.
2. **`LensPlacement` gained `anchor` and `rightInUnits`.** `anchor` defaults to `FACE`, which
   reproduces the old center-line behavior exactly, so this is additive.
3. **`LensPhysics`** — a pure damped-spring module (§4.2).

If your next effect needs a fourth capability, add it the same way: a default-valued field on
`LensPlacement` or a new pure module, never an `if (lens == Foo)` in the renderer.

### 4.2 The physics, and the two bugs designed out of it

A sticker pinned rigidly to a landmark reads as a decal. The reference puts a pendulum on six nodes;
this ships **one damped spring on the tongue**:

```text
lagged   = offset - drive * pivotShift      // the pivot moved, the mass has inertia and didn't
velocity = velocity + (-stiffness * lagged - damping * velocity) * dt
offset   = lagged + velocity * dt
```

Two decisions in there are worth stealing:

* **Drive on the pivot's *displacement*, not its velocity or acceleration.** Landmarks from a
  per-frame detector are noisy, and every derivative multiplies that noise by `1/dt` — a divide that
  blows up on exactly the short frames a camera produces most of. Displacement needs no division, so
  a jittery detector gives a jittery drive rather than an unbounded one.
* **Clamp `dt`.** A dropped frame, a paused preview, or the first frame after a bind produces a
  large gap, and explicit integration over an unclamped gap is precisely how a spring reaches
  infinity. Then comes `NaN`, then a vertex buffer that erases the whole quad with nothing in logcat.

**Step the simulation once per frame, not once per output.** `drawFrame` loops over the preview
surface *and* the recorder surface. Stepping inside that loop would advance the spring twice per
frame and give the preview and the recording different animations. It is stepped before the loop, in
the tracker's own coordinate space, producing a **dimensionless** angle every output can consume
after its own re-framing.

### 4.3 Removing a problem instead of solving it

The reference covers the mouth region with geometry that samples the camera to match skin tone
(§2.2). Reimplementing that is real work: a sampling mask, a smoothing pass, a second camera read.

It was never needed — because the mouth layer was designed as **lips and cavity** rather than as a
patch of cheek. A lip is lip-colored on everyone. There is no complexion to match, so the entire
capability became irrelevant rather than deferred.

This is the same move the repo already made once: PRD §10b pinned `ImageAnalysis` to 4:3 so the
field-of-view mismatch between streams *could not arise*, instead of modeling it. **When a vendor
capability looks expensive, check whether a design change makes the question disappear.** Reach for
that before reaching for the shader.

### 4.4 The detail that decides whether it reads at all

A tongue lolling out passes **over** the lower lip but **under** the upper teeth. That is a
three-way interleave, so the mouth cannot be one drawable — the cavity has to be behind the tongue
and the teeth in front of it. Hence, three mouth-region layers:

```text
1. mouth  (lips + cavity)   ← behind
2. tongue (wobbles)
3. teeth                    ← in front
4. eye (LEFT_EYE)
5. eye (RIGHT_EYE)          ← last; nothing may cover the eyes
```

Drawn as a single sticker, the tongue reads as a pink shape stuck on a chin. This kind of layering
question is what `List<LensArt>` is really for, and it is worth looking for in every effect you port.

---

## 5. Bumps, in the order they were hit

**The folder was empty.** The first pass found the directory tree with zero files in it — an
extracted archive that had lost its contents. Two tool calls went into hunting a `.zip` that did not
exist before the owner re-added the files. *If a reference folder looks structurally right but is
empty, say so immediately rather than searching for it.*

**`find -type f` returned nothing while `du` reported 52K.** Directory entries, no files. Worth
knowing as the signature of exactly the situation above.

**RTF parsing burned two attempts.** `Getting Started.rtf` is 68 KB of Aspose-generated style tables
wrapping about a page of DeepAR marketing. Naive control-word stripping produced one word per line
and a `SyntaxError` from quoting a regex inside a shell heredoc. *It contained nothing useful.* If a
vendor export ships a "Getting Started" document, it is onboarding copy, not a license — check the
project files instead, and write throwaway parsing to a scratch file rather than fighting shell
quoting.

**Test fixtures are not anatomy.** `LensAnchorTest.face()` is a synthetic head whose eye span was
chosen to make the face unit a round number — its eyes sit **0.53** units off the center line, not
the documented **0.40**. Asserting an anatomical claim against it failed, correctly. The fix was to
assert against the catalogue constants and the documented table, since that is what the claim is
actually about. *A property about your shipped numbers should read your shipped numbers.*

**A "half the distance" fixture was actually a quarter.** `eyeY 0.45 / mouthY 0.55` is a 0.10 span
against `0.30 / 0.70`'s 0.40 — a **quarter**, not a half. The scale-invariance test failed on my
arithmetic, not the code. *When an invariance test fails, suspect the fixture first.*

**The thumbnail was designed wrong, and only a human could tell.** The first carousel icon reused
the live art's cream eyeballs on a transparent background. The tray draws each thumbnail on a *light
glass chip*, so cream-on-white had almost no edge and the whole lens read as a pale blob. Every JVM
test passed, the emulator rendered it "correctly", and it was still bad — the owner spotted it in
seconds. The rule: **a thumbnail is designed for the chip it sits on, never scaled down from the
live art.** Light shapes need a heavy dark rim; the silhouette has to survive at ~56 dp.

**A mid-session revert wiped every tracked edit.** The owner tried to revert some images and the
discard reset *all* modified tracked files to `HEAD` — eight files, including the whole framework
change. Worth knowing exactly what that costs, because it is much less than it looks:

* **Untracked NEW files survive a discard.** `LensPhysics.kt`, `LensPhysicsTest.kt`, all five
  drawables and this guide were untouched — they had never been added, so there was no `HEAD`
  version to reset to. Only *modifications to tracked files* were lost.
* **`git status --short` is the damage report.** Output containing only `??` lines after an hour of
  editing tracked files means every modification is gone. Read it before re-editing anything.
* **The `.gitignore` going back also un-protected the vendor assets** (§7), which is the part that
  actually mattered — restore that first, before any source.

The recovery was mechanical because every change was still in the assistant's context. **The cheap
insurance is committing to the feature branch incrementally** on a multi-file change, so a discard
costs minutes rather than a re-derivation.

**The repo's own `preview_lens.py` could not help.** It is single-layer, has no anchor concept,
approximates SVG arcs as midpoint ellipses, and ignores `<aapt:attr>` gradients — so it would render
this lens's circles and gradients as garbage. Extending it to full fidelity would duplicate what the
JVM tests already assert more rigorously (geometry) and what the emulator shows better (appearance).
*Skipped deliberately.* If you port an effect whose art is flat vector shapes on the center line, the
tool is still the fastest check available.

---

## 6. Verifying an effect you cannot see

**The emulator's virtual scene is a static poster.** With the stock
`Walk_to_image_room` macro it will show ML Kit a detectable painted face — enough to prove the lens
binds, renders to preview, composites on a detection, and bakes into a recording. It will never move
a head. So it cannot exercise a single line of the physics.

Split verification accordingly, and be explicit about which half covers what:

| Property                                                       | Verified where                    | Why not the other                                |
| -------------------------------------------------------------- | --------------------------------- | ------------------------------------------------ |
| spring settles, never exceeds its limit, cannot explode        | `LensPhysicsTest` (JVM)           | the poster cannot move                           |
| anchors resolve to landmarks; swing preserves hanging distance | `LensAnchorTest` (JVM)            | arithmetic, so a picture is a weaker check       |
| eyeballs clear the bridge / stay on the head                   | `LensAnchorTest` (JVM)            | arithmetic                                       |
| tongue root stays behind the teeth **at the swing limit**      | `LensAnchorTest` (JVM)            | passes trivially at rest — test the limit        |
| art parses, gradients render, layers stack correctly           | emulator                          | no rasteriser here is faithful to VectorDrawable |
| carousel entry, touch targets                                  | `LensCarouselTest` (instrumented) | catalogue-driven; needed no edit                 |
| **does the swing look right on a real head**                   | **nowhere — owner, on hardware**  | say so plainly                                   |

### What the final run actually showed

`docs/e2e/2026-08-16-twisted-tongue-emulator.png` — the stock dining-room portrait with the lens
composited on it. Every geometric claim in §3 is visible in one frame: the eyeballs nearly touch at
the nose bridge and stay inside the head, the upper teeth are drawn **in front of** the tongue while
the tongue passes **over** the lower lip, and the tip hangs well past the chin. That single image
validates the anchoring, the layer ordering, and the sizing arithmetic together.

It proves nothing about the physics — the poster cannot move. The owner confirmed the lens on real
hardware the same day.

Final gate: clean `assembleDebug` + `assembleRelease` + `bundleRelease`, **419 unit tests / 0
failures** (61 in `LensAnchorTest`, 17 in `LensPhysicsTest`), Android Lint **0 errors** (25 warnings,
unchanged from the previous lens PR), and `zipalign -c -P 16` reporting `(OK)` — not
`(OK - compressed)` — on all 20 native libraries.

Write the physics tests as **properties** (settles, never exceeds, never diverges) rather than
expected numbers, so retuning the feel cannot silently weaken the safety guarantees. Make the
catalogue-wide ones iterate `Lens.entries`, so the next effect is covered the moment it exists —
`everyShippedWobbleSpecIsStableUnderTheClamp` will fail on a too-stiff spring in a lens nobody has
written yet.

And keep the last row honest. A static poster proves plumbing. It does not prove the joke is funny.

---

## 7. Licensing — the part that can actually hurt

**OpenLoop is public under Apache 2.0.** Anything committed is redistributed to everyone, under a
license granting them the right to redistribute it again.

The vendor project contained matcap textures, a 2.4 MB normal map, `.armesh`/`.fbx`
geometry and a compiled bundle. **We hold no redistribution rights to any of it.** Committing the
folder wholesale would publish another company's assets under our license. This has bitten the repo
before — PR #118 was blocked on exactly this question for the broccoli photograph (PRD §11.2).

That `twisted-tounge/` tree is gone — deleted from git on 2026-08-31, not coming back, and this
guide is the only leftover. There is no ignore rule for a path that no longer exists. This guide
lives in `docs/guides/`.

All shipped art is original vector drawable authored in this repo. For the next port:

1. Reference material in, shipped assets never — **assume no redistribution rights** unless you have
   the owner's explicit confirmation in writing, as PRD §11.2 records for the broccoli.
2. "We're allowed to use it" and "we're allowed to redistribute it under Apache 2.0" are **different
   claims**, and only the second one lets it ship. Ask the specific question.
3. Gitignore the vendor folder in the *same* change that adds it, not later.

---

## 8. The playbook

For the next effect, in order:

1. **Triage (20 min).** Vendor license → what the effect mechanically is → what this renderer can
   express. Write the **Dropped** column before any code.
2. **Gitignore the vendor folder** immediately (§7).
3. **Dump the scene graph** (§2.1). Look for physics components, constant blendshape weights, and
   symmetric positions. Read materials for `CameraTexture` bindings. Preview image *last*.
4. **Re-derive every number** in face units from `Lens.kt`'s anatomy table. Never convert the
   vendor's coordinates (§3).
5. **Ask what can be designed away** before implementing a vendor capability (§4.3).
6. **Extend the framework generically** — a default-valued field or a new pure module. If you are
   writing the lens's name outside `Lens.kt`, stop (§4.1).
7. **Put the new logic in a pure module** with no Android types. This repo's house pattern
   (`ZoomUi`, `BoomerangSequence`, `TrimHandleMath`, `LensAnchor`, now `LensPhysics`) exists because
   it is the only way to verify anything the emulator cannot show you.
8. **Work the layering** — what is in front of what, and at the extremes of any motion, not at rest
   (§4.4).
9. **Test properties, iterate `Lens.entries`**, and state plainly what only hardware can settle
   (§6).
10. **Record the decisions and the drops** in `docs/PRD-camera-lenses.md`. The drops matter more than
    the additions — they are what someone will otherwise re-attempt.

### The two habits that saved the most time

* **Read the rig, not the render.** `blendShapeWeights = [1.0]` in a scene-graph dump killed a whole
  sub-feature (mouth-open detection) in one line. No amount of staring at the preview image would
  have.
* **Make it arithmetic, then assert it.** Every geometric claim here — the nose-bridge gap, the head
  edge, the tongue root behind the teeth at full swing — is a number derived from a documented table
  and locked in by a unit test. On a feature you fundamentally cannot see while building it, that is
  not extra rigor. It is the only rigor available.
