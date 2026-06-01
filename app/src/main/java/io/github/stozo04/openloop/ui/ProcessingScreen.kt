package io.github.stozo04.openloop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.R
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.OpenLoopBackground
import io.github.stozo04.openloop.ui.theme.OverlayWhite

/**
 * Full-screen "rendering" surface shown while the boomerang is being created
 * ([OpenLoopUiState.Processing]). A centered neon spinner over the app's dark field, with a caption
 * and a live percentage.
 *
 * [progress] is a lambda (not a value) so the high-frequency render-progress read is deferred into
 * the percentage [Text]'s own scope — only that text recomposes as progress ticks, not the whole
 * screen (Lesson 016).
 *
 * The system back button is intentionally CONSUMED here: mid-render cancel ("oops") is out of scope
 * for slice 02, so back must not silently abort the in-flight Transformer or finish the Activity.
 * Loopifying runs in a WorkManager worker (Issue #40) so leaving the app is safe when notifications
 * are granted.
 */
@Composable
fun ProcessingScreen(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    showBackgroundExportHint: Boolean = false,
) {
    BackHandler(enabled = true) { /* consume: no mid-render cancel in slice 02 */ }

    OpenLoopBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("processing_screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = ElectricLime,
                trackColor = OverlayWhite,
            )
            Text(
                text = stringResource(R.string.processing_title),
                modifier = Modifier.padding(top = 24.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${(progress().coerceIn(0f, 1f) * 100).toInt()}%",
                modifier = Modifier.padding(top = 8.dp),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    if (showBackgroundExportHint) {
                        R.string.processing_background_hint_no_notifications
                    } else {
                        R.string.processing_background_hint
                    },
                ),
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
