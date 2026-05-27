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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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

val SwitchCameraIcon: ImageVector
    get() = ImageVector.Builder(
        name = "SwitchCamera",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = SolidColor(Color.White)
    ) {
        moveTo(20f, 4f)
        horizontalLineToRelative(-3.17f)
        lineTo(15f, 2f)
        horizontalLineTo(9f)
        lineTo(7.17f, 4f)
        horizontalLineTo(4f)
        curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
        verticalLineToRelative(12f)
        curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
        horizontalLineToRelative(16f)
        curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
        verticalLineTo(6f)
        curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
        close()
        moveTo(12f, 18f)
        curveTo(9.24f, 18f, 7f, 15.76f, 7f, 13f)
        horizontalLineTo(5f)
        lineToRelative(3.5f, -3.5f)
        lineTo(12f, 13f)
        horizontalLineToRelative(-2f)
        curveTo(10f, 14.66f, 11.34f, 16f, 12f, 16f)
        curveTo(12.8f, 16f, 13.53f, 15.68f, 14.07f, 15.16f)
        lineToRelative(1.41f, 1.41f)
        curveTo(15.42f, 17.48f, 13.82f, 18f, 12f, 18f)
        close()
        moveTo(15.5f, 16.5f)
        lineToRelative(-3.5f, -3.5f)
        horizontalLineToRelative(2f)
        curveTo(14f, 11.34f, 12.66f, 10f, 11f, 10f)
        curveTo(10.2f, 10f, 9.47f, 10.32f, 8.93f, 10.84f)
        lineTo(7.52f, 9.43f)
        curveTo(8.58f, 8.52f, 10.18f, 8f, 12f, 8f)
        curveTo(14.76f, 8f, 17f, 10.24f, 17f, 13f)
        horizontalLineToRelative(2f)
        lineToRelative(-3.5f, 3.5f)
        close()
    }.build()

@Composable
fun CameraScreen(
    viewModel: OpenRangViewModel,
    cameraManager: CameraManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val isRecording = uiState is OpenRangUiState.Recording

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
                .statusBarsPadding()
                .padding(top = 12.dp, bottom = 16.dp)
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
                    text = if (isRecording) "RECORDING BURST..." else "TAP TO LOOP",
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
                .navigationBarsPadding()
                .padding(bottom = 24.dp, start = 24.dp, end = 24.dp, top = 32.dp)
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
                        .background(if (isRecording) NeonCoral.copy(alpha = 0.2f) else GlassWhite)
                        .border(
                            width = if (isRecording) 5.dp else 3.dp,
                            color = if (isRecording) NeonCoral else Color.White,
                            shape = CircleShape
                        )
                        .padding(if (isRecording) 12.dp else 6.dp)
                        .clickable(
                            enabled = !isRecording,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            viewModel.startBurstCapture(context, cameraManager)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(
                                if (isRecording) {
                                    Brush.linearGradient(
                                        colors = listOf(NeonCoral, Color.Red)
                                    )
                                } else {
                                    Brush.linearGradient(
                                        colors = listOf(NeonCoral, NeonPurple)
                                    )
                                }
                            )
                    )
                }

                // Switch Camera / Lens Toggle Button (Sleek Glowing Neon Gradient!)
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(NeonCoral, NeonPurple)
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        .clickable {
                            cameraManager.toggleCamera(lifecycleOwner, previewView)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    CameraFlipIcon(
                        modifier = Modifier.size(28.dp),
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun CameraFlipIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 3.dp.toPx()
        val r = w * 0.33f
        val cx = w / 2f
        val cy = h / 2f

        // 1. Draw top arc (from 195 deg to 345 deg)
        drawArc(
            color = color,
            startAngle = 195f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )

        // 2. Draw bottom arc (from 15 deg to 165 deg)
        drawArc(
            color = color,
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )

        // 3. Arrowheads
        val arrowLength = 5.5.dp.toPx()

        // Top-Right Arrowhead (at 345 deg)
        val angle1 = 345f * (Math.PI / 180f)
        val ex1 = cx + (r * Math.cos(angle1)).toFloat()
        val ey1 = cy + (r * Math.sin(angle1)).toFloat()

        // Vertical line down, Horizontal line left
        drawLine(
            color = color,
            start = Offset(ex1, ey1),
            end = Offset(ex1 - arrowLength, ey1),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(ex1, ey1),
            end = Offset(ex1, ey1 + arrowLength),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )

        // Bottom-Left Arrowhead (at 165 deg)
        val angle2 = 165f * (Math.PI / 180f)
        val ex2 = cx + (r * Math.cos(angle2)).toFloat()
        val ey2 = cy + (r * Math.sin(angle2)).toFloat()

        // Vertical line up, Horizontal line right
        drawLine(
            color = color,
            start = Offset(ex2, ey2),
            end = Offset(ex2 + arrowLength, ey2),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(ex2, ey2),
            end = Offset(ex2, ey2 - arrowLength),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

