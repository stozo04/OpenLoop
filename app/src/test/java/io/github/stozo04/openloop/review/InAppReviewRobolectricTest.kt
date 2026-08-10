package io.github.stozo04.openloop.review

import android.app.Activity
import android.os.Looper
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
 * [FakeReviewManager] doesn't simulate any UI — it hands back a fake `ReviewInfo` and reports
 * success — so the only thing worth asserting is that the suspend function actually resumes and
 * returns. That is not free: a wrong ktx overload or a Task listener that never fires would hang
 * the coroutine here, and in production would hang the event collector that also drives every
 * snackbar. Robolectric supplies the `Context` and `Activity` the API requires and a plain JVM
 * test can't (same reason `AppUpdateControllerTest` had to reach for mockk instead).
 *
 * Whether a card is really shown depends on Play, the account, and the quota — verifiable only on
 * the internal test track. See the PR's manual QA checklist.
 */
@RunWith(RobolectricTestRunner::class)
class InAppReviewRobolectricTest {

    @Test
    fun `launchInAppReview completes against Google's fake review manager`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var completed = false

        // Unconfined so the coroutine resumes inline on whichever thread delivers the Play Task
        // callback; Robolectric's paused main looper only drains when we idle it below.
        val job = launch(Dispatchers.Unconfined) {
            launchInAppReview(manager = FakeReviewManager(activity), activity = activity)
            completed = true
        }
        shadowOf(Looper.getMainLooper()).idle()
        job.join()

        assertTrue("launchInAppReview never resumed — the review Task didn't complete", completed)
    }

    @Test
    fun `the gate is the third saved loop`() {
        // Guards the constant itself: the ViewModel test asserts "fires on the Nth save" against
        // this same symbol, so both would still pass if the value silently drifted to 1.
        assertEquals(3, REVIEW_AFTER_SAVED_LOOPS)
    }
}
