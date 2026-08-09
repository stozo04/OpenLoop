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
}

/** A lens's sticker: the drawable to composite and where it sits in the face frame. */
data class LensArt(
    @param:DrawableRes val drawableRes: Int,
    val placement: LensPlacement,
)
