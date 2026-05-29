package com.openrang.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openrang.app.media.BoomerangMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * UI tests for the stateless [BoomerangEditorContent] (slice 03). Driven directly — no ViewModel, no
 * capture — so we control the direction + reverse-ready state and assert on chip selection, the Save
 * gate, the loading shimmer, and the gated discard dialog. Lesson 017: no mockk in androidTest — plain
 * lambdas + a temp [File] for the (non-playing) source.
 */
@RunWith(AndroidJUnit4::class)
class BoomerangEditorScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val dummySource: File = File.createTempFile("editor_test_src", ".mp4")
    private val dummyReversed: File = File.createTempFile("editor_test_rev", ".mp4")

    private fun setContent(
        trimStartMs: Long = 0L,
        trimEndMs: Long = 5_000L,
        mode: BoomerangMode = BoomerangMode.FORWARD_THEN_REVERSE,
        reversedFile: File? = dummyReversed,
        isReversedFileLoading: Boolean = false,
        onSelectMode: (BoomerangMode) -> Unit = {},
        onSave: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            BoomerangEditorContent(
                sourceFile = dummySource,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                mode = mode,
                reversedFile = reversedFile,
                isReversedFileLoading = isReversedFileLoading,
                onSelectMode = onSelectMode,
                onSave = onSave,
                onBack = onBack,
            )
        }
    }

    @Test
    fun allFourDirectionChips_areDisplayed() {
        setContent()
        composeTestRule.onNodeWithTag("direction_chip_FORWARD").assertIsDisplayed()
        composeTestRule.onNodeWithTag("direction_chip_REVERSE").assertIsDisplayed()
        composeTestRule.onNodeWithTag("direction_chip_FORWARD_THEN_REVERSE").assertIsDisplayed()
        composeTestRule.onNodeWithTag("direction_chip_REVERSE_THEN_FORWARD").assertIsDisplayed()
    }

    @Test
    fun tappingChip_invokesOnSelectModeWithThatMode() {
        var selected: BoomerangMode? = null
        setContent(onSelectMode = { selected = it })
        composeTestRule.onNodeWithTag("direction_chip_REVERSE").performClick()
        assertEquals(BoomerangMode.REVERSE, selected)
    }

    @Test
    fun selectedChip_reportsSelectedSemantics() {
        setContent(mode = BoomerangMode.REVERSE_THEN_FORWARD)
        composeTestRule.onNodeWithTag("direction_chip_REVERSE_THEN_FORWARD").assertIsSelected()
    }

    @Test
    fun save_isEnabled_forForwardModeWithNoReverseNeeded() {
        setContent(mode = BoomerangMode.FORWARD, reversedFile = null)
        composeTestRule.onNodeWithTag("editor_save").assertIsEnabled()
    }

    @Test
    fun save_isEnabled_whenReversedClipIsReady() {
        setContent(mode = BoomerangMode.FORWARD_THEN_REVERSE, reversedFile = dummyReversed, isReversedFileLoading = false)
        composeTestRule.onNodeWithTag("editor_save").assertIsEnabled()
    }

    @Test
    fun save_isDisabled_whileReverseIsLoading() {
        setContent(mode = BoomerangMode.FORWARD_THEN_REVERSE, reversedFile = null, isReversedFileLoading = true)
        composeTestRule.onNodeWithTag("editor_save").assertIsNotEnabled()
    }

    @Test
    fun loadingShimmer_isShown_whileReverseNotReady() {
        setContent(mode = BoomerangMode.REVERSE, reversedFile = null, isReversedFileLoading = true)
        composeTestRule.onNodeWithTag("reverse_loading").assertIsDisplayed()
        composeTestRule.onNodeWithText("Preparing reverse…").assertIsDisplayed()
    }

    @Test
    fun back_withChangedDirection_showsDiscardDialog() {
        var backCalls = 0
        setContent(mode = BoomerangMode.REVERSE, onBack = { backCalls++ })
        composeTestRule.onNodeWithTag("editor_back").performClick()
        composeTestRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        assertFalse("back should be gated behind the confirm dialog", backCalls > 0)
    }

    @Test
    fun back_withDefaultDirection_returnsWithoutDialog() {
        var backCalls = 0
        setContent(mode = BoomerangMode.FORWARD_THEN_REVERSE, onBack = { backCalls++ })
        composeTestRule.onNodeWithTag("editor_back").performClick()
        assertEquals(1, backCalls)
    }

    @Test
    fun durationLabel_reflectsTheSelectedDirection() {
        // F→R over a 5 s trim at the fixed 2× speed → 5.0 s output (cycle 10 s / 2×).
        setContent(trimStartMs = 0L, trimEndMs = 5_000L, mode = BoomerangMode.FORWARD_THEN_REVERSE)
        composeTestRule.onNodeWithText("5.0s").assertIsDisplayed()
    }

    @Test
    fun durationLabel_forSingleDirection_isHalfOfTheTwoPartDuration() {
        // FORWARD over a 5 s trim at 2× → 2.5 s output (single cycle clip).
        setContent(trimStartMs = 0L, trimEndMs = 5_000L, mode = BoomerangMode.FORWARD, reversedFile = null)
        composeTestRule.onNodeWithText("2.5s").assertIsDisplayed()
    }
}
