package com.yugentech.quill.aira.rag

import org.json.JSONArray
import org.readium.r2.shared.publication.Locator

data class ResolvedTextRange(val text: String, val start: TextPoint, val end: TextPoint, val segments: List<Locator>) {
    val startLocator: Locator get() = segments.first()
    val endLocator: Locator get() = segments.last()
    fun segmentsJson(): String = JSONArray(segments.map { it.toJSON() }).toString()
}

object TextRangeResolver {
    fun resolve(structure: PublicationStructure, startSelection: Locator, endSelection: Locator): ResolvedTextRange {
        fun endpoint(locator: Locator, end: Boolean): TextPoint {
            val spine = structure.resources.indexOf(PublicationStructure.normalizeHref(locator.href.toString()))
            require(spine >= 0) { "That endpoint does not belong to this book." }
            val range = structure.documents[spine].exactRange(locator)
            require(range != null) { "The exact endpoint could not be resolved. Select the words again." }
            return TextPoint(spine, if (end) range.second else range.first)
        }
        val start = endpoint(startSelection, false)
        val end = endpoint(endSelection, true)
        require(end > start) { "The end must come after the start. Choose a later endpoint or adjust the start." }
        val text = structure.text(start, end)
        require(text.isNotBlank()) { "Select a range containing text." }
        val segments = (start.spine..end.spine).flatMap { spine ->
            val from = if (spine == start.spine) start.offset else 0
            val to = if (spine == end.spine) end.offset else structure.documents[spine].text.length
            if (to <= from) emptyList() else structure.documents[spine].rangeLocators(structure.resources[spine],
                structure.sections.filter { TextPoint(spine, from) >= it.start && TextPoint(spine, from) < it.end }.maxByOrNull { it.depth }?.title,
                from, to)
        }
        require(segments.isNotEmpty()) { "The range contains no highlightable text." }
        return ResolvedTextRange(text, start, end, segments)
    }
    fun segments(json: String?, fallback: String): List<Locator> = if (json == null)
        listOfNotNull(runCatching { Locator.fromJSON(org.json.JSONObject(fallback)) }.getOrNull())
    else runCatching { val array = JSONArray(json); (0 until array.length()).mapNotNull { Locator.fromJSON(array.getJSONObject(it)) } }.getOrDefault(emptyList())
}
