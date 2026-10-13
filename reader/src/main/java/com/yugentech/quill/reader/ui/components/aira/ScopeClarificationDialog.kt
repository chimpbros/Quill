package com.yugentech.quill.reader.ui.components.aira

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yugentech.quill.aira.response.AiraResponse

@Composable
fun ScopeClarificationDialog(clarification: AiraResponse.Clarification, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose reading scope") },
        text = {
            Column {
                Text(clarification.question)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(clarification.choices, key = { it.id }) { choice ->
                        TextButton(onClick = { onChoose(choice.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(choice.label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
