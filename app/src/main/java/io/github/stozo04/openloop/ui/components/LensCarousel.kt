package io.github.stozo04.openloop.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.R
import io.github.stozo04.openloop.camera.lens.Lens
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.OverlayArtBackdrop
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.OverlayWhite
import io.github.stozo04.openloop.ui.theme.OverlayWhiteBorder
import io.github.stozo04.openloop.ui.theme.TimerTextStyle

/** Diameter of an unselected lens thumbnail. Also the accessibility floor for the row. */
private val ThumbnailSize = 56.dp

/** The selected thumbnail grows — the "you are wearing this" cue, before colour or ring. */
private val SelectedThumbnailSize = 72.dp

/**
 * The lens tray: a horizontal rail of circular lens thumbnails with a clear-and-close control,
 * shown over the bottom of the live viewfinder (docs/PRD-camera-lenses.md §6.2).
 *
 * Stateless and hoisted, like [io.github.stozo04.openloop.ui.ShutterButton] — the whole rail can be
 * exercised in Compose tests without binding a camera.
 *
 * Deliberately no category tab row: the reference UI has one, but with a handful of lenses it is
 * chrome around an empty room. Add it when there are enough lenses to need grouping.
 */
@Composable
fun LensCarousel(
    lenses: List<Lens>,
    activeLens: Lens?,
    onSelect: (Lens) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag("lens_carousel"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (activeLens != null) {
            Box(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(OverlayWhite)
                    .background(OverlayScrim)
                    .border(1.dp, OverlayWhiteBorder, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(text = activeLens.displayName, style = TimerTextStyle, color = Color.White)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Clear + close, mirroring the reference UI's leading ✕.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(OverlayWhite)
                    .border(1.dp, OverlayWhiteBorder, CircleShape)
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = "Close lenses" }
                    .testTag("lens_close"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_lens_close),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color.White,
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(lenses, key = { it.name }) { lens ->
                    LensThumbnail(
                        lens = lens,
                        selected = lens == activeLens,
                        onClick = { onSelect(lens) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LensThumbnail(
    lens: Lens,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val size by animateDpAsState(
        targetValue = if (selected) SelectedThumbnailSize else ThumbnailSize,
        label = "lens_thumb_size",
    )

    Box(
        modifier = Modifier
            .size(SelectedThumbnailSize)
            .semantics {
                contentDescription = lens.displayName
                this.selected = selected
            }
            .testTag("lens_thumb_${lens.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                // Light backdrop, not the usual dark scrim — see OverlayArtBackdrop. The selected
                // ring stays ElectricLime (the app's active colour everywhere else): its outer edge
                // meets the video, so lime reads over both dark and bright scenes, where a dark
                // ring would disappear against a dark one. The size jump is the primary cue.
                .background(OverlayArtBackdrop)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) ElectricLime else OverlayWhiteBorder,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = lens.thumbnailRes),
                contentDescription = null,
                // Fit, not Crop: the Shades art is a wide 400x144 strip and cropping it to a
                // circle would leave a black disc. The padding is deliberately small — Fit already
                // insets wide art heavily, and 8.dp on top of that shrank Shades to a speck.
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(size)
                    .padding(3.dp),
            )
        }
    }
}
