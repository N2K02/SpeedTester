package com.speedtester.app

import android.graphics.Paint
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val dialCyan = Color(0xFF26E3EE)
private val dialBlue = Color(0xFF138CF7)
private val dialPurple = Color(0xFFA580FF)

/**
 * Speedtest-inspired, fully native Canvas dial; no images, WebViews or external dependencies.
 * The target speed may change several times a second. A critically damped spring continuously
 * retargets the needle and never overshoots a speed reading.
 */
@Composable
internal fun SpeedometerDial(
    speedMbps: Float,
    isUpload: Boolean,
    resetKey: Int,
    modifier: Modifier = Modifier
) {
    val safeSpeed = SpeedometerScale.sanitize(speedMbps)
    var scale by remember(resetKey) { mutableFloatStateOf(1000f) }
    LaunchedEffect(safeSpeed, resetKey) { scale = SpeedometerScale.scaleFor(safeSpeed, scale) }
    val angle by animateFloatAsState(
        targetValue = SpeedometerScale.angleFor(safeSpeed, scale),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "Speedometer needle angle"
    )
    val animatedSpeed by animateFloatAsState(
        targetValue = safeSpeed,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "Speedometer numeric readout"
    )
    val tint = if (isUpload) dialPurple else dialCyan
    val outerTrack = MaterialTheme.colorScheme.surfaceContainerHighest
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(312.dp)
            .semantics {
                contentDescription = (if (isUpload) "Upload " else "Download ") +
                    String.format(Locale.US, "%.1f megabits per second", safeSpeed)
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height * 0.48f)
            val radius = min(size.width * 0.435f, size.height * 0.46f)
            val diameter = radius * 2f
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val strokeWidth = 17.dp.toPx()
            drawArc(
                color = outerTrack,
                startAngle = SpeedometerScale.START_DEGREES,
                sweepAngle = SpeedometerScale.SWEEP_DEGREES,
                useCenter = false,
                topLeft = arcTopLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
            val sweep = (angle - SpeedometerScale.START_DEGREES).coerceIn(0f, SpeedometerScale.SWEEP_DEGREES)
            if (sweep > 0.01f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(dialBlue, dialCyan, dialPurple, dialBlue), center),
                    startAngle = SpeedometerScale.START_DEGREES,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            val tickOuter = radius - 24.dp.toPx()
            for (i in 0..48) {
                val degrees = SpeedometerScale.START_DEGREES + i * 5f
                val radians = Math.toRadians(degrees.toDouble())
                val isMajor = i % 6 == 0
                val tickInner = tickOuter - (if (isMajor) 11.dp.toPx() else 5.dp.toPx())
                fun tickOffset(r: Float) = Offset(
                    center.x + cos(radians).toFloat() * r,
                    center.y + sin(radians).toFloat() * r
                )
                drawLine(
                    if (degrees <= angle) tint else tickColor.copy(alpha = if (isMajor) 0.82f else 0.45f),
                    start = tickOffset(tickInner), end = tickOffset(tickOuter),
                    strokeWidth = (if (isMajor) 2.1f else 0.9f).dp.toPx(),
                    cap = StrokeCap.Round
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
            SpeedometerScale.marks(scale).forEachIndexed { index, mark ->
                val radians = Math.toRadians(
                    (SpeedometerScale.START_DEGREES +
                        SpeedometerScale.dialFractions[index] * SpeedometerScale.SWEEP_DEGREES).toDouble()
                )
                val r = radius - 48.dp.toPx()
                val labelX = center.x + cos(radians).toFloat() * r
                val labelY = center.y + sin(radians).toFloat() * r + 4.dp.toPx()
                val label = if (mark < 1000f) String.format(Locale.US, "%.0f", mark) else
                    String.format(Locale.US, "%.0f", mark)
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(label, labelX, labelY, textPaint)
                }
            }

            val radians = angle * PI / 180.0
            val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())
            val perpendicular = Offset(-direction.y, direction.x)
            val start = Offset(center.x - direction.x * 11.dp.toPx(),
                center.y - direction.y * 11.dp.toPx())
            val tip = Offset(center.x + direction.x * (radius - 62.dp.toPx()),
                center.y + direction.y * (radius - 62.dp.toPx()))
            val halfWidth = 5.dp.toPx()
            val needle = Path().apply {
                moveTo(start.x + perpendicular.x * halfWidth, start.y + perpendicular.y * halfWidth)
                lineTo(tip.x, tip.y)
                lineTo(start.x - perpendicular.x * halfWidth, start.y - perpendicular.y * halfWidth)
                close()
            }
            drawPath(needle, tint.copy(alpha = 0.28f), style = Stroke(width = 7.dp.toPx()))
            drawPath(
                needle,
                brush = Brush.linearGradient(
                    listOf(tint.copy(alpha = 0.75f), if (isUpload) Color(0xFFC4ADFF) else Color(0xFFB1F7FF)),
                    start = start, end = tip
                )
            )
            drawCircle(dialBlue.copy(alpha = 0.42f), radius = 11.dp.toPx(), center = center)
            drawCircle(Color(0xFF131C2E), radius = 7.dp.toPx(), center = center)
            drawCircle(tint, radius = 2.dp.toPx(), center = center)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (isUpload) "↑  Upload" else "↓  Download",
                color = tint,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                String.format(Locale.US, "%.1f", animatedSpeed),
                color = labelColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.displayLarge
            )
            Text("Mbps", color = tickColor, style = MaterialTheme.typography.titleMedium)
        }
    }
}
