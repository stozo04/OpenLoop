package io.github.stozo04.openloop.camera.lens

import androidx.annotation.DrawableRes
import io.github.stozo04.openloop.R

/**
 * The lens catalogue.
 *
 * Adding a lens is meant to be *only* this: one entry here plus its art. Nothing in
 * `LensSurfaceProcessor`, `CameraManager`, or the UI names an individual lens — the carousel
 * renders `Lens.entries` and the renderer switches on [art] / [warp] being present.
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
 * | mouth width, at rest | 0.8 |
 *
 * These are **measured off a real tracked face**, not assumed. An earlier version reasoned them
 * from published head statistics (treating one unit as the interpupillary distance) and every lens
 * came out ~20% oversized — eye-to-mouth is the larger of the two spans. If a lens ever needs
 * resizing, re-measure against this table rather than nudging a lens in isolation.
 *
 * Because they are ratios of the face to itself, one set of numbers holds for every face at every
 * distance and angle — there is nothing here to re-tune per device.
 */
enum class Lens(
    val displayName: String,
    @param:DrawableRes val thumbnailRes: Int,
    val art: LensArt?,
    val warp: WarpSpec?,
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
     * The art's face opening is centred at (220, 230) of a 440x620 viewport, i.e. 0.79 units above
     * the art's centre — hence the negative [LensPlacement.upInUnits], which drops the art so that
     * opening lands on the eyes and the stalk falls under the chin. Sizing is driven by the opening
     * needing to clear brow-to-chin (~1.6 units), which makes the whole wreath 2.7 units wide.
     */
    Broccoli(
        displayName = "Broccoli",
        thumbnailRes = R.drawable.lens_broccoli,
        art = LensArt(
            drawableRes = R.drawable.lens_broccoli_art,
            placement = LensPlacement(
                // The art is OPAQUE and covers the whole head — no window. Sized to swallow the
                // head and hang a stalk below the chin; the opening approach that came before left
                // a human nose and jaw on show, which read as a man in a costume rather than a
                // broccoli character.
                widthInUnits = 4.4f,
                artAspect = 1.117f,
                upInUnits = -1.42f,
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
        warp = null,
    ),

    /**
     * Straightforward prop lens — and the clearest demonstration of the face frame, since glasses
     * must sit *on* the eye line and span a little wider than the head whatever the head does.
     */
    Sunglasses(
        displayName = "Shades",
        thumbnailRes = R.drawable.lens_sunglasses,
        art = LensArt(
            drawableRes = R.drawable.lens_sunglasses,
            placement = LensPlacement(
                widthInUnits = 1.9f,
                artAspect = 0.36f,
                // Frames rest on the bridge, a touch above the pupil line.
                upInUnits = 0.06f,
            ),
        ),
        warp = null,
    ),

    /**
     * The warp lens: a radial bulge on the mouth. Deforms the camera pixels themselves, so it
     * carries no art — [thumbnailRes] is a carousel icon only.
     */
    BigMouth(
        displayName = "Big Mouth",
        thumbnailRes = R.drawable.lens_big_mouth,
        art = null,
        // Radius a little over a mouth-width (~0.8 units) so the bulge takes in the lips and the
        // jaw around them; strength high enough to read as a caricature rather than a lens flaw.
        warp = WarpSpec(radiusInUnits = 1.0f, strength = 0.78f),
    ),

    /** Two radial bulges centred on the tracked eyes; the thumbnail is carousel-only. */
    BugEyes(
        displayName = "Bug Eyes",
        thumbnailRes = R.drawable.lens_bug_eyes,
        art = null,
        warp = WarpSpec(
            radiusInUnits = 0.36f,
            strength = 0.75f,
            target = WarpTarget.EYES,
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
     * suggests. It is also why sizing it off the centre line alone would be meaningless.
     *
     * The width was solved against the reference table rather than eyeballed: model the head as the
     * ellipse those numbers describe (crown +1.25, chin −1.00, half-width 0.775 at the eye line),
     * then find the smallest `widthInUnits` whose *measured* silhouette covers that ellipse at every
     * height. At 3.45 the encoded wedge clears the head by at least **0.095 units everywhere**, with
     * its narrowest margin down at y = −0.64 where the slice is tapering fastest — nowhere near the
     * centre line, and exactly the place a single-point check would have missed. Top lands at
     * **+1.40**, a 0.15-unit margin over the crown, matching [Football].
     */
    PizzaFace(
        displayName = "Pizza Face",
        thumbnailRes = R.drawable.lens_pizza,
        art = LensArt(
            drawableRes = R.drawable.lens_pizza_art,
            placement = LensPlacement(
                widthInUnits = 3.45f,
                // Measured off the encoded 1024x972 asset per the A2 rule, not estimated.
                artAspect = 0.949f,
                // Drops the wedge so the crust sits above the brow and the tip falls past the chin.
                upInUnits = -0.18f,
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
        warp = null,
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
     * centre line: at the ear (±0.775 units) the ellipse has narrowed to ±1.23 and still covers
     * crown and chin, which is the check a bounding box would have missed.
     */
    Football(
        displayName = "Football",
        thumbnailRes = R.drawable.lens_football,
        art = LensArt(
            drawableRes = R.drawable.lens_football_art,
            placement = LensPlacement(
                widthInUnits = 4.7f,
                // Measured off the encoded asset (1024x585), not estimated — the art carries a
                // 1.5% transparent margin, so the ball itself is ~97% of the quad.
                artAspect = 0.571f,
                // Just above the eye line: the ball's centre sits between brow and crown so the
                // taller half of the head gets the deeper half of the ellipse.
                upInUnits = 0.10f,
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
        warp = null,
    ),

    /**
     * Dog — floppy ears and a snout, worn as a **prop**: [features] stays null so the subject's own
     * face shows through between the ears. Same mechanism as [Sunglasses], and like it the one
     * drawable serves as both art and carousel thumbnail (the tray uses `ContentScale.Fit`, so wide
     * art letterboxes into the circle rather than being cropped).
     *
     * **The eyes are what set the ear positions.** An eye sits ~0.40 units off the centre line, and
     * the head edge is at 0.775; an ear therefore has to live in the band between them — outboard
     * enough to miss the eye, inboard enough to still meet the head. These ears run from 0.59 to
     * 1.35 units, clearing each eye by 0.19. A first draft put the inner edges at 0.34 and sat them
     * squarely on top of both eyes; that is arithmetic, so it was caught without a face
     * (`swarm/tools/preview_lens.py`).
     *
     * Deliberately no drop-tongue. The canonical version animates a tongue on mouth-open, which
     * needs a second sticker plus mouth-open detection plus animation state — a capability this
     * renderer does not have and this change did not add.
     */
    Dog(
        displayName = "Dog",
        thumbnailRes = R.drawable.lens_dog,
        art = LensArt(
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
        warp = null,
    ),
}

/** A lens's sticker: the drawable to composite and where it sits in the face frame. */
data class LensArt(
    @param:DrawableRes val drawableRes: Int,
    val placement: LensPlacement,
)
