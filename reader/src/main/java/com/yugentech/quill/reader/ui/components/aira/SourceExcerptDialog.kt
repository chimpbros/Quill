package com.yugentech.quill.reader.ui.components.aira

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yugentech.quill.aira.context.SourceExcerpt

@Composable
fun SourceExcerptDialog(source: SourceExcerpt, onDismiss: () -> Unit, onJump: ((SourceExcerpt) -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("[${source.id}] ${source.title}") },
        text = { Text(source.text, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { onJump?.let { jump -> TextButton(onClick = { onDismiss(); jump(source) }) { Text("View in book") } } })
}
