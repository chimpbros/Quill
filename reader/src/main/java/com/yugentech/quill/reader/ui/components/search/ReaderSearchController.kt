package com.yugentech.quill.reader.ui.components.search

import com.yugentech.quill.aira.rag.BookTextSearch
import com.yugentech.quill.aira.rag.PublicationStructure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

data class ReaderSearchState(val query: String = "", val readSoFar: Boolean = false,
    val results: List<Locator> = emptyList(), val loading: Boolean = false,
    val finished: Boolean = true, val error: String? = null)

class ReaderSearchController(private val publication: Publication, private val structure: PublicationStructure,
    private val origin: Locator?, private val scope: CoroutineScope) : AutoCloseable {
    private val _state = MutableStateFlow(ReaderSearchState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var search: BookTextSearch? = null
    fun update(query: String = state.value.query, readSoFar: Boolean = state.value.readSoFar) {
        job?.cancel()
        val old = search
        search = null
        old?.close()
        _state.value = ReaderSearchState(query, readSoFar, loading = query.isNotBlank(), finished = query.isBlank())
        job = scope.launch {
            if (query.isBlank()) return@launch
            delay(300)
            val current = BookTextSearch(publication)
            search = current
            try {
                withContext(Dispatchers.IO) { current.start(query.trim()) }
                load(current)
            } catch (e: CancellationException) { current.close(); throw e }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); if (search === current) _state.value = state.value.copy(loading = false, finished = true, error = e.message ?: "Search failed.") }
        }
    }
    fun more() {
        val current = search ?: return
        if (state.value.loading || state.value.finished) return
        _state.value = state.value.copy(loading = true)
        job = scope.launch { try { load(current) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); if (search === current) _state.value = state.value.copy(loading = false, finished = true, error = e.message) } }
    }
    private suspend fun load(current: BookTextSearch) {
        val ceiling = origin?.let { structure.readingBoundary(it) }
        var batch: List<Locator>?
        do {
            batch = withContext(Dispatchers.IO) { current.next() }
            currentCoroutineContext().ensureActive()
            if (search !== current) return
            val filtered = batch?.filter { !state.value.readSoFar || (ceiling != null && structure.point(it)?.let { p -> p <= ceiling } == true) }.orEmpty()
            if (filtered.isNotEmpty()) {
                _state.value = state.value.copy(results = state.value.results + filtered, loading = false, finished = false)
                return
            }
        } while (batch != null)
        _state.value = state.value.copy(loading = false, finished = true)
    }
    override fun close() { job?.cancel(); search?.close(); search = null }
}
