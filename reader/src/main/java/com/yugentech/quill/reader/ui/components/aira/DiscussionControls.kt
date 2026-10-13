package com.yugentech.quill.reader.ui.components.aira

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yugentech.quill.aira.context.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscussionControls(discussion: DiscussionContext?, sources: List<SourceExcerpt>, loading: Boolean,
    onNewTopic: () -> Unit, onExpand: () -> Unit, onSourceJump: ((SourceExcerpt) -> Unit)? = null) {
    var showSources by remember { mutableStateOf(false) }
    if (discussion != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(discussion.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onNewTopic, enabled = !loading) { Text("New topic") }
                if (discussion.lastResponse != null && discussion.request.currentLocator != null)
                    TextButton(onClick = onExpand, enabled = !loading) { Text("Include more context") }
                if (sources.isNotEmpty()) TextButton(onClick = { showSources = true }) { Text("Sources") }
            }
        }
    }
    if (showSources) SourcesSheet(sources, { showSources = false }, onSourceJump)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(sources: List<SourceExcerpt>, onDismiss: () -> Unit, onSourceJump: ((SourceExcerpt) -> Unit)? = null) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("Book sources", style = MaterialTheme.typography.titleMedium)
                sources.forEach { source ->
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("[${source.id}] ${source.title}", style = MaterialTheme.typography.labelLarge)
                    Text(source.text, style = MaterialTheme.typography.bodyMedium)
                    onSourceJump?.let { jump -> TextButton(onClick = { onDismiss(); jump(source) }) { Text("View in book") } }
                }
            }
        }
}
