package com.openrang.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.graphics.drawscope.rotate

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F0C20), Color(0xFF15102A), Color(0xFF070510))
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
            OnboardingPageContent(page = page)
        }

        // Bottom Controls Overlays
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp, start = 28.dp, end = 28.dp),
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

            Spacer(modifier = Modifier.height(28.dp))

            // 2. Navigation Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Page 0 (Slide 1): Show only Next button in the center (Catchy Glowing Neon Gradient!)
                if (pagerState.currentPage == 0) {
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
                            .clickable {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        ArrowRightIcon(
                            modifier = Modifier.size(24.dp),
                            color = Color.White
                        )
                    }
                }

                // Page 1 (Slide 2): Show Back (Neon Purple Glass) and Next (Glowing Neon) side-by-side
                if (pagerState.currentPage == 1) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(GlassWhite)
                            .border(2.dp, NeonPurple, CircleShape)
                            .clickable {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
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
                            .clickable {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(2)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        ArrowRightIcon(
                            modifier = Modifier.size(24.dp),
                            color = Color.White
                        )
                    }
                }

                // Page 2 (Slide 3): Show "LET'S GO! 🪃" Button in clean, full-width glory
                if (pagerState.currentPage == 2) {
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
                            .clickable {
                                onGetStartedClick()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "LET'S GO! 🪃",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 1.5.sp
                        )
                    }
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
            val glowColor = when (page) {
                0 -> NeonCoral.copy(alpha = 0.25f)
                1 -> NeonPurple.copy(alpha = 0.25f)
                else -> Color.Cyan.copy(alpha = 0.25f)
            }
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .background(glowColor, CircleShape)
                    .blur(56.dp)
            )

            // Center visual card - premium frosted glass with high-definition generated asset
            Box(
                modifier = Modifier
                    .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0x1AFFFFFF))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                val drawableRes = when (page) {
                    0 -> com.openrang.app.R.drawable.onboarding_skater
                    1 -> com.openrang.app.R.drawable.onboarding_bubbles
                    else -> com.openrang.app.R.drawable.onboarding_confetti
                }

                Image(
                    painter = painterResource(id = drawableRes),
                    contentDescription = "Onboarding visual asset representing loops",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Bottom text copy
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val title = when (page) {
                0 -> "No Subscriptions & No Ads"
                1 -> "Built by Everyone, For Everyone"
                else -> "Just Point, Tap & Loop!"
            }

            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
        }
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
        rotate(degrees = rotation, pivot = Offset(cx, cy)) {
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
            
        }
    }
}

@Composable
fun ArrowLeftIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 2.dp.toPx()
        
        // Draw horizontal line
        drawLine(
            color = color,
            start = Offset(w * 0.25f, h * 0.5f),
            end = Offset(w * 0.75f, h * 0.5f),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        // Draw upper arrow tip
        drawLine(
            color = color,
            start = Offset(w * 0.25f, h * 0.5f),
            end = Offset(w * 0.45f, h * 0.3f),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        // Draw lower arrow tip
        drawLine(
            color = color,
            start = Offset(w * 0.25f, h * 0.5f),
            end = Offset(w * 0.45f, h * 0.7f),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@Composable
fun ArrowRightIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 2.dp.toPx()
        
        // Draw horizontal line
        drawLine(
            color = color,
            start = Offset(w * 0.25f, h * 0.5f),
            end = Offset(w * 0.75f, h * 0.5f),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        // Draw upper arrow tip
        drawLine(
            color = color,
            start = Offset(w * 0.75f, h * 0.5f),
            end = Offset(w * 0.55f, h * 0.3f),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        // Draw lower arrow tip
        drawLine(
            color = color,
            start = Offset(w * 0.75f, h * 0.5f),
            end = Offset(w * 0.55f, h * 0.7f),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}
