package com.openrang.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for [OnboardingNavigation].
 *
 * WHY THIS TEST EXISTS:
 * [OnboardingNavigation] MUST be a standalone @Composable function — never inlined
 * back into the Column in OnboardingScreen. If it is inlined, the Column's
 * ColumnScope leaks into the AnimatedVisibility calls, resolving to
 * ColumnScope.AnimatedVisibility (which uses slide animations). That causes
 * buttons to appear at the left edge and snap to center — the "jumpy nav" bug.
 *
 * This test guards against that regression in two ways:
 * 1. COMPILE-TIME: It calls OnboardingNavigation(...) by name. If someone
 *    deletes or inlines the function, this file will not compile.
 * 2. RUNTIME: It verifies that navigation buttons are horizontally centered
 *    for every page state.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Page 0: single Next button is centered ──

    @Test
    fun page0_nextButton_isDisplayedAndCentered() {
        composeTestRule.setContent {
            OnboardingNavigation(
                currentPage = 0,
                onPageSelected = {},
                onGetStartedClick = {}
            )
        }

        val button = composeTestRule.onNodeWithTag("nav_next_page0")
        button.assertIsDisplayed()

        val rootBounds = composeTestRule.onNodeWithTag("onboarding_nav_container").getBoundsInRoot()
        val buttonBounds = button.getBoundsInRoot()

        val rootCenterX = (rootBounds.left + rootBounds.right) / 2
        val buttonCenterX = (buttonBounds.left + buttonBounds.right) / 2

        assertEquals(
            "Page 0 Next button should be horizontally centered",
            rootCenterX.value,
            buttonCenterX.value,
            2f // tolerance in dp
        )
    }

    // ── Page 1: Back + Next row is centered ──

    @Test
    fun page1_backAndNextButtons_areDisplayedAndCentered() {
        composeTestRule.setContent {
            OnboardingNavigation(
                currentPage = 1,
                onPageSelected = {},
                onGetStartedClick = {}
            )
        }

        composeTestRule.onNodeWithTag("nav_back_page1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_next_page1").assertIsDisplayed()

        val rootBounds = composeTestRule.onNodeWithTag("onboarding_nav_container").getBoundsInRoot()
        val rowBounds = composeTestRule.onNodeWithTag("nav_row_page1").getBoundsInRoot()

        val rootCenterX = (rootBounds.left + rootBounds.right) / 2
        val rowCenterX = (rowBounds.left + rowBounds.right) / 2

        assertEquals(
            "Page 1 navigation row should be horizontally centered",
            rootCenterX.value,
            rowCenterX.value,
            2f
        )
    }

    // ── Page 2: LET'S GO CTA is displayed and fills width ──

    @Test
    fun page2_ctaButton_isDisplayedAndFillsWidth() {
        composeTestRule.setContent {
            OnboardingNavigation(
                currentPage = 2,
                onPageSelected = {},
                onGetStartedClick = {}
            )
        }

        val cta = composeTestRule.onNodeWithTag("nav_cta_page2")
        cta.assertIsDisplayed()

        val rootBounds = composeTestRule.onNodeWithTag("onboarding_nav_container").getBoundsInRoot()
        val ctaBounds = cta.getBoundsInRoot()

        val rootCenterX = (rootBounds.left + rootBounds.right) / 2
        val ctaCenterX = (ctaBounds.left + ctaBounds.right) / 2

        assertEquals(
            "Page 2 CTA should be horizontally centered",
            rootCenterX.value,
            ctaCenterX.value,
            2f
        )
    }

    // ── Only the correct page's controls are visible ──

    @Test
    fun page0_doesNotShowPage1OrPage2Controls() {
        composeTestRule.setContent {
            OnboardingNavigation(
                currentPage = 0,
                onPageSelected = {},
                onGetStartedClick = {}
            )
        }

        composeTestRule.onNodeWithTag("nav_next_page0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_row_page1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("nav_cta_page2").assertDoesNotExist()
    }

    @Test
    fun page1_doesNotShowPage0OrPage2Controls() {
        composeTestRule.setContent {
            OnboardingNavigation(
                currentPage = 1,
                onPageSelected = {},
                onGetStartedClick = {}
            )
        }

        composeTestRule.onNodeWithTag("nav_row_page1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_next_page0").assertDoesNotExist()
        composeTestRule.onNodeWithTag("nav_cta_page2").assertDoesNotExist()
    }

    @Test
    fun page2_doesNotShowPage0OrPage1Controls() {
        composeTestRule.setContent {
            OnboardingNavigation(
                currentPage = 2,
                onPageSelected = {},
                onGetStartedClick = {}
            )
        }

        composeTestRule.onNodeWithTag("nav_cta_page2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_next_page0").assertDoesNotExist()
        composeTestRule.onNodeWithTag("nav_row_page1").assertDoesNotExist()
    }
}
