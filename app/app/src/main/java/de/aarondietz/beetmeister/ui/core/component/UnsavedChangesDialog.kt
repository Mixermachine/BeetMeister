package de.aarondietz.beetmeister.ui.core.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp

@Composable
internal fun UnsavedChangesDialog(
    title: String,
    body: String,
    continueEditingLabel: String,
    discardLabel: String,
    saveAndLeaveLabel: String,
    saveEnabled: Boolean,
    onContinueEditing: () -> Unit,
    onDiscard: () -> Unit,
    onSaveAndLeave: () -> Unit,
) {
    Dialog(
        onDismissRequest = onContinueEditing,
        properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onContinueEditing) {
                        Text(continueEditingLabel)
                    }
                    TextButton(onClick = onDiscard) {
                        Text(discardLabel)
                    }
                    TextButton(onClick = onSaveAndLeave, enabled = saveEnabled) {
                        Text(saveAndLeaveLabel)
                    }
                }
            }
        }
    }
}
