package io.github.stozo04.openloop.camera.lens

import androidx.annotation.DrawableRes
import io.github.stozo04.openloop.R

/**
 * The lens catalogue.
 *
 * Adding a lens is meant to be *only* this: one entry here plus its art. Nothing in
 * `LensSurfaceProcessor`, `CameraManager`, or the UI names an individual lens — the carousel
 * renders `Lens.entries` and the renderer just walks [art] and [features].
 *
 * ## Adding a lens — the questions every entry answers
 *
 * 1. **Prop or character?** [features] null or not (`docs/PRD-camera-lenses.md` §4b).
 * 2. **Does the user interact with it?** [interaction] — required, no default (owner rule,
 *    2026-08-26). A head-sized character spins well under a hand wave; a pair of shades or a
 *    tongue does not. Answer on purpose: `SPIN` needs a layer carrying a [SpinSpec]
 *    ([SPIN_ON_A_HEAD] is the tuned one), `NONE` must carry none — `LensPhysicsTest` pins the
 *    declaration to the layers so the two cannot drift apart.
 * 3. **The numbers** — the table below, measured, never nudged.
 *
 * ## Reading the numbers
 *
 * Every measurement is in **face units**, where one unit is the subject's eye-to-mouth distance
 * (see [LensAnchor.faceFrame]). That makes the numbers below readable as real anatomy rather than
 * tuning constants:
 *
 * | Real measurement | ≈ face units |
 * |---|---|
 * | head width, ear to ear | 1.55 |
 * | eye line up to the crown | 1.25 |
 * | eye line up to the **top of the brow** | **0.30** |
 * | eye line down to the mouth | **1.00** (the definition) |
 * | eye line down to the **chin** | **1.75** |
 * | mouth width, at rest | 0.8 |
 *
 * These are **measured off a real tracked face**, not assumed. If a lens ever needs
 * resizing, re-measure against this table rather than nudging a lens in isolation.
 *
 * The **brow** row was added 2026-09-03 off an owner hardware capture, because [Cowboy] shipped a
 * hat brim tuned against an *assumed* brow at 0.35 and the real one measured 0.30 — the same class
 * of error as the chin row above, and the reason anything a lens sits against belongs in this table
 * rather than in one lens's head.
 *
 * Because they are ratios of the face to itself, one set of numbers holds for every face at every
 * distance and angle — there is nothing here to re-tune per device.
 */
enum class Lens(
    val displayName: String,
    /** The interaction question, answered per lens — see the header. No default on purpose. */
    val interaction: LensInteraction,
    @param:DrawableRes val thumbnailRes: Int,
    /**
     * The art layers, drawn in list order — later layers paint over earlier ones.
     *
     * Most lenses are one layer on the face's center line. A lens becomes multi-layer when its
     * parts track *different* anatomy: [TwistedTongue] puts an eyeball on each eye and a mouth and
     * tongue on the mouth, which one quad cannot do at any size. Each layer carries its own
     * [LensPlacement], so the anchor, offset and wobble are per-layer.
     */
    val art: List<LensArt>,
    /**
     * Non-null turns this lens into a **character**: the art is drawn opaque over the head and the
     * subject's own eyes and mouth are composited onto it. Null leaves the subject's face visible
     * and the art sits over it as a prop.
     */
    val features: FeatureLayout? = null,
) {
    /**
     * Broccoli Head — a **head replacement**, matching the reference lens
     * (<https://www.snapchat.com/lens/6ecbf4cd46014be9bddb8bc906531c36>): florets wreath the face,
     * the subject's own eyes and mouth show through the opening, and a pale stalk hangs below the
     * chin as a neck.
     *
     * The art's face opening is centered at (220, 230) of a 440x620 viewport, i.e. 0.79 units above
     * the art's center — hence the negative [LensPlacement.upInUnits], which drops the art so that
     * opening lands on the eyes and the stalk falls under the chin. Sizing is driven by the opening
     * needing to clear brow-to-chin (~1.6 units), which makes the whole wreath 2.7 units wide.
     */
    Broccoli(
        displayName = "Broccoli",
        interaction = LensInteraction.SPIN,
        thumbnailRes = R.drawable.lens_broccoli,
        art = listOf(
            LensArt(
                drawableRes = R.drawable.lens_broccoli_art,
                placement = LensPlacement(
                    // The art is OPAQUE and covers the whole head — no window. Sized to swallow the
                    // head and hang a stalk below the chin; the opening approach that came before left
                    // a human nose and jaw on show, which read as a man in a costume rather than a
                    // broccoli character.
                    widthInUnits = 4.4f,
                    artAspect = 1.117f,
                    // -1.20, was -1.42. At -1.42 the wreath topped out at +1.04 against the +1.25
                    // crown, leaving ~0.2 units of the top of the head bare — found by
                    // `characterLensesCoverTheWholeHead` on 2026-08-16, never reported in the
                    // field. Raising it 0.22 clears the crown by +0.13 and needs NO resize: the
                    // stalk still hangs 1.91 units below the chin (it had 2.13 to give away).
                    upInUnits = -1.20f,
                    // Owner call 2026-08-26: the vegetable spins too (PRD-lens-hand-flick D3).
                    spin = SPIN_ON_A_HEAD,
                ),
            ),
        ),
        // The eyes and mouth are lifted off the subject and painted onto the vegetable. Positions
        // are on the broccoli's own face — high on the head, well above the stalk — and are held
        // steady in the face frame so the character keeps its proportions no matter what the
        // camera does to the subject's. They shift with the art's `upInUnits`, since they belong to
        // the broccoli's face rather than the human's.
        features = FeatureLayout(
            eyeSpacingInUnits = 0.54f,
            eyeUpInUnits = 0.44f,
            eyeWidthInUnits = 0.80f,
            mouthUpInUnits = -0.58f,
            mouthWidthInUnits = 1.30f,
        ),
    ),

    /**
     * Straightforward prop lens — and the clearest demonstration of the face frame, since glasses
     * must sit *on* the eye line and span a little wider than the head whatever the head does.
     */
    Sunglasses(
        displayName = "Shades",
        // A prop on the eyes; spinning it would spin it off the face. Drag-down is the backlog verb.
        interaction = LensInteraction.NONE,
        thumbnailRes = R.drawable.lens_sunglasses,
        art = listOf(
            LensArt(
                drawableRes = R.drawable.lens_sunglasses,
                placement = LensPlacement(
                    widthInUnits = 1.9f,
                    artAspect = 0.36f,
                    // Frames rest on the bridge, a touch above the pupil line.
                    upInUnits = 0.06f,
                ),
            ),
        ),
    ),

    /**
     * Pizza Face — an opaque **slice** worn as a head, crust across the brow and the tip below the
     * chin, with the subject's own eyes and mouth composited onto the cheese. Character pattern,
     * same as [Broccoli]. Source: owner-supplied `pizza-slice.jpg` (owner decision — a whole pie was
     * tried first and rejected).
     *
     * **A wedge is not a disc, so none of these numbers survived from the pie.** The art tapers from
     * a wide crust to a point, and a head tapers the same way — widest across the cheekbones,
     * narrowing to the jaw — which is why a downward wedge is a better head-cover than its area
     * suggests. It is also why sizing it off the center line alone would be meaningless.
     *
     * The width was solved against the reference table rather than eyeballed: model the head as the
     * ellipse those numbers describe (crown +1.25, chin −1.00, half-width 0.775 at the eye line),
     * then find the smallest `widthInUnits` whose *measured* silhouette covers that ellipse at every
     * height. At 3.45 the encoded wedge clears the head by at least **0.095 units everywhere**, with
     * its narrowest margin down at y = −0.64 where the slice is tapering fastest — nowhere near the
     * center line, and exactly the place a single-point check would have missed. Top lands at
     * **+1.40**, a 0.15-unit margin over the crown, matching [Football].
     */
    PizzaFace(
        displayName = "Pizza Face",
        interaction = LensInteraction.SPIN,
        thumbnailRes = R.drawable.lens_pizza,
        art = listOf(
            LensArt(
                drawableRes = R.drawable.lens_pizza_art,
                placement = LensPlacement(
                    // 3.95 / -0.55, not the original 3.45 / -0.18. The wedge's BOUNDING BOX always
                    // reached past the chin, which is exactly why a bounding-box check missed this:
                    // a wedge tapers to a point, so from y = -0.50 downward it was narrower than
                    // the jaw and the sides of the subject's lower face showed through. Re-solved
                    // against the measured alpha silhouette, not the box — smallest clearing width,
                    // +0.07 margin at the tightest point.
                    widthInUnits = 3.95f,
                    // Measured off the encoded 1024x972 asset per the A2 rule, not estimated.
                    artAspect = 0.949f,
                    // Drops the wedge so the crust sits above the brow and the tip falls past the chin.
                    upInUnits = -0.55f,
                    // Owner call 2026-08-26: the slice spins too (PRD-lens-hand-flick D3).
                    spin = SPIN_ON_A_HEAD,
                ),
            ),
        ),
        // Unchanged from the pie: these still land on cheese rather than crust, and the slice is
        // wider than they need at both heights — 1.28 units available at the eye row against the
        // 0.88 they span, and 0.76 at the mouth row against 0.58.
        features = FeatureLayout(
            eyeSpacingInUnits = 0.50f,
            eyeUpInUnits = 0.30f,
            eyeWidthInUnits = 0.75f,
            mouthUpInUnits = -0.50f,
            mouthWidthInUnits = 1.15f,
        ),
    ),

    /**
     * Football Head — the owner-supplied Wilson ball worn as a head, with the subject's own eyes
     * and mouth composited onto it. Character pattern, same as [Broccoli].
     *
     * The ball is photographed **lying flat**, long axis horizontal, and it is kept that way: the
     * Wilson script and NFL shield stay upright and readable, which is the whole point of the
     * owner's ruling that the marks stay. Rotating the art to stand the ball on end would have made
     * a head-shaped silhouette but laid both marks on their side.
     *
     * That orientation is what drives the width. A head is taller than it is wide (1.25 units from
     * the eye line to the crown, ~1.0 down to the chin) while the art is **wider** than it is tall
     * by the [LensPlacement.artAspect] below, so the only way an opaque ball covers brow-to-jaw is
     * to be wide. At 4.7 units the
     * encoded ellipse reaches 1.40 above the eye line — a 0.15-unit margin over the crown, matching
     * [PizzaFace] — and 1.20 below it, clearing the chin. Checked at the *sides* too, not just the
     * center line: at the ear (±0.775 units) the ellipse has narrowed to ±1.23 and still covers
     * crown and chin, which is the check a bounding box would have missed.
     */
    Football(
        displayName = "Football",
        interaction = LensInteraction.SPIN,
        thumbnailRes = R.drawable.lens_football,
        art = listOf(
            LensArt(
                drawableRes = R.drawable.lens_football_art,
                placement = LensPlacement(
                    // 5.60 / -0.26, not the original 4.7 / +0.10. At 4.7 the ellipse ended at
                    // -1.24 — just past the mouth — so the whole chin was bare (owner report,
                    // 2026-08-16; see the chin note in this file's header). Re-solved by measuring
                    // the encoded alpha silhouette against the corrected head at 0.05-unit steps:
                    // this is the SMALLEST width that clears it everywhere, with a +0.10 margin at
                    // the tightest point. A horizontal ball has to be wide to cover a vertical
                    // head — that is geometry, not a tuning preference.
                    widthInUnits = 5.60f,
                    // Measured off the encoded asset (1024x585), not estimated — the art carries a
                    // 1.5% transparent margin, so the ball itself is ~97% of the quad.
                    artAspect = 0.571f,
                    // Centered just below the eye line so the ellipse reaches +1.34 over the crown
                    // and -1.86 under the chin.
                    upInUnits = -0.26f,
                    // The first flickable layer (docs/PRD-lens-hand-flick.md): wave a hand past
                    // the ball and it spins about its own center, always landing back on a whole
                    // revolution, with the composited eyes and mouth hidden mid-spin (D2).
                    spin = SPIN_ON_A_HEAD,
                ),
            ),
        ),
        // The ball's own face. Placed on the upper-middle of the ellipse where the Wilson script
        // sits and above the NFL shield, which is exactly the overlap the owner predicted when he
        // ruled the marks stay. Wider mouth than [Broccoli]'s eye spacing suggests, because a
        // horizontal ball gives the character a broad face and a narrow mouth reads pinched on it.
        features = FeatureLayout(
            eyeSpacingInUnits = 0.58f,
            eyeUpInUnits = 0.45f,
            eyeWidthInUnits = 0.80f,
            mouthUpInUnits = -0.45f,
            mouthWidthInUnits = 1.30f,
        ),
    ),

    /**
     * Dog — floppy ears and a snout, worn as a **prop**: [features] stays null so the subject's own
     * face shows through between the ears. Same mechanism as [Sunglasses], and like it the one
     * drawable serves as both art and carousel thumbnail (the tray uses `ContentScale.Fit`, so wide
     * art letterboxes into the circle rather than being cropped).
     *
     * **The eyes are what set the ear positions.** An eye sits ~0.40 units off the center line, and
     * the head edge is at 0.775; an ear therefore has to live in the band between them — outboard
     * enough to miss the eye, inboard enough to still meet the head. These ears run from 0.59 to
     * 1.35 units, clearing each eye by 0.19. A first draft put the inner edges at 0.34 and sat them
     * squarely on top of both eyes; that is arithmetic, so it was caught without a face
     * (`swarm/tools/preview_lens.py`).
     *
     * **No drop-tongue yet — but the renderer can now do one.** This KDoc used to say the
     * capability did not exist; as of [TwistedTongue] all three pieces are in the tree and that
     * claim was stale:
     *
     * * a second sticker → [art] is a `List<LensArt>`;
     * * mouth-open detection → [LensAnchor.mouthOpenness], off `MOUTH_BOTTOM`, which
     *   `LANDMARK_MODE_ALL` already provides at no per-frame cost (so PRD §5.1's rejection of
     *   `CONTOUR_MODE` stands);
     * * animation state → [LensPhysics.ease], plus [LensPhysics.step] if it should swing.
     *
     * Adding one is now a catalogue edit: a `MOUTH`-anchored layer with
     * `mouthOpen = MouthOpenSpec(restFraction = 0f)` so it is hidden until the jaw drops. It is
     * left out only because it needs its own art, which this change did not draw.
     */
    Dog(
        displayName = "Dog",
        // Ears, nose and tongue on the subject's own face — a spin would tear the parts off it.
        interaction = LensInteraction.NONE,
        thumbnailRes = R.drawable.lens_dog,
        art = listOf(
            LensArt(
                drawableRes = R.drawable.lens_dog,
                placement = LensPlacement(
                    widthInUnits = 2.90f,
                    // Measured from the authored 290x213 viewport, which is exactly 100 viewport units
                    // per face unit at this width — so every coordinate in the vector is readable as
                    // anatomy. Keep the two in sync if either changes.
                    artAspect = 0.7345f,
                    // Ear tips reach 1.45 above the eye line, clearing the 1.25 crown; the snout lands
                    // on the subject's own nose and stops well clear of the mouth at -1.0.
                    upInUnits = 0.385f,
                ),
            ),
        ),
    ),

    /**
     * Twisted Tongue — bulging cartoon eyeballs and a long tongue lolling out of an open mouth.
     * Reverse-engineered from a DeepAR Studio project; the full derivation, the mapping from its
     * scene graph to these numbers, and what was deliberately dropped are in
     * `docs/guides/porting-third-party-ar-effects.md`.
     *
     * **The first lens with parts on different anatomy.** Every lens before it is one quad on the
     * face's center line. This one cannot be: an eyeball has to sit on each *eye* and the tongue has
     * to hang from the *mouth*, and no single quad tracks three landmarks. Hence [LensPlacement]'s
     * `anchor`, and hence [art] being a list.
     *
     * A **prop**, not a character — [features] stays null and the subject's own face shows around
     * the parts. The reference effect covers the mouth region with skin-toned geometry that samples
     * the camera for its color; that machinery is not here and is not needed, because the mouth
     * layer is lips-and-cavity rather than a patch of cheek, so it has no skin tone to match.
     *
     * ## The numbers
     *
     * Solved against the reference table at the top of this file, not eyeballed:
     *
     * * **Eyeballs.** A ball 0.62 units across, centered on each tracked eye. Eyes sit ±0.40 off the
     *   center line, so the inner edges land at 0.09 — an 0.18 gap across the bridge, which is the
     *   nearly-touching look of the reference — and the outer edges at 0.71, inside the 0.775 head
     *   edge. The ball is 200/220 of its square viewport (the rest is the contact shadow that makes
     *   it read as *proud of* the socket rather than painted on it), so `widthInUnits` is
     *   0.62 / 0.909 = 0.68.
     * * **Mouth.** 1.02 units wide against a 0.8-unit resting mouth, because it is drawn open.
     *   Dropped 0.10 so it spans -0.80 to -1.40 from the eye line: the jaw opens downward, so an
     *   open mouth is not centered on the closed mouth's corners.
     * * **Tongue.** 0.56 x 1.33 units, centered 0.565 below the mouth, which puts its root 0.10
     *   *above* the anchor — up inside the mouth, overlapping the teeth that hide it. The tip lands
     *   2.23 units below the eye line, well past the chin; that length *is* the joke.
     *
     * ## Why the teeth are a separate layer
     *
     * A tongue lolling out passes **over** the lower lip but **under** the upper teeth. That is a
     * three-way interleave, so the mouth cannot be one drawable: the cavity has to be behind the
     * tongue and the teeth in front of it. Hence `_mouth` (lips + cavity) and `_teeth` as two
     * layers sharing one placement, with the tongue sandwiched between them. Drawn as a single
     * sticker instead, the tongue reads as a pink shape stuck on a chin.
     *
     * The root stays covered at full swing: it sits 0.10 units from the pivot, so the 0.22-radian
     * limit moves it 0.022 sideways, putting the tongue's edge at 0.302 against the teeth's 0.408
     * half-width and the cavity's 0.414. Arithmetic, so it did not need a face to check.
     *
     * Draw order is cavity, tongue, teeth, then the eyes last because nothing may cover them.
     */
    TwistedTongue(
        displayName = "Twisted Tongue",
        // Eyeballs and a hanging tongue: they wobble on their own; spinning them reads as a glitch.
        interaction = LensInteraction.NONE,
        thumbnailRes = R.drawable.lens_twisted_tongue,
        art = listOf(
            LensArt(
                drawableRes = R.drawable.lens_twisted_tongue_mouth,
                placement = LensPlacement(
                    widthInUnits = 1.02f,
                    // Measured off the authored 340x200 viewport.
                    artAspect = 0.588f,
                    upInUnits = -0.10f,
                    anchor = LensAnchorPoint.MOUTH,
                ),
            ),
            LensArt(
                drawableRes = R.drawable.lens_twisted_tongue_tongue,
                placement = LensPlacement(
                    widthInUnits = 0.56f,
                    // Measured off the authored 160x380 viewport.
                    artAspect = 2.375f,
                    // Root 0.10 ABOVE the anchor, so it overlaps the teeth that hide it.
                    upInUnits = -0.565f,
                    anchor = LensAnchorPoint.MOUTH,
                    // The one part that hangs, so the one part that swings. The reference effect
                    // puts a pendulum on four tongue joints; this is the single-flap stand-in.
                    // 150 rings at ~1.95 Hz and 6.0 is a quarter of critical damping, so it bounces
                    // three or four times before settling — floppy, not springy. See LensPhysics.
                    wobble = WobbleSpec(
                        stiffness = 150f,
                        damping = 6f,
                        drive = 1.2f,
                        // 0.22 rad swings the tip 0.33 units — a big, readable arc that still keeps
                        // the root behind the teeth (see the KDoc above).
                        limitRadians = 0.22f,
                    ),
                    // Extends as the jaw drops. The reference effect has no trigger — its
                    // blendshape weight is a constant — so a restFraction of 0 would have been
                    // *unfaithful*, not merely different: the joke is a tongue that is always out.
                    // 0.55 keeps it clearly out at rest and nearly doubles it wide open, which
                    // adds the response without trading away the lens's identity.
                    mouthOpen = MouthOpenSpec(restFraction = 0.55f),
                ),
            ),
            // Same placement as the cavity above — one geometry contract, used twice, so the teeth
            // can never drift off the mouth they belong to.
            LensArt(
                drawableRes = R.drawable.lens_twisted_tongue_teeth,
                placement = LensPlacement(
                    widthInUnits = 1.02f,
                    artAspect = 0.588f,
                    upInUnits = -0.10f,
                    anchor = LensAnchorPoint.MOUTH,
                ),
            ),
            LensArt(
                drawableRes = R.drawable.lens_twisted_tongue_eye,
                placement = LensPlacement(
                    widthInUnits = 0.68f,
                    artAspect = 1f,
                    anchor = LensAnchorPoint.LEFT_EYE,
                ),
            ),
            LensArt(
                drawableRes = R.drawable.lens_twisted_tongue_eye,
                placement = LensPlacement(
                    widthInUnits = 0.68f,
                    artAspect = 1f,
                    anchor = LensAnchorPoint.RIGHT_EYE,
                ),
            ),
        ),
    ),

    /**
     * Elvis — photoreal gold aviator shades and pompadour. Two-layer BITMAP prop lens using
     * chroma-keyed 3D renders, not vector XML.
     *
     * A **prop**, not a character — [features] stays null and the subject's own face shows through.
     * Two layers on the FACE anchor: hair (with sideburns integrated) drawn first, then shades on top.
     *
     * ## The assets
     *
     * **BITMAP workflow** (Snapchat pattern): photoreal 3D renders baked to front-on PNGs, chroma-keyed
     * from green (#00FF00) to alpha, saved as WebP in `drawable-nodpi/`. No 3D renderer, no vector XML.
     *
     * * **lens_elvis_pompadour_art.webp** — U-wig: glossy black quiff + baked sideburns + face hole.
     *   974×980 PNG (aspect 1.0062). Front view, high volume above crown, sideburns down the sides,
     *   face hole in lower-center exposes eyes and mouth. Sized generously (2.6 units) so sideburns
     *   land on cheeks, not forehead — smaller quad parks the burns too high.
     * * **lens_elvis_shades_art.webp** — Gold aviators. 1420×504 PNG (aspect 0.3549). PBR metal
     *   frames (#C9A227-range), double bridge, brown gradient lenses with real reflections.
     *
     * ## The numbers
     *
     * Solved against the reference table at the top of this file, not eyeballed. upInUnits is the
     * quad CENTER, derived from (top + bottom) / 2. artAspect measured from the processed PNG's
     * opaque bounds (height / width after trim): 980/974 = 1.0062 (hair), 504/1420 = 0.3549 (shades).
     *
     * * **Hair.** 2.6 units wide (generous, following Broccoli's face-hole pattern: 4.4 units for
     *   that larger piece). Height 2.6 × 1.0062 = 2.616 units. Centered +0.70 → top +2.008 (well
     *   above +1.25 crown), bottom −0.608 (face hole exposes eyes at 0, mouth at −1.00). Sideburns
     *   in the PNG's sides land on cheeks, not pupils.
     * * **Shades.** 2.1 units wide (overhang past Sunglasses 1.9). Height 2.1 × 0.3549 = 0.745 units.
     *   Centered +0.06 on bridge → top +0.433, bottom −0.313. Contains y=0 (eye line), clears mouth.
     *
     * Draw order: hair first (back), shades LAST (front) so glasses sit over everything.
     */
    Elvis(
        displayName = "Elvis",
        // A wig and shades worn on the real face — nothing here is a thing you would spin.
        interaction = LensInteraction.NONE,
        thumbnailRes = R.drawable.lens_elvis,
        art = listOf(
            // Hair with integrated sideburns (U-wig with face hole), drawn first.
            LensArt(
                drawableRes = R.drawable.lens_elvis_pompadour_art,
                placement = LensPlacement(
                    // Generous width so sideburns land on cheeks. Measured PNG: 974×980.
                    widthInUnits = 2.6f,
                    // Measured: 980 / 974 = 1.0062.
                    artAspect = 1.0062f,
                    // Center +0.70 → top +2.008 (above crown), bottom −0.608 (face hole clears eyes/mouth).
                    upInUnits = 0.70f,
                ),
            ),
            // Shades drawn last, sit on eye line.
            LensArt(
                drawableRes = R.drawable.lens_elvis_shades_art,
                placement = LensPlacement(
                    // Wider than Sunglasses (1.9) for aviator overhang. Measured PNG: 1420×504.
                    widthInUnits = 2.1f,
                    // Measured: 504 / 1420 = 0.3549.
                    artAspect = 0.3549f,
                    // Rests on bridge, same as Sunglasses. Spans +0.433 to −0.313 (contains y=0).
                    upInUnits = 0.06f,
                ),
            ),
        ),
    ),

    /**
     * Cowboy — a felt hat on the crown and a handlebar mustache on the upper lip, from an
     * owner-supplied reference. A **prop**, like [Elvis]: [features] stays null so the subject's
     * own face carries the expression and only the two pieces are added.
     *
     * The art is **original, generated in this repo**, not the reference itself — that arrived as
     * a screenshot of a stock silhouette, and PRD §11.2 is explicit that a public Apache 2.0 repo
     * ships only art it can redistribute. What was taken from it is the *shape*: both silhouettes
     * were traced off the reference at 5% column steps (per-column top and bottom edges) and then
     * made symmetric, since the reference is drawn slightly three-quarter on and a lens worn on a
     * face has to be square to it. Tracing is what fixed the mustache — drawn by eye first, its
     * philtrum notch and center arch both ran far too deep, and it read as two separate blobs.
     *
     * ## The assets
     *
     * Photoreal WebP, the [Elvis] pattern — but where Elvis's binaries are opaque, these are
     * **rendered from committed source** by `swarm/tools/render_lens_art.py`: the traced
     * silhouettes live in `swarm/art/`, and the tool turns each region into a lit, textured
     * surface (the crown a creased cylinder, the brim a rolled edge on a saddle, the mustache
     * ~34k individual hairs through a flow field). Re-run it rather than editing a `.webp`.
     *
     * * **lens_cowboy_hat_art.webp** — 1024x492, so [LensPlacement.artAspect] is 492/1024.
     * * **lens_cowboy_mustache_art.webp** — 1024x413, so `artAspect` is 413/1024.
     * * **lens_cowboy.webp** — the 320x320 carousel chip, composed from those two so it cannot
     *   drift away from what the lens paints on a face.
     *
     * The flat vector this shipped with first read as clip art beside a photographic face on the
     * owner's 2026-09-03 capture; the geometry below is unchanged from it.
     *
     * ## The two anchors, and why they are two
     *
     * A hat belongs to the *skull* and a mustache belongs to the *mouth*, and those move
     * independently: a jaw drops without the crown moving. So this is not one quad — the hat is on
     * `FACE` and the mustache on [LensAnchorPoint.MOUTH], the same reason [TwistedTongue] splits.
     *
     * ## The numbers
     *
     * Solved against the reference table at the top of this file. `artAspect` is the authored
     * viewport's own ratio in both cases, so every coordinate inside the drawables is readable as
     * anatomy rather than as art-space.
     *
     * * **Hat.** 3.6 units across a 1.55-unit head — 2.3x, which is a real cowboy hat's ratio, and
     *   the crown at 0.47 of that width clears the skull. The 750x360 viewport is then 208.33 units
     *   per face unit. Centred +1.20 puts the brim's front dip (viewport y=360) at **+0.34**, just
     *   clear of the +0.30 brow, and the crown's shoulders (y=18) at **+2.06** — 0.81 units of hat
     *   above the +1.25 crown, which is a real crown height. The brim's inner line (y=324) lands at
     *   +0.51 — 0.74 units *below* the skull's crown, so the hat is worn down over the head rather
     *   than balanced on top of it.
     *
     *   **This was +1.48 until the first hardware capture (owner, 2026-09-03).** At that height the
     *   dip sat at +0.62 and 0.39 units of bare forehead showed between the brim and the brow, so
     *   the hat read as held above the head rather than worn. The placement math was correct — it
     *   was tuned against an assumed 0.35 brow, and the real one is 0.30. That row is now in the
     *   header table so the next lens does not have to rediscover it.
     * * **Mustache.** 1.4 units was tried first and read as a pencil mustache on the schematic
     *   head; 1.6 is the handlebar the reference draws, and it puts the tips at ±0.80 against the
     *   0.775 head half-width — at the cheek edge, where a handlebar's tips belong. The 620x250
     *   viewport is 387.5 units per face unit. Centred +0.01 **on the mouth**, its top edge (y=1)
     *   lands at +0.33 — the nose base — and its centre arch (y=80) at **+0.12**, so the lip line
     *   stays clear and the wearer can still be seen talking. The lobes (y=248) reach −0.31,
     *   hanging beside the mouth corners rather than over them, and well clear of the −0.75 chin.
     *
     * Draw order is hat then mustache; they are 1.00 units apart at the closest, so neither
     * overlaps the other and the subject's eyes, nose and cheeks show between them.
     *
     */
    Cowboy(
        displayName = "Cowboy",
        // Both pieces are worn on the real face: a spin would throw the hat off the head and tear
        // the mustache off the lip. Same answer as Elvis, for the same reason.
        interaction = LensInteraction.NONE,
        thumbnailRes = R.drawable.lens_cowboy,
        art = listOf(
            LensArt(
                drawableRes = R.drawable.lens_cowboy_hat_art,
                placement = LensPlacement(
                    widthInUnits = 3.6f,
                    // The encoded asset's own ratio: 492 / 1024.
                    artAspect = 0.48047f,
                    // 1.20, was 1.48. At 1.48 the brim's dip landed at +0.62 and the hat read as
                    // held ABOVE the head rather than worn: measured off the owner's 2026-09-03
                    // capture, 0.39 units of bare forehead showed between the brim and the brow.
                    // The placement was doing exactly what it was told; the input was wrong. This
                    // drops it 0.28 units so the dip sits at +0.34, just clear of the +0.30 brow.
                    upInUnits = 1.20f,
                ),
            ),
            LensArt(
                drawableRes = R.drawable.lens_cowboy_mustache_art,
                placement = LensPlacement(
                    widthInUnits = 1.6f,
                    // The encoded asset's own ratio: 413 / 1024.
                    artAspect = 0.40332f,
                    upInUnits = 0.01f,
                    // The mouth, not the eye line — a jaw drops without the hat moving.
                    anchor = LensAnchorPoint.MOUTH,
                ),
            ),
        ),
    ),
    ;

    /**
     * Whether a hand can flick this lens — the one switch that turns the hand tracker on
     * (`docs/PRD-lens-hand-flick.md` D5). Reads the declared [interaction]; `LensPhysicsTest`
     * guarantees a `SPIN` lens really carries a spin-capable layer, so nothing outside this file
     * ever names a lens.
     */
    val isFlickable: Boolean
        get() = interaction == LensInteraction.SPIN
}

/**
 * The interaction question every lens answers (owner rule, 2026-08-26): some art makes sense to
 * spin under a hand wave and some does not, so the decision is made per lens, on purpose, never
 * by omission — which is why [Lens.interaction] has no default.
 */
enum class LensInteraction {
    /** The lens only tracks the face. */
    NONE,

    /** A hand waved across the lens spins its spin-capable layer — `docs/PRD-lens-hand-flick.md`. */
    SPIN,
}

/**
 * The one tuned spin for a head-sized character layer (Football, Broccoli, Pizza Face). Shared on
 * purpose: the three quads are 4–5.6 face units wide, so one feel fits all and a retune is one
 * edit.
 *
 * Tuning arithmetic, not magic: total travel ≈ ω₀ × halfLife / ln 2 ≈ 0.87 × ω₀ radians. A
 * comfortable wave (≈7 units/s at a ≈2-unit lever) lands ≈0.9 revolutions at this gain, a fast one
 * ≈2; the cap bounds the hardest at ≈3.5. `minHandSpeed` is ≈20 cm/s: a wave clears it, a hand
 * adjusting hair does not (PRD §3.3). The catalogue-driven `LensPhysicsTest` pins these as
 * properties, so the feel can be retuned here freely without weakening the guarantees.
 */
private val SPIN_ON_A_HEAD = SpinSpec(
    gain = 1.8f,
    frictionHalfLifeSeconds = 0.6f,
    maxAngularVelocity = 25f,
    minHandSpeed = 3f,
)

/** A lens's sticker: the drawable to composite and where it sits in the face frame. */
data class LensArt(
    @param:DrawableRes val drawableRes: Int,
    val placement: LensPlacement,
)
