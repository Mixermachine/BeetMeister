package de.aarondietz.beetmeister.ui.core.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.aarondietz.beetmeister.ui.NavigationSuiteTestTags
import de.aarondietz.beetmeister.ui.core.app.TopLevelScreen

/**
 * Compact, low-profile Material 3 bottom navigation bar.
 *
 * Replaces the default bloated NavigationBar/NavigationSuiteScaffold padding:
 * - Total height: 50.dp (content) + system navigation bar insets.
 * - Perfectly centered icon + label without excess vertical empty space.
 * - Material 3 pill selection indicator matching theme colors.
 * - Preserves exact test tags for automated robots.
 */
@Composable
internal fun BeetBottomNavigationBar(
    selectedScreen: TopLevelScreen,
    onScreenSelected: (TopLevelScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelScreen.entries.forEach { screen ->
                BeetBottomNavItem(
                    screen = screen,
                    selected = selectedScreen == screen,
                    onClick = { onScreenSelected(screen) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BeetBottomNavItem(
    screen: TopLevelScreen,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }

    val iconColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "iconColor",
    )
    val pillColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "pillColor",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "textColor",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .height(26.dp)
                .padding(horizontal = 12.dp)
                .background(pillColor, RoundedCornerShape(13.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides iconColor,
            ) {
                screen.icon()
            }
        }

        Text(
            text = stringResource(screen.labelRes),
            modifier = Modifier
                .testTag(NavigationSuiteTestTags.tagFor(screen))
                .padding(top = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = textColor,
            maxLines = 1,
        )
    }
}
