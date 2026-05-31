package io.github.stozo04.openloop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.stozo04.openloop.ui.theme.OpenLoopTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for [PermissionExplanationScreen] — the shared composable behind both the
 * permission-rationale and permanent-denial screens (Issue #11).
 *
 * Guards the contract that the secondary action is variant-specific:
 * - Rationale variant: "Grant Permissions" + "Not now" (cancel), no Settings link.
 * - Denial variant: "Try Again" + "Open Device Settings".
 * - Primary-only: no secondary button rendered.
 */
@RunWith(AndroidJUnit4::class)
class PermissionExplanationScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rationaleVariant_showsGrantAndCancel_hidesSettings() {
        composeTestRule.setContent {
            OpenLoopTheme {
                PermissionExplanationScreen(
                    title = "We need a quick permission",
                    body = "OpenLoop needs Camera and Audio to capture your video loops.",
                    primaryActionLabel = "Grant Permissions",
                    onPrimaryAction = {},
                    secondaryActionLabel = "Not now",
                    onSecondaryAction = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Grant Permissions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open Device Settings").assertDoesNotExist()
    }

    @Test
    fun denialVariant_showsTryAgainAndSettings() {
        composeTestRule.setContent {
            OpenLoopTheme {
                PermissionExplanationScreen(
                    title = "Permissions Required",
                    body = "OpenLoop needs Camera and Audio recording permissions.",
                    primaryActionLabel = "Try Again",
                    onPrimaryAction = {},
                    secondaryActionLabel = "Open Device Settings",
                    onSecondaryAction = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Try Again").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open Device Settings").assertIsDisplayed()
    }

    @Test
    fun noSecondaryAction_rendersPrimaryOnly() {
        composeTestRule.setContent {
            OpenLoopTheme {
                PermissionExplanationScreen(
                    title = "Title",
                    body = "Body",
                    primaryActionLabel = "Grant Permissions",
                    onPrimaryAction = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Grant Permissions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now").assertDoesNotExist()
        composeTestRule.onNodeWithText("Open Device Settings").assertDoesNotExist()
    }

    @Test
    fun primaryAndSecondaryClicks_invokeTheirCallbacks() {
        var primaryClicked = false
        var secondaryClicked = false

        composeTestRule.setContent {
            OpenLoopTheme {
                PermissionExplanationScreen(
                    title = "We need a quick permission",
                    body = "Body",
                    primaryActionLabel = "Grant Permissions",
                    onPrimaryAction = { primaryClicked = true },
                    secondaryActionLabel = "Not now",
                    onSecondaryAction = { secondaryClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Grant Permissions").performClick()
        composeTestRule.onNodeWithText("Not now").performClick()

        assertTrue("Primary action should fire on tap", primaryClicked)
        assertTrue("Secondary action should fire on tap", secondaryClicked)
    }
}
