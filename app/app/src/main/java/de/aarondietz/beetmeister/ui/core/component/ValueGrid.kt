package de.aarondietz.beetmeister.ui.core.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement

/**
 * Lays out a label/value pair on the left and a label/value pair on the right
 * inside a single [Row]. Used by Overview and Settings for compact read-only
 * data rows.
 *
 * Tags are applied to the value [Text]s only (not the labels, not the
 * row container) so that:
 *  - the per-field readback contracts from the E2E test harness plan
 *    (e.g. `assertControllerInfoProtocolVersion`,
 *    `assertValveEnabledValue`) can address each value node uniquely.
 *  - the same `onAllNodesWithTag(tag)[index]` pattern works for both the
 *    "left of pair N" and the "right of pair N" cases.
 *
 * @param leftValueModifier  applied to the left value [Text] (or ignored if
 *                           the value is still loading). Use [Modifier.testTag]
 *                           to make the left value addressable by the E2E
 *                           robot.
 * @param rightValueModifier applied to the right value [Text] the same way.
 */
@Composable
internal fun ValueGridRow(
    leftLabel: String,
    leftValue: String?,
    rightLabel: String,
    rightValue: String?,
    modifier: Modifier = Modifier,
    leftValueModifier: Modifier = Modifier,
    rightValueModifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ValueCell(
            label = leftLabel,
            value = leftValue,
            modifier = Modifier.weight(1f),
            valueModifier = leftValueModifier,
        )
        ValueCell(
            label = rightLabel,
            value = rightValue,
            modifier = Modifier.weight(1f),
            valueModifier = rightValueModifier,
        )
    }
}

@Composable
private fun ValueCell(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    valueModifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = valueModifier,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}
