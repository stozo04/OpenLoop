package io.github.stozo04.openloop.review

import android.app.Activity
import android.util.Log
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewManager

/**
 * Saved loops after which OpenLoop asks for a rating. Three is "this person came back and finished
 * the job more than once" — enough experience to have an opinion worth publishing.
 *
 * The ask fires on **exactly** this save and never again (`== ` in `OpenLoopViewModel`, over a
 * monotonic DataStore counter). Google's quota would silently swallow repeat calls anyway, and
 * "don't prompt excessively" is explicit guidance.
 *
 * ponytail: one lifetime ask. Two windows lose it — process death between the save and the share
 * sheet closing, and a sideloaded install where Play no-ops the card. If that ever matters, add a
 * persisted `hasAskedForReview` flag and switch the gate to `>=`.
 */
const val REVIEW_AFTER_SAVED_LOOPS: Int = 3

/**
 * Show Play's in-app review card.
 *
 * Two Play calls, both of which can decline to do anything: `requestReview` fails off-Play, and
 * `launchReview` returns normally whether or not a card was shown — the API deliberately never
 * reports whether the user rated. So there is nothing to branch on and nothing to retry; this
 * returns Unit and the caller carries on regardless.
 *
 * Design rules the *call site* has to keep (Google's, not ours): no question before or during the
 * card, no "Rate us" button wired to this, and nothing drawn over it.
 * https://developer.android.com/guide/playcore/in-app-review
 */
suspend fun launchInAppReview(manager: ReviewManager, activity: Activity) {
    try {
        manager.launchReview(activity, manager.requestReview())
    } catch (e: ReviewException) {
        // Routine off-Play / no-network outcome, exactly like AppUpdateController's check() —
        // log at WARN, never Crashlytics, never user-visible.
        Log.w(TAG, "In-app review unavailable (errorCode=${e.errorCode})", e)
    }
}

private const val TAG = "InAppReview"
