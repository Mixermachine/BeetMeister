package de.aarondietz.beetmeister.ui.core.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standard spacing tokens for BeetMeister UI layout.
 * Exposed through MaterialTheme.spacing.
 */
@Immutable
data class BeetSpacing(
    val screenHorizontal: Dp = 16.dp,
    val screenTop: Dp = 0.dp,
    val screenBottom: Dp = 12.dp,
    val listGap: Dp = 10.dp,
    val cardHorizontal: Dp = 14.dp,
    val cardVertical: Dp = 12.dp,
    val elementGap: Dp = 8.dp,
    val headerGap: Dp = 6.dp,
    val gridRowVertical: Dp = 2.dp,
) {
    val screenContentPadding: PaddingValues
        get() = PaddingValues(
            start = screenHorizontal,
            end = screenHorizontal,
            top = screenTop,
            bottom = screenBottom,
        )

    val cardPadding: PaddingValues
        get() = PaddingValues(
            horizontal = cardHorizontal,
            vertical = cardVertical,
        )
}

val LocalBeetSpacing = staticCompositionLocalOf { BeetSpacing() }

/**
 * Accessor for layout spacing values matching MaterialTheme.colorScheme and MaterialTheme.typography.
 */
val MaterialTheme.spacing: BeetSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalBeetSpacing.current
