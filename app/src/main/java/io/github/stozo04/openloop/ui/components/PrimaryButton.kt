package io.github.stozo04.openloop.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OverlayWhite

/** Canonical flat-lime CTA height (Trim NEXT, onboarding, gallery close preview). */
internal val PrimaryButtonHeight = 56.dp

/** Press-scale target shared with gallery thumbnail cards (Issue #35 §3). */
internal const val PrimaryButtonPressedScale = 0.93f

/**
 * Shared primary CTA: [ElectricLime] fill, [MaterialTheme.shapes.extraLarge] pill,
 * [MaterialTheme.typography.labelLarge] + [LimeInk] when enabled.
 *
 * Visible press feedback via scale-down; a light [HapticFeedbackType.Confirm] on successful tap
 * when [enabled]. Both are suppressed while disabled (e.g. Trim NEXT below [MIN_TRIM_MS]).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
    trailingIcon: ImageVector? = null,
    enablePressFeedback: Boolean = true,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enablePressFeedback && enabled && isPressed) PrimaryButtonPressedScale else 1f,
        label = "primary_button_scale",
    )
    val taggedModifier = if (testTag != null) modifier.testTag(testTag) else modifier
    Box(
        modifier = taggedModifier
            .scale(scale)
            .height(PrimaryButtonHeight)
            .clip(shape)
            .background(if (enabled) ElectricLime else OverlayWhite)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        val labelColor = if (enabled) LimeInk else Color.White.copy(alpha = 0.4f)
        if (trailingIcon != null) {
            Row(
                modifier = Modifier.padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(text = text, color = labelColor, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Text(
                text = text,
                color = labelColor,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}
