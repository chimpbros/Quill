package com.yugentech.quill.reader.ui.components.range

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yugentech.quill.aira.rag.ResolvedTextRange

@Composable
fun RangeSelectionBar(state: ReaderRangeState, onCancel: () -> Unit, onChangeStart: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(if (state.adjustingStart) "Selecting a range · choose its start" else "Selecting a range · choose its end",
                style = MaterialTheme.typography.labelMedium)
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = onChangeStart) { Text("Change start") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangePreview(state: ReaderRangeState, onDismiss: () -> Unit, onAdjustStart: () -> Unit, onAdjustEnd: () -> Unit,
    onExplain: (ResolvedTextRange) -> Unit, onSave: () -> Unit, onNote: (String) -> Unit, onColor: (Int) -> Unit) {
    val range = state.range ?: return
    ModalBottomSheet(onDismissRequest = { if (!state.saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Selected range", style = MaterialTheme.typography.titleMedium)
            Text("Start · ${range.startLocator.title ?: "Book passage"} · “${range.startLocator.text.highlight.orEmpty().take(48)}”", style = MaterialTheme.typography.labelMedium)
            Text("End · ${range.endLocator.title ?: "Book passage"} · “${range.endLocator.text.highlight.orEmpty().takeLast(48)}”", style = MaterialTheme.typography.labelMedium)
            Text(range.text, Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp))
            Row {
                TextButton(onClick = onAdjustStart, enabled = !state.saving) { Text("Adjust start") }
                TextButton(onClick = onAdjustEnd, enabled = !state.saving) { Text("Adjust end") }
            }
            OutlinedTextField(state.note, onNote, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val colors = listOf(0xFFFFE082, 0xFFA5D6A7, 0xFF90CAF9, 0xFFCE93D8, 0xFFFF8A80)
                colors.forEachIndexed { index, value ->
                    val color = value.toInt()
                    Box(Modifier.size(40.dp).background(Color(color), CircleShape)
                        .semantics { contentDescription = "Highlight color ${index + 1}" }
                        .clickable(enabled = !state.saving) { onColor(color) }) {
                        if (state.colorInt == color) Text("✓", Modifier.padding(10.dp), color = Color.Black)
                    }
                }
            }
            state.error?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onExplain(range) }, enabled = !state.saving) { Text("Explain") }
                Button(onClick = onSave, enabled = !state.saving) { Text(if (state.saving) "Saving…" else "Save highlight") }
            }
        }
    }
}
