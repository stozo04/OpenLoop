package io.github.stozo04.openloop.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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

@RunWith(AndroidJUnit4::class)
class TrimScreenTest {

    // v1 rule kept deliberately (matches the rest of the suite); the v2 variant flips the test
    // dispatcher and is a separate migration (see OpenLoopNavHostTest).
    @Suppress("DEPRECATION")
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
    fun save_isEnabled_whenWindowMeetsMinimum() {
        setContent(durationMs = 3_000L, startMs = 0L, endMs = 3_000L)
        composeTestRule.onNodeWithTag("trim_save").assertIsEnabled()
    }

    @Test
    fun save_isDisabled_whenWindowBelowMinimum() {
        setContent(durationMs = 3_000L, startMs = 1_000L, endMs = 1_200L)
        composeTestRule.onNodeWithTag("trim_save").assertIsNotEnabled()
    }

    @Test
    fun save_belowMinimum_doesNotInvokeOnNext() {
        var nextCalls = 0
        setContent(durationMs = 3_000L, startMs = 1_000L, endMs = 1_200L, onNext = { nextCalls++ })
        composeTestRule.onNodeWithTag("trim_save").performClick()
        assertFalse("disabled SAVE must not fire onNext", nextCalls > 0)
    }

    @Test
    fun trimSectionTitle_isDisplayed() {
        setContent()
        composeTestRule.onNodeWithText("TRIM YOUR VIDEO").assertIsDisplayed()
    }

    @Test
    fun rangePill_reflectsTheTrimmedWindow() {
        setContent(durationMs = 5_000L, startMs = 1_000L, endMs = 4_000L)
        composeTestRule.onNodeWithTag("trim_range_label").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.00s  —  4.00s").assertIsDisplayed()
    }

    @Test
    fun trimHandles_meetMinimumTouchTarget() {
        setContent()
        composeTestRule.onNodeWithTag("trim_handle_start").assertWidthIsAtLeast(44.dp)
        composeTestRule.onNodeWithTag("trim_handle_end").assertWidthIsAtLeast(44.dp)
    }

    @Test
    fun back_showsDiscardConfirmDialog() {
        setContent()
        composeTestRule.onNodeWithTag("trim_back").performClick()
        composeTestRule.onNodeWithText("Discard this clip?").assertIsDisplayed()
    }

    @Test
    fun bottomToolbar_showsAllFiveSlots() {
        setContent()
        composeTestRule.onNodeWithTag("tab_trim").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_speed").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_loop").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_filter").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_delete").assertIsDisplayed()
    }

    @Test
    fun trimHandles_exposeAdjustableRangeSemantics() {
        setContent(durationMs = 4_000L, startMs = 0L, endMs = 4_000L)

        composeTestRule.onNodeWithContentDescription("Trim start")
            .assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..4_000f))
        composeTestRule.onNodeWithContentDescription("Trim end")
            .assertRangeInfoEquals(ProgressBarRangeInfo(4_000f, 0f..4_000f))
    }

    /**
     * The spoken forms spell the unit out. `0.00s` is fine to read but a TTS engine can voice the
     * trailing `s` as the letter, so the semantics say "0.00 seconds" while the pill and handles
     * still *show* the compact `0.00s`. A test cannot drive TalkBack itself, so this pins the
     * semantics TalkBack reads from.
     */
    @Test
    fun trimReadouts_speakTheUnitInsteadOfABareS() {
        setContent(durationMs = 4_000L, startMs = 0L, endMs = 4_000L)

        composeTestRule.onNodeWithContentDescription("Trim start")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0.00 seconds"))
        composeTestRule.onNodeWithContentDescription("Trim end")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "4.00 seconds"))
        // The pill's em-dash is visual shorthand; spoken it becomes "to".
        composeTestRule.onNodeWithContentDescription("0.00 seconds to 4.00 seconds").assertIsDisplayed()
        // …and the visible text is unchanged by any of that.
        composeTestRule.onNodeWithText("0.00s  —  4.00s").assertIsDisplayed()
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

        composeTestRule.onNodeWithContentDescription("Trim start")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1_000f) }

        assertEquals(1_000L, committedStart)
        assertEquals(4_000L, committedEnd)
    }
}
