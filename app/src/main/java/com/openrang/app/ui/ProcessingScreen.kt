package com.openrang.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen "rendering" surface shown while the boomerang is being created
 * ([OpenRangUiState.Processing]). A centered neon spinner over the app's dark field, with a caption
 * and a live percentage.
 *
 * [progress] is a lambda (not a value) so the high-frequency render-progress read is deferred into
 * the percentage [Text]'s own scope — only that text recomposes as progress ticks, not the whole
 * screen (Lesson 016).
 *
 * The system back button is intentionally CONSUMED here: mid-render cancel ("oops") is out of scope
 * for slice 02, so back must not silently abort the in-flight Transformer or finish the Activity.
 */
@Composable
fun ProcessingScreen(
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true) { /* consume: no mid-render cancel in slice 02 */ }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("processing_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            color = NeonPurple,
            trackColor = GlassWhite,
        )
        Text(
            text = "Creating boomerang…",
            modifier = Modifier.padding(top = 24.dp),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Text(
            text = "${(progress().coerceIn(0f, 1f) * 100).toInt()}%",
            modifier = Modifier.padding(top = 8.dp),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
    }
}
