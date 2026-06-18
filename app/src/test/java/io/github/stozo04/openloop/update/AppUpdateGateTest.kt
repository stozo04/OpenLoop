package io.github.stozo04.openloop.update

import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 verification — pure-function coverage of the FLEXIBLE update gate. Documented in
 * `docs/active/in-app-updates/IMPLEMENTATION.md` §Verification.
 *
 * Every input is a primitive; no Play `AppUpdateInfo` is constructed. The matrix exercises
 * availability × staleness × FLEXIBLE-allowed × bypass.
 */
class AppUpdateGateTest {

    @Test
    fun `prompts when update available, stale enough, flexible allowed, no bypass`() {
        assertTrue(
            shouldPromptForFlexibleUpdate(
                availability = UpdateAvailability.UPDATE_AVAILABLE,
                stalenessDays = 3,
                flexibleAllowed = true,
                thresholdDays = 3,
                bypassStaleness = false,
            ),
        )
    }

    @Test
    fun `prompts when staleness exceeds threshold`() {
        assertTrue(
            shouldPromptForFlexibleUpdate(
                availability = UpdateAvailability.UPDATE_AVAILABLE,
                stalenessDays = 10,
                flexibleAllowed = true,
                thresholdDays = 3,
                bypassStaleness = false,
            ),
        )
    }

    @Test
    fun `does NOT prompt when staleness is one day short of threshold`() {
        assertFalse(
            shouldPromptForFlexibleUpdate(
                availability = UpdateAvailability.UPDATE_AVAILABLE,
                stalenessDays = 2,
                flexibleAllowed = true,
                thresholdDays = 3,
                bypassStaleness = false,
            ),
        )
    }

    @Test
    fun `does NOT prompt when staleness is null and bypass is off`() {
        // Play returns null when it hasn't computed staleness yet (typical for fresh releases
        // and for Internal App Sharing). Without the bypass, that must hold the prompt back.
        assertFalse(
            shouldPromptForFlexibleUpdate(
                availability = UpdateAvailability.UPDATE_AVAILABLE,
                stalenessDays = null,
                flexibleAllowed = true,
                thresholdDays = 3,
                bypassStaleness = false,
            ),
        )
    }

    @Test
    fun `bypass forces a prompt even with null staleness`() {
        // This is the IAS-verification case: bypass on (debug build) overrides the staleness gate
        // so the snackbar UI can be manually confirmed end-to-end before merging.
        assertTrue(
            shouldPromptForFlexibleUpdate(
                availability = UpdateAvailability.UPDATE_AVAILABLE,
                stalenessDays = null,
                flexibleAllowed = true,
                thresholdDays = 3,
                bypassStaleness = true,
            ),
        )
    }

    @Test
    fun `bypass does NOT override availability`() {
        // Even with bypass on, if Play says no update is available we must not prompt — the
        // bypass only relaxes the staleness check, never invents an update.
        assertFalse(
            shouldPromptForFlexibleUpdate(
                availability = UpdateAvailability.UPDATE_NOT_AVAILABLE,
                stalenessDays = null,
                flexibleAllowed = true,
                thresholdDays = 3,
                bypassStaleness = true,
            ),
        )
    }

    @Test
    fun `does NOT prompt when FLEXIBLE update type is not allowed`() {
        assertFalse(
            shouldPromptForFlexibleUpdate(
                availability = UpdateAvailability.UPDATE_AVAILABLE,
                stalenessDays = 30,
                flexibleAllowed = false,
                thresholdDays = 3,
                bypassStaleness = false,
            ),
        )
    }

    @Test
    fun `does NOT prompt for unknown availability state`() {
        // UNKNOWN, DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS, etc. — silent no-op.
        assertFalse(
            shouldPromptForFlexibleUpdate(
                availability = UpdateAvailability.UNKNOWN,
                stalenessDays = 30,
                flexibleAllowed = true,
                thresholdDays = 3,
                bypassStaleness = false,
            ),
        )
        assertFalse(
            shouldPromptForFlexibleUpdate(
                availability = UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
                stalenessDays = 30,
                flexibleAllowed = true,
                thresholdDays = 3,
                bypassStaleness = false,
            ),
        )
    }
}
