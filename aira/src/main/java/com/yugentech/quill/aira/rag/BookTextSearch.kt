package com.yugentech.quill.aira.rag

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse

/** Local literal search is independent of the AI index and provider connection. */
@OptIn(ExperimentalReadiumApi::class)
class BookTextSearch(private val publication: Publication) : AutoCloseable {
    private var iterator: SearchIterator? = null
    suspend fun start(query: String) {
        close()
        check(publication.isSearchable) { "Text search is unavailable for this book." }
        iterator = publication.search(query, SearchService.Options(caseSensitive = false,
            diacriticSensitive = false, wholeWord = false, exact = true, regularExpression = false))
        check(iterator != null) { "Unable to start book search." }
    }
    suspend fun next(): List<Locator>? {
        val batch = iterator?.next() ?: return null
        return batch.getOrElse { error("Unable to search book text: ${it.message}") }?.locators
    }
    override fun close() { iterator?.close(); iterator = null }
}
