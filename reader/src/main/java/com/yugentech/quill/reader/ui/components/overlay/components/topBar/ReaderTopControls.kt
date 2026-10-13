package com.yugentech.quill.reader.ui.components.overlay.components.topBar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(isVisible: Boolean, bookTitle: String, onBackClick: () -> Unit,
    onTocClick: () -> Unit, onSoundClick: () -> Unit, onSettingsClick: () -> Unit,
    isBookmarked: Boolean, onSearchClick: () -> Unit, onBookmarkClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // Keep the popup's anchor alive when the reader's auto-hide timer expires.
    AnimatedVisibility(isVisible || menu, enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
        TopAppBar(title = { Text(bookTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = onTocClick) { Icon(Icons.Default.Menu, "Chapters and bookmarks") }
                IconButton(onClick = onSearchClick) { Icon(Icons.Default.Search, "Search in book") }
                IconButton(onClick = onBookmarkClick) { Icon(if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    if (isBookmarked) "Remove bookmark here" else "Bookmark this place") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Reader options") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Reading settings") }, onClick = { menu = false; onSettingsClick() })
                        DropdownMenuItem(text = { Text("Ambient sounds") }, onClick = { menu = false; onSoundClick() })
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }
}
