package com.yugentech.quill.personal

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.rag.*
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.database.entity.*
import com.yugentech.quill.database.model.BookSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderNavigationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var epub: File
    private lateinit var db: AppDatabase
    @Before fun setup() {
        epub = File(context.cacheDir, "navigation-${UUID.randomUUID()}.epub"); writeNavigationFixture(epub)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }
    @After fun cleanup() { db.close(); epub.delete() }
    private fun locator(href: String, highlight: String? = null) = Locator(href = Url(href)!!, mediaType = MediaType.XHTML, text = Locator.Text(highlight = highlight))
    @Test fun sharedFileAnchorsAndMultiFileNovelsResolve() = runBlocking {
        val structure = EpubTextExtractor(context).structure(epub.absolutePath)
        assertEquals("Night Watch", structure.active(locator("OEBPS/one.xhtml#b"))?.title)
        val novel = structure.sections.single { it.title == "The Lantern" }
        val text = structure.text(novel)
        assertTrue(text.contains("Mara chose to keep")); assertFalse(text.contains("UNREAD-SECRET"))
        assertEquals(0, structure.sections.single { it.title == "Night Watch" }.start.spine)
        assertEquals(1, structure.sections.single { it.title == "Homecoming" }.start.spine)
        assertEquals(2, structure.sections.count { it.title == "Arrival" })
        assertEquals("The Lantern", structure.active(locator("OEBPS/one.xhtml"))?.title)
    }
    @Test fun literalSearchWorksWithoutIndexAndFindsUnicode() = runBlocking {
        EpubTextExtractor(context).withPublication(epub.absolutePath) { publication ->
            val structure = PublicationStructure.load(publication)
            val search = BookTextSearch(publication)
            try {
                search.start("starlamp")
                val results = mutableListOf<Locator>()
                while (true) { results.addAll(search.next() ?: break) }
                assertEquals(2, results.size)
                assertEquals("Night Watch", structure.active(results.first())?.title)
                val start = structure.point(results.first())!!
                assertTrue(structure.text(TextPoint(start.spine, (start.offset - 20).coerceAtLeast(0)), TextPoint(start.spine, start.offset + 120)).contains("StarlAmp"))
                search.start("cafe")
                assertTrue(search.next()!!.any { it.text.highlight?.contains("Café") == true })
                search.start("no-such-phrase")
                assertNull(search.next())
            } finally { search.close() }
        }
    }
    @Test fun tocNavigationPreservesSharedFileAnchor() = runBlocking {
        EpubTextExtractor(context).withPublication(epub.absolutePath) { publication ->
            val locator = publication.locatorAtHref("OEBPS/one.xhtml#b", "Night Watch")!!
            assertEquals("OEBPS/one.xhtml", locator.href.toString())
            assertEquals(listOf("b"), locator.locations.fragments)
            assertEquals("Night Watch", PublicationStructure.load(publication).active(locator)?.title)
            assertNull(publication.locatorAtHref("missing.xhtml#b"))
        }
    }
    @Test fun bookmarksPersistLabelsDeduplicateAndCascade() = runBlocking {
        val book = BookEntity(id = "bookmark-test", title = "Test", author = "Test", coverUrl = null, downloadUrl = "", source = BookSource.USER_IMPORTED)
        db.bookDao().insertBook(book)
        val item = BookmarkEntity("one", book.id, "{}", "position:2", "Lamp")
        assertTrue(db.bookmarkDao().insert(item) != -1L)
        assertEquals(-1L, db.bookmarkDao().insert(item.copy(id = "two")))
        db.bookmarkDao().rename(item.id, "Beacon")
        assertEquals("Beacon", db.bookmarkDao().observe(book.id).first().single().label)
        db.bookmarkDao().delete(item.id); assertTrue(db.bookmarkDao().observe(book.id).first().isEmpty())
        db.bookmarkDao().insert(item)
        db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = 'bookmark-test'")
        assertTrue(db.bookmarkDao().observe(book.id).first().isEmpty())
    }
    @Test fun logicalBookmarkKeyIgnoresPresentationAndLocatorTitle() = runBlocking {
        val structure = EpubTextExtractor(context).structure(epub.absolutePath)
        val loc = locator("OEBPS/one.xhtml#b").copy(locations = Locator.Locations(position = 3, progression = 0.6))
        assertEquals(structure.locationKey(loc), structure.locationKey(loc.copy(title = "Different layout", locations = loc.locations.copy(progression = 0.7))))
    }
    @Test fun searchControllerCancelsOldQueryAndHonorsCapturedProgress() = runBlocking {
        EpubTextExtractor(context).withPublication(epub.absolutePath) { publication ->
            val structure = PublicationStructure.load(publication)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = com.yugentech.quill.reader.ui.components.search.ReaderSearchController(publication, structure,
                locator("OEBPS/one.xhtml#b", "Mara saw a moving shape."), scope)
            try {
                controller.update("no-such-phrase")
                controller.update("starlamp", readSoFar = true)
                val result = withTimeout(15000) { controller.state.first { it.query == "starlamp" && !it.loading } }
                assertNull(result.error); assertEquals(1, result.results.size)
                assertFalse(result.results.any { it.href.toString().contains("three.xhtml") })
                controller.more()
                withTimeout(15000) { controller.state.first { it.finished } }
                controller.update("cafe", readSoFar = false)
                val unicode = withTimeout(15000) { controller.state.first { it.query == "cafe" && !it.loading } }
                assertEquals(1, unicode.results.size)
                controller.update(""); assertTrue(controller.state.value.results.isEmpty())
            } finally { controller.close(); scope.cancel() }
        }
    }
    @Test fun bookmarksSurviveDatabaseReopeningAndRemovingOnlyDownloadedFile() = runBlocking {
        val name = "bookmark-restart-${UUID.randomUUID()}.db"
        var persisted = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val book = BookEntity("restart-book", "Test", "Author", null, downloadUrl = "", source = BookSource.USER_IMPORTED, localFilePath = epub.absolutePath)
        val structure = EpubTextExtractor(context).structure(epub.absolutePath)
        val loc = locator("OEBPS/one.xhtml#b").copy(locations = Locator.Locations(fragments = listOf("b"), position = 3))
        val key = structure.locationKey(loc)
        try {
            persisted.bookDao().insertBook(book)
            persisted.bookmarkDao().insert(BookmarkEntity("restart-mark", book.id, loc.toJSON().toString(), key, "Night vision"))
            persisted.close(); persisted = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(loc.toJSON().toString(), persisted.bookmarkDao().atLocation(book.id, key)!!.locatorJson)
            persisted.openHelper.writableDatabase.execSQL("UPDATE books SET localFilePath=NULL WHERE id='restart-book'")
            assertEquals("Night vision", persisted.bookmarkDao().atLocation(book.id, key)!!.label)
        } finally { persisted.close(); context.deleteDatabase(name) }
    }
    @Test fun visibleContentLocatorTracksSharedFileChapterAndReadingBoundary() = runBlocking {
        val structure = EpubTextExtractor(context).structure(epub.absolutePath)
        val coarse = locator("OEBPS/one.xhtml").copy(locations = Locator.Locations(progression = 0.3, position = 1, totalProgression = 0.0))
        val snapshot = org.json.JSONObject("""{"first":{"css":"#b","text":"Night Watch","before":""},"last":{"css":"body > p:nth-of-type(20)","text":"shape.","before":"StarlAmp painted the nighttime desert in blues and whites. Mara saw a moving "}}""")
        val tracked = com.yugentech.quill.reader.ui.components.engine.ReaderLocatorTracker.apply(coarse, snapshot, structure)
        assertEquals("Night Watch", structure.active(tracked)?.title)
        assertTrue(structure.text(structure.beginning, structure.readingBoundary(tracked)).endsWith("Mara saw a moving shape."))
        assertTrue(tracked.locations.totalProgression!! > 0.4)
        val restored = Locator.fromJSON(tracked.toJSON())!!
        assertEquals(structure.locationKey(tracked), structure.locationKey(restored))
        assertEquals(structure.readingBoundary(tracked), structure.readingBoundary(restored))
        assertEquals(structure.locationKey(tracked), structure.locationKey(tracked.copy(locations = tracked.locations.copy(progression = 0.9, position = 9))))
        assertTrue(com.yugentech.quill.aira.context.ScopeResolver.resolve(structure,
            com.yugentech.quill.aira.context.ContextPlan(com.yugentech.quill.aira.context.RequestedScope.CURRENT_CHAPTER), coarse, "Omnibus", true)
            is com.yugentech.quill.aira.context.ScopeResolution.Clarify)
    }
}
