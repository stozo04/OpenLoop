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
 * | eye line down to the mouth | **1.00** (the definition) |
 * | eye line down to the **chin** | **1.75** |
 * | mouth width, at rest | 0.8 |
 *
 * These are **measured off a real tracked face**, not assumed. An earlier version reasoned them
 * from published head statistics (treating one unit as the interpupillary distance) and every lens
 * came out ~20% oversized — eye-to-mouth is the larger of the two spans. If a lens ever needs
 * resizing, re-measure against this table rather than nudging a lens in isolation.
 *
 * ## The chin row was wrong until 2026-08-16, and it shipped two bugs
 *
 * This table used to say **chin = 1.00** — which is the same number as the mouth, and that
 * coincidence is the tell: the row was the *mouth* wearing the chin's label. A real jaw runs about
 * another **0.75 units** below the mouth (stomion→menton ≈ 50 mm against a ≈ 67 mm pupil→stomion
 * face unit), putting the chin at **-1.75**.
 *
 * [PizzaFace] and [Football] were both sized against the wrong number, so both stopped at roughly
 * the mouth and left the subject's real chin — and the corners of their real mouth — on show below
 * the art. That is what "the mouth looks duplicated" was: the character's composited mouth *and*
 * the subject's own, at once. [Broccoli] was never affected because it hangs a stalk far below the
 * jaw and covers to -3.88 regardless.
 *
 * `LensAnchorTest.characterLensesCoverTheWholeHead` now asserts crown-to-chin coverage for every
 * character lens, so the class of bug cannot come back silently.
 *
 * Because they are ratios of the face to itself, one set of numbers holds for every face at every
 * distance and angle — there is nothing here to re-tune per device.
 */
enum class Lens(
    val displayName: String,
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
     * `twisted-tounge/GUIDE.md`.
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
}

/** A lens's sticker: the drawable to composite and where it sits in the face frame. */
data class LensArt(
    @param:DrawableRes val drawableRes: Int,
    val placement: LensPlacement,
)
