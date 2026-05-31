package io.github.stozo04.openloop.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.stozo04.openloop.data.RecordedVideo
import io.github.stozo04.openloop.data.VideoKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for the stateless [GalleryContent] (Issue #35). Driven directly (no ViewModel / no
 * ExoPlayer) so we can exercise the long-press selection model, the contextual action bar, and the
 * Delete → optimistic-removal flow. Lesson 017: no mockk in androidTest — plain lambdas + a local
 * mutable list standing in for the ViewModel's `visibleVideos`.
 */
@RunWith(AndroidJUnit4::class)
class GalleryScreenTest {

    // ComponentActivity (not the plain compose rule) so GalleryContent's BackHandler gets a real
    // OnBackPressedDispatcherOwner to compose against. The v1 factory is deliberately kept (matching
    // the rest of the suite); the v2 variant flips the test dispatcher and is a separate migration.
    @Suppress("DEPRECATION")
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun video(id: Long) = RecordedVideo(
        id = id,
        videoPath = "/tmp/clip_$id.mp4",
        thumbnailPath = "/tmp/clip_$id.jpg",
        kind = VideoKind.RAW,
    )

    @Test
    fun longPress_entersSelectionMode_showingTheActionBarAndCount() {
        composeTestRule.setContent {
            GalleryContent(
                videos = listOf(video(1L), video(2L), video(3L)),
                onPlay = {},
                onRequestDelete = {},
                onBackClick = {},
                onRecordLoop = {},
                onImportVideo = {},
            )
        }

        // At rest there is no action bar.
        composeTestRule.onNodeWithTag("gallery_action_bar").assertDoesNotExist()

        composeTestRule.onNodeWithTag("gallery_tile_1").performTouchInput { longClick() }

        composeTestRule.onNodeWithTag("gallery_action_bar").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test
    fun tappingAnotherTileInSelectionMode_extendsTheSelectionCount() {
        composeTestRule.setContent {
            GalleryContent(
                videos = listOf(video(1L), video(2L), video(3L)),
                onPlay = {},
                onRequestDelete = {},
                onBackClick = {},
                onRecordLoop = {},
                onImportVideo = {},
            )
        }

        composeTestRule.onNodeWithTag("gallery_tile_1").performTouchInput { longClick() }
        composeTestRule.onNodeWithTag("gallery_tile_2").performClick()

        composeTestRule.onNodeWithText("2 selected").assertIsDisplayed()
    }

    @Test
    fun tappingOutOfSelectionMode_playsRatherThanSelects() {
        var played: Long? = null
        composeTestRule.setContent {
            GalleryContent(
                videos = listOf(video(1L), video(2L)),
                onPlay = { played = it.id },
                onRequestDelete = {},
                onBackClick = {},
                onRecordLoop = {},
                onImportVideo = {},
            )
        }

        composeTestRule.onNodeWithTag("gallery_tile_1").performClick()

        assertEquals(1L, played)
        composeTestRule.onNodeWithTag("gallery_action_bar").assertDoesNotExist()
    }

    @Test
    fun delete_invokesOnRequestDelete_andRemovesTileOptimistically() {
        val deleted = mutableListOf<List<RecordedVideo>>()
        composeTestRule.setContent {
            // Back the grid with a mutable list so a Delete that "hides" the tile (as the ViewModel's
            // visibleVideos does) actually drops it from this stateless content's input.
            val videos = remember { mutableStateListOf(video(1L), video(2L), video(3L)) }
            GalleryContent(
                videos = videos,
                onPlay = {},
                onRequestDelete = { batch ->
                    deleted += batch
                    videos.removeAll(batch)
                },
                onBackClick = {},
                onRecordLoop = {},
                onImportVideo = {},
            )
        }

        composeTestRule.onNodeWithTag("gallery_tile_2").performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("Delete selected").performClick()

        // The selected loop was handed to onRequestDelete, the tile is gone, and selection exited.
        assertEquals(1, deleted.size)
        assertEquals(listOf(2L), deleted.single().map { it.id })
        composeTestRule.onNodeWithTag("gallery_tile_2").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gallery_action_bar").assertDoesNotExist()
    }

    @Test
    fun emptyState_recordLoopNavigatesToCamera_andImportStillAvailable() {
        var recordLoopCalls = 0
        var importCalls = 0
        composeTestRule.setContent {
            GalleryContent(
                videos = emptyList(),
                onPlay = {},
                onRequestDelete = {},
                onBackClick = {},
                onRecordLoop = { recordLoopCalls++ },
                onImportVideo = { importCalls++ },
            )
        }

        composeTestRule.onNodeWithTag("gallery_empty_record").assertIsDisplayed()
        composeTestRule.onNodeWithText("RECORD A LOOP").performClick()
        assertEquals(1, recordLoopCalls)

        composeTestRule.onNodeWithText("…or import one").performClick()
        assertEquals(1, importCalls)
    }

    @Test
    fun exitSelection_clearsSelectionWithoutDeleting() {
        var deleteCalls = 0
        composeTestRule.setContent {
            GalleryContent(
                videos = listOf(video(1L), video(2L)),
                onPlay = {},
                onRequestDelete = { deleteCalls++ },
                onBackClick = {},
                onRecordLoop = {},
                onImportVideo = {},
            )
        }

        composeTestRule.onNodeWithTag("gallery_tile_1").performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("Exit selection").performClick()

        composeTestRule.onNodeWithTag("gallery_action_bar").assertDoesNotExist()
        assertEquals(0, deleteCalls)
    }
}
