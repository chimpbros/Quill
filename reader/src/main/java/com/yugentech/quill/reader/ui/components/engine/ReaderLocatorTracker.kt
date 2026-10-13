package com.yugentech.quill.reader.ui.components.engine

import com.yugentech.quill.aira.rag.PublicationStructure
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/** Readium's current locator can contain only a resource progression, which cannot identify
 * chapters sharing a file. Capture stable DOM selectors and the visible reading boundary. */
object ReaderLocatorTracker {
    suspend fun capture(nav: EpubNavigatorFragment, locator: Locator, structure: PublicationStructure): Locator {
        val resource = PublicationStructure.normalizeHref(locator.href.toString())
        val anchors = structure.sections.filter { PublicationStructure.normalizeHref(it.href) == resource }
            .map { it.href.substringAfter('#', "") }.filter { it.isNotBlank() }
        val script = """
            (function() {
                var expected = ${JSONObject.quote(resource)};
                if (!decodeURIComponent(location.pathname).endsWith(expected)) return null;
                var ids = ${JSONArray(anchors)};
                var width = innerWidth, height = innerHeight;
                function visible(rect) { return rect.width > 0 && rect.height > 0 && rect.right > 0 && rect.left < width && rect.bottom > 0 && rect.top < height; }
                function anchorStartsVisible(rect) { return visible(rect) && rect.top >= -2 && rect.left >= -2; }
                function domPoint(node, offset) {
                    if (!window.__quillCaptureDomRange) return null;
                    var point = document.createRange(); point.setStart(node, offset); point.collapse(true);
                    var captured = window.__quillCaptureDomRange(point);
                    return captured && captured.start;
                }
                function path(element) {
                    if (element.id) return '#' + CSS.escape(element.id);
                    var parts = [];
                    while (element && element.nodeType === 1) {
                        var tag = element.localName, count = 1, sibling = element.previousElementSibling;
                        while (sibling) { if (sibling.localName === tag) count++; sibling = sibling.previousElementSibling; }
                        parts.unshift(tag + ':nth-of-type(' + count + ')');
                        if (tag === 'body') break;
                        element = element.parentElement;
                    }
                    return parts.join(' > ');
                }
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                var first = null, last = null, node;
                while ((node = walker.nextNode())) {
                    if (!node.textContent.trim() || node.parentElement.closest('script,style') || getComputedStyle(node.parentElement).visibility === 'hidden') continue;
                    var range = document.createRange(); range.selectNodeContents(node);
                    if (!Array.from(range.getClientRects()).some(visible)) continue;
                    var words = /\S+/g, match, firstWord = null, lastWord = null;
                    while ((match = words.exec(node.textContent))) {
                        range.setStart(node, match.index); range.setEnd(node, match.index + match[0].length);
                        var rects = Array.from(range.getClientRects()), shown = rects.filter(visible);
                        if (shown.length) {
                            var from = match.index, to = match.index + match[0].length;
                            // Unbroken text (including CJK) or a hyphenated word can span
                            // several pages. A partly visible word is not all permitted text.
                            if (!rects.every(function(r){return visible(r) && r.left >= 0 && r.right <= width && r.top >= 0 && r.bottom <= height;})) {
                                var rtl = getComputedStyle(node.parentElement).direction === 'rtl';
                                function caret(rect, atEnd) {
                                    var x = (atEnd !== rtl) ? Math.min(width-1,rect.right-1) : Math.max(1,rect.left+1);
                                    var y = Math.max(1,Math.min(height-1,(rect.top+rect.bottom)/2));
                                    var c = document.caretRangeFromPoint && document.caretRangeFromPoint(x,y);
                                    return c && c.startContainer === node ? c.startOffset : null;
                                }
                                from = caret(shown[0], false); to = caret(shown[shown.length-1], true);
                                if (from === null || to === null || to <= from) continue;
                            }
                            if (!firstWord) firstWord = {offset:from, text:node.textContent.slice(from,Math.min(to,from+120)), dom:domPoint(node,from)};
                            var tail = Math.max(from,to-120);
                            lastWord = {offset:tail, text:node.textContent.slice(tail,to), dom:domPoint(node,to)};
                        }
                    }
                    if (!firstWord) continue;
                    var element = node.parentElement.closest('p,h1,h2,h3,h4,h5,h6,li,blockquote,td,div') || node.parentElement;
                    if (!first) first = {css:path(element), text:firstWord.text.replace(/\s+/g,' '), before:node.textContent.slice(0,firstWord.offset).replace(/\s+/g,' ').slice(-80), dom:firstWord.dom};
                    last = {css:path(element), text:lastWord.text, before:node.textContent.slice(0,lastWord.offset).replace(/\s+/g,' ').slice(-80), dom:lastWord.dom};
                }
                if (!first || !last) return null;
                // A visible TOC heading identifies the section reached by a chapter jump even
                // when paginated layout also shows the previous section above it.
                for (var id of ids) {
                    var anchor = document.getElementById(decodeURIComponent(id));
                    if (anchor && Array.from(anchor.getClientRects()).some(anchorStartsVisible)) {
                        var anchorText = document.createTreeWalker(anchor,NodeFilter.SHOW_TEXT).nextNode();
                        first = {css:path(anchor), text:anchor.textContent.trim().replace(/\s+/g,' ').slice(0,120), before:'', dom:anchorText && domPoint(anchorText,0)}; break;
                    }
                }
                return JSON.stringify({first:first, last:last});
            })();
        """.trimIndent()
        return try {
            val raw = nav.evaluateJavascript(script) ?: return locator
            val value = JSONTokener(raw).nextValue()
            val json = if (value is String) JSONObject(value) else value as? JSONObject ?: return locator
            apply(locator, json, structure)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { locator }
    }

    fun apply(locator: Locator, json: JSONObject, structure: PublicationStructure): Locator {
        val first = json.getJSONObject("first")
        val last = json.getJSONObject("last")
        fun endpoint(item: JSONObject): Map<String, Any>? = item.optJSONObject("dom")?.let {
            mapOf("cssSelector" to it.getString("cssSelector"), "textNodeIndex" to it.getInt("textNodeIndex"), "charOffset" to it.getInt("charOffset"))
        }
        val startDom = endpoint(first)
        val endDom = endpoint(last)
        val dom = if (startDom != null && endDom != null) mapOf("domRange" to mapOf("start" to startDom, "end" to endDom)) else emptyMap()
        val enriched = locator.copy(locations = locator.locations.copy(otherLocations = locator.locations.otherLocations + mapOf(
            "cssSelector" to first.getString("css"), "quillReadingEndCss" to last.getString("css"),
            "quillReadingEndText" to last.getString("text"), "quillReadingEndBefore" to last.getString("before")) + dom),
            text = Locator.Text(highlight = first.getString("text"), before = first.getString("before")))
        return enriched.copy(title = structure.active(enriched)?.title ?: locator.title,
            locations = enriched.locations.copy(totalProgression = structure.totalProgression(enriched)))
    }
}
