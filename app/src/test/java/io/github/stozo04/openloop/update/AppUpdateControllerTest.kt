package io.github.stozo04.openloop.update

import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.appupdate.AppUpdateManager
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
 * T2 verification — `AppUpdateController` wiring against a mocked [AppUpdateManager]. Documented
 * in `docs/active/in-app-updates/IMPLEMENTATION.md` §Verification.
 *
 * **Why mockk, not `FakeAppUpdateManager`:** Google's `FakeAppUpdateManager` requires a [Context]
 * in its constructor, which a plain JVM unit test cannot provide. Either we add Robolectric (a
 * sizeable test-classpath dep just for this) or we move T2 to instrumented (`androidTest`, needs
 * an emulator). Mockk catches the same wiring contracts — listener registered on attach, listener
 * routes DOWNLOADED to the host callback, listener unregistered on detach, `completeUpdate()` is
 * reachable — without any of that cost. The end-to-end UI verification lives in T3 (Internal App
 * Sharing) where it genuinely belongs.
 */
class AppUpdateControllerTest {

    /**
     * `AppUpdateController` logs progress via [android.util.Log]; the JVM stub throws unless mocked.
     * Stubbed once per test rather than globally via `testOptions.unitTests.isReturnDefaultValues`
     * so the change is local to this file.
     */
    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After
    fun releaseAndroidLog() {
        unmockkStatic(Log::class)
    }

    private fun newController(
        manager: AppUpdateManager,
        bypassStaleness: Boolean = false,
    ) = AppUpdateController(
        appUpdateManager = manager,
        bypassStaleness = bypassStaleness,
        stalenessThresholdDays = 3,
    )

    @Test
    fun `attach registers a listener and detach unregisters the SAME instance`() {
        val manager = mockk<AppUpdateManager>(relaxed = true)
        val launcher = mockk<ActivityResultLauncher<IntentSenderRequest>>(relaxed = true)

        val controller = newController(manager)
        controller.attach(launcher)

        val registeredSlot = slot<InstallStateUpdatedListener>()
        verify(exactly = 1) { manager.registerListener(capture(registeredSlot)) }

        controller.detach()
        verify(exactly = 1) { manager.unregisterListener(registeredSlot.captured) }
    }

    @Test
    fun `attach is idempotent — second call does not re-register the listener`() {
        val manager = mockk<AppUpdateManager>(relaxed = true)
        val launcher = mockk<ActivityResultLauncher<IntentSenderRequest>>(relaxed = true)

        val controller = newController(manager)
        controller.attach(launcher)
        controller.attach(launcher)

        verify(exactly = 1) { manager.registerListener(any()) }
    }

    @Test
    fun `listener routes DOWNLOADED to onUpdateDownloaded`() {
        val manager = mockk<AppUpdateManager>(relaxed = true)
        val launcher = mockk<ActivityResultLauncher<IntentSenderRequest>>(relaxed = true)
        val controller = newController(manager)

        var downloadedCallbackInvocations = 0
        controller.onUpdateDownloaded = { downloadedCallbackInvocations++ }

        val listenerSlot = slot<InstallStateUpdatedListener>()
        every { manager.registerListener(capture(listenerSlot)) } returns Unit
        controller.attach(launcher)

        val downloadedState = mockk<InstallState>()
        every { downloadedState.installStatus() } returns InstallStatus.DOWNLOADED
        listenerSlot.captured.onStateUpdate(downloadedState)

        assertEquals(1, downloadedCallbackInvocations)
    }

    @Test
    fun `listener does NOT invoke onUpdateDownloaded for non-DOWNLOADED states`() {
        val manager = mockk<AppUpdateManager>(relaxed = true)
        val launcher = mockk<ActivityResultLauncher<IntentSenderRequest>>(relaxed = true)
        val controller = newController(manager)

        var downloadedCallbackInvocations = 0
        controller.onUpdateDownloaded = { downloadedCallbackInvocations++ }

        val listenerSlot = slot<InstallStateUpdatedListener>()
        every { manager.registerListener(capture(listenerSlot)) } returns Unit
        controller.attach(launcher)

        // Drive the listener through every non-DOWNLOADED status — none should fire the snackbar.
        val nonDownloadedStatuses = listOf(
            InstallStatus.PENDING,
            InstallStatus.DOWNLOADING,
            InstallStatus.INSTALLING,
            InstallStatus.INSTALLED,
            InstallStatus.FAILED,
            InstallStatus.CANCELED,
        )
        nonDownloadedStatuses.forEach { status ->
            val state = mockk<InstallState>()
            every { state.installStatus() } returns status
            listenerSlot.captured.onStateUpdate(state)
        }

        assertEquals(0, downloadedCallbackInvocations)
    }

    @Test
    fun `completeUpdate delegates to the AppUpdateManager`() {
        val manager = mockk<AppUpdateManager>(relaxed = true)
        val controller = newController(manager)

        controller.completeUpdate()

        verify(exactly = 1) { manager.completeUpdate() }
    }

    @Test
    fun `detach before attach is a safe no-op`() {
        val manager = mockk<AppUpdateManager>(relaxed = true)
        val controller = newController(manager)

        controller.detach() // should not throw, should not call unregisterListener
        verify(exactly = 0) { manager.unregisterListener(any()) }
    }
}
