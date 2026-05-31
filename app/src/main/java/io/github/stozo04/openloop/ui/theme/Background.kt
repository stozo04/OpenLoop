package io.github.stozo04.openloop.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's shared full-bleed background (Issue #35, section 1). Fills the screen with the neutral
 * near-black [Canvas] surface via `MaterialTheme.colorScheme.background`, so every chrome screen sits
 * on the same field instead of re-declaring a `Color.Black` literal.
 *
 * Screens that draw over live video — the camera viewfinder and the gallery play overlay — keep a
 * true-black letterbox on purpose (the standard backdrop for a `RESIZE_MODE_FIT` video surface) and
 * do NOT use this wrapper. Everything else (Trim, Editor, Processing, loaders) should.
 */
@Composable
fun OpenLoopBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        content = content,
    )
}
