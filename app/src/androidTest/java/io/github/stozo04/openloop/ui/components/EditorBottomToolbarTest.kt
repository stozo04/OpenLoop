package io.github.stozo04.openloop.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorBottomToolbarTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun toolbarLabels_haveRoomToRender() {
        composeTestRule.setContent {
            EditorBottomToolbar(
                activeSlot = EditorToolbarSlot.TRIM,
                onTrimClick = {},
                onSpeedClick = {},
                onLoopClick = {},
                onFilterClick = {},
                onDeleteClick = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }

        listOf("Trim", "Speed", "Loop", "Filter", "Delete").forEach { label ->
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
            composeTestRule.onNodeWithText(label).assertHeightIsAtLeast(14.dp)
        }
    }
}
