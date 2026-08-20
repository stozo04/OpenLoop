package io.github.stozo04.openloop.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the enabled-gating and priority of [CameraScreen]'s back handler (WARNING-2). At
 * targetSdk 36 predictive back is default-on, so a mid-record back gesture would otherwise finish
 * the Activity and silently discard the clip; the handler must intercept back ONLY while recording
 * or while the lens tray is open, and pass it through otherwise.
 *
 * [CameraBackHost] mirrors the production handler exactly — one handler with an explicit `when`,
 * rather than two stacked [BackHandler]s whose order would depend on Compose's registration order.
 * Testing a *different* shape than production would make this a test of a fiction (Lesson 029), so
 * the host below must be kept in step with `CameraScreen`.
 *
 * Uses [createAndroidComposeRule] with [ComponentActivity] because it supplies a real
 * `OnBackPressedDispatcher`; the plain `createComposeRule` does not.
 */
@RunWith(AndroidJUnit4::class)
class CameraBackHandlerTest {

    // v1 rule kept deliberately (matches the rest of the suite); the v2 variant flips the test
    // dispatcher and is a separate migration (see OpenLoopNavHostTest).
    @Suppress("DEPRECATION")
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * [onFellThrough] is registered as an always-enabled handler *below* the one under test.
     *
     * That is not decoration. Without it, a back press the screen declines reaches the dispatcher's
     * fallback and finishes the Activity — and a class full of such tests races
     * `ActivityScenarioRule`'s teardown until instrumentation aborts with "System has crashed".
     * Catching the fall-through also turns "did nothing" into a positive assertion instead of an
     * absence of one.
     *
     * `OnBackPressedDispatcher` dispatches most-recently-added first, so the handler under test
     * still wins whenever it is enabled.
     */
    @Composable
    private fun CameraBackHost(
        isRecording: Boolean,
        lensTrayOpen: Boolean,
        onCloseLensTray: () -> Unit,
        onStopRecording: () -> Unit,
        onFellThrough: () -> Unit,
    ) {
        BackHandler(enabled = true) { onFellThrough() }
        BackHandler(enabled = isRecording || lensTrayOpen) {
            when {
                lensTrayOpen -> onCloseLensTray()
                else -> onStopRecording()
            }
        }
    }

    private fun assertBack(
        isRecording: Boolean,
        lensTrayOpen: Boolean,
        expectedStops: Int,
        expectedTrayCloses: Int,
        expectedFallThroughs: Int,
        message: String,
    ) {
        var stops = 0
        var trayCloses = 0
        var fellThrough = 0
        composeTestRule.setContent {
            CameraBackHost(
                isRecording = isRecording,
                lensTrayOpen = lensTrayOpen,
                onCloseLensTray = { trayCloses++ },
                onStopRecording = { stops++ },
                onFellThrough = { fellThrough++ },
            )
        }

        // onBackPressed() runs callbacks synchronously on the UI thread.
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertEquals("$message (stop recording)", expectedStops, stops)
        assertEquals("$message (close lens tray)", expectedTrayCloses, trayCloses)
        assertEquals("$message (passed through)", expectedFallThroughs, fellThrough)
    }

    @Test
    fun back_whileIdle_passesThrough_handlerNotInvoked() {
        assertBack(
            isRecording = false,
            lensTrayOpen = false,
            expectedStops = 0,
            expectedTrayCloses = 0,
            expectedFallThroughs = 1,
            message = "A disabled handler must NOT intercept back on an idle camera",
        )
    }

    @Test
    fun back_whileRecording_stopsTheRecordingExactlyOnce() {
        assertBack(
            isRecording = true,
            lensTrayOpen = false,
            expectedStops = 1,
            expectedTrayCloses = 0,
            expectedFallThroughs = 0,
            message = "An enabled handler must intercept back exactly once while recording",
        )
    }

    @Test
    fun back_withLensTrayOpen_closesTheTray_withoutTouchingTheCamera() {
        assertBack(
            isRecording = false,
            lensTrayOpen = true,
            expectedStops = 0,
            expectedTrayCloses = 1,
            expectedFallThroughs = 0,
            message = "Back must dismiss the lens tray rather than exiting the camera",
        )
    }

    @Test
    fun back_recordingWithLensTrayOpen_dismissesTheTrayFirst() {
        // Transient UI wins: the first back closes the tray and the recording keeps running, so a
        // user browsing lenses mid-capture cannot lose the clip to a stray back gesture.
        assertBack(
            isRecording = true,
            lensTrayOpen = true,
            expectedStops = 0,
            expectedTrayCloses = 1,
            expectedFallThroughs = 0,
            message = "The lens tray must take priority over the recording backstop",
        )
    }
}
