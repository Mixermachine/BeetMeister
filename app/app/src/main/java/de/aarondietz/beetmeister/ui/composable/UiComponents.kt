package de.aarondietz.beetmeister.ui.composable

import android.graphics.PathMeasure
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import de.aarondietz.beetmeister.beet.BeetRepositoryState

@Composable
internal fun Header(state: BeetRepositoryState) {
    val info = state.controllerInfo
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = info?.deviceId ?: "Connected controller",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Live BLE session",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ConnectedStatusChip(
            label = if (state.connection.phase == de.aarondietz.beetmeister.beet.BeetConnectionPhase.Connected) {
                "Connected"
            } else {
                state.connection.phase.name
            },
            syncLabel = if (state.eventSync.active) {
                "${state.eventSync.downloaded}/${state.eventSync.total}"
            } else {
                null
            },
            progress = state.eventSync.progress,
            progressActive = state.eventSync.active,
        )
    }
}

@Composable
private fun ConnectedStatusChip(
    label: String,
    syncLabel: String?,
    progress: Float,
    progressActive: Boolean,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = if (progressActive) progress.coerceIn(0f, 1f) else 0f,
        label = "connected_status_progress",
    )
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val progressColor = MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.drawWithCache {
            val strokeWidth = 3.dp.toPx()
            val inset = strokeWidth / 2f
            val radius = (size.height / 2f) - inset
            val roundRect = RoundRect(
                rect = Rect(inset, inset, size.width - inset, size.height - inset),
                cornerRadius = CornerRadius(radius.coerceAtLeast(0f), radius.coerceAtLeast(0f)),
            )
            val basePath = Path().apply { addRoundRect(roundRect) }
            val androidBasePath = basePath.asAndroidPath()
            val pathMeasure = PathMeasure(androidBasePath, false)
            val segmentPath = android.graphics.Path()
            val segmentLength = pathMeasure.length * animatedProgress
            if (segmentLength > 0f) {
                pathMeasure.getSegment(0f, segmentLength, segmentPath, true)
            }
            val progressPath = segmentPath.asComposePath()

            onDrawWithContent {
                drawPath(
                    path = basePath,
                    color = outlineColor,
                    style = Stroke(width = strokeWidth),
                )
                if (segmentLength > 0f) {
                    drawPath(
                        path = progressPath,
                        color = progressColor,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
                drawContent()
            }
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            if (syncLabel != null) {
                Text(
                    syncLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ValueGridRow(leftLabel: String, leftValue: String, rightLabel: String, rightValue: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ValueCell(label = leftLabel, value = leftValue, modifier = Modifier.weight(1f))
        ValueCell(label = rightLabel, value = rightValue, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ValueCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun PairEnabledToggleButton(pairEnabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onToggle, modifier = modifier) {
        Text(if (pairEnabled) "Disable pair" else "Enable pair")
    }
}

@Composable
internal fun PairErrorClearButton(canClearError: Boolean, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClear, enabled = canClearError, modifier = modifier) {
        Text("Clear error")
    }
}
