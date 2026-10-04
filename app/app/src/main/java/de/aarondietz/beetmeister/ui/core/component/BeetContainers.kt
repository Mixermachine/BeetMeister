package de.aarondietz.beetmeister.ui.core.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.aarondietz.beetmeister.ui.core.theme.spacing

/**
 * Standard list container enforcing consistent screen padding and item gaps across all screens.
 */
@Composable
fun BeetLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = MaterialTheme.spacing.screenContentPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(MaterialTheme.spacing.listGap),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * Standard elevated card enforcing consistent container styling and internal content padding.
 */
@Composable
fun BeetCard(
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    contentPadding: PaddingValues = MaterialTheme.spacing.cardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = colors,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}
