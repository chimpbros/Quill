package com.yugentech.quill.personal

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.context.*
import com.yugentech.quill.aira.intent.model.*
import com.yugentech.quill.aira.rag.*
import com.yugentech.quill.aira.provider.*
import com.yugentech.quill.aira.service.AiraChatService
import com.yugentech.quill.aira.chat.bookChat.repository.BookChatRepositoryImpl
import com.yugentech.quill.aira.chat.bookChat.service.BookChatService
import com.yugentech.quill.aira.chat.generalChat.repository.GeneralChatRepositoryImpl
import com.yugentech.quill.aira.chat.generalChat.service.GeneralChatService
import com.yugentech.quill.aira.intent.repository.IntentDetectionRepository
import com.yugentech.quill.aira.intent.service.IntentDetectionService
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.database.entity.*
import com.yugentech.quill.database.model.BookSource
import com.yugentech.quill.domain.LocalProfile
import com.yugentech.quill.domain.ai.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContextScopeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var epub: File
    private val extractor by lazy { EpubTextExtractor(context) }
    @Before fun setup() { epub = File(context.cacheDir, "scope-${UUID.randomUUID()}.epub"); writeNavigationFixture(epub) }
    @After fun cleanup() { epub.delete() }
    private fun locator(href: String, text: String? = null) = Locator(Url(href)!!, MediaType.XHTML, text = Locator.Text(highlight = text))
    private val current get() = locator("OEBPS/one.xhtml#b", "Mara saw a moving shape.")
    private fun route(scope: RequestedScope, target: String? = null, term: String? = null, granted: Boolean = false) =
        Intent.BookRelated(listOf(term ?: "Mara"), listOfNotNull(term), listOf("Mara"),
            if (term == null) QueryIntent.CHAPTER_SUMMARY else QueryIntent.TERM_EXPLANATION,
            plan = ContextPlan(scope, target, term, permissionGranted = granted))
    @Test fun namedNovelAndChapterNumberUseTocBoundaries() = runBlocking {
        val structure = extractor.structure(epub.absolutePath)
        val named = ScopeResolver.resolve(structure, route(RequestedScope.NAMED_SECTION, "The Lantern", granted = true).plan, current, "Omnibus", true) as ScopeResolution.Ready
        val text = structure.text(named.start, named.end)
        assertTrue(text.contains("Mara chose to keep")); assertFalse(text.contains("UNREAD-SECRET"))
        val third = ScopeResolver.resolve(structure, route(RequestedScope.NAMED_SECTION, "chapter 3", granted = true).plan, current, "Omnibus", true) as ScopeResolution.Ready
        assertEquals("Homecoming", third.label); assertEquals(1, third.start.spine)
        assertFalse(structure.text(third.start, third.end).contains("Night Watch"))
    }
    @Test fun ambiguityAndSpoilersRequireRequestSpecificClarification() = runBlocking {
        val structure = extractor.structure(epub.absolutePath)
        val ambiguous = ScopeResolver.resolve(structure, ContextPlan(RequestedScope.UNRESOLVED), current, "Omnibus", true) as ScopeResolution.Clarify
        assertTrue(ambiguous.choices.any { it.label == "The Lantern" }); assertTrue(ambiguous.choices.any { it.label == "The Bridge" })
        assertTrue(ScopeResolver.resolve(structure, ContextPlan(RequestedScope.NAMED_SECTION, "Arrival"), current, "Omnibus", true) is ScopeResolution.Clarify)
        val wider = ScopeResolver.resolve(structure, ContextPlan(RequestedScope.WHOLE_BOOK), current, "Omnibus", true) as ScopeResolution.Clarify
        assertFalse(wider.choices.first().plan.permissionGranted)
        assertTrue(wider.choices.last().plan.permissionGranted)
        val approved = ScopeResolver.resolve(structure, wider.choices.last().plan, current, "Omnibus", true) as ScopeResolution.Ready
        assertTrue(approved.requiresSpoilerPermission)
        assertTrue(ScopeResolver.resolve(structure, ContextPlan(RequestedScope.WHOLE_BOOK), current, "Omnibus", true) is ScopeResolution.Clarify)
    }
    @Test fun literalTermEvidenceIsAvailableWithoutEmbeddingsAndClippedToProgress() = runBlocking {
        val evidence = BookEvidence(extractor).build(epub.absolutePath, "Omnibus", true,
            route(RequestedScope.RELEVANT_PASSAGES, term = "StarlAmp"), null, ReaderRequestContext(current)) as EvidenceResult.Ready
        assertTrue(evidence.text.contains("blues and whites")); assertFalse(evidence.text.contains("UNREAD-SECRET"))
        assertFalse(evidence.text.contains("second StarlAmp")); assertFalse(evidence.requiresSpoilerPermission)
        val missing = BookEvidence(extractor).build(epub.absolutePath, "Omnibus", true,
            route(RequestedScope.RELEVANT_PASSAGES, term = "NeverMentioned"), null, ReaderRequestContext(current)) as EvidenceResult.Missing
        assertTrue(missing.message.contains("No literal matches"))
        val selected = BookEvidence(extractor).build(epub.absolutePath, "Omnibus", true,
            route(RequestedScope.PASSAGE, term = "NeverMentioned"), "A usable selection", ReaderRequestContext(current)) as EvidenceResult.Ready
        assertTrue(selected.text.contains("selection"))
        assertTrue(BookEvidence(extractor).build(null, "Omnibus", true, route(RequestedScope.PASSAGE), "A supplied passage", ReaderRequestContext()) is EvidenceResult.Ready)
        assertTrue(BookEvidence(extractor).build(null, "Omnibus", true, route(RequestedScope.READ_SO_FAR), null, ReaderRequestContext()) is EvidenceResult.Missing)
    }
    @Test fun currentChapterIsCompleteButReadSoFarStopsAtLocator() = runBlocking {
        val chapter = BookEvidence(extractor).build(epub.absolutePath, "Omnibus", true, route(RequestedScope.CURRENT_CHAPTER), null, ReaderRequestContext(current)) as EvidenceResult.Ready
        assertTrue(chapter.text.contains("Night Watch")); assertFalse(chapter.text.contains("Café"))
        assertTrue(chapter.requiresSpoilerPermission)
        val soFar = BookEvidence(extractor).build(epub.absolutePath, "Omnibus", false, route(RequestedScope.READ_SO_FAR), null, ReaderRequestContext(current)) as EvidenceResult.Ready
        assertTrue(soFar.text.contains("Café")); assertTrue(soFar.text.endsWith("Mara saw a moving shape.")); assertFalse(soFar.text.contains("UNREAD-SECRET"))
    }
    @Test fun broaderAnswersCannotLeakThroughSubsequentHistoryAndSelectionsSurviveTypedChat() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = ConnectionStore(context, context.getSharedPreferences("scope-test-${UUID.randomUUID()}", 0))
        settings.select("text", AiConnection(AiProvider.OPENAI, "test", contextTokens = 1_050_000))
        val bodies = mutableListOf<JSONObject>()
        val client = HttpClient(MockEngine { req ->
            val body = JSONObject((req.body as TextContent).text); bodies.add(body)
            respond("data: {\"type\":\"response.output_text.delta\",\"delta\":\"UNREAD-SECRET mocked answer\"}\n\ndata: {\"type\":\"response.completed\"}\n\n", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        try {
            val gateway = DirectAiGateway(settings, HttpAiProvider(client, CredentialResolver { "dummy" }), CredentialStore(context))
            val service = AiraChatService(IntentDetectionRepository(IntentDetectionService(gateway)),
                GeneralChatRepositoryImpl(GeneralChatService(gateway)), BookChatRepositoryImpl(BookChatService(gateway), extractor),
                db.bookDao(), db.airaMessageDao(), object : LocalProfile { override val currentUser = "test"; override val currentUserName = "Reader" })
            val book = BookEntity("scope-test", "Omnibus", "Test", null, downloadUrl = "", source = BookSource.USER_IMPORTED,
                localFilePath = epub.absolutePath, lastLocatorJson = current.toJSON().toString())
            db.bookDao().insertBook(book)
            val clarification = service.askBook(book.id, "Recap The Lantern", null, ReaderRequestContext(),
                route(RequestedScope.NAMED_SECTION, "The Lantern")).toList().last() as AiraResponse.Clarification
            assertTrue(bodies.isEmpty())
            val approved = service.continueRequest(clarification.requestId, "allow-requested").toList().last() as AiraResponse.Success
            assertTrue(approved.requiresSpoilerPermission)
            assertTrue(db.airaMessageDao().getMessagesForBookOnce(book.id).all { it.requiresSpoilerPermission })
            service.askBook(book.id, "What is StarlAmp?", "SELECTED-ANCHOR", ReaderRequestContext(),
                route(RequestedScope.RELEVANT_PASSAGES, term = "StarlAmp")).toList()
            val last = bodies.last().getJSONArray("input")
            assertEquals(1, last.length()); assertTrue(last.toString().contains("SELECTED-ANCHOR")); assertFalse(last.toString().contains("UNREAD-SECRET"))
            assertTrue(last.toString().contains("blues and whites"))
            assertTrue(service.continueRequest(clarification.requestId, "allow-requested").toList().last() is AiraResponse.Error)
        } finally { client.close(); db.close(); settings.preferences.edit().clear().commit() }
    }
}
