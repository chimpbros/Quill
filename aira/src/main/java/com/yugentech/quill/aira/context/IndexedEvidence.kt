package com.yugentech.quill.aira.context

import com.yugentech.quill.aira.rag.*

/** Walk only permitted paragraphs, newest first, without allocating the book prefix. */
fun precedingParagraphs(structure: PublicationStructure, end: TextPoint, check: () -> Unit = {}): Sequence<EvidenceParagraph> = sequence {
    for (spine in end.spine downTo 0) {
        check()
        val doc = structure.documents[spine]
        val ceiling = if (spine == end.spine) end.offset.coerceIn(0, doc.text.length) else doc.text.length
        val last = doc.paragraphBefore(ceiling) ?: continue
        var index = doc.paragraphs.binarySearchBy(last.start) { it.start }
        while (index >= 0) {
            check()
            val span = doc.paragraphs[index]
            val to = minOf(span.end, ceiling)
            if (to > span.start) {
                val crossFileBreak = index == 0 && spine > 0 && structure.documents[spine - 1].let { previous ->
                    previous.sceneBreakOffsets.lastOrNull()?.let { it >= (previous.paragraphs.lastOrNull()?.end ?: 0) } == true
                }
                yield(EvidenceParagraph(TextPoint(spine, span.start), TextPoint(spine, to),
                    doc.text.substring(span.start, to).trim(), span.sceneBreakBefore || crossFileBreak))
            }
            index--
        }
    }
}

/** Bounded chunks make cancellation/deadlines effective even for a single enormous paragraph. */
fun literalContains(text: String, term: String, check: () -> Unit): Boolean {
    val needle = TextIndex.literal(term)
    if (needle.isBlank()) return false
    val chunkSize = maxOf(8192, needle.length * 2)
    var from = 0
    var tail = ""
    while (from < text.length) {
        check()
        var to = minOf(text.length, from + chunkSize)
        if (to < text.length && Character.isHighSurrogate(text[to - 1])) to--
        val chunk = TextIndex.literal(tail + TextIndex.literal(text.substring(from, to), trim = false), trim = false)
        if (chunk.contains(needle)) return true
        tail = chunk.takeLast(needle.length + 2)
        from = to
    }
    return false
}

fun indexedContains(structure: PublicationStructure, paragraph: EvidenceParagraph, term: String, check: () -> Unit): Boolean {
    check()
    val normalized = structure.documents[paragraph.start.spine].literalParagraph(paragraph.start.offset, paragraph.end.offset, check)
        ?: return literalContains(paragraph.text, term, check)
    val needle = TextIndex.literal(term)
    if (needle.isBlank()) return false
    var from = 0
    while (from < normalized.length) {
        check()
        val to = minOf(normalized.length, from + 8192 + needle.length)
        if (normalized.substring(from, to).contains(needle)) return true
        from += 8192
    }
    return false
}

internal class LookupExpired : RuntimeException()
internal class LookupDeadline(milliseconds: Long, private val checkCancellation: () -> Unit) {
    private val started = System.nanoTime()
    private val budget = milliseconds.coerceAtLeast(0) * 1_000_000L
    fun check() {
        checkCancellation()
        if (System.nanoTime() - started >= budget) throw LookupExpired()
    }
}
