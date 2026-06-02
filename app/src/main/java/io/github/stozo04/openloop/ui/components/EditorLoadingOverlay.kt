package io.github.stozo04.openloop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.OverlayWhiteBorder

/**
 * Glassmorphic loading card over the video preview (or full-screen during delete).
 * [message] comes from [io.github.stozo04.openloop.ui.EditorLoadingKind].
 */
@Composable
fun EditorLoadingOverlay(
    message: String,
    modifier: Modifier = Modifier,
    testTag: String = "editor_loading_overlay",
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f))
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .background(OverlayScrim)
                .border(1.dp, OverlayWhiteBorder, MaterialTheme.shapes.medium)
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            CircularProgressIndicator(
                color = ElectricLime,
                strokeWidth = 3.dp,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("editor_loading_message"),
            )
        }
    }
}
