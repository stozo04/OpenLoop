package io.github.stozo04.openloop.review

import android.app.Activity
import android.os.Looper
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.testing.FakeReviewManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Locks the two-call Play sequence in [launchInAppReview] against Google's own test double.
 *
 * [FakeReviewManager] simulates no UI, so "did a card appear" isn't observable — [CountingReviewManager]
 * wraps it to record whether `launchReviewFlow` was reached at all, which is what the `isIdle` guard
 * decides. Resuming at all is also worth asserting: a wrong ktx overload or a Task listener that never
 * fires would hang the coroutine here, and in production would hang the event collector that drives
 * every snackbar. Robolectric supplies the `Context` and `Activity` the API requires and a plain JVM
 * test can't (same reason `AppUpdateControllerTest` had to reach for mockk instead).
 *
 * Whether a card is really shown depends on Play, the account, and the quota — verifiable only on
 * the internal test track. See the PR's manual QA checklist.
 */
@RunWith(RobolectricTestRunner::class)
class InAppReviewRobolectricTest {

    /** Delegates to [FakeReviewManager] (the only source of a real [ReviewInfo]) and counts launches. */
    private class CountingReviewManager(private val delegate: ReviewManager) : ReviewManager {
        var launches = 0
            private set

        override fun requestReviewFlow(): Task<ReviewInfo> = delegate.requestReviewFlow()

        override fun launchReviewFlow(activity: Activity, reviewInfo: ReviewInfo): Task<Void> {
            launches++
            return delegate.launchReviewFlow(activity, reviewInfo)
        }
    }

    /** A device with no usable Play Store: the request Task fails with something that isn't a ReviewException. */
    private class FailingReviewManager : ReviewManager {
        override fun requestReviewFlow(): Task<ReviewInfo> =
            Tasks.forException(IllegalStateException("no Play Store on this device"))

        override fun launchReviewFlow(activity: Activity, reviewInfo: ReviewInfo): Task<Void> =
            throw AssertionError("launchReviewFlow must not be reached when the request failed")
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** Runs [launchInAppReview] to completion, asserting it resumed rather than hanging or throwing. */
    private fun runReview(manager: ReviewManager, activity: Activity, isIdle: () -> Boolean) {
        var completed = false
        runTest {
            // Unconfined so the coroutine resumes inline on whichever thread delivers the Play Task
            // callback; Robolectric's paused main looper only drains when we idle it below.
            val job = launch(Dispatchers.Unconfined) {
                launchInAppReview(manager = manager, activity = activity, isIdle = isIdle)
                completed = true
            }
            shadowOf(Looper.getMainLooper()).idle()
            job.join()
        }
        assertTrue("launchInAppReview never resumed — the review Task didn't complete", completed)
    }

    @Test
    fun `launchInAppReview shows the card when the user is still idle`() {
        val activity = activity()
        val manager = CountingReviewManager(FakeReviewManager(activity))

        runReview(manager, activity, isIdle = { true })

        assertEquals(1, manager.launches)
    }

    /**
     * The regression this guard exists for: Play's request round trip is not instant, so the user can
     * start recording between the ask being armed and the card going up. A card on top of a live
     * recording destroys the take — dropping the ask is the better failure (Issue #121).
     */
    @Test
    fun `launchInAppReview drops the card when the user is no longer idle`() {
        val activity = activity()
        val manager = CountingReviewManager(FakeReviewManager(activity))

        runReview(manager, activity, isIdle = { false })

        assertEquals(
            "the card must never launch once the user has left the resting screens",
            0,
            manager.launches,
        )
    }

    /**
     * A non-`ReviewException` failure must not escape. This runs on the host's single event collector
     * *ahead of* the "Saved" snackbar, so anything thrown here kills the collector and every snackbar
     * after it. The user would lose their save confirmation to a rating ask that couldn't be shown.
     */
    @Test
    fun `launchInAppReview swallows a non-ReviewException failure instead of killing the collector`() {
        runReview(FailingReviewManager(), activity(), isIdle = { true })
    }
}
