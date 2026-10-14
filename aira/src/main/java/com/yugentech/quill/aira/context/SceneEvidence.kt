package com.yugentech.quill.aira.context

import com.yugentech.quill.aira.rag.*
import org.readium.r2.shared.publication.Publication
import kotlinx.coroutines.*

data class EvidenceParagraph(val start: TextPoint, val end: TextPoint, val text: String, val sceneBreakBefore: Boolean = false)

object SceneEvidence {
    fun paragraphs(structure: PublicationStructure, end: TextPoint): List<EvidenceParagraph> = precedingParagraphs(structure, end).toList().asReversed()
    fun paragraphs(documents: List<String>, end: TextPoint): List<EvidenceParagraph> = buildList {
        for (spine in 0..end.spine) {
            val text = documents[spine]
            val ceiling = if (spine == end.spine) end.offset.coerceIn(0, text.length) else text.length
            var from = 0
            while (from < ceiling) {
                val to = text.indexOf('\n', from).let { if (it < 0) ceiling else minOf(it, ceiling) }
                val part = text.substring(from, to)
                if (part.isNotBlank()) add(EvidenceParagraph(TextPoint(spine, from), TextPoint(spine, to), part.trim()))
                from = to + 1
            }
        }
    }
    // Compatibility for existing callers; scene retrieval no longer needs Readium's search service.
    suspend fun build(publication: Publication, structure: PublicationStructure, selection: String?,
        request: ReaderRequestContext, entities: List<String>): EvidenceResult = build(structure, selection, request, entities)

    suspend fun build(structure: PublicationStructure, selection: String?, request: ReaderRequestContext,
        entities: List<String>, lookupBudgetMillis: Long = 5000, preparing: suspend (String) -> Unit = {}): EvidenceResult = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()[Job]
        val check = { job?.ensureActive(); Unit }
        val endpoint = request.currentLocator
        val end = structure.exactReadingBoundary(endpoint)
        if (end == null) return@withContext if (!selection.isNullOrBlank()) EvidenceResult.Ready(
            "Only the supplied passage is available. Its exact position could not be established; explain it without inventing earlier setup.",
            "Selected passage", false) else EvidenceResult.Missing("Select a passage so I can identify this scene precisely.")
        val nearby = precedingParagraphs(structure, end, check).take(request.paragraphCount.coerceAtLeast(24)).toList().asReversed()
        if (nearby.isEmpty()) return@withContext EvidenceResult.Missing("There is no preceding scene text at this position. Select a passage to explain.")
        val start = nearby.first().start
        // Requested text stays intact. Candidate extraction alone is bounded.
        val seed = (selection ?: nearby.takeLast(3).joinToString("\n") { it.text }).take(32768)
        val names = (entities.asSequence() + Regex("\\b\\p{Lu}[\\p{L}’'-]{2,}\\b").findAll(nearby.takeLast(3).joinToString("\n") { it.text }.take(32768))
            .map { it.value }.filter { it.lowercase() !in COMMON_WORDS } + Regex("\\b[\\p{L}’'-]{4,}\\b").findAll(seed)
            .map { it.value }.filter { it.lowercase() !in COMMON_WORDS })
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(12).toList()
        preparing("Looking up earlier references…")
        val earlier = linkedMapOf<TextPoint, EvidenceParagraph>()
        var notice: String? = null
        val deadline = LookupDeadline(lookupBudgetMillis, check)
        try {
            for (name in names) {
                var matches = 0
                val iterator = precedingParagraphs(structure, start, deadline::check).iterator()
                while (iterator.hasNext() && matches < 3 && earlier.size < 18) {
                    deadline.check()
                    val paragraph = iterator.next()
                    if (indexedContains(structure, paragraph, name, deadline::check)) {
                        earlier[paragraph.start] = paragraph
                        matches++
                        if (iterator.hasNext() && earlier.size < 18) {
                            val previous = iterator.next()
                            earlier[previous.start] = previous
                        }
                    }
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: LookupExpired) { notice = "Earlier-reference lookup reached its time limit. This explanation uses the captured scene and the references found so far; earlier context may be incomplete." }
        catch (_: Exception) { notice = "Earlier-reference lookup was unavailable. This explanation uses the captured scene and any references already found; earlier context may be incomplete." }
        check()
        val orderedEarlier = earlier.values.sortedBy { it.start }
        val supplied = orderedEarlier + nearby
        val sources = supplied.mapIndexed { index, paragraph ->
            check()
            SourceExcerpt("S${index + 1}", structure.sectionAt(paragraph.start)?.title ?: "Book passage", paragraph.text,
                structure.locator(paragraph.start, paragraph.text).toJSON().toString())
        }
        fun render(from: Int, to: Int) = (from until to).joinToString("\n\n") { index ->
            val source = sources[index]
            (if (supplied[index].sceneBreakBefore) "[EXPLICIT SCENE BREAK]\n" else "") + "[${source.id}: ${source.title}]\n${source.text}"
        }
        EvidenceResult.Ready((notice?.let { "$it Do not invent missing setup.\n" } ?: "") +
            "EARLIER REFERENCES:\n${render(0, orderedEarlier.size)}\n\n" +
            "NEARBY TEXT IN STORY ORDER (ends at the captured reading boundary):\n${render(orderedEarlier.size, supplied.size)}\nENDPOINT",
            "Recent scene · ${request.paragraphCount} preceding paragraphs", false, sources, notice)
    }
    private val NON_NAMES = setOf("the", "this", "that", "these", "those", "then", "there", "here", "what", "when", "where", "why", "how", "who", "which", "she", "her", "him", "his", "they", "them", "their", "you", "your", "our", "and", "but", "not", "yes", "well", "now", "after", "before", "chapter", "with", "from", "for", "some", "nothing", "something", "perhaps", "only", "all", "one")
    private val COMMON_WORDS = NON_NAMES + setOf("said", "says", "asked", "answered", "keep", "have", "been", "were", "will", "would", "could", "should", "does", "didn't", "don't", "again", "conversation", "line", "waited", "beside")
}
