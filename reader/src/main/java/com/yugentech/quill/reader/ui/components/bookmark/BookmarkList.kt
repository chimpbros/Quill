package com.yugentech.quill.reader.ui.components.bookmark

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yugentech.quill.database.entity.BookmarkEntity
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import kotlin.math.roundToInt

fun bookmarkLocator(bookmark: BookmarkEntity): Locator? = runCatching { Locator.fromJSON(JSONObject(bookmark.locatorJson)) }.getOrNull()

@Composable
fun BookmarkList(bookmarks: List<BookmarkEntity>, onJump: (BookmarkEntity) -> Unit,
    onRename: (String, String) -> Unit, onRemove: (BookmarkEntity) -> Unit,
    onRestore: (BookmarkEntity) -> Unit, modifier: Modifier = Modifier,
    structure: com.yugentech.quill.aira.rag.PublicationStructure? = null) {
    var editing by remember { mutableStateOf<BookmarkEntity?>(null) }
    var label by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sorted = remember(bookmarks, structure) { bookmarks.sortedWith(compareBy<BookmarkEntity> {
        bookmarkLocator(it)?.let { loc -> structure?.point(loc) } ?: com.yugentech.quill.aira.rag.TextPoint(Int.MAX_VALUE, 0)
    }.thenBy { bookmarkLocator(it)?.locations?.totalProgression ?: 0.0 }
        .thenBy { bookmarkLocator(it)?.locations?.position ?: 0 }.thenBy { it.createdAt }) }
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (sorted.isEmpty()) item { Text("No bookmarks yet. Tap the bookmark icon while reading to save a place.", Modifier.padding(24.dp)) }
            items(sorted, key = { it.id }) { bookmark ->
                val locator = remember(bookmark.locatorJson) { bookmarkLocator(bookmark) }
                val chapter = structure?.active(locator)?.title ?: locator?.title
                Row(Modifier.fillMaxWidth().clickable(enabled = locator != null) { onJump(bookmark) }.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(bookmark.label ?: chapter ?: "Saved place", style = MaterialTheme.typography.titleMedium)
                        Text(if (locator == null) "Saved location unavailable" else listOfNotNull(
                            chapter?.takeIf { bookmark.label != null },
                            locator.locations.totalProgression?.let { "${(it * 100).roundToInt()}%" }
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { editing = bookmark; label = bookmark.label.orEmpty() }) { Icon(Icons.Default.Edit, "Rename bookmark") }
                    IconButton(onClick = {
                        onRemove(bookmark)
                        scope.launch { if (snackbar.showSnackbar("Bookmark removed", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onRestore(bookmark) }
                    }) { Icon(Icons.Default.DeleteOutline, "Remove bookmark") }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
    editing?.let { bookmark ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Bookmark label") },
            text = { OutlinedTextField(label, { label = it }, label = { Text("Optional label") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(bookmark.id, label); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}
