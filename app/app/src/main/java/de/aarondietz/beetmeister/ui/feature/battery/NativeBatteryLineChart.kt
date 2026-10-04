package de.aarondietz.beetmeister.ui.feature.battery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

data class BatteryChartPoint(
    val sequenceNumber: Long,
    val timestampLabel: String,
    val millivolts: Int,
)

/**
 * Clean native Android Compose Canvas line chart for battery history.
 *
 * Features:
 * - Smooth cubic bezier curve matching Material 3 primary palette.
 * - Subtle vertical gradient fill beneath the curve fading to transparent.
 * - Subtle horizontal gridlines with voltage labels.
 * - Time axis labels along the bottom.
 * - Interactive touch scrubbing: displays a vertical guideline, highlighted point,
 *   and notifies [onScrubPoint] of the currently selected reading.
 */
@Composable
fun NativeBatteryLineChart(
    points: List<BatteryChartPoint>,
    modifier: Modifier = Modifier,
    onScrubPoint: ((BatteryChartPoint?) -> Unit)? = null,
) {
    if (points.size < 2) return

    val textMeasurer = rememberTextMeasurer()
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val gridLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val textStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
    )
    val scrubColor = MaterialTheme.colorScheme.tertiary

    var scrubIndex by remember { mutableStateOf<Int?>(null) }

    val minMv = remember(points) { points.minOf { it.millivolts } }
    val maxMv = remember(points) { points.maxOf { it.millivolts } }
    val rangePadding = remember(minMv, maxMv) {
        val diff = (maxMv - minMv).coerceAtLeast(40)
        (diff * 0.15f).roundToInt().coerceAtLeast(15)
    }
    val chartMin = (minMv - rangePadding).coerceAtLeast(0)
    val chartMax = maxMv + rangePadding
    val range = (chartMax - chartMin).coerceAtLeast(1)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(points) {
                detectTapGestures(
                    onPress = { offset ->
                        val idx = findClosestPointIndex(offset.x, size.width.toFloat(), points.size)
                        scrubIndex = idx
                        onScrubPoint?.invoke(idx?.let { points[it] })
                    },
                    onTap = {
                        scrubIndex = null
                        onScrubPoint?.invoke(null)
                    },
                )
            }
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val idx = findClosestPointIndex(offset.x, size.width.toFloat(), points.size)
                        scrubIndex = idx
                        onScrubPoint?.invoke(idx?.let { points[it] })
                    },
                    onDragEnd = {
                        scrubIndex = null
                        onScrubPoint?.invoke(null)
                    },
                    onDragCancel = {
                        scrubIndex = null
                        onScrubPoint?.invoke(null)
                    },
                    onDrag = { change, _ ->
                        val idx = findClosestPointIndex(change.position.x, size.width.toFloat(), points.size)
                        scrubIndex = idx
                        onScrubPoint?.invoke(idx?.let { points[it] })
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
            val leftPadding = 56.dp.toPx()
            val rightPadding = 16.dp.toPx()
            val topPadding = 16.dp.toPx()
            val bottomPadding = 28.dp.toPx()

            val chartWidth = size.width - leftPadding - rightPadding
            val chartHeight = size.height - topPadding - bottomPadding

            if (chartWidth <= 0 || chartHeight <= 0) return@Canvas

            // 1. Draw horizontal grid lines & Y labels (3 guide lines: min, mid, max)
            val gridSteps = 2
            for (i in 0..gridSteps) {
                val ratio = i.toFloat() / gridSteps
                val y = topPadding + chartHeight * (1f - ratio)
                val mvValue = chartMin + (range * ratio).roundToInt()

                drawLine(
                    color = gridLineColor,
                    start = Offset(leftPadding, y),
                    end = Offset(leftPadding + chartWidth, y),
                    strokeWidth = 1.dp.toPx(),
                )

                val labelText = "$mvValue mV"
                val measured = textMeasurer.measure(labelText, textStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = (leftPadding - measured.size.width - 8.dp.toPx()).coerceAtLeast(0f),
                        y = y - (measured.size.height / 2f),
                    ),
                )
            }

            // 2. Compute coordinates for each point
            val coords = points.mapIndexed { index, point ->
                val x = leftPadding + (index.toFloat() / (points.size - 1)) * chartWidth
                val yFraction = (point.millivolts - chartMin).toFloat() / range
                val y = topPadding + chartHeight * (1f - yFraction)
                Offset(x, y)
            }

            // 3. Draw filled gradient under curve
            val fillPath = Path().apply {
                moveTo(coords.first().x, topPadding + chartHeight)
                lineTo(coords.first().x, coords.first().y)
                for (i in 0 until coords.size - 1) {
                    val p0 = coords[i]
                    val p1 = coords[i + 1]
                    val midX = (p0.x + p1.x) / 2f
                    cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                }
                lineTo(coords.last().x, topPadding + chartHeight)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.28f),
                        primaryColor.copy(alpha = 0.02f),
                    ),
                    startY = topPadding,
                    endY = topPadding + chartHeight,
                ),
            )

            // 4. Draw smooth bezier line
            val strokePath = Path().apply {
                moveTo(coords.first().x, coords.first().y)
                for (i in 0 until coords.size - 1) {
                    val p0 = coords[i]
                    val p1 = coords[i + 1]
                    val midX = (p0.x + p1.x) / 2f
                    cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                }
            }

            drawPath(
                path = strokePath,
                color = primaryColor,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                ),
            )

            // 5. Draw point dots if reasonable count
            if (points.size <= 30) {
                coords.forEach { offset ->
                    drawCircle(
                        color = surfaceColor,
                        radius = 4.dp.toPx(),
                        center = offset,
                    )
                    drawCircle(
                        color = primaryColor,
                        radius = 2.5.dp.toPx(),
                        center = offset,
                    )
                }
            }

            // 6. Draw X labels (first, middle, last)
            val labelIndices = if (points.size >= 3) {
                listOf(0, points.size / 2, points.size - 1)
            } else {
                listOf(0, points.size - 1)
            }
            labelIndices.distinct().forEach { idx ->
                val label = points[idx].timestampLabel
                val measured = textMeasurer.measure(label, textStyle)
                val xPos = coords[idx].x - (measured.size.width / 2f)
                val clampedX = xPos.coerceIn(leftPadding, leftPadding + chartWidth - measured.size.width)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(clampedX, topPadding + chartHeight + 6.dp.toPx()),
                )
            }

            // 7. Draw interactive scrub indicator
            scrubIndex?.let { idx ->
                if (idx in coords.indices) {
                    val scrubOffset = coords[idx]
                    // Guideline
                    drawLine(
                        color = scrubColor.copy(alpha = 0.8f),
                        start = Offset(scrubOffset.x, topPadding),
                        end = Offset(scrubOffset.x, topPadding + chartHeight),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    // Outer pulsing ring
                    drawCircle(
                        color = scrubColor.copy(alpha = 0.25f),
                        radius = 8.dp.toPx(),
                        center = scrubOffset,
                    )
                    // Inner solid dot
                    drawCircle(
                        color = scrubColor,
                        radius = 4.5.dp.toPx(),
                        center = scrubOffset,
                    )
                }
            }
        }
    }
}

private fun findClosestPointIndex(touchX: Float, width: Float, count: Int): Int? {
    if (count < 2 || width <= 0f) return null
    val fraction = (touchX / width).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
}
