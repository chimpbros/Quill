package com.yugentech.quill.personal

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.chat.bookChat.repository.BookChatRepositoryImpl
import com.yugentech.quill.aira.chat.bookChat.service.BookChatService
import com.yugentech.quill.aira.chat.generalChat.repository.GeneralChatRepositoryImpl
import com.yugentech.quill.aira.chat.generalChat.service.GeneralChatService
import com.yugentech.quill.aira.context.*
import com.yugentech.quill.aira.intent.repository.IntentDetectionRepository
import com.yugentech.quill.aira.intent.service.IntentDetectionService
import com.yugentech.quill.aira.provider.*
import com.yugentech.quill.aira.rag.EpubTextExtractor
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.aira.service.AiraChatService
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.database.entity.*
import com.yugentech.quill.database.model.BookSource
import com.yugentech.quill.domain.LocalProfile
import com.yugentech.quill.domain.ai.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
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
class DiscussionContextTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var databaseName: String
    private lateinit var file: File
    private lateinit var client: HttpClient
    private lateinit var settings: ConnectionStore
    private lateinit var gateway: DirectAiGateway
    private val requests = mutableListOf<JSONObject>()
    private val selection = "Ivo said, ‘Keep your promise, Mara.’"
    private val answer = "Ivo is reminding Mara of her promise to repair the lamp. [S1]"
    private fun locator(id: String, text: String) = Locator(Url("OEBPS/two.xhtml")!!, MediaType.XHTML,
        title = "The conversation", locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#$id")),
        text = Locator.Text(highlight = text))
    private val current get() = locator("q30", selection)
    @Before fun setup() = runBlocking {
        databaseName = "discussion-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        file = File(context.cacheDir, "discussion-${UUID.randomUUID()}.epub").also(::writeDiscussionFixture)
        settings = ConnectionStore(context, context.getSharedPreferences("discussion-${UUID.randomUUID()}", 0))
        settings.select("text", AiConnection(AiProvider.OPENAI, "test", contextTokens = 32768))
        client = HttpClient(MockEngine { request ->
            val body = JSONObject((request.body as TextContent).text); requests.add(body)
            val content = if (body.getString("instructions").contains("Classify a reading-companion")) {
                if (body.toString().contains("Recap The earlier promise"))
                    """{"isRAG":true,"queryIntent":"chapter_summary","queryVariations":["The earlier promise"],"entities":[],"keywords":[],"scope":"NAMED_SECTION","targetName":"The earlier promise"}"""
                else """{"isRAG":true,"queryIntent":"relationship","queryVariations":["Ivo Mara promise"],"entities":["Ivo","Mara"],"keywords":["promise"],"scope":"RELEVANT_PASSAGES"}"""
            } else answer
            respond("data: " + JSONObject().put("type", "response.output_text.delta").put("delta", content) +
                "\n\ndata: {\"type\":\"response.completed\"}\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        gateway = DirectAiGateway(settings, HttpAiProvider(client, CredentialResolver { "dummy" }), CredentialStore(context))
        db.bookDao().insertBook(BookEntity("discussion-test", "The Promise", "Test Author", null, downloadUrl = "",
            source = BookSource.USER_IMPORTED, localFilePath = file.absolutePath, lastLocatorJson = current.toJSON().toString()))
    }
    @After fun cleanup() { client.close(); db.close(); context.deleteDatabase(databaseName); file.delete(); settings.preferences.edit().clear().commit() }
    private fun service() = AiraChatService(IntentDetectionRepository(IntentDetectionService(gateway)),
        GeneralChatRepositoryImpl(GeneralChatService(gateway)), BookChatRepositoryImpl(BookChatService(gateway), EpubTextExtractor(context)),
        db.bookDao(), db.airaMessageDao(), object : LocalProfile { override val currentUser = "test"; override val currentUserName = "Reader" }, db.airaDiscussionDao())

    @Test fun followUpRestoresPassageAndAnswerWithFrozenBoundary() = runBlocking {
        service().scene("discussion-test", selection, ReaderRequestContext(current, current), true).toList()
        val stored = db.airaDiscussionDao().get("discussion-test")!!
        assertEquals(answer, stored.lastResponse); assertEquals(selection, stored.selectionText)
        db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        val reopened = service()
        assertEquals(stored.topicId, reopened.observeDiscussion("discussion-test").first()!!.topicId)
        val later = locator("q35", "UNREAD-SCENE 35: Ivo reveals the ending.")
        val response = reopened.ask("discussion-test", "Why did he say that?", request = ReaderRequestContext(later)).toList().last()
        assertTrue(response is AiraResponse.Success)
        assertTrue((response as AiraResponse.Success).sources.any { it.text.contains("repair the lamp") })
        val classifier = requests.first { it.getString("instructions").contains("Classify a reading-companion") }
        assertEquals(3, classifier.getJSONArray("input").length())
        assertTrue(classifier.toString().contains(answer)); assertTrue(classifier.toString().contains(selection))
        assertFalse(requests.last().toString().contains("UNREAD-SCENE"))
        assertEquals(stored.boundaryLocatorJson, db.airaDiscussionDao().get("discussion-test")!!.boundaryLocatorJson)
        assertTrue(db.airaMessageDao().getMessagesForBookOnce("discussion-test").all { it.topicId == stored.topicId })
    }
    @Test fun selectedCrossFileRangeFreezesItsFinalEndpointForFollowUps() = runBlocking {
        val structure = EpubTextExtractor(context).structure(file.absolutePath)
        val beginning = Locator(Url("OEBPS/one.xhtml")!!, MediaType.XHTML,
            locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#unicode")), text = Locator.Text(highlight = "Café — 雨 🌙, said Mara."))
        val end = locator("q1", "Mara and Ivo waited beside the harbor. Conversation line 1.")
        val range = com.yugentech.quill.aira.rag.TextRangeResolver.resolve(structure, beginning, end)
        val service = service()
        assertTrue(service.scene("discussion-test", range.text,
            ReaderRequestContext(current, range.startLocator, endLocator = range.endLocator), true).toList().last() is AiraResponse.Success)
        val stored = db.airaDiscussionDao().get("discussion-test")!!
        assertEquals(range.endLocator, parseLocator(stored.boundaryLocatorJson))
        assertTrue(service.ask("discussion-test", "Why did he say that?", request = ReaderRequestContext(current)).toList().last() is AiraResponse.Success)
        assertTrue(requests.last().toString().contains("Café"))
        assertFalse(requests.last().toString().contains("Conversation line 2."))
        assertFalse(requests.last().toString().contains("UNREAD-SCENE"))
        assertEquals(stored.topicId, db.airaDiscussionDao().get("discussion-test")!!.topicId)
    }
    @Test fun explicitSectionStartsFreshDiscussionWithoutTheOldSelection() = runBlocking {
        val service = service()
        service.scene("discussion-test", selection, ReaderRequestContext(current, current), true).toList()
        val old = db.airaDiscussionDao().get("discussion-test")!!
        val result = service.ask("discussion-test", "Recap The earlier promise", request = ReaderRequestContext(current)).toList().last()
        assertTrue(result.toString(), result is AiraResponse.Success)
        val fresh = db.airaDiscussionDao().get("discussion-test")!!
        assertNotEquals(old.topicId, fresh.topicId);assertNull(fresh.selectionText);assertEquals("BOOK", fresh.mode)
        assertFalse(requests.last().toString().contains(selection))
        assertTrue(requests.last().toString().contains("repair the lamp"))
        assertEquals(4, db.airaMessageDao().getMessagesForBookOnce("discussion-test").size)
    }
    @Test fun expansionMovesBackwardAcrossFilesAndKeepsSameTopicAndEndpoint() = runBlocking {
        val service = service()
        val first = service.scene("discussion-test", null, ReaderRequestContext(current), false).toList().last() as AiraResponse.Success
        val before = db.airaDiscussionDao().get("discussion-test")!!
        val expanded = service.expandContext("discussion-test").toList().last() as AiraResponse.Success
        val after = db.airaDiscussionDao().get("discussion-test")!!
        assertEquals(48, after.paragraphCount); assertEquals(before.topicId, after.topicId)
        assertEquals(before.boundaryLocatorJson, after.boundaryLocatorJson)
        assertTrue(expanded.sources.any { it.text.contains("Café") })
        assertFalse(expanded.sources.any { it.text.contains("UNREAD-SCENE") })
        assertTrue(expanded.sources.size >= first.sources.size)
        assertTrue(expanded.sources.all { parseLocator(it.locatorJson) != null })
    }
    @Test fun newTopicRetainsTranscriptButExcludesPreviousDiscussionFromRequest() = runBlocking {
        val service = service()
        service.rememberQuick("discussion-test", "Explain this", "OLD-TOPIC-ANSWER", selection, ReaderRequestContext(current, current))
        service.newTopic("discussion-test")
        assertNull(db.airaDiscussionDao().get("discussion-test"))
        service.ask("discussion-test", "What does Mara promise?", request = ReaderRequestContext(current)).toList()
        assertFalse(requests.any { it.toString().contains("OLD-TOPIC-ANSWER") })
        assertTrue(db.airaMessageDao().getMessagesForBookOnce("discussion-test").any { it.content == "OLD-TOPIC-ANSWER" })
    }
    @Test fun approximateLocationRequiresSelectionAndBookDeletionCascades() = runBlocking {
        val approximate = Locator(Url("OEBPS/two.xhtml")!!, MediaType.XHTML, locations = Locator.Locations(progression = 0.8))
        val response = service().scene("discussion-test", null, ReaderRequestContext(approximate), false).toList().last()
        assertTrue(response is AiraResponse.Error); assertTrue(requests.isEmpty())
        db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id='discussion-test'")
        assertNull(db.airaDiscussionDao().get("discussion-test"))
    }
    @Test fun permissionForBroaderAnswerIsNotInheritedThroughRememberedHistory() = runBlocking {
        val service = service()
        service.rememberQuick("discussion-test", "Explain this", "SAFE-ANSWER", selection, ReaderRequestContext(current, current))
        service.rememberQuick("discussion-test", "Allow broader answer", "UNREAD-ANSWER", selection,
            ReaderRequestContext(current, current), broader = true)
        service.ask("discussion-test", "Why did he say that?").toList()
        assertFalse(requests.any { it.toString().contains("UNREAD-ANSWER") })
        assertTrue(requests.first().toString().contains("SAFE-ANSWER"))
    }
}
