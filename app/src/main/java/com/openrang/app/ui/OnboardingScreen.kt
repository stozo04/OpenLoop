package com.openrang.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onGetStartedClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F0C20), Color(0xFF15102A), Color(0xFF070510))
                )
            )
    ) {
        // Horizontal swiper of the three pages
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            OnboardingPageContent(page = page)
        }

        // Bottom Controls Overlays
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 54.dp, start = 28.dp, end = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Pager Dots Indicator
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(if (isSelected) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) NeonCoral else Color.White.copy(alpha = 0.3f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // 2. Navigation Actions
            if (pagerState.currentPage < 2) {
                // Next Screen Circular Glass Button
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(GlassWhite)
                        .border(1.dp, GlassWhiteBorder, CircleShape)
                        .clickable {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "→",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                // Pulse Glowing "GET STARTED" primary action
                Button(
                    onClick = onGetStartedClick,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCoral),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                ) {
                    Text(
                        text = "LET'S GO! 🪃",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun OnboardingPageContent(page: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Logo Spacer
        Text(
            text = "🪃 OPENRANG",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.4f),
            letterSpacing = 3.sp
        )

        // Center visual card (animated Compose drawings)
        Box(
            modifier = Modifier
                .size(260.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0x331E1E2E))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Render specific looping animation based on the page index
            when (page) {
                0 -> SkateboardKickflipAnimation()
                1 -> FloatingBubblesAnimation()
                2 -> ConfettiExplosionAnimation()
            }
        }

        // Bottom text copy
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val title = when (page) {
                0 -> "ALWAYS FREE"
                1 -> "ALWAYS OPEN"
                else -> "LET'S GO!"
            }

            val tagline = when (page) {
                0 -> "Create Loops that Wow — No Subscriptions!"
                1 -> "Built by Everyone, For Everyone"
                else -> "Just Point, Tap & Boom!"
            }

            val subtitle = when (page) {
                0 -> "Unlock full creative power with zero ads, zero paywalls, and unlimited high-quality local loops."
                1 -> "100% open-source with local processing under your control. No data collection, ever."
                else -> "No accounts required. Start recording stunning speed-controlled loops instantly."
            }

            Text(
                text = title,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = if (page == 0) NeonCoral else if (page == 1) NeonPurple else Color.White,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = tagline,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }

        // Spacer to leave room for indicators/buttons
        Spacer(modifier = Modifier.height(86.dp))
    }
}

// 1. Skateboard Animation (Infinite Loops)
@Composable
fun SkateboardKickflipAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "SkateLoop")
    
    // Forward-backward looping rotation of the board
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "boardRotation"
    )

    // Forward-backward hopping displacement
    val hopY by infiniteTransition.animateFloat(
        initialValue = 30f,
        targetValue = -30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "boardHop"
    )

    Canvas(modifier = Modifier.size(160.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2 + hopY

        // Draw dynamic neon looping background circles
        drawCircle(
            color = NeonCoral.copy(alpha = 0.1f),
            radius = cx * 0.9f,
            center = Offset(cx, h / 2)
        )
        drawCircle(
            color = NeonCoral.copy(alpha = 0.2f),
            radius = cx * 0.6f,
            center = Offset(cx, h / 2),
            style = Stroke(width = 1.dp.toPx())
        )

        // Draw Skateboard deck rotates in place
        androidx.compose.ui.graphics.drawscope.withTransform({
            rotate(degrees = rotation, pivot = Offset(cx, cy))
        }) {
            // Board Deck
            drawRoundRect(
                color = NeonCoral,
                topLeft = Offset(cx - 50.dp.toPx(), cy - 12.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(100.dp.toPx(), 24.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx(), 12.dp.toPx())
            )
            // Left Wheel
            drawCircle(
                color = Color.White,
                radius = 7.dp.toPx(),
                center = Offset(cx - 30.dp.toPx(), cy + 14.dp.toPx())
            )
            // Right Wheel
            drawCircle(
                color = Color.White,
                radius = 7.dp.toPx(),
                center = Offset(cx + 30.dp.toPx(), cy + 14.dp.toPx())
            )
        }
    }
}

// 2. Bubble Floating Animation (Infinite Loops)
@Composable
fun FloatingBubblesAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "BubbleLoop")

    // Y position float loop
    val bubbleY1 by infiniteTransition.animateFloat(
        initialValue = 40f,
        targetValue = -40f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bubble1"
    )
    val bubbleY2 by infiniteTransition.animateFloat(
        initialValue = -30f,
        targetValue = 30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bubble2"
    )

    Canvas(modifier = Modifier.size(160.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2

        // Draw large purple looping ring
        drawCircle(
            color = NeonPurple.copy(alpha = 0.15f),
            radius = cx * 0.8f,
            center = Offset(cx, cy)
        )

        // Draw Bubble 1
        drawCircle(
            color = NeonPurple.copy(alpha = 0.5f),
            radius = 28.dp.toPx(),
            center = Offset(cx - 20.dp.toPx(), cy + bubbleY1),
            style = Stroke(width = 2.dp.toPx())
        )
        // Bubble 1 highlight
        drawCircle(
            color = Color.White.copy(alpha = 0.7f),
            radius = 4.dp.toPx(),
            center = Offset(cx - 28.dp.toPx(), cy + bubbleY1 - 12.dp.toPx())
        )

        // Draw Bubble 2
        drawCircle(
            color = Color.Cyan.copy(alpha = 0.5f),
            radius = 20.dp.toPx(),
            center = Offset(cx + 30.dp.toPx(), cy + bubbleY2),
            style = Stroke(width = 2.dp.toPx())
        )
        // Bubble 2 highlight
        drawCircle(
            color = Color.White.copy(alpha = 0.7f),
            radius = 3.dp.toPx(),
            center = Offset(cx + 24.dp.toPx(), cy + bubbleY2 - 8.dp.toPx())
        )
    }
}

// 3. Confetti Animation (Infinite Loops)
@Composable
fun ConfettiExplosionAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "ConfettiLoop")

    // Dynamic expansion of distance from center (explodes out, sucks in)
    val explodeScale by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "confettiScale"
    )

    Canvas(modifier = Modifier.size(160.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2

        // Draw central camera shutter ring indicator
        drawCircle(
            color = Color.White.copy(alpha = 0.05f),
            radius = 48.dp.toPx(),
            center = Offset(cx, cy)
        )
        drawCircle(
            color = NeonCoral.copy(alpha = 0.3f),
            radius = 36.dp.toPx() * explodeScale,
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx())
        )

        // Confetti pieces expanding outwards radially
        val pieceDistance = 55.dp.toPx() * explodeScale
        val colors = listOf(NeonCoral, NeonPurple, Color.Cyan, Color.Yellow)

        for (i in 0 until 8) {
            val angleRad = (i * 45) * (Math.PI / 180.0)
            val px = cx + (pieceDistance * Math.cos(angleRad)).toFloat()
            val py = cy + (pieceDistance * Math.sin(angleRad)).toFloat()
            
            drawRect(
                color = colors[i % colors.size],
                topLeft = Offset(px - 4.dp.toPx(), py - 4.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(8.dp.toPx(), 8.dp.toPx())
            )
        }
    }
}
