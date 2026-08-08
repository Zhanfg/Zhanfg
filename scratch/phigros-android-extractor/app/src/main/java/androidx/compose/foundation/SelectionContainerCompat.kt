package androidx.compose.foundation

import androidx.compose.foundation.text.selection.SelectionContainer as MaterialSelectionContainer
import androidx.compose.runtime.Composable

/** Compatibility bridge for the migrated screen; delegates to the official selection container. */
@Composable
fun SelectionContainer(content: @Composable () -> Unit) {
    MaterialSelectionContainer(content = content)
}
