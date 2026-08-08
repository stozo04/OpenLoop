package io.github.stozo04.openloop.update

import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.InstallStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Covers the wiring [AppUpdateController] owns — routing install states to the host callback and
 * releasing the listener. The gate lives in [AppUpdateGateTest]; everything else is Play's library.
 *
 * mockk rather than Google's `FakeAppUpdateManager`, which needs a `Context` a plain JVM test can't
 * supply (Robolectric or an emulator would be a large cost for this). End-to-end verification is
 * manual, over Internal App Sharing — see the PR's test plan.
 */
class AppUpdateControllerTest {

    private val manager = mockk<AppUpdateManager>(relaxed = true)
    private val launcher = mockk<ActivityResultLauncher<IntentSenderRequest>>(relaxed = true)
    private val listener = slot<InstallStateUpdatedListener>()

    /** `AppUpdateController` logs via [Log]; the JVM stub throws unless mocked. */
    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        every { manager.registerListener(capture(listener)) } returns Unit
    }

    @After
    fun releaseAndroidLog() {
        unmockkStatic(Log::class)
    }

    private fun state(status: Int) = mockk<InstallState> {
        every { installStatus() } returns status
    }

    @Test
    fun `only DOWNLOADED reaches the host callback`() {
        var prompts = 0
        AppUpdateController(manager, launcher).onUpdateDownloaded = { prompts++ }

        listOf(
            InstallStatus.PENDING,
            InstallStatus.DOWNLOADING,
            InstallStatus.INSTALLING,
            InstallStatus.INSTALLED,
            InstallStatus.FAILED,
            InstallStatus.CANCELED,
        ).forEach { listener.captured.onStateUpdate(state(it)) }
        assertEquals(0, prompts)

        listener.captured.onStateUpdate(state(InstallStatus.DOWNLOADED))
        assertEquals(1, prompts)
    }

    @Test
    fun `detach unregisters the same listener instance it registered`() {
        AppUpdateController(manager, launcher).detach()

        verify(exactly = 1) { manager.unregisterListener(listener.captured) }
    }

    @Test
    fun `completeUpdate hands off to Play`() {
        AppUpdateController(manager, launcher).completeUpdate()

        verify(exactly = 1) { manager.completeUpdate() }
    }
}
