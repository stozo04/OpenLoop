package io.github.stozo04.openloop.update

import android.content.Context
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.test.core.app.ApplicationProvider
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * End-to-end regression cover for the in-app update flow, driven through Google's
 * [FakeAppUpdateManager] (it ships inside `app-update`, so no extra test dependency). The fake runs
 * the real Play state machine — availability, the confirmation dialog, download, install — without
 * a Play Store, which is the only way to exercise "the user is behind a version" off-device.
 *
 * Robolectric supplies the [Context] the fake's constructor needs; the mockk'd launcher is never
 * driven, because the fake shows no real UI and reports the dialog via
 * [FakeAppUpdateManager.isConfirmationDialogVisible] instead.
 *
 * Covers the paths a future change could silently break:
 *  - a stale build surfaces Play's update dialog,
 *  - accepting it downloads and reaches the "Update ready — restart" prompt, and restarting installs,
 *  - declining it leaves the app usable and does not re-nag on the next resume,
 *  - a fresh build is never prompted at all.
 */
@RunWith(RobolectricTestRunner::class)
class AppUpdateFlowRobolectricTest {

    private lateinit var play: FakeAppUpdateManager
    private val launcher = mockk<ActivityResultLauncher<IntentSenderRequest>>(relaxed = true)

    /** Prompts observed by the host — each one is an "Update ready — restart to install" snackbar. */
    private var updateReadyPrompts = 0

    @Before
    fun setUp() {
        play = FakeAppUpdateManager(ApplicationProvider.getApplicationContext())
        updateReadyPrompts = 0
    }

    /**
     * Play delivers `appUpdateInfo` and install-state callbacks by posting to the main looper, which
     * Robolectric leaves paused. Flush it so the controller's listeners actually run.
     */
    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** A controller with the real production gate — [bypassStaleness] off, as in a release build. */
    private fun controller() = AppUpdateController(
        appUpdateManager = play,
        launcher = launcher,
        bypassStaleness = false,
    ).apply { onUpdateDownloaded = { updateReadyPrompts++ } }

    /** Play reports a newer build that has been out longer than the staleness threshold. */
    private fun playHasANewerBuild(staleDays: Int = UPDATE_STALENESS_DAYS_THRESHOLD) {
        play.setUpdateAvailable(BUILD_CONFIG_VERSION_CODE + 1)
        play.setClientVersionStalenessDays(staleDays)
    }

    @Test
    fun `a stale build shows Play's update dialog`() {
        playHasANewerBuild()

        controller().check()
        settle()

        assertTrue("Play's confirmation dialog should be showing", play.isConfirmationDialogVisible())
        assertEquals(0, updateReadyPrompts) // nothing downloaded yet — no restart prompt
    }

    @Test
    fun `accepting the update downloads it, prompts to restart, and restarting installs`() {
        playHasANewerBuild()
        val controller = controller()

        controller.check()
        settle()
        assertTrue(play.isConfirmationDialogVisible())

        play.userAcceptsUpdate()
        settle()
        play.downloadStarts()
        settle()
        assertEquals("Downloading is not done — do not prompt yet", 0, updateReadyPrompts)

        play.downloadCompletes()
        settle()
        assertEquals("A completed download must prompt to restart", 1, updateReadyPrompts)

        // The user taps "Restart".
        controller.completeUpdate()
        settle()
        assertTrue("Play should be installing", play.isInstallSplashScreenVisible())

        play.installCompletes()
        settle()
    }

    @Test
    fun `a download that finished while backgrounded is re-surfaced on the next resume`() {
        playHasANewerBuild()
        val controller = controller()

        controller.check()
        settle()
        play.userAcceptsUpdate()
        settle()
        play.downloadStarts()
        settle()
        play.downloadCompletes()
        settle()
        assertEquals(1, updateReadyPrompts) // via the install-state listener

        // The user backgrounds and returns: onResume calls check(), which must find the staged APK
        // and prompt again rather than rely on a listener callback that already fired.
        controller.check()
        settle()

        assertEquals("onResume must re-surface a staged update", 2, updateReadyPrompts)
    }

    @Test
    fun `declining the update leaves the app usable and does not re-nag on the next resume`() {
        playHasANewerBuild()
        val controller = controller()

        controller.check()
        settle()
        assertTrue(play.isConfirmationDialogVisible())

        play.userRejectsUpdate()
        settle()
        assertFalse("Dialog should be dismissed", play.isConfirmationDialogVisible())
        assertEquals("Nothing downloaded, so no restart prompt", 0, updateReadyPrompts)

        // Every later onResume calls check() again — declining must not re-open Play's dialog.
        controller.check()
        settle()
        controller.check()
        settle()

        assertFalse("Declining must not re-nag on resume", play.isConfirmationDialogVisible())
        assertEquals(0, updateReadyPrompts)
    }

    @Test
    fun `a build that is not stale enough is never prompted`() {
        playHasANewerBuild(staleDays = UPDATE_STALENESS_DAYS_THRESHOLD - 1)

        controller().check()
        settle()

        assertFalse("Too fresh to nudge", play.isConfirmationDialogVisible())
        assertEquals(0, updateReadyPrompts)
    }

    @Test
    fun `no update available is a silent no-op`() {
        play.setUpdateNotAvailable()

        controller().check()
        settle()

        assertFalse(play.isConfirmationDialogVisible())
        assertEquals(0, updateReadyPrompts)
    }

    private companion object {
        /** Any installed version — the fake only cares that the available code is higher. */
        const val BUILD_CONFIG_VERSION_CODE = 1
    }
}
