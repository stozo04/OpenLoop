package io.github.stozo04.openloop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stozo04.openloop.ui.EditorTab
import io.github.stozo04.openloop.ui.theme.CoralRed
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.TextSecondary

/** Which bottom-toolbar slot is highlighted (Trim screen or editor content tab). */
enum class EditorToolbarSlot {
    TRIM,
    SPEED,
    LOOP,
    FILTER,
}

/** Maps editor content tabs to toolbar slots (Trim/Delete are navigation actions, not [EditorTab]s). */
fun EditorTab.toToolbarSlot(): EditorToolbarSlot = when (this) {
    EditorTab.SPEED -> EditorToolbarSlot.SPEED
    EditorTab.DIRECTION -> EditorToolbarSlot.LOOP
    EditorTab.LOOKS -> EditorToolbarSlot.FILTER
}

/** Opens the editor on the tab that matches this toolbar slot (after Trim → editor navigation). */
fun EditorToolbarSlot.toEditorTab(): EditorTab = when (this) {
    EditorToolbarSlot.TRIM -> EditorTab.DIRECTION // unused — Trim stays on the Trim screen
    EditorToolbarSlot.SPEED -> EditorTab.SPEED
    EditorToolbarSlot.LOOP -> EditorTab.DIRECTION
    EditorToolbarSlot.FILTER -> EditorTab.LOOKS
}

private val ToolbarLabelStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(
        lineHeight = 16.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = true),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

/** Shared bottom tool bar: Trim · Speed · Loop · Filter · Delete (reference trim mock). */
@Composable
fun EditorBottomToolbar(
    activeSlot: EditorToolbarSlot?,
    onTrimClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onLoopClick: () -> Unit,
    onFilterClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .navigationBarsPadding()
            .testTag("editor_bottom_toolbar"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            ToolbarSlot(
                label = "Trim",
                icon = Icons.Outlined.ContentCut,
                testTag = "tab_trim",
                active = activeSlot == EditorToolbarSlot.TRIM,
                onClick = onTrimClick,
            )
            ToolbarSlot(
                label = "Speed",
                icon = Icons.Outlined.Speed,
                testTag = "tab_speed",
                active = activeSlot == EditorToolbarSlot.SPEED,
                onClick = onSpeedClick,
            )
            ToolbarSlot(
                label = "Loop",
                icon = Icons.Outlined.AllInclusive,
                testTag = "tab_loop",
                active = activeSlot == EditorToolbarSlot.LOOP,
                onClick = onLoopClick,
            )
            ToolbarSlot(
                label = "Filter",
                icon = Icons.Outlined.FilterAlt,
                testTag = "tab_filter",
                active = activeSlot == EditorToolbarSlot.FILTER,
                onClick = onFilterClick,
            )
            ToolbarSlot(
                label = "Delete",
                icon = Icons.Outlined.Delete,
                testTag = "tab_delete",
                active = false,
                destructive = true,
                onClick = onDeleteClick,
            )
        }
    }
}

@Composable
private fun ToolbarSlot(
    label: String,
    icon: ImageVector,
    testTag: String,
    active: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val accent = if (destructive) CoralRed else ElectricLime
    val iconTint = when {
        destructive -> CoralRed
        active -> ElectricLime
        else -> Color.White
    }
    val labelTint = when {
        destructive -> CoralRed
        active -> ElectricLime
        else -> TextSecondary
    }

    Column(
        modifier = Modifier
            .clickable(role = Role.Tab) { onClick() }
            .testTag(testTag)
            .semantics {
                contentDescription = label
                selected = active
                role = Role.Tab
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .height(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
        Text(
            text = label,
            color = labelTint,
            style = ToolbarLabelStyle,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
