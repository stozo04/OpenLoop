package com.openrang.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openrang.app.media.BoomerangMode
import com.openrang.app.media.VideoFilter
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
        speed: Float = 2.0f,
        filter: VideoFilter = VideoFilter.ORIGINAL,
        activeTab: EditorTab = EditorTab.DIRECTION,
        reversedFile: File? = dummyReversed,
        isReversedFileLoading: Boolean = false,
        onSelectMode: (BoomerangMode) -> Unit = {},
        onSpeedChange: (Float) -> Unit = {},
        onFilterChange: (VideoFilter) -> Unit = {},
        onSwitchTab: (EditorTab) -> Unit = {},
        onSave: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            BoomerangEditorContent(
                sourceFile = dummySource,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                mode = mode,
                speed = speed,
                filter = filter,
                activeTab = activeTab,
                reversedFile = reversedFile,
                isReversedFileLoading = isReversedFileLoading,
                onSelectMode = onSelectMode,
                onSpeedChange = onSpeedChange,
                onFilterChange = onFilterChange,
                onSwitchTab = onSwitchTab,
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
        composeTestRule.onNodeWithText("Loopifying…").assertIsDisplayed()
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

    // ── Speed tab (slice 04) ──

    @Test
    fun tabBar_showsThreeSlots() {
        setContent()
        composeTestRule.onNodeWithTag("tab_direction").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_speed").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_looks").assertIsDisplayed()
    }

    @Test
    fun tappingSpeedTab_invokesOnSwitchTabWithSpeed() {
        var switched: EditorTab? = null
        setContent(onSwitchTab = { switched = it })
        composeTestRule.onNodeWithTag("tab_speed").performClick()
        assertEquals(EditorTab.SPEED, switched)
    }

    @Test
    fun directionTabActive_showsChips_notSlider() {
        setContent(activeTab = EditorTab.DIRECTION)
        composeTestRule.onNodeWithTag("direction_chip_FORWARD").assertIsDisplayed()
        composeTestRule.onNodeWithTag("speed_slider").assertDoesNotExist()
    }

    @Test
    fun speedTabActive_showsSlider_notChips() {
        setContent(activeTab = EditorTab.SPEED)
        composeTestRule.onNodeWithTag("speed_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("direction_chip_FORWARD").assertDoesNotExist()
    }

    @Test
    fun slider_reportsCurrentSpeedAsRangeInfo() {
        setContent(activeTab = EditorTab.SPEED, speed = 1.75f)
        composeTestRule.onNodeWithTag("speed_slider")
            .assertRangeInfoEquals(ProgressBarRangeInfo(1.75f, 0.25f..3.0f))
    }

    @Test
    fun draggingSlider_invokesOnSpeedChange() {
        var changed: Float? = null
        setContent(activeTab = EditorTab.SPEED, speed = 2.0f, onSpeedChange = { changed = it })
        // Drive the slider via its SetProgress semantics action (stable across the custom drawing).
        composeTestRule.onNodeWithTag("speed_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertEquals(0.5f, changed!!, 0.0001f)
    }

    @Test
    fun durationLabel_reflectsTheSelectedSpeed() {
        // F→R over a 5 s trim: cycle = 10 s. At 0.5× → 20.0 s output (10 s / 0.5).
        setContent(trimStartMs = 0L, trimEndMs = 5_000L, mode = BoomerangMode.FORWARD_THEN_REVERSE, speed = 0.5f)
        composeTestRule.onNodeWithText("20.0s").assertIsDisplayed()
    }

    // ── Looks tab (slice 05) ──

    @Test
    fun tappingLooksTab_invokesOnSwitchTabWithLooks() {
        var switched: EditorTab? = null
        setContent(onSwitchTab = { switched = it })
        composeTestRule.onNodeWithTag("tab_looks").performClick()
        assertEquals(EditorTab.LOOKS, switched)
    }

    @Test
    fun looksTabActive_showsAllFilterChips_notSlider() {
        setContent(activeTab = EditorTab.LOOKS)
        // All five looks present; the speed slider is gone.
        VideoFilter.entries.forEach { look ->
            composeTestRule.onNodeWithTag("look_chip_${look.name}").assertIsDisplayed()
        }
        composeTestRule.onNodeWithTag("speed_slider").assertDoesNotExist()
    }

    @Test
    fun tappingLookChip_invokesOnFilterChange() {
        var changed: VideoFilter? = null
        setContent(activeTab = EditorTab.LOOKS, onFilterChange = { changed = it })
        composeTestRule.onNodeWithTag("look_chip_NOIR").performClick()
        assertEquals(VideoFilter.NOIR, changed)
    }

    @Test
    fun selectedLook_reportsSelectedSemantics() {
        setContent(activeTab = EditorTab.LOOKS, filter = VideoFilter.WARM)
        composeTestRule.onNodeWithTag("look_chip_WARM").assertIsSelected()
    }
}
