package com.example.videodownloader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Two calm Aurora waves only. Motion is deliberately slow and low-amplitude. */
@Composable
internal fun AuroraAnimatedBackground(
    modifier: Modifier = Modifier,
    isActive: Boolean
) {
    val motion = remember { Animatable(0f) }

    LaunchedEffect(isActive) {
        if (isActive) {
            while (true) {
                motion.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 26000,
                        easing = LinearEasing
                    )
                )
                motion.snapTo(0f)
            }
        } else {
            motion.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0D1633),
                        Color(0xFF171D43),
                        Color(0xFF251B4A)
                    )
                )
            )
            .drawBehind {
                val phase = motion.value * (2f * Math.PI.toFloat())
                val driftA = kotlin.math.sin(phase)
                val driftB = kotlin.math.sin(phase + 2.1f)
                val w = size.width
                val h = size.height

                // Broad, restrained blue glow.
                val blueCenter = Offset(
                    w * (0.84f - 0.018f * driftA),
                    h * (0.08f + 0.009f * driftB)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x665F6DDA), Color.Transparent),
                        center = blueCenter,
                        radius = w * 0.82f
                    ),
                    radius = w * 0.82f,
                    center = blueCenter
                )

                // Soft violet glow, with no extra wave layers.
                val violetCenter = Offset(
                    w * (0.06f + 0.020f * driftB),
                    h * (0.58f + 0.012f * driftA)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x554A83C9), Color.Transparent),
                        center = violetCenter,
                        radius = w * 0.76f
                    ),
                    radius = w * 0.76f,
                    center = violetCenter
                )

                // Upper wave.
                val upperWave = Path().apply {
                    moveTo(0f, h * (0.08f + 0.008f * driftA))
                    cubicTo(
                        w * 0.28f, h * (0.01f + 0.009f * driftB),
                        w * 0.66f, h * (0.22f + 0.010f * driftA),
                        w, h * (0.09f + 0.008f * driftB)
                    )
                    lineTo(w, h * (0.34f + 0.008f * driftA))
                    cubicTo(
                        w * 0.70f, h * (0.43f + 0.008f * driftB),
                        w * 0.30f, h * (0.20f + 0.009f * driftA),
                        0f, h * (0.35f + 0.008f * driftB)
                    )
                    close()
                }
                drawPath(
                    path = upperWave,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0x224E75C9),
                            Color(0x447B59C9),
                            Color(0x114E75C9)
                        ),
                        start = Offset(0f, h * 0.08f),
                        end = Offset(w, h * 0.35f)
                    )
                )

                // Lower wave. No middle wave or extra ribbons.
                val lowerWave = Path().apply {
                    moveTo(0f, h * (0.78f + 0.008f * driftB))
                    cubicTo(
                        w * 0.30f, h * (0.65f + 0.008f * driftA),
                        w * 0.66f, h * (0.94f + 0.009f * driftB),
                        w, h * (0.77f + 0.008f * driftA)
                    )
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    path = lowerWave,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0x114E75C9),
                            Color(0x337B59C9),
                            Color(0x224E75C9)
                        ),
                        start = Offset(0f, h * 0.70f),
                        end = Offset(w, h)
                    )
                )
            }
    )
}

/**
 * Filmstrip Flow: this is the actual progress bar, not a decorative route.
 * The filled width is driven only by the smoothed foreground-service percentage.
 * Film perforations and frame dividers move inside the filled region; there are
 * no endpoint icons, no curved path, and no simulated progress beyond the service value.
 */

@Composable
internal fun FilmstripProgressBar(
    progress: Float,
    active: Boolean,
    accent: Color,
    accentSoft: Color,
    track: Color,
    modifier: Modifier = Modifier
) {
    val phase = remember { Animatable(0f) }
    val safeProgress = progress.coerceIn(0f, 1f)

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(
            durationMillis = 450,
            easing = LinearEasing
        ),
        label = "downloadProgress"
    )

    val isComplete = safeProgress >= 0.999f

    LaunchedEffect(active, isComplete) {
        if (active && !isComplete) {
            while (true) {
                phase.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 1750,
                        easing = LinearEasing
                    )
                )
                phase.snapTo(0f)
            }
        } else {
            phase.snapTo(0f)
        }
    }

    Canvas(
        modifier = modifier.height(22.dp)
    ) {
        val width = size.width
        val height = size.height
        val radius = height / 2f
        val fillWidth = width * animatedProgress
        val fillLeft = width - fillWidth

        // Full track.
        drawRoundRect(
            color = track,
            size = Size(width, height),
            cornerRadius = CornerRadius(radius, radius)
        )

        if (fillWidth <= 0.5f) return@Canvas

        // Exact service progress: fill grows from RIGHT to LEFT.
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    accentSoft.copy(alpha = 0.98f),
                    accent,
                    accent.copy(alpha = 0.92f)
                ),
                startX = fillLeft,
                endX = width
            ),
            topLeft = Offset(fillLeft, 0f),
            size = Size(fillWidth, height),
            cornerRadius = CornerRadius(radius, radius)
        )

        // Keep all film details inside the actual completed portion.
        clipRect(
            left = fillLeft,
            top = 0f,
            right = width,
            bottom = height
        ) {
            val dp = density
            val holeWidth = 4.2f * dp
            val holeHeight = 3.2f * dp
            val holeRadius = CornerRadius(1.2f * dp, 1.2f * dp)
            val holeStep = 18f * dp
            val horizontalShift = phase.value * holeStep

            // Film perforations move from RIGHT to LEFT.
            var holeX = width - 8f * dp - holeWidth - horizontalShift

            while (holeX > fillLeft + 5f * dp) {
                if (holeX < width - 4f * dp) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.34f),
                        topLeft = Offset(holeX, 3.0f * dp),
                        size = Size(holeWidth, holeHeight),
                        cornerRadius = holeRadius
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.34f),
                        topLeft = Offset(
                            holeX,
                            height - 3.0f * dp - holeHeight
                        ),
                        size = Size(holeWidth, holeHeight),
                        cornerRadius = holeRadius
                    )
                }

                holeX -= holeStep
            }

            // Film frame cells also move from RIGHT to LEFT.
            val frameWidth = 13f * dp
            val frameHeight = 7f * dp
            val frameStep = 23f * dp
            val frameShift = phase.value * frameStep

            var frameX = width - 8f * dp - frameWidth - frameShift

            while (frameX > fillLeft + 3f * dp) {
                if (frameX + frameWidth < width - 3f * dp) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.16f),
                        topLeft = Offset(
                            frameX,
                            (height - frameHeight) / 2f
                        ),
                        size = Size(frameWidth, frameHeight),
                        cornerRadius = CornerRadius(1.5f * dp, 1.5f * dp)
                    )

                    // Thin center divider.
                    drawLine(
                        color = Color.White.copy(alpha = 0.24f),
                        start = Offset(
                            frameX + frameWidth * 0.5f,
                            height * 0.39f
                        ),
                        end = Offset(
                            frameX + frameWidth * 0.5f,
                            height * 0.61f
                        ),
                        strokeWidth = 0.8f * dp
                    )
                }

                frameX -= frameStep
            }

            // Highlight travels from RIGHT to LEFT.
            if (active && !isComplete && fillWidth > 18f * dp) {
                val highlightX = (
                        width - phase.value * fillWidth
                        ).coerceIn(
                        fillLeft + 7f * dp,
                        width - 7f * dp
                    )

                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.38f),
                            Color.Transparent
                        ),
                        startX = highlightX - 7f * dp,
                        endX = highlightX + 7f * dp
                    ),
                    topLeft = Offset(highlightX - 7f * dp, 5f * dp),
                    size = Size(14f * dp, height - 10f * dp),
                    cornerRadius = CornerRadius(5f * dp, 5f * dp)
                )
            }
        }

        // Leading edge follows the real progress from RIGHT to LEFT.
        if (!isComplete && fillWidth > 3f) {
            drawLine(
                color = Color.White.copy(alpha = 0.78f),
                start = Offset(fillLeft, 5f * density),
                end = Offset(fillLeft, height - 5f * density),
                strokeWidth = 1.5f * density
            )
        }

        // Completed state remains static.
        if (isComplete) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.13f),
                topLeft = Offset(2f * density, 2f * density),
                size = Size(
                    width - 4f * density,
                    height - 4f * density
                ),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = 1f * density)
            )
        }
    }
}

/** A compact film-frame scanner used only while extracting video metadata. */

@Composable
internal fun AnalysisAnimation(
    accent: Color,
    muted: Color,
    track: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val scanFraction = remember { Animatable(0f) }
    val scanGlow = remember { Animatable(0.58f) }

    LaunchedEffect(isActive) {
        if (isActive) {
            while (true) {
                scanFraction.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        1750,
                        easing = LinearEasing
                    )
                )
                scanFraction.snapTo(0f)

                scanGlow.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        650,
                        easing = FastOutSlowInEasing
                    )
                )
                scanGlow.animateTo(
                    targetValue = 0.58f,
                    animationSpec = tween(
                        650,
                        easing = FastOutSlowInEasing
                    )
                )
            }
        } else {
            scanFraction.snapTo(0f)
            scanGlow.snapTo(0.58f)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            val left = size.width * 0.12f
            val top = size.height * 0.14f
            val panelWidth = size.width * 0.76f
            val panelHeight = size.height * 0.72f

            drawRoundRect(
                color = track.copy(alpha = 0.72f),
                topLeft = Offset(left, top),
                size = Size(panelWidth, panelHeight),
                cornerRadius = CornerRadius(9.dp.toPx())
            )

            drawRoundRect(
                color = accent.copy(alpha = 0.42f),
                topLeft = Offset(left, top),
                size = Size(panelWidth, panelHeight),
                cornerRadius = CornerRadius(9.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )

            val gap = 7.dp.toPx()
            val tileWidth = (panelWidth - 4f * gap) / 3f
            val tileTop = top + 9.dp.toPx()
            val tileHeight = panelHeight - 18.dp.toPx()

            val tileColors = listOf(
                Color(0xFF526FAE),
                Color(0xFF665A9D),
                Color(0xFF3D7194)
            )

            for (index in 0..2) {
                val tileLeft = left + gap + index * (tileWidth + gap)

                drawRoundRect(
                    color = tileColors[index].copy(alpha = 0.62f),
                    topLeft = Offset(tileLeft, tileTop),
                    size = Size(tileWidth, tileHeight),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )

                drawLine(
                    color = Color.White.copy(alpha = 0.42f),
                    start = Offset(
                        tileLeft + 5.dp.toPx(),
                        tileTop + tileHeight * 0.68f
                    ),
                    end = Offset(
                        tileLeft + tileWidth - 5.dp.toPx(),
                        tileTop + tileHeight * 0.68f
                    ),
                    strokeWidth = 1.2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            // Scanner starts at the RIGHT and moves to the LEFT.
            val scannerX = left + panelWidth * (1f - scanFraction.value)

            drawLine(
                color = accent.copy(alpha = 0.22f * scanGlow.value),
                start = Offset(scannerX, top - 2.dp.toPx()),
                end = Offset(scannerX, top + panelHeight + 2.dp.toPx()),
                strokeWidth = 12.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            drawLine(
                color = Color(0xFFE3DCFF).copy(
                    alpha = 0.90f * scanGlow.value
                ),
                start = Offset(scannerX, top - 1.dp.toPx()),
                end = Offset(scannerX, top + panelHeight + 1.dp.toPx()),
                strokeWidth = 1.8.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        Text(
            text = "جاري فحص الفيديو واستخراج الجودات",
            color = muted,
            fontSize = 11.sp
        )
    }
}