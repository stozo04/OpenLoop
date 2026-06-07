package io.github.stozo04.openloop.ui

import android.content.ContentResolver
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.stozo04.openloop.R
import io.github.stozo04.openloop.ui.theme.Canvas
import io.github.stozo04.openloop.ui.components.PrimaryButton
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.OverlayWhite
import io.github.stozo04.openloop.ui.theme.OverlayWhiteBorder
import io.github.stozo04.openloop.ui.theme.SurfaceContainerLow

// ── Page data model ──

private data class OnboardingPage(
    // Big headline shown over the bottom of the full-bleed media.
    val headline: String,
    // Glass "pills" beneath the headline — the persistent trust promises (no subscriptions, open source).
    val badges: List<String> = emptyList(),
    val drawableRes: Int,
    // When set, the page plays this looping raw-resource video full-bleed instead of [drawableRes].
    // [drawableRes] is still required: in inspection mode (Compose @Preview) the page falls back to it,
    // since an ExoPlayer can't render in a preview (see LocalInspectionMode in OnboardingPageMedia).
    val videoRawRes: Int? = null,
)

// Single-screen onboarding: one strong value/trust screen, then straight into the camera. Per Google's
// onboarding guidance (developer.android.com/design/ui/mobile/guides/patterns/onboarding) a one-tap app
// doesn't need a multipage walkthrough, and camera permission is primed in-context at the shutter — not
// as a startup gate (developer.android.com/training/permissions/usage-notes).
private val onboardingPage = OnboardingPage(
    headline = "Free. Forever.",
    badges = listOf(
        "No Subscriptions · No Ads",
        "Open source · 100% on your phone",
    ),
    drawableRes = R.drawable.onboarding_skater,
    videoRawRes = R.raw.onboarding_loop_1,
)

// ── Onboarding Screen ──

@Composable
fun OnboardingScreen(
    onGetStartedClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // Gradient is only a fallback behind the media (load delay / letterboxing); the full-bleed
            // media draws over it edge-to-edge.
            .background(
                Brush.verticalGradient(
                    colors = listOf(SurfaceContainerLow, Canvas)
                )
            )
    ) {
        // Full-bleed, edge-to-edge looping demo with a baked-in scrim so the floating title/CTA stay
        // legible over any frame. Playback is lifecycle-aware (pauses when backgrounded).
        OnboardingPageMedia(playing = true)

        // Floating bottom stack — title + trust badges + the single launch CTA — anchored over the scrim
        // and kept clear of the system bars by safeDrawingPadding (media stays edge-to-edge behind it).
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(bottom = 32.dp, start = 28.dp, end = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OnboardingTitle()

            Spacer(modifier = Modifier.height(36.dp))

            GetStartedButton(
                onClick = onGetStartedClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Launch CTA ──
// Standalone composable (not inlined into the Column above) so it carries a stable test tag and keeps
// the screen body lean. See OnboardingScreenTest.

@Composable
internal fun GetStartedButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PrimaryButton(
        text = "LET'S GO!",
        onClick = onClick,
        modifier = modifier,
        testTag = "onboarding_cta",
    )
}

// ── Full-bleed page media ──

/**
 * The full-screen onboarding hero: the looping product video (or a still drawable in inspection mode),
 * cropped to fill the whole screen, with a baked-in vertical scrim so the floating title and CTA stay
 * legible over any frame. The top scrim protects the status-bar icons; the heavier bottom scrim sits
 * under the title/CTA.
 */
@Composable
private fun OnboardingPageMedia(playing: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize()) {
        // A Compose @Preview / inspection host can't run an ExoPlayer, so fall back to the static
        // drawable there; on-device the video plays full-bleed.
        if (onboardingPage.videoRawRes != null && !LocalInspectionMode.current) {
            OnboardingVideoCard(
                rawResId = onboardingPage.videoRawRes,
                playing = playing,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(id = onboardingPage.drawableRes),
                contentDescription = "Onboarding visual asset representing loops",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Legibility scrim: dark at the very top (status bar) and heavy at the bottom (title/CTA),
        // clear through the middle so the media reads as the hero.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Canvas.copy(alpha = 0.55f),
                        0.16f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1.0f to Canvas.copy(alpha = 0.92f),
                    )
                )
        )
    }
}

// ── Title + benefit badges ──

@Composable
private fun OnboardingTitle() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = onboardingPage.headline,
            style = MaterialTheme.typography.displayMedium,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        onboardingPage.badges.forEach { badge ->
            Spacer(modifier = Modifier.height(12.dp))
            BenefitBadge(text = badge)
        }
    }
}

/** Small frosted "pill" with a leading check — a persistent trust promise (no subscriptions, open source). */
@Composable
private fun BenefitBadge(text: String) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(OverlayWhite)
            .border(1.dp, OverlayWhiteBorder, CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckIcon(modifier = Modifier.size(14.dp), color = ElectricLime)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

// ── Onboarding video card ──

/**
 * Autoplay, looping, muted preview of a bundled raw-resource video, cropped to fill the full-bleed
 * onboarding page. Mirrors the gallery's `LoopingVideoOverlay` (ExoPlayer in an [AndroidView], released
 * in a [DisposableEffect]) but inline and silent. Plays only while [playing] and pauses on `ON_STOP` so a
 * backgrounded app stays idle. There is intentionally no *runtime* still-image fallback: the bundled clip
 * is the product demo, and a decode failure simply leaves the scrimmed gradient background (per the
 * onboarding-video PRD). The static `OnboardingPage.drawableRes` is only the inspection-mode fallback —
 * [OnboardingPageMedia] swaps it in under `LocalInspectionMode` since a Compose @Preview can't host an
 * ExoPlayer.
 *
 * `REPEAT_MODE_ALL` loops the whole (unclipped) item — the same pattern `LoopingVideoOverlay` uses;
 * the known repeat stall only affects *clipped* items, which this is not. The raw clip is referenced
 * via the `android.resource://` URI scheme, which Media3's `DefaultDataSource` routes to its
 * raw-resource reader — the non-deprecated replacement for `RawResourceDataSource.buildRawResourceUri`.
 */
@OptIn(UnstableApi::class)
@Composable
private fun OnboardingVideoCard(
    rawResId: Int,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val uri = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$rawResId".toUri()
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f // muted: onboarding plays silently
            playWhenReady = playing
            prepare()
        }
    }
    // Lifecycle-aware playback: play only while [playing] AND the app is started, and pause on ON_STOP
    // so a backgrounded app isn't decoding (developer.android.com/media/implement/playback-app —
    // stop/release playback in onStop on API 24+). The player itself is released on leave-composition.
    LifecycleStartEffect(playing) {
        exoPlayer.playWhenReady = playing
        onStopOrDispose { exoPlayer.playWhenReady = false }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // crop-to-fill the full-bleed page
            }
        },
        // The looping clip is a decorative product demo; the title below is the real label. Give
        // TalkBack a short description rather than leaving the bare PlayerView unlabeled.
        modifier = modifier.semantics {
            contentDescription = "Looping demo of a boomerang video"
        },
    )
}

// ── Check Icon ──

@Composable
fun CheckIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()

        drawLine(color, Offset(w * 0.18f, h * 0.52f), Offset(w * 0.42f, h * 0.76f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.42f, h * 0.76f), Offset(w * 0.84f, h * 0.26f), sw, StrokeCap.Round)
    }
}

// ── Previews ──

@Preview(showBackground = true, backgroundColor = 0xFF0F0C20, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingScreenPreview() {
    OnboardingScreen(onGetStartedClick = {})
}
