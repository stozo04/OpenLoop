package io.github.stozo04.openloop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.ui.theme.OverlayWhite
import io.github.stozo04.openloop.ui.theme.OverlayWhiteBorder

/** Shared glass circle control size (editor, gallery, trim back). */
internal val GlassIconButtonSize = 56.dp

private val GlassIconSize = 22.dp

/**
 * Glassmorphic circular icon button: [OverlayWhite] fill, border, white icon tint.
 * Used by [BackButton] and any screen that needs the same neutral chrome control.
 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(GlassIconButtonSize)
            .clip(CircleShape)
            .background(OverlayWhite)
            .border(1.dp, OverlayWhiteBorder, CircleShape)
            .clickable(role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(GlassIconSize),
        )
    }
}

/** Standard back affordance: [Icons.AutoMirrored.Filled.ArrowBack] in a glass circle. */
@Composable
fun BackButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CircleIconButton(
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
    )
}
