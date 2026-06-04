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
 * Writes to public Downloads so the file survives test APK teardown, with a unique per-run name:
 * a FIXED name EACCES-es on the second run — the first run's file is orphaned when AGP uninstalls
 * the test APK, and the fresh install may not overwrite another owner's file under scoped storage.
 * Pull the newest after the test:
 * `adb shell ls -t /sdcard/Download/openloop_loopifying-processing-42pct_*.png | head -1`
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
            "openloop_loopifying-processing-42pct_${System.currentTimeMillis()}.png",
        )
        val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(screenshot).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "Failed to write ${screenshot.absolutePath}"
            }
        }
    }
}
