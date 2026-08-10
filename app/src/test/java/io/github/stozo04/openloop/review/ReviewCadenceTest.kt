package io.github.stozo04.openloop.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The ask cadence, as a table. Pure function, so no Robolectric.
 *
 * This is the product rule, not an implementation detail: ask once the user has real experience,
 * then keep offering, because Play never tells us whether they reviewed and silently withholds the
 * card from anyone who already did.
 */
class ReviewCadenceTest {

    @Test
    fun `asks on the 3rd save and then every 10th, nothing in between`() {
        // 6, 9 and 12 must be absent: the obvious "% 3" rewrite of the old `== 3` gate would fire
        // there and ask three times per Play quota window instead of roughly once.
        assertEquals(listOf(3, 10, 20, 30, 40), (0..45).filter { shouldAskForReview(it) })
    }

    @Test
    fun `a zero tally never asks`() {
        // The counter is read back from DataStore, which returns 0 on a corrupted or missing file.
        assertFalse(shouldAskForReview(0))
    }
}
