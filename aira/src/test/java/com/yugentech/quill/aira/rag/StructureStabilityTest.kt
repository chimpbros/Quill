package com.yugentech.quill.aira.rag

import com.yugentech.quill.aira.context.literalContains
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class StructureStabilityTest {
    @Test fun generatedDomPathsResolveLargeSiblingListsAndRejectAmbiguity() {
        val doc = HtmlTextDocument((0..2999).joinToString("") { "<p>Line $it <em>雨 🌙</em>.</p>" })
        val css = "html:nth-of-type(1) > body:nth-of-type(1) > p:nth-of-type(2999)"
        assertEquals(doc.text.indexOf("Line 2998"), doc.domOffset(mapOf("cssSelector" to css, "textNodeIndex" to 0, "charOffset" to 0)))
        assertNull(doc.selector("body > p"))
        assertNull(doc.selector("body:nth-of-type(1) > p:nth-of-type(9999)"))
        val duplicated = HtmlTextDocument("<p id='same'>First.</p><p id='same'>Second.</p>")
        assertNull(duplicated.selector("#same")); assertNull(duplicated.anchor("same"))
    }
    @Test fun canonicalBookTextKeepsExactOffsetsWithoutACharacterMappingArray() {
        val raw = "Mara — 雨 🌙.\n".repeat(10000)
        val index = TextIndex(raw)
        assertEquals(0, index.offsetStorageSize)
        val offset = raw.lastIndexOf("雨")
        assertEquals(offset, index.originalStart(index.lowerBound(offset)))
        assertEquals(offset + 1, index.originalEnd(offset))
        val collapsed = TextIndex("First.\nThen  Mara 🌙.")
        val moon = collapsed.text.indexOf("🌙")
        assertEquals("First.\nThen  Mara 🌙.".indexOf("🌙"), collapsed.originalStart(moon))
    }
    private fun structure() = PublicationStructure.create(listOf("one.xhtml"), emptyList(), listOf(HtmlTextDocument("<p>First.</p><hr/><p>Second 雨 🌙.</p>")))
    @Test fun whitespaceIndexMapsUnicodeBackToExactOriginalOffsets() {
        val raw = "A\u00a0\u00a0B\n雨 🌙"
        val index = TextIndex(raw)
        assertEquals("A B 雨 🌙", index.text)
        assertEquals(1, index.originalStart(1)); assertEquals(3, index.originalEnd(1))
        assertEquals(raw.indexOf("雨"), index.originalStart(index.text.indexOf("雨")))
    }
    @Test fun lookupPreservesPunctuationAndCaseDiacriticRules() {
        assertTrue(literalContains("Café — MARA’s promise.", "cafe — mara’s", {}))
        assertFalse(literalContains("Mara’s", "Mara's", {}))
    }
    @Test fun lookupMatchesAcrossChunkWhitespaceAndSurrogateBoundaries() {
        val text = "x".repeat(8188) + "Mara   Ivo 🌙" + "y".repeat(9000)
        assertTrue(literalContains(text, "mara ivo 🌙", {}))
    }
    @Test fun enormousParagraphLookupChecksCancellation() {
        var checks = 0
        try { literalContains("z".repeat(500000), "missing", { if (++checks == 4) throw CancellationException() }); fail() }
        catch (_: CancellationException) { assertEquals(4, checks) }
    }
    @Test fun indexingChecksCancellationInsideLongTextNodes() {
        var checks = 0
        try { HtmlTextDocument("<p>" + "x".repeat(500000) + "</p>") { if (++checks == 10) throw CancellationException() }; fail() }
        catch (_: CancellationException) { assertEquals(10, checks) }
    }
    @Test fun paragraphIndexClipsEndAndPreservesSceneBreaks() {
        val doc = structure().documents.single()
        val span = doc.paragraphBefore(doc.text.indexOf("🌙"))!!
        assertTrue(span.sceneBreakBefore); assertEquals(doc.text.indexOf("🌙"), span.end)
        assertEquals("First.", doc.text.substring(doc.paragraphs.first().start, doc.paragraphs.first().end))
    }
    @Test fun concurrentRequestsShareOneLoadAndTrimKeepsPinnedEntries(): Unit = runBlocking {
        val file = File.createTempFile("structure", ".epub")
        try {
            val store = BookStructureStore(); val loads = AtomicInteger()
            val ready = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
            val a = async { store.acquire("one", file.path) { loads.incrementAndGet(); ready.complete(Unit); resume.await(); structure() } }
            ready.await()
            val b = async { store.acquire("one", file.path) { loads.incrementAndGet(); structure() } }
            resume.complete(Unit)
            val first = a.await(); val second = b.await()
            assertSame(first.structure, second.structure); assertEquals(1, loads.get())
            store.trimMemory(); assertEquals(1, store.cachedCount())
            first.close(); second.close(); store.trimMemory(); assertEquals(0, store.cachedCount())
        } finally { file.delete() }
    }
    @Test fun cancellationWhileWaitingDoesNotEvictOrDuplicateLoader(): Unit = runBlocking {
        val file = File.createTempFile("structure", ".epub")
        try {
            val store = BookStructureStore(); val ready = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
            val a = async { store.acquire("one", file.path) { ready.complete(Unit); resume.await(); structure() } }
            ready.await()
            val b = launch { store.acquire("one", file.path) { error("Duplicate loader") }.close() }
            yield(); b.cancelAndJoin(); resume.complete(Unit)
            a.await().close(); assertEquals(1, store.cachedCount())
        } finally { file.delete() }
    }
    @Test fun onlyOneUnusedBookIsRetainedAndFileChangesInvalidate(): Unit = runBlocking {
        val file = File.createTempFile("structure", ".epub")
        try {
            val store = BookStructureStore()
            val old = store.acquire("one", file.path) { structure() }; val original = old.structure; old.close()
            file.writeText("new revision")
            store.acquire("one", file.path) { structure() }.use { assertNotSame(original, it.structure) }
            store.acquire("two", file.path) { structure() }.close()
            assertEquals(1, store.cachedCount())
            store.invalidateAll(); assertEquals(0, store.cachedCount())
        } finally { file.delete() }
    }
    @Test fun invalidationCancelsActiveRequestAndNeverPublishesItsResult(): Unit = runBlocking {
        val file = File.createTempFile("structure", ".epub")
        try {
            val store = BookStructureStore(); val started = CompletableDeferred<Unit>()
            val request = launch { store.acquire("one", file.path) { started.complete(Unit); awaitCancellation() } }
            started.await(); store.invalidate("one"); request.join()
            assertTrue(request.isCancelled); assertEquals(0, store.cachedCount())
            store.acquire("one", file.path) { structure() }.close(); assertEquals(1, store.cachedCount())
        } finally { file.delete() }
    }
}
