package io.github.stozo04.openloop.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.stozo04.openloop.ui.theme.OpenLoopTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Captures PR proof screenshots for Issue #41 — the full-screen [ProcessingScreen] ("Loopifying…").
 * Writes to public Downloads so the file survives test APK teardown. Pull after the test:
 * `MSYS_NO_PATHCONV=1 adb pull /sdcard/Download/openloop_loopifying-processing-42pct.png`
 */
@RunWith(AndroidJUnit4::class)
class LoopifyingScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val grantStorageRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    @Test
    fun captureLoopifyingProcessingScreen_midProgress() {
        composeTestRule.setContent {
            OpenLoopTheme {
                ProcessingScreen(progress = { 0.42f })
            }
        }
        composeTestRule.onNodeWithTag("processing_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Creating..").assertIsDisplayed()
        composeTestRule.onNodeWithText("42%").assertIsDisplayed()
        composeTestRule.waitForIdle()

        val screenshot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "openloop_loopifying-processing-42pct.png",
        )
        val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(screenshot).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "Failed to write ${screenshot.absolutePath}"
            }
        }
    }
}
