package io.github.stozo04.openloop.review

import android.app.Activity
import android.util.Log
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewManager

/**
 * Saved loops after which OpenLoop asks for a rating — enough repeat use to have an opinion worth
 * publishing. Fires on exactly this save and never again — see
 * [io.github.stozo04.openloop.ui.BoomerangEvent.RequestReview] for the where and why.
 *
 * ponytail: one lifetime ask. Three windows lose it — process death between the save and the share
 * sheet closing, a sideloaded install where Play no-ops the card, and the user starting a recording
 * during Play's request round trip (see [launchInAppReview]). Dropping the ask is always better than
 * covering the viewfinder; if it ever matters, add a persisted `hasAskedForReview` flag, switch the
 * gate to `>=`, and re-arm instead of consuming on a skip.
 */
const val REVIEW_AFTER_SAVED_LOOPS: Int = 3

/**
 * Show Play's in-app review card. Either call may decline to do anything (off-Play, quota) and the
 * API never reports whether the user rated, so there is nothing to branch on and nothing to retry.
 *
 * Google's call-site rules: no question before or during the card, no "Rate us" button wired to
 * this, nothing drawn over it. https://developer.android.com/guide/playcore/in-app-review
 *
 * [isIdle] is re-checked *after* the request resolves and before the card goes up: Play's round trip
 * is not instant, and a card that lands on a recording the user started in the meantime destroys the
 * take. Dropping the ask is the better failure.
 */
suspend fun launchInAppReview(manager: ReviewManager, activity: Activity, isIdle: () -> Boolean) {
    try {
        val reviewInfo = manager.requestReview()
        if (!isIdle()) {
            Log.i(TAG, "Skipping the review card — the user is no longer idle")
            return
        }
        manager.launchReview(activity, reviewInfo)
    } catch (e: ReviewException) {
        // Routine off-Play / no-network outcome, exactly like AppUpdateController's check().
        Log.w(TAG, "In-app review unavailable (errorCode=${e.errorCode})", e)
    }
}

private const val TAG = "InAppReview"
