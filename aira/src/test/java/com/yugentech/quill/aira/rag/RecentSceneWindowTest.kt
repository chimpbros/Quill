package com.yugentech.quill.aira.rag

import com.yugentech.quill.aira.context.SceneEvidence
import org.junit.Assert.*
import org.junit.Test

class RecentSceneWindowTest {
    @Test fun precedingWindowIsOrderedAcrossResourcesAndClippedInsideParagraph() {
        val first = HtmlTextDocument("<html><body><p>Mara promised to return.</p><p>***</p></body></html>")
        val second = HtmlTextDocument("<html><body><p>Ivo asked, ‘Why?’</p><p>Café — 雨 🌙. UNREAD-SECRET</p></body></html>")
        val end = TextPoint(1, second.text.indexOf("UNREAD-SECRET"))
        val paragraphs = SceneEvidence.paragraphs(listOf(first.text, second.text), end)
        assertEquals(listOf("Mara promised to return.", "***", "Ivo asked, ‘Why?’", "Café — 雨 🌙."), paragraphs.map { it.text })
        assertFalse(paragraphs.any { it.text.contains("UNREAD") })
        assertTrue(paragraphs.zipWithNext().all { (a, b) -> a.end <= b.start })
    }
    @Test fun expansionAddsEarlierParagraphsAndKeepsEndpoint() {
        val doc = HtmlTextDocument("<html><body>" + (0..60).joinToString("") { "<p>Paragraph $it.</p>" } + "</body></html>")
        val end = TextPoint(0, doc.text.indexOf("Paragraph 50."))
        val permitted = SceneEvidence.paragraphs(listOf(doc.text), end)
        val original = permitted.takeLast(24)
        val expanded = permitted.takeLast(48)
        assertEquals(original, expanded.takeLast(24))
        assertEquals(original.last().end, expanded.last().end)
        assertTrue(expanded.first().start < original.first().start)
        assertFalse(expanded.any { it.text.contains("Paragraph 50.") })
    }
}
