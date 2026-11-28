package com.speedtester.app

import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val dialCyan = Color(0xFF26E3EE)
private val dialBlue = Color(0xFF138CF7)
private val dialPurple = Color(0xFFA580FF)
// Live targets arrive about every 250 ms. A linear sweep keeps moving between updates.
private val needleAnimation = tween<Float>(durationMillis = 320, easing = LinearEasing)

@Composable
internal fun SpeedometerDial(
    speedMbps: Float,
    isUpload: Boolean,
    needleSpeedMbps: Float,
    modifier: Modifier = Modifier,
    descriptionPrefix: String? = null
) {
    val safeSpeed = SpeedometerScale.sanitize(speedMbps)
    val animatedAngle = animateFloatAsState(
        targetValue = SpeedometerScale.angleFor(needleSpeedMbps),
        animationSpec = needleAnimation,
        label = "Speedometer needle angle"
    )
    val tint = if (isUpload) dialPurple else dialCyan
    val outerTrack = MaterialTheme.colorScheme.surfaceContainerHighest
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurface

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(268.dp)
            .semantics {
                contentDescription = (descriptionPrefix ?: if (isUpload) "Upload " else "Download ") +
                    String.format(Locale.US, "%.1f megabits per second", safeSpeed)
            }
            .drawWithCache {
                val center = Offset(size.width / 2f, size.height * 0.48f)
                val radius = min(size.width * 0.435f, size.height * 0.46f)
                val arcSize = Size(radius * 2f, radius * 2f)
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val trackStroke = Stroke(width = 17.dp.toPx(), cap = StrokeCap.Butt)
                val progressStroke = Stroke(width = trackStroke.width, cap = StrokeCap.Round)
                val progressBrush = Brush.sweepGradient(listOf(dialBlue, dialCyan, dialPurple, dialBlue), center)

                val tickOuter = radius - 24.dp.toPx()
                val ticks = SpeedometerScale.ticks.map { (degrees, isMajor) ->
                    val radians = Math.toRadians(degrees.toDouble())
                    val tickInner = tickOuter - (if (isMajor) 11.dp.toPx() else 5.dp.toPx())
                    DialTick(
                        degrees = degrees,
                        start = dialPoint(center, tickInner, radians),
                        end = dialPoint(center, tickOuter, radians),
                        width = (if (isMajor) 2.1f else 0.9f).dp.toPx(),
                        inactiveColor = tickColor.copy(alpha = if (isMajor) 0.82f else 0.45f)
                    )
                }

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(
                        (labelColor.red * 255f).toInt(),
                        (labelColor.green * 255f).toInt(),
                        (labelColor.blue * 255f).toInt()
                    )
                    textSize = 11.sp.toPx()
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                }
                val labels = SpeedometerScale.marks.mapIndexed { index, mark ->
                    val degrees = SpeedometerScale.markAngle(index)
                    val position = dialPoint(center, radius - 48.dp.toPx(), Math.toRadians(degrees.toDouble()))
                    DialLabel(String.format(Locale.US, "%.0f", mark), position + Offset(0f, 4.dp.toPx()))
                }

                // Cache the needle pointing right and rotate it about the dial center when drawing.
                val needleStart = Offset(center.x - 11.dp.toPx(), center.y)
                val needleTip = Offset(center.x + radius - 62.dp.toPx(), center.y)
                val halfWidth = 5.dp.toPx()
                val needle = Path().apply {
                    moveTo(needleStart.x, needleStart.y + halfWidth)
                    lineTo(needleTip.x, needleTip.y)
                    lineTo(needleStart.x, needleStart.y - halfWidth)
                    close()
                }
                val needleGlow = tint.copy(alpha = 0.28f)
                val needleStroke = Stroke(width = 7.dp.toPx())
                val needleBrush = Brush.linearGradient(
                    listOf(tint.copy(alpha = 0.75f), if (isUpload) Color(0xFFC4ADFF) else Color(0xFFB1F7FF)),
                    start = needleStart,
                    end = needleTip
                )
                val hubGlow = dialBlue.copy(alpha = 0.42f)
                val hubBackground = Color(0xFF131C2E)
                val hubOuterRadius = 11.dp.toPx()
                val hubInnerRadius = 7.dp.toPx()
                val hubCenterRadius = 2.dp.toPx()

                // Cache geometry and paints, but render directly with Canvas.
                // This avoids relying on GraphicsLayer recording on older Android GPUs.
                onDrawBehind {
                    val angle = animatedAngle.value
                    drawArc(
                        color = outerTrack,
                        startAngle = SpeedometerScale.START_DEGREES,
                        sweepAngle = SpeedometerScale.SWEEP_DEGREES,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = trackStroke
                    )
                    ticks.forEach { tick ->
                        drawLine(tick.inactiveColor, tick.start, tick.end, tick.width, cap = StrokeCap.Round)
                    }
                    drawIntoCanvas { canvas ->
                        labels.forEach { label ->
                            canvas.nativeCanvas.drawText(label.text, label.position.x, label.position.y, textPaint)
                        }
                    }
                    val sweep = (angle - SpeedometerScale.START_DEGREES)
                        .coerceIn(0f, SpeedometerScale.SWEEP_DEGREES)
                    if (sweep > 0.01f) {
                        drawArc(
                            brush = progressBrush,
                            startAngle = SpeedometerScale.START_DEGREES,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = progressStroke
                        )
                    }
                    for (tick in ticks) {
                        if (tick.degrees > angle) break
                        drawLine(tint, tick.start, tick.end, tick.width, cap = StrokeCap.Round)
                    }
                    rotate(degrees = angle, pivot = center) {
                        drawPath(needle, needleGlow, style = needleStroke)
                        drawPath(needle, needleBrush)
                    }
                    drawCircle(hubGlow, radius = hubOuterRadius, center = center)
                    drawCircle(hubBackground, radius = hubInnerRadius, center = center)
                    drawCircle(tint, radius = hubCenterRadius, center = center)
                }
            }
    )
}

private fun dialPoint(center: Offset, radius: Float, radians: Double): Offset =
    Offset(center.x + cos(radians).toFloat() * radius, center.y + sin(radians).toFloat() * radius)

private data class DialTick(
    val degrees: Float,
    val start: Offset,
    val end: Offset,
    val width: Float,
    val inactiveColor: Color
)

private data class DialLabel(val text: String, val position: Offset)
