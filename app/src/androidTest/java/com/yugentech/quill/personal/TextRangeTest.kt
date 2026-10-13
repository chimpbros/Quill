package com.yugentech.quill.personal

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.rag.*
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.database.entity.*
import com.yugentech.quill.database.model.BookSource
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TextRangeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File
    private lateinit var structure: PublicationStructure
    @Before fun setup() = runBlocking {
        file = File(context.cacheDir, "range-${UUID.randomUUID()}.epub").also(::writeDiscussionFixture)
        structure = EpubTextExtractor(context).structure(file.absolutePath)
    }
    @After fun cleanup() { file.delete() }
    private fun locator(resource: String, id: String, text: String) = Locator(Url(resource)!!, MediaType.XHTML,
        locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#$id")), text = Locator.Text(highlight = text))
    private val beginning get() = locator("OEBPS/one.xhtml", "promise", "Mara promised Ivo she would repair the lamp before the boat returned.")
    private val ending get() = locator("OEBPS/two.xhtml", "q30", "Ivo said, ‘Keep your promise, Mara.’")

    @Test fun crossFileRangePreservesCompleteTextAndExactSegmentEndpoints() {
        val range = TextRangeResolver.resolve(structure, beginning, ending)
        assertTrue(range.text.startsWith("Mara promised Ivo")); assertTrue(range.text.endsWith("Ivo said, ‘Keep your promise, Mara.’"))
        assertTrue(range.text.contains("Café — 雨 🌙, said Mara.")); assertFalse(range.text.contains("UNREAD-SCENE"))
        assertEquals(2, range.segments.map { it.href.toString() }.distinct().size)
        assertEquals(range.start, structure.point(range.startLocator)); assertEquals(range.end, structure.readingBoundary(range.endLocator))
        assertEquals(range.segments, TextRangeResolver.segments(range.segmentsJson(), range.startLocator.toJSON().toString()))
        range.segments.forEach { segment ->
            val spine = structure.resources.indexOf(segment.href.toString())
            assertNotNull(structure.documents[spine].exactRange(segment))
            assertNotNull(segment.locations.otherLocations["domRange"])
        }
    }
    @Test fun reversedAndAmbiguousEndpointsFailInsteadOfGuessingProgress() {
        try { TextRangeResolver.resolve(structure, ending, beginning); fail("Reversed range accepted") } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("end")) }
        val approximate = Locator(Url("OEBPS/two.xhtml")!!, MediaType.XHTML, locations = Locator.Locations(progression = 0.5))
        try { TextRangeResolver.resolve(structure, beginning, approximate); fail("Approximate range accepted") } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("endpoint")) }
    }
    @Test fun repeatedTextAndInlineFormattingResolveToTheSelectedOccurrence() {
        val doc = HtmlTextDocument("<body><p id='a'>Same <em>words</em>.</p><p id='b'>Same <em>words</em>.</p><p id='c'>Café  — 雨 🌙&nbsp;&nbsp;end.</p></body>")
        val nav = PublicationStructure.create(listOf("one.xhtml"), emptyList(), listOf(doc))
        val a = locator("one.xhtml", "a", "Same words.")
        val b = locator("one.xhtml", "b", "Same words.")
        val first = TextRangeResolver.resolve(nav, a, a)
        val second = TextRangeResolver.resolve(nav, b, b)
        assertTrue(second.start > first.start)
        assertEquals("Same words.", second.text)
        assertTrue(second.segments.first().locations.otherLocations["cssSelector"].toString().contains("b"))
        val unicode = locator("one.xhtml", "c", "Café — 雨 🌙\u00a0\u00a0end.")
        val resolved = TextRangeResolver.resolve(nav, unicode, unicode)
        assertTrue(resolved.segments.single().text.highlight!!.contains("Café  — 雨 🌙\u00a0\u00a0end."))
    }
    @Test fun groupedHighlightReopensAsOneEntryAndLegacyHighlightStaysSingle() = runBlocking {
        val name = "range-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            db.bookDao().insertBook(BookEntity("range-book", "The Promise", "Test", null, downloadUrl = "", source = BookSource.USER_IMPORTED))
            val range = TextRangeResolver.resolve(structure, beginning, ending)
            db.highlightDao().insertHighlight(HighlightEntity("group", "range-book", range.startLocator.toJSON().toString(),
                0xFF90CAF9.toInt(), "A note", segmentsJson = range.segmentsJson(), selectedText = range.text))
            db.close(); db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val saved = db.highlightDao().getHighlightsForBook("range-book").single()
            assertEquals(range.text, saved.selectedText); assertEquals("A note", saved.note)
            assertTrue(TextRangeResolver.segments(saved.segmentsJson, saved.locatorJson).size > 1)
            assertEquals(1, TextRangeResolver.segments(null, beginning.toJSON().toString()).size)
            db.highlightDao().deleteHighlight(saved.id)
            assertTrue(db.highlightDao().getHighlightsForBook("range-book").isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
