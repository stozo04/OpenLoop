package io.github.stozo04.openloop.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the single-screen onboarding's launch CTA ([GetStartedButton]).
 *
 * Onboarding was trimmed from a 3-page pager to one value/trust screen (per Google's onboarding
 * guidance — a one-tap app doesn't need a walkthrough). The only control is the "LET'S GO" CTA, which
 * MUST stay a standalone @Composable carrying the stable `onboarding_cta` tag so MainActivity's nav and
 * these tests can find it. If someone deletes or renames it, this file won't compile.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun getStartedButton_isDisplayedAndClickable() {
        composeTestRule.setContent {
            GetStartedButton(onClick = {})
        }

        composeTestRule.onNodeWithTag("onboarding_cta")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun getStartedButton_invokesCallbackOnClick() {
        var clicked = false
        composeTestRule.setContent {
            GetStartedButton(onClick = { clicked = true })
        }

        composeTestRule.onNodeWithTag("onboarding_cta").performClick()

        assertTrue("Tapping the onboarding CTA should invoke onClick", clicked)
    }
}
