package io.github.stozo04.openloop.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OutlineVariant
import io.github.stozo04.openloop.ui.theme.SurfaceContainer
import io.github.stozo04.openloop.ui.theme.SurfaceContainerHigh
import io.github.stozo04.openloop.ui.theme.TextSecondary

private val LOOP_PANEL_CORNER = 16.dp
private val LOOP_PANEL_MAX_WIDTH = 520.dp
private val CHIP_SIZE_INACTIVE = 48.dp
private val CHIP_SIZE_ACTIVE = 56.dp

private data class LoopModeChip(
    val mode: BoomerangMode,
    val accessibilityLabel: String,
    val helpTitle: String,
    val helpDescription: String,
)

private val LOOP_MODE_CHIPS = listOf(
    LoopModeChip(
        mode = BoomerangMode.FORWARD,
        accessibilityLabel = "Forward loop",
        helpTitle = "Forward",
        helpDescription = "Repeats your trim playing forward only — like a normal short loop.",
    ),
    LoopModeChip(
        mode = BoomerangMode.REVERSE,
        accessibilityLabel = "Reverse loop",
        helpTitle = "Reverse",
        helpDescription = "Repeats your trim playing backward only.",
    ),
    LoopModeChip(
        mode = BoomerangMode.FORWARD_THEN_REVERSE,
        accessibilityLabel = "Forward then reverse",
        helpTitle = "Boomerang",
        helpDescription = "Plays forward, then backward, then repeats — the classic ping-pong boomerang.",
    ),
    LoopModeChip(
        mode = BoomerangMode.REVERSE_THEN_FORWARD,
        accessibilityLabel = "Reverse then forward",
        helpTitle = "Reverse then forward",
        helpDescription = "Plays backward, then forward, then repeats — the mirror of Boomerang.",
    ),
)

/**
 * Loop (direction) tab matching the reference mock: rounded card, header + info affordance, and four
 * circular mode buttons (selected chip scales up in lime).
 */
@Composable
fun LoopTabPanel(
    mode: BoomerangMode,
    onSelectMode: (BoomerangMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var showHelp by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("loop_tab_panel"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = LOOP_PANEL_MAX_WIDTH)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(LOOP_PANEL_CORNER))
                .background(SurfaceContainer)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Select loop direction",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("loop_direction_title"),
                )
                IconButton(
                    onClick = { showHelp = true },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("loop_direction_info"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "What do the loop icons mean?",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LOOP_MODE_CHIPS.forEach { chip ->
                    LoopModeButton(
                        chip = chip,
                        selected = chip.mode == mode,
                        onClick = {
                            if (chip.mode != mode) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelectMode(chip.mode)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showHelp) {
        LoopDirectionHelpDialog(
            onDismiss = { showHelp = false },
        )
    }
}

@Composable
private fun LoopDirectionHelpDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Loop directions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LOOP_MODE_CHIPS.forEach { chip ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(SurfaceContainerHigh)
                                .border(1.dp, OutlineVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            DirectionChipIcon(
                                mode = chip.mode,
                                tint = Color.White,
                                iconSize = 22.dp,
                            )
                        }
                        Column {
                            Text(
                                text = chip.helpTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = chip.helpDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
    )
}

@Composable
private fun LoopModeButton(
    chip: LoopModeChip,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val size by animateDpAsState(
        targetValue = if (selected) CHIP_SIZE_ACTIVE else CHIP_SIZE_INACTIVE,
        label = "loop_chip_size",
    )
    val background = if (selected) ElectricLime else SurfaceContainerHigh
    val iconTint = if (selected) LimeInk else Color.White

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(1.dp, OutlineVariant, CircleShape)
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = chip.accessibilityLabel
                this.selected = selected
                role = Role.Button
            }
            .testTag("direction_chip_${chip.mode.name}"),
        contentAlignment = Alignment.Center,
    ) {
        DirectionChipIcon(
            mode = chip.mode,
            tint = iconTint,
            iconSize = if (selected) 26.dp else 22.dp,
        )
    }
}
