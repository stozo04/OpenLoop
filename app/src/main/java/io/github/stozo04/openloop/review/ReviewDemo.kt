package io.github.stozo04.openloop.review

/**
 * Intent extra that makes **every** saved loop ask for a review, for that launch. Debug builds only —
 * the read is guarded by `BuildConfig.DEBUG` in `MainActivity`, so R8 strips the branch from release.
 * Mirrors `EXTRA_DEMO_UPDATE`.
 *
 * The gate is a lifetime counter, so once an install passes the cadence there is no way to reach the
 * ask again without rewriting DataStore by hand. This is that, without the hex editor:
 *
 * ```
 * adb shell am start -n io.github.stozo04.openloop/.MainActivity --ez openloop.demoReview true
 * ```
 *
 * It forces the *ask*, not the card: on a locally-installed build (`installer=null`) Play still
 * refuses to draw anything, so the pass condition is `requestInAppReview` in logcat. Seeing the real
 * card needs the internal test track.
 */
const val EXTRA_DEMO_REVIEW: String = "openloop.demoReview"
