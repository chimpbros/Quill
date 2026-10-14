package com.yugentech.quill.aira.context

import com.yugentech.quill.aira.intent.model.Intent
import com.yugentech.quill.aira.rag.*
import kotlinx.coroutines.*
import org.readium.r2.shared.publication.Publication

sealed interface EvidenceResult {
    data class Ready(val text: String, val scopeLabel: String, val requiresSpoilerPermission: Boolean,
        val sources: List<SourceExcerpt> = emptyList(), val retrievalNotice: String? = null) : EvidenceResult
    data class Clarify(val resolution: ScopeResolution.Clarify) : EvidenceResult
    data class Missing(val message: String) : EvidenceResult
}

class BookEvidence(private val extractor: EpubTextExtractor, private val structures: BookStructureStore = BookStructureStore()) {
    suspend fun build(filePath: String?, title: String, spoilerLock: Boolean, route: Intent.BookRelated,
        selection: String?, request: ReaderRequestContext, bookId: String = filePath.orEmpty(),
        preparing: suspend (String) -> Unit = {}): EvidenceResult = withContext(Dispatchers.Default) {
        if (filePath == null) return@withContext unavailable(selection)
        try {
            structures.acquire(bookId, filePath) { extractor.structure(filePath) }.use { lease ->
                build(lease.structure, title, spoilerLock, route, selection, request, preparing)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            if (!selection.isNullOrBlank()) EvidenceResult.Ready(
                "Supplementary retrieval failed. Explain the supplied selection; do not infer that the term has no meaning in the book.",
                "Selected passage", false, retrievalNotice = "Book lookup was unavailable. This answer uses the selected passage alone.")
            else EvidenceResult.Missing(error.message ?: "Book text retrieval failed. Please try again.")
        }
    }
    private fun unavailable(selection: String?) = if (selection.isNullOrBlank()) EvidenceResult.Missing(
        "Book text is unavailable. Download or reimport this book to answer from its text.")
    else EvidenceResult.Ready("Supplementary book text is unavailable. The supplied selection remains usable.", "Selected passage", false)

    suspend fun build(publication: Publication, structure: PublicationStructure, title: String, spoilerLock: Boolean,
        route: Intent.BookRelated, selection: String?, request: ReaderRequestContext): EvidenceResult =
        build(structure, title, spoilerLock, route, selection, request)

    suspend fun build(structure: PublicationStructure, title: String, spoilerLock: Boolean, route: Intent.BookRelated,
        selection: String?, request: ReaderRequestContext, preparing: suspend (String) -> Unit = {}): EvidenceResult = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()[Job]
        val check = { job?.ensureActive(); Unit }
        if (route.plan.scope == RequestedScope.RECENT_SCENE)
            return@withContext SceneEvidence.build(structure, selection, request, route.entities, preparing = preparing)
        val resolved = ScopeResolver.resolve(structure, route.plan, request.currentLocator, title, spoilerLock)
        if (resolved is ScopeResolution.Clarify) return@withContext EvidenceResult.Clarify(resolved)
        resolved as ScopeResolution.Ready
        if (route.plan.scope !in setOf(RequestedScope.RELEVANT_PASSAGES, RequestedScope.PASSAGE)) {
            check()
            val text = structure.text(resolved.start, resolved.end)
            return@withContext if (text.isBlank() && selection.isNullOrBlank()) EvidenceResult.Missing("No book text is available within that reading scope.")
                else EvidenceResult.Ready(text, resolved.label, resolved.requiresSpoilerPermission)
        }
        val found = linkedMapOf<TextPoint, EvidenceParagraph>()
        fun addAround(paragraph: EvidenceParagraph) {
            if (found.size >= 54) return
            val doc = structure.documents[paragraph.start.spine]
            val index = doc.paragraphs.binarySearchBy(paragraph.start.offset) { it.start }
            if (index < 0) return
            for (i in maxOf(0, index - 1)..minOf(doc.paragraphs.lastIndex, index + 1)) {
                val span = doc.paragraphs[i]
                val point = TextPoint(paragraph.start.spine, span.start)
                val endpoint = TextPoint(point.spine, span.end)
                if (endpoint <= resolved.start || point >= resolved.end) continue
                val from = if (point.spine == resolved.start.spine) maxOf(span.start, resolved.start.offset) else span.start
                val to = if (point.spine == resolved.end.spine) minOf(span.end, resolved.end.offset) else span.end
                if (to > from) found[TextPoint(point.spine, from)] = EvidenceParagraph(TextPoint(point.spine, from), TextPoint(point.spine, to), doc.text.substring(from, to))
            }
        }
        request.selectionLocator?.let { locator ->
            val point = structure.point(locator)
            if (point != null && point >= resolved.start && point < resolved.end) {
                structure.documents[point.spine].paragraphBefore(point.offset + 1)?.let { span ->
                    addAround(EvidenceParagraph(TextPoint(point.spine, span.start), TextPoint(point.spine, span.end), ""))
                }
            }
        }
        val terms = (listOfNotNull(route.plan.term, route.characterName) + route.entities).filter { it.isNotBlank() }.distinct().take(12)
        val keywords = (route.keywords + route.queryVariations.flatMap { it.split(Regex("\\s+")) })
            .filter { it.length >= 4 && it.lowercase() !in STOP_WORDS }.distinct().take(20)
        val deadline = LookupDeadline(5000, check)
        var notice: String? = null
        preparing("Looking up earlier references…")
        try {
            for (term in terms) {
                var hits = 0
                for (paragraph in precedingParagraphs(structure, resolved.end, deadline::check)) {
                    if (paragraph.end <= resolved.start || hits >= 18 || found.size >= 54) break
                    val allowed = if (paragraph.start < resolved.start) paragraph.copy(start = resolved.start,
                        text = paragraph.text.drop(resolved.start.offset - paragraph.start.offset)) else paragraph
                    if (indexedContains(structure, allowed, term, deadline::check)) { addAround(allowed); hits++ }
                }
            }
            if (route.plan.term == null && found.size < 54) {
                // Bounded best candidates; never accumulate and sort every paragraph in the book.
                val scored = mutableListOf<Pair<EvidenceParagraph, Int>>()
                for (paragraph in precedingParagraphs(structure, resolved.end, deadline::check)) {
                    if (paragraph.end <= resolved.start) break
                    val allowed = if (paragraph.start < resolved.start) paragraph.copy(start = resolved.start,
                        text = paragraph.text.drop(resolved.start.offset - paragraph.start.offset)) else paragraph
                    val score = keywords.count { indexedContains(structure, allowed, it, deadline::check) }
                    if (score > 0) {
                        scored.add(allowed to score)
                        scored.sortByDescending { it.second }
                        if (scored.size > 18) scored.removeAt(scored.lastIndex)
                    }
                }
                scored.forEach { addAround(it.first) }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: LookupExpired) { notice = "Book lookup reached its time limit. Earlier references may be incomplete." }
        check()
        val sources = found.values.sortedBy { it.start }.mapIndexed { index, paragraph ->
            check()
            SourceExcerpt("S${index + 1}", structure.sectionAt(paragraph.start)?.title ?: "Book passage", paragraph.text,
                structure.locator(paragraph.start, paragraph.text).toJSON().toString())
        }
        val evidence = sources.joinToString("\n\n") { "[${it.id}: ${it.title}]\n${it.text}" }
        if (evidence.isBlank() && selection.isNullOrBlank()) return@withContext EvidenceResult.Missing(notice
            ?: if (route.plan.term != null) "No literal matches for “${route.plan.term}” were found within the permitted reading scope."
            else "No relevant passages were found within the permitted reading scope. Try a name, term, or selected passage.")
        EvidenceResult.Ready((notice?.let { "$it Do not infer missing events.\n" } ?: "") + evidence.ifBlank { "Supplementary lookup found no matches. Explain the supplied selection." },
            resolved.label, resolved.requiresSpoilerPermission, sources, notice)
    }
    companion object {
        private val STOP_WORDS = setOf("what", "when", "where", "which", "this", "that", "about", "explain", "please", "book", "story", "chapter", "does", "have", "from", "with", "summarize", "recap")
    }
}
