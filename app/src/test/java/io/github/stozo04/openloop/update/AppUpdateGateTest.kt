package io.github.stozo04.openloop.update

import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The FLEXIBLE-update gate: availability x staleness x flexible-allowed x debug bypass. */
class AppUpdateGateTest {

    private fun gate(
        availability: Int = UpdateAvailability.UPDATE_AVAILABLE,
        stalenessDays: Int? = UPDATE_STALENESS_DAYS_THRESHOLD,
        flexibleAllowed: Boolean = true,
        bypassStaleness: Boolean = false,
    ) = shouldPromptForFlexibleUpdate(
        availability = availability,
        stalenessDays = stalenessDays,
        flexibleAllowed = flexibleAllowed,
        bypassStaleness = bypassStaleness,
    )

    @Test
    fun `prompts when available, flexible-allowed and stale enough`() {
        assertTrue(gate())
        assertTrue(gate(stalenessDays = UPDATE_STALENESS_DAYS_THRESHOLD + 30))
    }

    @Test
    fun `no prompt when Play reports no update`() {
        assertFalse(gate(availability = UpdateAvailability.UPDATE_NOT_AVAILABLE))
        assertFalse(gate(availability = UpdateAvailability.UNKNOWN))
        // A flow already running must not be restarted, even with the debug bypass on.
        assertFalse(
            gate(
                availability = UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
                bypassStaleness = true,
            ),
        )
    }

    @Test
    fun `no prompt when the build is not stale enough`() {
        assertFalse(gate(stalenessDays = UPDATE_STALENESS_DAYS_THRESHOLD - 1))
        assertFalse(gate(stalenessDays = 0))
    }

    @Test
    fun `null staleness counts as not stale enough`() {
        // Play returns null until it has computed staleness — and always over Internal App Sharing.
        assertFalse(gate(stalenessDays = null))
    }

    @Test
    fun `no prompt when Play disallows the FLEXIBLE type`() {
        assertFalse(gate(flexibleAllowed = false))
        // The bypass relaxes staleness only — it must not override an unsupported update type.
        assertFalse(gate(flexibleAllowed = false, stalenessDays = null, bypassStaleness = true))
    }

    @Test
    fun `bypass prompts on a fresh build so IAS can verify the snackbar`() {
        assertTrue(gate(stalenessDays = null, bypassStaleness = true))
        assertTrue(gate(stalenessDays = 0, bypassStaleness = true))
    }
}
