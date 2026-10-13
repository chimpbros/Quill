package com.yugentech.quill.aira.rag

import org.junit.Assert.*
import org.junit.Test

class HtmlTextDocumentTest {
    @Test fun anchorsAndSelectorsUseTheSameOrderedText() {
        val doc = HtmlTextDocument("<html><head><style>fake</style></head><body><h1 id='a'>First</h1><p>Café one.</p><h1 id='b'>Second</h1><p>StarlAmp sees darkness.</p><script>bad</script></body></html>")
        assertFalse(doc.text.contains("fake")); assertFalse(doc.text.contains("bad"))
        assertTrue(doc.anchor("b")!! > doc.anchor("a")!!)
        assertEquals(doc.anchor("b"), doc.selector("#b"))
        assertTrue(doc.text.substring(doc.anchor("b")!!).contains("StarlAmp"))
    }
    @Test fun normalizationPreservesLiteralPlusAndDecodesEscapes() {
        assertEquals("Books/café+one.xhtml", PublicationStructure.normalizeHref("./Books/caf%C3%A9+one.xhtml#heading"))
        assertEquals("b.xhtml", PublicationStructure.normalizeHref("folder/../b.xhtml"))
    }
    @Test fun pointsOrderWithinAndAcrossResources() {
        assertTrue(TextPoint(0, 10) < TextPoint(0, 20))
        assertTrue(TextPoint(0, Int.MAX_VALUE) < TextPoint(1, 0))
    }
}
