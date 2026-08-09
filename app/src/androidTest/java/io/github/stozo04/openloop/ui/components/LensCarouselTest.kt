package io.github.stozo04.openloop.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.stozo04.openloop.camera.lens.Lens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behaviour guard for the lens tray (docs/PRD-camera-lenses.md §6.2).
 *
 * No mocking framework — instrumented tests in this repo can't use mockk (Lesson 017), and the
 * carousel is hoisted precisely so plain lambdas suffice.
 */
@RunWith(AndroidJUnit4::class)
class LensCarouselTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyRegisteredLens_getsAThumbnail() {
        composeTestRule.setContent {
            LensCarousel(lenses = Lens.entries, activeLens = null, onSelect = {}, onClose = {})
        }

        Lens.entries.forEach { lens ->
            composeTestRule.onNodeWithTag("lens_thumb_${lens.name}").assertIsDisplayed()
        }
    }

    @Test
    fun thumbnails_meetTheAccessibilityTouchTargetFloor() {
        composeTestRule.setContent {
            LensCarousel(lenses = Lens.entries, activeLens = null, onSelect = {}, onClose = {})
        }

        // 48.dp is the Material minimum; a pre-launch accessibility-scanner failure on the gallery
        // button (CameraScreen.kt) is why this is asserted rather than assumed.
        Lens.entries.forEach { lens ->
            composeTestRule.onNodeWithTag("lens_thumb_${lens.name}")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        composeTestRule.onNodeWithTag("lens_close")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun tappingAThumbnail_reportsThatLens() {
        var selected: Lens? = null
        composeTestRule.setContent {
            LensCarousel(
                lenses = Lens.entries,
                activeLens = null,
                onSelect = { selected = it },
                onClose = {},
            )
        }

        composeTestRule.onNodeWithTag("lens_thumb_${Lens.BigMouth.name}").performClick()

        assertEquals(Lens.BigMouth, selected)
    }

    @Test
    fun closeButton_reportsClose_withoutSelectingALens() {
        var closed = false
        var selected: Lens? = null
        composeTestRule.setContent {
            LensCarousel(
                lenses = Lens.entries,
                activeLens = Lens.Broccoli,
                onSelect = { selected = it },
                onClose = { closed = true },
            )
        }

        composeTestRule.onNodeWithTag("lens_close").performClick()

        assertEquals(true, closed)
        assertNull(selected)
    }

    @Test
    fun activeLensName_isShownOnlyWhileALensIsActive() {
        composeTestRule.setContent {
            // remember, or the state is rebuilt on every recomposition and the click is lost.
            var active by remember { mutableStateOf<Lens?>(null) }
            LensCarousel(
                lenses = Lens.entries,
                activeLens = active,
                onSelect = { active = it },
                onClose = { active = null },
            )
        }

        composeTestRule.onNodeWithText(Lens.Broccoli.displayName).assertDoesNotExist()

        composeTestRule.onNodeWithTag("lens_thumb_${Lens.Broccoli.name}").performClick()

        composeTestRule.onNodeWithText(Lens.Broccoli.displayName).assertIsDisplayed()
    }
}
