package com.openrang.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openrang.app.camera.CameraManager

// Let's define premium theme colors
val GlassWhite = Color(0x33FFFFFF)
val GlassWhiteBorder = Color(0x4DFFFFFF)
val NeonCoral = Color(0xFFFF5252)
val NeonPurple = Color(0xFF7C4DFF)
val DeepCharcoal = Color(0xCC1A1A1D)

@Composable
fun CameraScreen(
    viewModel: OpenRangViewModel,
    cameraManager: CameraManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Set up standard aspect-ratio responsive PreviewView
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Trigger Camera binding when LifecycleOwner changes
    LaunchedEffect(lifecycleOwner) {
        cameraManager.startCamera(lifecycleOwner, previewView)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Camera Viewfinder
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Translucent Glassmorphic Gradient Top Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DeepCharcoal,
                            Color.Transparent
                        )
                    )
                )
                .padding(top = 28.dp, bottom = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🪃 OPENRANG",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 4.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "TAP TO LOOP",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCoral,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // 3. Glassmorphic Control Overlay & Shutter Button at bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            DeepCharcoal
                        )
                    )
                )
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp, top = 40.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Placeholder (for future settings / gallery preview)
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(GlassWhite)
                        .border(1.dp, GlassWhiteBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "1.5s",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                // Shutter Button with stunning dual-ring glowing aesthetics
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .clip(CircleShape)
                        .background(GlassWhite)
                        .border(3.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            // Capture action will be wired here in Phase 2
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(NeonCoral, NeonPurple)
                                )
                            )
                    )
                }

                // Switch Camera / Lens Toggle Button
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(GlassWhite)
                        .border(1.dp, GlassWhiteBorder, CircleShape)
                        .clickable {
                            cameraManager.toggleCamera(lifecycleOwner, previewView)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Custom drawn camera flip icon using custom Compose drawing
                    CameraFlipIcon(
                        modifier = Modifier.size(24.dp),
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun CameraFlipIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    // Custom Vector icon representing a clean camera switch action
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        
        // Draw the circular flip arrow outline elegantly
        drawArc(
            color = color,
            startAngle = 45f,
            sweepAngle = 270f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
        
        // Draw arrows on the arc tips
        val arrowLength = 5.dp.toPx()
        // Upper arrow tip
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.15f),
            end = androidx.compose.ui.geometry.Offset(w * 0.85f - arrowLength, h * 0.15f),
            strokeWidth = 2.dp.toPx()
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.15f),
            end = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.15f + arrowLength),
            strokeWidth = 2.dp.toPx()
        )
        
        // Lower arrow tip
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.85f),
            end = androidx.compose.ui.geometry.Offset(w * 0.15f + arrowLength, h * 0.85f),
            strokeWidth = 2.dp.toPx()
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.85f),
            end = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.85f - arrowLength),
            strokeWidth = 2.dp.toPx()
        )
    }
}
