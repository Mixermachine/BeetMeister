package de.aarondietz.beetmeister.ui.events.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.ui.formatting.formatDuration
import kotlin.math.max

@Composable
internal fun DurationBarChart(pairTotalsSeconds: List<Int>) {
    val maxValue = max(pairTotalsSeconds.maxOrNull() ?: 0, 1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pairTotalsSeconds.forEachIndexed { index, totalSeconds ->
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Pair ${index + 1}")
                    Text(formatDuration(totalSeconds))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(Color(0xFFDDE4D7), RoundedCornerShape(50)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(totalSeconds.toFloat() / maxValue.toFloat())
                            .height(18.dp)
                            .background(Color(0xFF6B8F52), RoundedCornerShape(50)),
                    )
                }
            }
        }
    }
}
