package com.yugentech.quill.reader.ui.components.tocSheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugentech.quill.aira.rag.PublicationStructure
import com.yugentech.quill.database.entity.BookmarkEntity
import com.yugentech.quill.reader.ui.components.bookmark.BookmarkList
import org.readium.r2.shared.publication.Locator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(structure: PublicationStructure, currentLocator: Locator?, bookmarks: List<BookmarkEntity>,
    onTocItemClick: (String) -> Unit, onBookmarkJump: (BookmarkEntity) -> Unit,
    onBookmarkRename: (String, String) -> Unit, onBookmarkRemove: (BookmarkEntity) -> Unit,
    onBookmarkRestore: (BookmarkEntity) -> Unit, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val active = remember(structure, currentLocator) { structure.active(currentLocator) }
    val index = structure.sections.indexOfFirst { it.id == active?.id }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = index)
    LaunchedEffect(active?.id, tab) { if (tab == 0) listState.scrollToItem(index) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Navigation", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chapters") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Bookmarks") })
        }
        if (tab == 1) BookmarkList(bookmarks, onBookmarkJump, onBookmarkRename, onBookmarkRemove, onBookmarkRestore, structure = structure)
        else LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 12.dp)) {
            itemsIndexed(structure.sections, key = { _, section -> section.id }) { _, section ->
                val selected = section.id == active?.id
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .semantics { if (selected) stateDescription = "Current chapter" }
                    .clickable { onTocItemClick(section.href) }.padding(start = 16.dp + (section.depth * 12).dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
                    Text(section.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    if (selected) Text("Current chapter", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
