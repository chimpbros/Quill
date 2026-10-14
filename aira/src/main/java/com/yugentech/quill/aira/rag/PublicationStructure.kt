package com.yugentech.quill.aira.rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse
import java.net.URI
import java.net.URLDecoder
import java.util.IdentityHashMap

/** Preserve the anchor instead of asking linkWithHref to find a fragment-bearing resource. */
fun Publication.locatorAtHref(href: String, title: String? = null): Locator? {
    val resource = PublicationStructure.normalizeHref(href)
    val link = readingOrder.firstOrNull { PublicationStructure.normalizeHref(it.href.toString()) == resource } ?: return null
    val fragment = href.substringAfter('#', "")
    val url = org.readium.r2.shared.util.Url(link.href.toString()) ?: return null
    return Locator(href = url, mediaType = link.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML,
        title = title ?: link.title, locations = Locator.Locations(fragments = fragment.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()))
}

/** Spine positions and navigation entries are deliberately different coordinate systems. */
data class TextPoint(val spine: Int, val offset: Int) : Comparable<TextPoint> {
    override fun compareTo(other: TextPoint): Int = compareValuesBy(this, other, TextPoint::spine, TextPoint::offset)
}
data class PublicationSection(val id: String, val title: String, val href: String, val depth: Int,
    val parentId: String?, val start: TextPoint, val end: TextPoint)

data class ParagraphSpan(val start: Int, val end: Int, val sceneBreakBefore: Boolean)

class HtmlTextDocument(html: String, private var checkWork: () -> Unit = {}) {
    // Android's regex engine gives \s Unicode semantics; raw DOM offsets need one rule on every runtime.
    private val offsetWhitespace = Regex("[ \\t\\n\\u000B\\u000C\\r]+")
    private val document = Jsoup.parse(html)
    private val offsets = IdentityHashMap<Node, Int>()
    private val textNodes = mutableListOf<TextNode>()
    private val breaks = mutableListOf<Int>()
    val sceneBreakOffsets: List<Int> get() = breaks
    private val nodeLengths = IdentityHashMap<TextNode, Int>()
    private val idElements = mutableMapOf<String, Element?>()
    private val siblingIndexes = IdentityHashMap<Element, Int>()
    private val selectorCache = IdentityHashMap<Element, String>()
    private val elementEnds = IdentityHashMap<Element, Int>()
    private val childrenByTag = IdentityHashMap<Node, MutableMap<String, MutableList<Element>>>()
    private val cssPart = Regex("([A-Za-z][A-Za-z0-9-]*)(?::nth-of-type\\((\\d+)\\))?")
    private val cssId = Regex("#[A-Za-z][A-Za-z0-9_-]*")
    private lateinit var nodeStarts: IntArray
    val paragraphs: List<ParagraphSpan>
    private val quoteIndex: TextIndex
    private val literalParagraphs = java.util.concurrent.ConcurrentHashMap<Int, String>()
    fun literalParagraph(start: Int, end: Int, check: () -> Unit): String? {
        val index = paragraphs.binarySearchBy(start) { it.start }
        if (index < 0 || paragraphs[index].end != end) return null
        return literalParagraphs.computeIfAbsent(start) { TextIndex.literalIndex(text.substring(start, end), check) }
    }
    val text: String
    init {
        document.select("script, style, head").remove()
        val builder = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                checkWork()
                if (node is Element) {
                    val id = node.id()
                    if (id.isNotBlank()) idElements[id] = if (idElements.containsKey(id)) null else node
                    node.parent()?.let { parent ->
                        val siblings = childrenByTag.getOrPut(parent) { mutableMapOf() }.getOrPut(node.normalName()) { mutableListOf() }
                        siblings.add(node); siblingIndexes[node] = siblings.size
                    }
                }
                if (node is Element && node.isBlock && builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
                offsets[node] = builder.length
                if (node is Element && node.normalName() == "hr") breaks.add(builder.length)
                if (node is TextNode) {
                    textNodes.add(node)
                    val value = normalizeDomText(node.wholeText)
                    nodeLengths[node] = if (value.isBlank()) 0 else value.length
                    if (value.isNotBlank()) builder.append(value)
                }
            }
            override fun tail(node: Node, depth: Int) {
                if (node is Element) elementEnds[node] = builder.length
                if (node is Element && node.isBlock && builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
            }
        }, document.body())
        text = builder.toString()
        nodeStarts = textNodes.map { offsets[it] ?: 0 }.toIntArray()
        paragraphs = buildList {
            var start = 0
            var previousEnd = 0
            var breakIndex = 0
            for (i in 0..text.length) {
                if (i % 4096 == 0) checkWork()
                if (i == text.length || text[i] == '\n') {
                    var from = start; var to = i
                    while (from < to && text[from].isWhitespace()) from++
                    while (to > from && text[to - 1].isWhitespace()) to--
                    if (to > from) {
                        while (breakIndex < breaks.size && breaks[breakIndex] < previousEnd) breakIndex++
                        add(ParagraphSpan(from, to, breaks.getOrNull(breakIndex)?.let { it <= from } == true))
                        previousEnd = to
                    }
                    start = i + 1
                }
            }
        }
        quoteIndex = TextIndex(text, checkWork)
    }
    fun finishIndexing() { checkWork = {} }
    fun anchor(id: String): Int? = idElements[id]?.let { offsets[it] }
    fun selector(css: String): Int? = element(css)?.let { offsets[it] }
    /** App-generated paths use direct child indexes; general selectors retain their strict fallback. */
    private fun element(css: String): Element? = runCatching {
        val parts = css.split('>').map { it.trim() }
        var current: Element? = null
        for ((index, part) in parts.withIndex()) {
            if (cssId.matches(part)) {
                val found = idElements[part.drop(1)] ?: return@runCatching null
                if (index > 0 && found.parent() !== current) return@runCatching null
                current = found
            } else {
                val match = cssPart.matchEntire(part) ?: return@runCatching document.select(css).singleOrNull()
                val tag = match.groupValues[1].lowercase()
                val number = match.groupValues[2]
                val nth = number.takeIf { it.isNotEmpty() }?.toIntOrNull()
                if (number.isNotEmpty() && (nth == null || nth < 1)) return@runCatching null
                if (index == 0) {
                    if (nth != null && nth != 1 && tag in setOf("html", "body")) return@runCatching null
                    current = when (tag) {
                        "body" -> document.body()
                        "html" -> document.children().singleOrNull { it.normalName() == "html" }
                        else -> return@runCatching document.select(css).singleOrNull()
                    }
                } else {
                    val children = childrenByTag[current]?.get(tag) ?: return@runCatching null
                    current = if (nth == null) children.singleOrNull() else children.getOrNull(nth - 1)
                    if (current == null) return@runCatching null
                }
            }
        }
        current
    }.getOrNull()
    fun selectorAt(offset: Int): String? {
        var index = nodeStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        while (index >= 0 && (nodeLengths[textNodes[index]] ?: 0) == 0) index--
        return textNodes.getOrNull(index)?.parent()?.let { parent ->
            val container = parent as? Element ?: return@let null
            val element = container.closest("p,li,blockquote,h1,h2,h3,h4,h5,h6,td,div") ?: container
            stableSelector(element)
        }
    }
    private fun normalizeDomText(value: String): String {
        var previousSpace = false
        var needsNormalization = false
        for (index in value.indices) {
            if (index % 4096 == 0) checkWork()
            val ch = value[index]
            val whitespace = ch in " \t\n\u000B\u000C\r"
            if (whitespace && (ch != ' ' || previousSpace)) { needsNormalization = true; break }
            previousSpace = whitespace
        }
        if (!needsNormalization) return value
        return buildString(value.length) {
        var i = 0
        while (i < value.length) {
            if (i % 4096 == 0) checkWork()
            val ch = value[i++]
            if (ch in " \t\n\u000B\u000C\r") {
                append(' ')
                while (i < value.length && value[i] in " \t\n\u000B\u000C\r") { if (i % 4096 == 0) checkWork(); i++ }
            } else append(ch)
        }
    }
    }
    private fun normalized(node: TextNode) = if ((nodeLengths[node] ?: 0) == 0) "" else normalizeDomText(node.wholeText)
    private fun stableSelector(element: Element): String = synchronized(selectorCache) {
        selectorCache.getOrPut(element) {
            if (element.id().matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) && idElements[element.id()] === element) "#${element.id()}"
            else {
                val parts = mutableListOf<String>()
                var current: Element? = element
                while (current != null && current.normalName() != "#root") {
                    val tag = current.normalName()
                    parts.add("$tag:nth-of-type(${siblingIndexes[current] ?: 1})")
                    if (tag == "body") break
                    current = current.parent()
                }
                parts.asReversed().joinToString(" > ")
            }
        }
    }
    fun paragraphBefore(offset: Int): ParagraphSpan? {
        var lo = 0; var hi = paragraphs.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (paragraphs[mid].start < offset) lo = mid + 1 else hi = mid }
        return paragraphs.getOrNull(lo - 1)?.let { it.copy(end = minOf(it.end, offset)) }
    }
    private fun rawOffset(node: TextNode, normalizedOffset: Int): Int {
        if (normalizedOffset <= 0) return 0
        var raw = 0
        var count = 0
        val value = node.wholeText
        while (raw < value.length && count < normalizedOffset) {
            if (value[raw] in " \t\n\u000B\u000C\r") { while (raw < value.length && value[raw] in " \t\n\u000B\u000C\r") raw++ }
            else raw++
            count++
        }
        return raw
    }
    fun domOffset(boundary: Map<*, *>?): Int? {
        boundary ?: return null
        val css = boundary["cssSelector"] as? String ?: return null
        val element = element(css) ?: return null
        val index = (boundary["textNodeIndex"] as? Number)?.toLong() ?: return null
        val children = element.childNodes().filterIsInstance<TextNode>()
        if (index !in 0 until children.size.toLong()) return null
        val node = children[index.toInt()]
        val raw = (boundary["charOffset"] as? Number)?.toLong() ?: 0L
        if (raw !in 0..node.wholeText.length.toLong()) return null
        val offset = raw.toInt()
        if (offset > 0 && offset < node.wholeText.length && Character.isHighSurrogate(node.wholeText[offset - 1]) &&
            Character.isLowSurrogate(node.wholeText[offset])) return null
        val prefix = if (normalized(node).isEmpty()) 0 else node.wholeText.substring(0, offset).replace(offsetWhitespace, " ").length
        return offsets[node]?.plus(prefix)
    }
    fun exactRange(locator: Locator): Pair<Int, Int>? {
        val dom = locator.locations.otherLocations["domRange"] as? Map<*, *>
        if (dom != null) {
            val start = domOffset(dom["start"] as? Map<*, *>) ?: return null
            val end = domOffset(dom["end"] as? Map<*, *>) ?: return null
            return (start to end).takeIf { end > start }
        }
        val quote = locator.text.highlight?.takeIf { it.isNotBlank() } ?: return null
        val css = locator.locations.otherLocations["cssSelector"] as? String
        val element = css?.let { element(it) }
        if (css != null && element == null) return null
        val from = element?.let { offsets[it] } ?: 0
        val to = element?.let { elementEnds[it] } ?: text.length
        val index = quoteIndex
        val needle = TextIndex.clean(quote)
        if (needle.isEmpty()) return null
        val before = locator.text.before?.let(TextIndex::clean).orEmpty()
        val after = locator.text.after?.let(TextIndex::clean).orEmpty()
        var cursor = index.lowerBound(from)
        val ceiling = index.lowerBound(to)
        var found: Pair<Int, Int>? = null
        while (cursor < ceiling) {
            val match = index.text.indexOf(needle, cursor)
            if (match < 0 || match + needle.length > ceiling) break
            val end = match + needle.length
            // Compare only adjacent context, never copy/normalize the whole prefix and suffix per hit.
            var left = match
            while (left > 0 && index.text[left - 1] == ' ') left--
            var right = end
            while (right < index.text.length && index.text[right] == ' ') right++
            if ((before.isEmpty() || left >= before.length && index.text.regionMatches(left - before.length, before, 0, before.length)) &&
                (after.isEmpty() || index.text.regionMatches(right, after, 0, after.length))) {
                if (found != null) return null
                found = index.originalStart(match) to index.originalEnd(end - 1)
            }
            cursor = match + 1
        }
        return found
    }
    /** Readium 3.1.2 renders text quotes. Scope each quote to a stable block, with exact DOM endpoints too. */
    fun rangeLocators(resource: String, title: String?, from: Int, to: Int): List<Locator> {
        require(from >= 0 && to <= text.length && to > from)
        val first = textNodes.indexOfFirst { (offsets[it] ?: 0) + normalized(it).length > from }
        val last = textNodes.indexOfLast { (offsets[it] ?: 0) < to && normalized(it).isNotEmpty() }
        require(first >= 0 && last >= first) { "The selected endpoints do not contain text." }
        val groups = mutableListOf<MutableList<TextNode>>()
        fun block(node: TextNode) = (node.parent() as Element).closest("p,li,td,h1,h2,h3,h4,h5,h6,div,blockquote,body") ?: node.parent() as Element
        for (node in textNodes.subList(first, last + 1)) {
            if (groups.isEmpty() || block(groups.last().last()) !== block(node)) groups.add(mutableListOf())
            groups.last().add(node)
        }
        fun boundary(node: TextNode, raw: Int): Map<String, Any> {
            val parent = node.parent() as Element
            return mapOf("cssSelector" to stableSelector(parent), "textNodeIndex" to parent.childNodes().filterIsInstance<TextNode>().indexOf(node), "charOffset" to raw)
        }
        return groups.mapNotNull { group ->
            val startNode = group.first(); val endNode = group.last(); val container = block(startNode)
            val all = textNodes.filter { node -> generateSequence<Node>(node) { it.parent() }.any { it === container } }
            val rawText = all.joinToString("") { it.wholeText }
            val rawStart = if (startNode === textNodes[first]) rawOffset(startNode, from - (offsets[startNode] ?: 0)) else 0
            val rawEnd = if (endNode === textNodes[last]) rawOffset(endNode, to - (offsets[endNode] ?: 0)) else endNode.wholeText.length
            val startIndex = all.takeWhile { it !== startNode }.sumOf { it.wholeText.length } + rawStart
            val endIndex = all.takeWhile { it !== endNode }.sumOf { it.wholeText.length } + rawEnd
            val quote = rawText.substring(startIndex, endIndex)
            if (quote.isBlank()) null else Locator(org.readium.r2.shared.util.Url(resource)!!,
                org.readium.r2.shared.util.mediatype.MediaType.XHTML, title,
                Locator.Locations(otherLocations = mapOf("cssSelector" to stableSelector(container),
                    "domRange" to mapOf("start" to boundary(startNode, rawStart), "end" to boundary(endNode, rawEnd)))),
                Locator.Text(highlight = quote, before = rawText.substring(0, startIndex).takeIf { it.isNotEmpty() },
                    after = rawText.substring(endIndex).takeIf { it.isNotEmpty() }))
        }
    }
    fun point(locator: Locator): Pair<Int, Boolean> {
        if (locator.locations.otherLocations.containsKey("domRange")) {
            exactRange(locator)?.let { return it.first to true }
            return 0 to false
        }
        if (!locator.text.highlight.isNullOrBlank()) {
            exactRange(locator)?.let { return it.first to true }
            // A repeated or unresolvable quote is not an exact reading position.
            return ((locator.locations.progression ?: 0.0) * text.length).toInt().coerceIn(0, text.length) to false
        }
        val fragment = locator.href.toString().substringAfter('#', "").ifBlank { locator.locations.fragments.firstOrNull().orEmpty() }
        val anchor = fragment.takeIf { it.isNotBlank() }?.let { anchor(decode(it)) }
        val selector = (locator.locations.otherLocations["cssSelector"] as? String)?.let(::selector)
        val start = selector ?: anchor ?: 0
        if (selector != null || anchor != null) return start to true
        return ((locator.locations.progression ?: 0.0) * text.length).toInt().coerceIn(0, text.length) to false
    }
    companion object {
        fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
    }
}

class PublicationStructure private constructor(
    val sections: List<PublicationSection>,
    val resources: List<String>,
    val documents: List<HtmlTextDocument>
) {
    fun point(locator: Locator): TextPoint? {
        val index = resources.indexOf(normalizeHref(locator.href.toString()))
        if (index < 0) return null
        return TextPoint(index, documents[index].point(locator).first)
    }
    /** Automatic scenes require an exact endpoint, including the captured visible end. */
    fun exactReadingBoundary(locator: Locator?): TextPoint? {
        locator ?: return null
        val spine = resources.indexOf(normalizeHref(locator.href.toString()))
        if (spine < 0) return null
        val doc = documents[spine]
        val extra = locator.locations.otherLocations
        if (extra.containsKey("domRange")) return doc.exactRange(locator)?.let { TextPoint(spine, it.second) }
        if (extra.containsKey("quillReadingEndCss") || extra.containsKey("quillReadingEndText")) {
            val css = extra["quillReadingEndCss"] as? String ?: return null
            val quote = extra["quillReadingEndText"] as? String ?: return null
            val end = locator.copy(locations = locator.locations.copy(otherLocations = extra + ("cssSelector" to css)),
                text = Locator.Text(highlight = quote, before = extra["quillReadingEndBefore"] as? String))
            return doc.exactRange(end)?.let { TextPoint(spine, it.second) }
        }
        if (!locator.text.highlight.isNullOrBlank()) return doc.exactRange(locator)?.let { TextPoint(spine, it.second) }
        return doc.point(locator).takeIf { it.second }?.let { TextPoint(spine, it.first) }
    }
    fun readingBoundary(locator: Locator?): TextPoint {
        locator ?: return beginning
        val dom = locator.locations.otherLocations["domRange"] as? Map<*, *>
        if (dom != null) {
            val spine = resources.indexOf(normalizeHref(locator.href.toString()))
            if (spine >= 0) documents[spine].domOffset(dom["end"] as? Map<*, *>)?.let { return TextPoint(spine, it) }
        }
        val extra = locator.locations.otherLocations
        val endCss = extra["quillReadingEndCss"] as? String
        val endText = extra["quillReadingEndText"] as? String
        val endLocator = if (endCss != null && endText != null) locator.copy(
            locations = locator.locations.copy(otherLocations = extra + ("cssSelector" to endCss)),
            text = Locator.Text(highlight = endText, before = extra["quillReadingEndBefore"] as? String)) else locator
        val endSpine = resources.indexOf(normalizeHref(endLocator.href.toString()))
        if (endSpine >= 0) documents[endSpine].exactRange(endLocator)?.let { return TextPoint(endSpine, it.second) }
        val point = point(endLocator) ?: return beginning
        val length = endLocator.text.highlight?.replace(Regex("\\s+"), " ")?.length ?: 0
        return point.copy(offset = (point.offset.toLong() + length).coerceAtMost(documents[point.spine].text.length.toLong()).toInt())
    }
    fun totalProgression(locator: Locator): Double {
        val point = point(locator) ?: return locator.locations.totalProgression ?: 0.0
        val total = documents.sumOf { it.text.length.toLong() }.coerceAtLeast(1L)
        val before = documents.take(point.spine).sumOf { it.text.length.toLong() }
        return ((before + point.offset).toDouble() / total).coerceIn(0.0, 1.0)
    }
    fun active(locator: Locator?): PublicationSection? {
        locator ?: return null
        val point = point(locator) ?: return null
        val matching = sections.filter { point >= it.start && point < it.end }
        val precise = documents[point.spine].point(locator).second
        if (precise) return matching.maxWithOrNull(compareBy<PublicationSection> { it.depth }.thenBy { it.start })
        // A progression alone cannot reliably disambiguate anchors in one resource.
        val inResource = sections.filter { it.start.spine == point.spine }
        if (inResource.count { it.href.contains('#') } > 1) {
            return matching.filter { !it.href.contains('#') }.maxByOrNull { it.depth }
        }
        return matching.maxWithOrNull(compareBy<PublicationSection> { it.depth }.thenBy { it.start })
    }
    fun isPrecise(locator: Locator?): Boolean {
        val point = locator?.let(::point) ?: return false
        return documents[point.spine].point(locator).second
    }
    fun text(start: TextPoint, end: TextPoint): String = buildString {
        for (index in start.spine..end.spine.coerceAtMost(documents.lastIndex)) {
            val text = documents[index].text
            val from = if (index == start.spine) start.offset.coerceIn(0, text.length) else 0
            val to = if (index == end.spine) end.offset.coerceIn(from, text.length) else text.length
            if (to > from) { if (isNotEmpty()) append("\n\n"); append(text.substring(from, to)) }
        }
    }.trim()
    fun text(section: PublicationSection): String = text(section.start, section.end)
    fun sectionAt(point: TextPoint): PublicationSection? = sections.filter { point >= it.start && point < it.end }.maxByOrNull { it.depth }
    fun locator(point: TextPoint, excerpt: String): Locator {
        val doc = documents[point.spine]
        return Locator(org.readium.r2.shared.util.Url(resources[point.spine])!!,
            org.readium.r2.shared.util.mediatype.MediaType.XHTML,
            title = sectionAt(point)?.title,
            locations = Locator.Locations(otherLocations = doc.selectorAt(point.offset)?.let { mapOf("cssSelector" to it) }.orEmpty()),
            text = Locator.Text(highlight = excerpt, before = doc.text.substring((point.offset - 80).coerceAtLeast(0), point.offset)))
    }
    val beginning: TextPoint get() = TextPoint(0, 0)
    val end: TextPoint get() = TextPoint(documents.lastIndex, documents.lastOrNull()?.text?.length ?: 0)
    fun locationKey(locator: Locator): String {
        val resource = normalizeHref(locator.href.toString())
        val css = locator.locations.otherLocations["cssSelector"] as? String
        return when {
            css != null -> "$resource|css:$css|offset:${point(locator)?.offset ?: 0}"
            locator.locations.position != null -> "$resource|position:${locator.locations.position}"
            else -> "$resource|offset:${point(locator)?.offset ?: 0}"
        }
    }
    companion object {
        fun normalizeHref(href: String): String = HtmlTextDocument.decode(runCatching {
            URI(href.substringBefore('#').substringBefore('?')).normalize().path
        }.getOrDefault(href.substringBefore('#').substringBefore('?'))).removePrefix("./")

        suspend fun load(publication: Publication): PublicationStructure = withContext(Dispatchers.Default) {
            val job = currentCoroutineContext()[kotlinx.coroutines.Job]
            val documents = publication.readingOrder.map { link ->
                currentCoroutineContext().ensureActive()
                val resource = publication.get(link) ?: error("Book resource is unavailable: ${link.href}")
                val bytes = withContext(Dispatchers.IO) { resource.read().getOrElse { error("Cannot read book resource: ${link.href}") } }
                HtmlTextDocument(String(bytes, Charsets.UTF_8), { job?.ensureActive() }).also { it.finishIndexing() }
            }
            create(publication.readingOrder.map { it.href.toString() }, publication.tableOfContents, documents)
        }
        fun create(resources: List<String>, toc: List<Link>, documents: List<HtmlTextDocument>): PublicationStructure {
            require(resources.isNotEmpty() && resources.size == documents.size)
            val normalized = resources.map(::normalizeHref)
            data class Entry(val link: Link, val depth: Int, val parent: String?, val start: TextPoint, val id: String)
            val entries = mutableListOf<Entry>()
            fun walk(links: List<Link>, depth: Int, parent: String?) {
                for (link in links) {
                    val href = link.href.toString()
                    val spine = normalized.indexOf(normalizeHref(href))
                    if (spine < 0) { walk(link.children, depth, parent); continue }
                    val fragment = href.substringAfter('#', "")
                    val offset = if (fragment.isBlank()) 0 else documents[spine].anchor(HtmlTextDocument.decode(fragment))
                    if (offset == null) { walk(link.children, depth, parent); continue }
                    val id = "toc:${entries.size}:$href"
                    entries.add(Entry(link, depth, parent, TextPoint(spine, offset), id))
                    walk(link.children, depth + 1, id)
                }
            }
            walk(toc, 0, null)
            val bookEnd = TextPoint(documents.lastIndex, documents.last().text.length)
            if (entries.isEmpty()) return PublicationStructure(resources.mapIndexed { index, href ->
                PublicationSection("spine:$index", "Chapter ${index + 1}", href, 0, null, TextPoint(index, 0),
                    if (index < resources.lastIndex) TextPoint(index + 1, 0) else bookEnd)
            }, normalized, documents)
            val sections = entries.mapIndexed { index, entry ->
                val next = entries.drop(index + 1).firstOrNull { it.depth <= entry.depth && it.start > entry.start }
                PublicationSection(entry.id, entry.link.title ?: "Untitled", entry.link.href.toString(), entry.depth,
                    entry.parent, entry.start, next?.start ?: bookEnd)
            }
            return PublicationStructure(sections, normalized, documents)
        }
    }
}
