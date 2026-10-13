package com.yugentech.quill.reader.ui.components.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.yugentech.quill.aira.rag.PublicationStructure
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(publication: Publication, structure: PublicationStructure, origin: Locator?,
    onResult: (Locator) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(publication, origin) { ReaderSearchController(publication, structure, origin, scope) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val state by controller.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Search in book", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(state.query, { controller.update(query = it) }, label = { Text("Word or phrase") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Read so far", Modifier.weight(1f))
            Switch(state.readSoFar, { controller.update(readSoFar = it) }, enabled = origin != null)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            state.error?.let { item { Text(it, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) } }
            if (state.query.isNotBlank() && state.finished && state.results.isEmpty() && state.error == null) item { Text("No matches found.", Modifier.padding(24.dp)) }
            itemsIndexed(state.results) { _, locator ->
                Column(Modifier.fillMaxWidth().clickable { onResult(locator) }.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(structure.active(locator)?.title ?: locator.title ?: "Book text", style = MaterialTheme.typography.labelLarge)
                    Text(buildAnnotatedString {
                        append(locator.text.before.orEmpty().takeLast(100))
                        withStyle(SpanStyle(background = MaterialTheme.colorScheme.primaryContainer)) { append(locator.text.highlight.orEmpty()) }
                        append(locator.text.after.orEmpty().take(100))
                    })
                }
            }
            if (state.loading) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            else if (!state.finished) item { TextButton(onClick = controller::more, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Load more results") } }
        }
    }
}
