package io.github.stozo04.openloop.review

import android.app.Activity
import android.util.Log
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewManager
import kotlin.coroutines.cancellation.CancellationException

/** Saved loops before the first ask — enough repeat use to have an opinion worth publishing. */
const val REVIEW_FIRST_ASK_AFTER_SAVES: Int = 3

/**
 * Saves between re-asks after the first one. Play's quota is the real throttle — documented only as
 * "time-bound", roughly under a month, and explicitly subject to change — so this only has to be
 * loose enough that we don't burn every ask inside one spent window. Ten saves is about a month of
 * regular use.
 */
const val REVIEW_REASK_EVERY_SAVES: Int = 10

/**
 * Whether the save that brought the lifetime tally to [savedLoopCount] should ask for a rating.
 *
 * **We re-ask because the API cannot tell us whether the user reviewed.** Google's words: *"The API
 * does not indicate whether the user reviewed or not, or even whether the review dialog was shown"*,
 * and *"do not change your app's behavior based on the completion of the review flow"*. So there is
 * no reviewed-or-not signal to branch on, and building one is out of the question.
 *
 * Play arbitrates instead, and it already does both halves of what we'd want:
 * - **Reviewed already** → Play withholds the card (having no existing review is a documented
 *   precondition for it to appear).
 * - **Didn't review** → the quota window reopens, so a later ask can land.
 *
 * Asking exactly once left everyone who dismissed the card unreachable forever, which is backwards.
 * An ask Play refuses is a silent ~300 ms no-op, so a missed one costs far more than a wasted one.
 *
 * ponytail: a plain modulo over saves, not a schedule that remembers what Play did — it can't know.
 * If asks ever need spacing by *time* rather than by saves, persist the last-ask timestamp.
 */
fun shouldAskForReview(savedLoopCount: Int): Boolean =
    savedLoopCount == REVIEW_FIRST_ASK_AFTER_SAVES ||
        (savedLoopCount > REVIEW_FIRST_ASK_AFTER_SAVES &&
            savedLoopCount % REVIEW_REASK_EVERY_SAVES == 0)

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
    } catch (e: CancellationException) {
        throw e // never swallow structured-concurrency cancellation
    } catch (e: Exception) {
        // Deliberately broad. This runs on the host's single event collector, ahead of the "Saved"
        // snackbar — anything that escapes here kills that collector and every snackbar after it,
        // costing the user their save confirmation to salvage an ask we don't even need. Play's own
        // Task can surface non-ReviewException failures (no Play Store on the device, a dead
        // service binding), so an unshowable ask has to degrade silently.
        Log.w(TAG, "In-app review failed unexpectedly", e)
    }
}

private const val TAG = "InAppReview"
