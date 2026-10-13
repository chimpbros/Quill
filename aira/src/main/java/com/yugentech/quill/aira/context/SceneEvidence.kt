package com.yugentech.quill.aira.context

import com.yugentech.quill.aira.rag.*
import org.readium.r2.shared.publication.Publication
import kotlinx.coroutines.CancellationException

data class EvidenceParagraph(val start: TextPoint, val end: TextPoint, val text: String, val sceneBreakBefore: Boolean = false)

object SceneEvidence {
    fun paragraphs(structure: PublicationStructure, end: TextPoint): List<EvidenceParagraph> {
        val ordered = paragraphs(structure.documents.map { it.text }, end)
        val breaks = structure.documents.flatMapIndexed { spine, document -> document.sceneBreakOffsets.map { TextPoint(spine, it) } }
        return ordered.mapIndexed { index, paragraph -> paragraph.copy(sceneBreakBefore = breaks.any {
            it >= (ordered.getOrNull(index - 1)?.end ?: structure.beginning) && it <= paragraph.start
        }) }
    }
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

    suspend fun build(publication: Publication, structure: PublicationStructure, selection: String?,
        request: ReaderRequestContext, entities: List<String>): EvidenceResult {
        val endpoint = request.currentLocator
        if (!structure.isPrecise(endpoint)) {
            return if (!selection.isNullOrBlank()) EvidenceResult.Ready(
                "Only the supplied passage is available. Its exact position could not be established; explain it without inventing earlier setup.",
                "Selected passage", false) else EvidenceResult.Missing("Select a passage so I can identify this scene precisely.")
        }
        val end = structure.readingBoundary(endpoint)
        val paragraphs = paragraphs(structure, end)
        val nearby = paragraphs.takeLast(request.paragraphCount.coerceAtLeast(24))
        if (nearby.isEmpty()) return EvidenceResult.Missing("There is no preceding scene text at this position. Select a passage to explain.")
        val start = nearby.first().start
        val references = Regex("\\b[\\p{L}’'-]{4,}\\b").findAll((selection ?: nearby.takeLast(3).joinToString("\n") { it.text }))
            .map { it.value }.filter { it.lowercase() !in COMMON_WORDS }.toList()
        val names = (references + entities + Regex("\\b\\p{Lu}[\\p{L}’'-]{2,}\\b").findAll(nearby.joinToString("\n") { it.text })
            .map { it.value }.filter { it.lowercase() !in NON_NAMES }.toList()).distinctBy { it.lowercase() }.take(12)
        val earlier = linkedMapOf<TextPoint, EvidenceParagraph>()
        val search = BookTextSearch(publication)
        var lookupUnavailable = false
        try {
            for (name in names) {
                search.start(name)
                val hits = mutableListOf<TextPoint>()
                while (true) {
                    val batch = search.next() ?: break
                    for (locator in batch) structure.point(locator)?.let { if (it < start) hits.add(it) }
                    if (batch.lastOrNull()?.let(structure::point)?.let { it >= start } == true) break
                }
                hits.distinct().takeLast(3).forEach { hit ->
                    val index = paragraphs.indexOfLast { it.start <= hit }
                    if (index >= 0) for (i in maxOf(0, index - 1)..index) {
                        val paragraph = paragraphs[i]
                        if (paragraph.end <= start) earlier[paragraph.start] = paragraph
                    }
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { lookupUnavailable = true }
        finally { search.close() }
        val orderedEarlier = earlier.values.sortedBy { it.start }.takeLast(18)
        val suppliedParagraphs = orderedEarlier + nearby
        val sources = suppliedParagraphs.mapIndexed { index, paragraph -> SourceExcerpt("S${index + 1}",
            structure.active(structure.locator(paragraph.start, paragraph.text))?.title ?: "Book passage",
            paragraph.text, structure.locator(paragraph.start, paragraph.text).toJSON().toString()) }
        val split = orderedEarlier.size
        fun render(items: List<SourceExcerpt>) = items.joinToString("\n\n") {
            (if (suppliedParagraphs[sources.indexOf(it)].sceneBreakBefore) "[EXPLICIT SCENE BREAK]\n" else "") + "[${it.id}: ${it.title}]\n${it.text}"
        }
        return EvidenceResult.Ready((if (lookupUnavailable) "Earlier-reference search was unavailable. Explain only the supplied nearby text; do not invent its setup.\n" else "") +
            "EARLIER REFERENCES:\n${render(sources.take(split))}\n\n" +
            "NEARBY TEXT IN STORY ORDER (ends at the captured reading boundary):\n${render(sources.drop(split))}\nENDPOINT",
            "Recent scene · ${request.paragraphCount} preceding paragraphs", false, sources)
    }
    private val NON_NAMES = setOf("the", "this", "that", "these", "those", "then", "there", "here", "what", "when", "where", "why", "how", "who", "which", "she", "her", "him", "his", "they", "them", "their", "you", "your", "our", "and", "but", "not", "yes", "well", "now", "after", "before", "chapter", "with", "from", "for", "some", "nothing", "something", "perhaps", "only", "all", "one")
    private val COMMON_WORDS = NON_NAMES + setOf("said", "says", "asked", "answered", "keep", "have", "been", "were", "will", "would", "could", "should", "does", "didn't", "don't", "again", "conversation", "line", "waited", "beside")
}
