package com.yugentech.quill.aira.rag

import org.junit.Assert.*
import org.junit.Test

class HtmlRangePointTest {
    private fun point(css: String, child: Int, offset: Int) = mapOf("cssSelector" to css, "textNodeIndex" to child, "charOffset" to offset)
    @Test fun domOffsetsResolveInlineFormattingAndUnicodeWithoutSplittingSurrogates() {
        val doc = HtmlTextDocument("<body><p id='u'>Alpha  beta<span>雨 🌙</span> tail</p></body>")
        assertEquals(doc.text.indexOf("雨"), doc.domOffset(point("#u", 0, 11)))
        assertEquals(doc.text.indexOf(" tail"), doc.domOffset(point("#u > span", 0, 4)))
        assertNull(doc.domOffset(point("#u > span", 0, 3)))
        assertEquals(doc.text.indexOf(" tail"), doc.domOffset(point("#u", 1, 0)))
    }
    @Test fun repeatedParagraphsUseTheirOwnSelectorsAndInvalidPointsAreRejected() {
        val doc = HtmlTextDocument("<body><p id='a'>Same words.</p><p id='b'>Same words.</p></body>")
        assertTrue(doc.domOffset(point("#b", 0, 0))!! > doc.domOffset(point("#a", 0, 0))!!)
        assertNull(doc.domOffset(point("#missing", 0, 0)))
        assertNull(doc.domOffset(point("#a", 9, 0)))
        assertNull(doc.domOffset(point("#a", 0, 999)))
        assertNull(doc.domOffset(point("p", 0, 0)))
    }
    @Test fun nonbreakingSpacesRemainConsistentWithStoredTextOffsets() {
        val doc = HtmlTextDocument("<body><p id='u'>A&nbsp;&nbsp;B</p></body>")
        assertEquals(doc.text.indexOf("B"), doc.domOffset(point("#u", 0, 3)))
    }
    @Test fun explicitSceneBreaksKeepTextAndDomOffsetsUnchanged() {
        val doc = HtmlTextDocument("<body><p id='a'>Earlier scene.</p><hr/><p id='b'>Current scene.</p></body>")
        assertEquals(listOf(doc.text.indexOf("Current scene.")), doc.sceneBreakOffsets)
        assertEquals(doc.text.indexOf("Current scene."), doc.domOffset(point("#b", 0, 0)))
        assertFalse(doc.text.contains("EXPLICIT SCENE BREAK"))
    }
}
