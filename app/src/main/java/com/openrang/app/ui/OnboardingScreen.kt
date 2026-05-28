package com.openrang.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrang.app.R
import kotlinx.coroutines.launch

// ── Onboarding color palette ──

private val DeepIndigo = Color(0xFF0F0C20)
private val DarkPlum = Color(0xFF15102A)
private val VoidBlack = Color(0xFF070510)
private val FrostedGlass = Color(0x1AFFFFFF)
private val FrostedGlassBorder = Color.White.copy(alpha = 0.15f)

// ── Page data model ──

private data class OnboardingPage(
    val title: String,
    val drawableRes: Int,
    val glowColor: Color
)

private val onboardingPages = listOf(
    OnboardingPage(
        title = "No Subscriptions & No Ads",
        drawableRes = R.drawable.onboarding_skater,
        glowColor = NeonCoral.copy(alpha = 0.25f)
    ),
    OnboardingPage(
        title = "Built by Everyone, For Everyone",
        drawableRes = R.drawable.onboarding_bubbles,
        glowColor = NeonPurple.copy(alpha = 0.25f)
    ),
    OnboardingPage(
        title = "Just Point, Tap & Loop!",
        drawableRes = R.drawable.onboarding_confetti,
        glowColor = Color.Cyan.copy(alpha = 0.25f)
    )
)

// ── Onboarding Screen ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onGetStartedClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepIndigo, DarkPlum, VoidBlack)
                )
            )
            .safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Horizontal swiper of the three pages
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            OnboardingPageContent(page = onboardingPages[page])
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp, start = 28.dp, end = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Animated Pager Dots Indicator
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(onboardingPages.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    val dotSize by animateFloatAsState(
                        targetValue = if (isSelected) 10f else 6f,
                        label = "dotSize_$index"
                    )
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(dotSize.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) NeonCoral
                                else Color.White.copy(alpha = 0.3f)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 2. Navigation Actions with animated transitions
            OnboardingNavigation(
                currentPage = pagerState.currentPage,
                onPageSelected = { targetPage ->
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(targetPage)
                    }
                },
                onGetStartedClick = onGetStartedClick
            )
        }
    }
}

// ── Navigation Controls (extracted to break ColumnScope receiver chain) ──
// IMPORTANT: This MUST remain a standalone composable — do NOT inline into the
// Column above. ColumnScope.AnimatedVisibility uses slide animations that cause
// buttons to jump from the left edge. See OnboardingNavigationTest.

@Composable
internal fun OnboardingNavigation(
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    onGetStartedClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_nav_container"),
        contentAlignment = Alignment.Center
    ) {
        // Page 0: Next button only (centered, glowing neon gradient)
        AnimatedVisibility(
            visible = currentPage == 0,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(NeonCoral, NeonPurple)
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .clickable { onPageSelected(1) }
                    .testTag("nav_next_page0"),
                contentAlignment = Alignment.Center
            ) {
                ArrowRightIcon(
                    modifier = Modifier.size(24.dp),
                    color = Color.White
                )
            }
        }

        // Page 1: Back (purple glass) + Next (neon gradient) side-by-side
        AnimatedVisibility(
            visible = currentPage == 1,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Row(
                modifier = Modifier.testTag("nav_row_page1"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(GlassWhite)
                        .border(2.dp, NeonPurple, CircleShape)
                        .clickable { onPageSelected(0) }
                        .testTag("nav_back_page1"),
                    contentAlignment = Alignment.Center
                ) {
                    ArrowLeftIcon(
                        modifier = Modifier.size(24.dp),
                        color = NeonPurple
                    )
                }

                Spacer(modifier = Modifier.width(24.dp))

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(NeonCoral, NeonPurple)
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        .clickable { onPageSelected(2) }
                        .testTag("nav_next_page1"),
                    contentAlignment = Alignment.Center
                ) {
                    ArrowRightIcon(
                        modifier = Modifier.size(24.dp),
                        color = Color.White
                    )
                }
            }
        }

        // Page 2: "LET'S GO!" full-width CTA
        AnimatedVisibility(
            visible = currentPage == 2,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(NeonCoral, NeonPurple)
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                    .clickable { onGetStartedClick() }
                    .testTag("nav_cta_page2"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "LET'S GO!",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 1.5.sp
                )
            }
        }
    }
}

// ── Page Content ──

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Ambient soft neon glow behind the frosted glass card
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .background(page.glowColor, CircleShape)
                    .blur(56.dp)
            )

            // Frosted glass visual card
            Box(
                modifier = Modifier
                    .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(FrostedGlass)
                    .border(1.dp, FrostedGlassBorder, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = page.drawableRes),
                    contentDescription = "Onboarding visual asset representing loops",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Bottom title
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = page.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Arrow Icons ──

@Composable
fun ArrowLeftIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()

        drawLine(color, Offset(w * 0.25f, h * 0.5f), Offset(w * 0.75f, h * 0.5f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.25f, h * 0.5f), Offset(w * 0.45f, h * 0.3f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.25f, h * 0.5f), Offset(w * 0.45f, h * 0.7f), sw, StrokeCap.Round)
    }
}

@Composable
fun ArrowRightIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()

        drawLine(color, Offset(w * 0.25f, h * 0.5f), Offset(w * 0.75f, h * 0.5f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.75f, h * 0.5f), Offset(w * 0.55f, h * 0.3f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.75f, h * 0.5f), Offset(w * 0.55f, h * 0.7f), sw, StrokeCap.Round)
    }
}

// ── Previews ──

@Preview(showBackground = true, backgroundColor = 0xFF0F0C20)
@Composable
private fun OnboardingScreenPreview() {
    OnboardingScreen(onGetStartedClick = {})
}

@Preview(showBackground = true, backgroundColor = 0xFF0F0C20, widthDp = 360, heightDp = 640)
@Composable
private fun OnboardingPage1Preview() {
    OnboardingPageContent(page = onboardingPages[0])
}

@Preview(showBackground = true, backgroundColor = 0xFF0F0C20, widthDp = 360, heightDp = 640)
@Composable
private fun OnboardingPage2Preview() {
    OnboardingPageContent(page = onboardingPages[1])
}

@Preview(showBackground = true, backgroundColor = 0xFF0F0C20, widthDp = 360, heightDp = 640)
@Composable
private fun OnboardingPage3Preview() {
    OnboardingPageContent(page = onboardingPages[2])
}
