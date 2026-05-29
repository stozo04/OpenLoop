package com.openrang.app.ui

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
 * Verifies the enabled-gating of the `Recording`-state [BackHandler] added to [CameraScreen]
 * (WARNING-2). At targetSdk 36 predictive back is default-on, so a mid-record back gesture would
 * otherwise finish the Activity and silently discard the clip; the handler must intercept back
 * ONLY while recording, and pass it through otherwise.
 *
 * This drives a tiny stateless host wrapping the same `BackHandler(enabled = isRecording) { … }`
 * the screen uses, so the gating logic is tested without binding the camera. The lambda's real
 * target — `viewModel.stopBurstCapture(...)` — is already covered by [OpenRangViewModelTest].
 *
 * Uses [createAndroidComposeRule] with [ComponentActivity] because it supplies a real
 * `OnBackPressedDispatcher`; the plain `createComposeRule` does not.
 */
@RunWith(AndroidJUnit4::class)
class CameraBackHandlerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun RecordingBackHost(isRecording: Boolean, onBack: () -> Unit) {
        BackHandler(enabled = isRecording) { onBack() }
    }

    @Test
    fun back_whileNotRecording_passesThrough_handlerNotInvoked() {
        var backCount = 0
        composeTestRule.setContent {
            RecordingBackHost(isRecording = false, onBack = { backCount++ })
        }

        // onBackPressed() runs callbacks synchronously on the UI thread; assert immediately, before
        // the dispatcher's fallback (which finishes the Activity) tears anything down.
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(
                "A disabled handler must NOT intercept back when not recording",
                0,
                backCount
            )
        }
    }

    @Test
    fun back_whileRecording_invokesHandlerExactlyOnce() {
        var backCount = 0
        composeTestRule.setContent {
            RecordingBackHost(isRecording = true, onBack = { backCount++ })
        }

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(
                "An enabled handler must intercept back exactly once while recording",
                1,
                backCount
            )
        }
    }
}
