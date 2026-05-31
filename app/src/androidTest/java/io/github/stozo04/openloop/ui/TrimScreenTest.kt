package io.github.stozo04.openloop.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * UI tests for the stateless [TrimScreenContent]. Driven directly (no ViewModel / no capture), so we
 * control the trim window and assert on the NEXT gate, ≥44 dp handle targets, and the discard dialog.
 * Lesson 017: no mockk in androidTest — plain lambdas + a temp [File] for the (non-playing) source.
 */
@RunWith(AndroidJUnit4::class)
class TrimScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val dummySource: File = File.createTempFile("trim_test_src", ".mp4")

    private fun setContent(
        durationMs: Long = 3_000L,
        startMs: Long = 0L,
        endMs: Long = 3_000L,
        onCommitTrim: (Long, Long) -> Unit = { _, _ -> },
        onNext: () -> Unit = {},
        onDiscard: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            TrimScreenContent(
                sourceFile = dummySource,
                sourceDurationMs = durationMs,
                committedStartMs = startMs,
                committedEndMs = endMs,
                onCommitTrim = onCommitTrim,
                onNext = onNext,
                onDiscard = onDiscard,
            )
        }
    }

    @Test
    fun next_isEnabled_whenWindowMeetsMinimum() {
        setContent(durationMs = 3_000L, startMs = 0L, endMs = 3_000L)
        composeTestRule.onNodeWithTag("next_button").assertIsEnabled()
    }

    @Test
    fun next_isDisabled_whenWindowBelowMinimum() {
        // 200ms window < 400ms minimum.
        setContent(durationMs = 3_000L, startMs = 1_000L, endMs = 1_200L)
        composeTestRule.onNodeWithTag("next_button").assertIsNotEnabled()
    }

    @Test
    fun next_belowMinimum_doesNotInvokeOnNext() {
        var nextCalls = 0
        setContent(durationMs = 3_000L, startMs = 1_000L, endMs = 1_200L, onNext = { nextCalls++ })
        composeTestRule.onNodeWithTag("next_button").performClick()
        assertFalse("disabled NEXT must not fire onNext", nextCalls > 0)
    }

    @Test
    fun trimHandles_meetMinimumTouchTarget() {
        setContent()
        composeTestRule.onNodeWithTag("trim_handle_start").assertWidthIsAtLeast(44.dp)
        composeTestRule.onNodeWithTag("trim_handle_end").assertWidthIsAtLeast(44.dp)
    }

    @Test
    fun durationLabel_reflectsTheTrimmedWindow() {
        setContent(durationMs = 5_000L, startMs = 1_000L, endMs = 4_000L)
        composeTestRule.onNodeWithText("3.0s").assertIsDisplayed()
    }

    @Test
    fun handleLabels_showEachHandlesTimestamp() {
        // Start handle at 1.0s, end handle at 4.0s, window length 3.0s (the duration pill).
        setContent(durationMs = 5_000L, startMs = 1_000L, endMs = 4_000L)
        composeTestRule.onNodeWithTag("trim_label_start").assertIsDisplayed()
        composeTestRule.onNodeWithTag("trim_label_end").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.0s").assertIsDisplayed() // start timestamp
        composeTestRule.onNodeWithText("4.0s").assertIsDisplayed() // end timestamp
    }

    @Test
    fun back_showsDiscardConfirmDialog() {
        setContent()
        composeTestRule.onNodeWithTag("trim_back").performClick()
        composeTestRule.onNodeWithText("Discard this clip?").assertIsDisplayed()
    }

    // ── Accessibility: the custom trim handles must expose adjustable semantics (not be invisible
    //    to TalkBack). Each handle is a labeled, range-valued node with a SetProgress action. ──

    @Test
    fun trimHandles_exposeAdjustableRangeSemantics() {
        setContent(durationMs = 4_000L, startMs = 0L, endMs = 4_000L)

        composeTestRule.onNodeWithContentDescription("Trim start")
            .assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..4_000f))
        composeTestRule.onNodeWithContentDescription("Trim end")
            .assertRangeInfoEquals(ProgressBarRangeInfo(4_000f, 0f..4_000f))
    }

    @Test
    fun trimStartHandle_setProgressAction_commitsClampedValue() {
        var committedStart = -1L
        var committedEnd = -1L
        setContent(
            durationMs = 4_000L,
            startMs = 0L,
            endMs = 4_000L,
            onCommitTrim = { s, e -> committedStart = s; committedEnd = e },
        )

        // A TalkBack "set value" gesture routes through the SetProgress action and must move + commit.
        composeTestRule.onNodeWithContentDescription("Trim start")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1_000f) }

        assertEquals(1_000L, committedStart)
        assertEquals(4_000L, committedEnd)
    }
}
