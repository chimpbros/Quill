package com.yugentech.quill.aira.context

import com.yugentech.quill.aira.intent.model.Intent
import com.yugentech.quill.aira.rag.*
import kotlinx.coroutines.CancellationException
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

sealed interface EvidenceResult {
    data class Ready(val text: String, val scopeLabel: String, val requiresSpoilerPermission: Boolean,
        val sources: List<SourceExcerpt> = emptyList()) : EvidenceResult
    data class Clarify(val resolution: ScopeResolution.Clarify) : EvidenceResult
    data class Missing(val message: String) : EvidenceResult
}

class BookEvidence(private val extractor: EpubTextExtractor) {
    suspend fun build(filePath: String?, title: String, spoilerLock: Boolean, route: Intent.BookRelated,
        selection: String?, request: ReaderRequestContext): EvidenceResult {
        if (filePath == null) return unavailable(selection)
        return try {
            extractor.withPublication(filePath) { publication ->
                val structure = PublicationStructure.load(publication)
                build(publication, structure, title, spoilerLock, route, selection, request)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            if (!selection.isNullOrBlank()) EvidenceResult.Ready(
                "Supplementary retrieval failed. Explain the supplied selection; do not infer that the term has no meaning in the book.",
                "Selected passage", false)
            else EvidenceResult.Missing(error.message ?: "Book text retrieval failed. Please try again.")
        }
    }

    private fun unavailable(selection: String?) = if (selection.isNullOrBlank()) EvidenceResult.Missing(
        "Book text is unavailable. Download or reimport this book to answer from its text.")
    else EvidenceResult.Ready("Supplementary book text is unavailable. The supplied selection remains usable.", "Selected passage", false)

    suspend fun build(publication: Publication, structure: PublicationStructure, title: String, spoilerLock: Boolean,
        route: Intent.BookRelated, selection: String?, request: ReaderRequestContext): EvidenceResult {
        if (route.plan.scope == RequestedScope.RECENT_SCENE)
            return SceneEvidence.build(publication, structure, selection, request, route.entities)
        val resolved = ScopeResolver.resolve(structure, route.plan, request.currentLocator, title, spoilerLock)
        if (resolved is ScopeResolution.Clarify) return EvidenceResult.Clarify(resolved)
        resolved as ScopeResolution.Ready
        if (route.plan.scope !in setOf(RequestedScope.RELEVANT_PASSAGES, RequestedScope.PASSAGE)) {
            val text = structure.text(resolved.start, resolved.end)
            return if (text.isBlank() && selection.isNullOrBlank()) EvidenceResult.Missing("No book text is available within that reading scope.")
                else EvidenceResult.Ready(text, resolved.label, resolved.requiresSpoilerPermission)
        }
        data class Range(val spine: Int, val from: Int, val to: Int)
        val ranges = mutableListOf<Range>()
        fun addAround(point: TextPoint) {
            if (point < resolved.start || point >= resolved.end) return
            val text = structure.documents[point.spine].text
            var from = text.lastIndexOf('\n', (point.offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            // One paragraph before and after each hit; never cross the permitted boundary.
            if (from > 0) from = text.lastIndexOf('\n', (from - 2).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            var to = text.indexOf('\n', point.offset).let { if (it < 0) text.length else it }
            if (to < text.length) to = text.indexOf('\n', to + 1).let { if (it < 0) text.length else it }
            if (point.spine == resolved.start.spine) from = from.coerceAtLeast(resolved.start.offset)
            if (point.spine == resolved.end.spine) to = to.coerceAtMost(resolved.end.offset)
            if (to > from) ranges.add(Range(point.spine, from, to))
        }
        request.selectionLocator?.let(structure::point)?.let(::addAround)
        val literalTerms = (listOfNotNull(route.plan.term, route.characterName) + route.entities).distinct().filter { it.isNotBlank() }
        val search = BookTextSearch(publication)
        try {
            // An exact term is searched before any broader keyword evidence, without an AI index.
            for (term in literalTerms) {
                search.start(term)
                while (true) {
                    val batch = search.next() ?: break
                    for (locator in batch) structure.point(locator)?.let(::addAround)
                    if (batch.lastOrNull()?.let(structure::point)?.let { it >= resolved.end } == true) break
                }
            }
        } finally { search.close() }
        if (route.plan.term == null) {
            val keywords = (route.keywords + route.queryVariations.flatMap { it.split(Regex("\\s+")) })
                .filter { it.length >= 4 && it.lowercase() !in STOP_WORDS }.distinct().take(20)
            val scored = mutableListOf<Pair<TextPoint, Int>>()
            structure.documents.forEachIndexed { spine, doc ->
                var offset = 0
                for (paragraph in doc.text.split('\n')) {
                    val point = TextPoint(spine, offset)
                    if (point >= resolved.start && point < resolved.end) {
                        val normalized = ScopeResolver.normalized(paragraph)
                        val score = keywords.count { normalized.contains(ScopeResolver.normalized(it)) }
                        if (score > 0) scored.add(point to score)
                    }
                    offset += paragraph.length + 1
                }
            }
            scored.sortedByDescending { it.second }.take(18).forEach { addAround(it.first) }
        }
        val merged = mutableListOf<Range>()
        for (range in ranges.sortedWith(compareBy<Range> { it.spine }.thenBy { it.from })) {
            val last = merged.lastOrNull()
            if (last != null && last.spine == range.spine && range.from <= last.to) {
                merged[merged.lastIndex] = last.copy(to = maxOf(last.to, range.to))
            } else merged.add(range)
        }
        val sources = merged.mapIndexed { index, range ->
            val section = structure.sections.filter { TextPoint(range.spine, range.from) >= it.start && TextPoint(range.spine, range.from) < it.end }
                .maxByOrNull { it.depth }
            val text = structure.documents[range.spine].text.substring(range.from, range.to).trim()
            SourceExcerpt("S${index + 1}", section?.title ?: "Book passage", text,
                structure.locator(TextPoint(range.spine, range.from), text).toJSON().toString())
        }
        val evidence = sources.joinToString("\n\n") { "[${it.id}: ${it.title}]\n${it.text}" }
        if (evidence.isBlank() && selection.isNullOrBlank()) return EvidenceResult.Missing(
            if (route.plan.term != null) "No literal matches for “${route.plan.term}” were found within the permitted reading scope."
            else "No relevant passages were found within the permitted reading scope. Try a name, term, or selected passage.")
        return EvidenceResult.Ready(evidence.ifBlank { "Supplementary lookup found no matches. Explain the supplied selection." },
            resolved.label, resolved.requiresSpoilerPermission, sources)
    }
    companion object {
        private val STOP_WORDS = setOf("what", "when", "where", "which", "this", "that", "about", "explain", "please", "book", "story", "chapter", "does", "have", "from", "with", "summarize", "recap")
    }
}
