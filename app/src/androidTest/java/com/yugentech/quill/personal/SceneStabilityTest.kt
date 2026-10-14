package com.yugentech.quill.personal

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.chat.bookChat.repository.BookChatRepositoryImpl
import com.yugentech.quill.aira.chat.bookChat.service.BookChatService
import com.yugentech.quill.aira.chat.generalChat.repository.GeneralChatRepositoryImpl
import com.yugentech.quill.aira.chat.generalChat.service.GeneralChatService
import com.yugentech.quill.aira.chat.quickChat.repository.QuickChatRepositoryImpl
import com.yugentech.quill.aira.chat.quickChat.service.*
import com.yugentech.quill.aira.chat.quickChat.prompt.QuickPrompt
import com.yugentech.quill.aira.context.*
import com.yugentech.quill.aira.intent.repository.IntentDetectionRepository
import com.yugentech.quill.aira.intent.service.IntentDetectionService
import com.yugentech.quill.aira.provider.*
import com.yugentech.quill.aira.rag.*
import com.yugentech.quill.aira.repository.AiraChatRepositoryImpl
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.aira.service.AiraChatService
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.database.entity.BookEntity
import com.yugentech.quill.database.model.*
import com.yugentech.quill.domain.*
import com.yugentech.quill.domain.ai.*
import com.yugentech.quill.reader.ReaderActivity
import com.yugentech.quill.reader.repository.book.ReaderBookRepository
import com.yugentech.quill.reader.viewmodel.quick.QuickViewModel
import com.yugentech.quill.di.modules.shared.readerModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.*
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import java.util.UUID
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class SceneStabilityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "stability-${UUID.randomUUID()}"
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var settings: ConnectionStore
    private lateinit var gateway: DirectAiGateway
    private lateinit var vault: CredentialStore
    private lateinit var client: HttpClient
    private lateinit var store: BookStructureStore
    private val requests = Collections.synchronizedList(mutableListOf<JSONObject>())
    @Volatile private var delayAnswer = 0L
    private val selection = "Ivo said, ‘Keep your promise, Mara.’"
    private val current get() = Locator(Url("OEBPS/two.xhtml")!!, MediaType.XHTML, title = "Primitive fixture",
        locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#q30")), text = Locator.Text(highlight = selection))
    private fun service() = AiraChatService(IntentDetectionRepository(IntentDetectionService(gateway)),
        GeneralChatRepositoryImpl(GeneralChatService(gateway)), BookChatRepositoryImpl(BookChatService(gateway), EpubTextExtractor(context), store),
        db.bookDao(), db.airaMessageDao(), object : LocalProfile { override val currentUser = "test"; override val currentUserName = "Reader" }, db.airaDiscussionDao())

    @Before fun setup(): Unit = runBlocking {
        db = GlobalContext.get().get()
        store = GlobalContext.get().get()
        file = File(context.cacheDir, "$id.epub")
        writeDiscussionFixture(file)
        val entries = ZipFile(file).use { zip -> zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } } }.toMutableMap()
        val large = buildString {
            append("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Earlier chapters</title></head><body>")
            repeat(24000) { i -> append("<p${if (i == 23990) " id='r$i'" else ""}>Mara and Ivo discussed the lamp beside the harbor. Café — 雨 🌙. A repeated name can occur many times without identifying a unique passage. Earlier event $i.</p>") }
            append("<hr/><p id='contract'>Mara promised Ivo she would repair the lamp before the boat returned.</p></body></html>")
        }
        assertTrue(large.toByteArray().size > 3_000_000)
        entries["OEBPS/one.xhtml"] = large
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
        settings = ConnectionStore(context, context.getSharedPreferences(id, 0))
        settings.select("text", AiConnection(AiProvider.OPENAI, "test", contextTokens = 32768))
        client = HttpClient(MockEngine { request ->
            val body = JSONObject((request.body as TextContent).text); requests.add(body)
            val classifier = body.getString("instructions").contains("Classify a reading-companion")
            if (!classifier) delay(delayAnswer)
            val answer = if (classifier) """{"isRAG":true,"queryIntent":"relationship","queryVariations":[],"entities":["Ivo","Mara"],"keywords":["promise"],"scope":"RELEVANT_PASSAGES"}"""
                else "Ivo is reminding Mara of her promise to repair the lamp. [S1]"
            respond("data: " + JSONObject().put("type", "response.output_text.delta").put("delta", answer) +
                "\n\ndata: {\"type\":\"response.completed\"}\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        val credentialContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = this@SceneStabilityTest.context.getSharedPreferences("$id-$name", mode)
        }
        vault = CredentialStore(credentialContext).also { it.put("OPENAI", "dummy") }
        gateway = DirectAiGateway(settings, HttpAiProvider(client, CredentialResolver { "dummy" }), vault)
        db.bookDao().insertBook(BookEntity(id, "Large scene fixture", "Test", null, downloadUrl = "", source = BookSource.USER_IMPORTED,
            localFilePath = file.absolutePath, lastLocatorJson = current.toJSON().toString(), downloadStatus = DownloadStatus.DOWNLOADED, totalPages = 800))
    }
    @After fun cleanup(): Unit = runBlocking {
        store.invalidate(id)
        db.bookDao().deleteBook(id); file.delete(); settings.preferences.edit().clear().commit(); client.close()
        context.getSharedPreferences("$id-ai_credentials", 0).edit().clear().commit()
    }

    @Test fun coldAndWarmSceneRequestsStayResponsiveAndKeepBoundary(): Unit = runBlocking {
        val owner = service()
        val gaps = AtomicLong(); val beats = AtomicLong()
        val ticker = launch(Dispatchers.Main) {
            var previous = SystemClock.uptimeMillis()
            while (isActive) { delay(16); val now = SystemClock.uptimeMillis(); gaps.accumulateAndGet(now - previous, ::maxOf); previous = now; beats.incrementAndGet() }
        }
        try {
            val before = store.diagnostics().loads
            val coldStart = SystemClock.uptimeMillis()
            val first = withContext(Dispatchers.Main) { owner.scene(id, null, ReaderRequestContext(current), false).toList() }
            val cold = SystemClock.uptimeMillis() - coldStart
            assertTrue(first.first() is AiraResponse.Preparing); assertTrue(first.last().toString(), first.last() is AiraResponse.Success)
            val original = db.airaDiscussionDao().get(id)!!
            val later = current.copy(text = Locator.Text(highlight = "UNREAD-SCENE 35: Ivo reveals the ending."),
                locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#q35")))
            withContext(Dispatchers.Main) { owner.ask(id, "Why did he say that?", request = ReaderRequestContext(later)).toList() }
            withContext(Dispatchers.Main) { owner.expandContext(id).toList() }
            assertEquals(before + 1, store.diagnostics().loads)
            assertEquals(original.boundaryLocatorJson, db.airaDiscussionDao().get(id)!!.boundaryLocatorJson)
            assertEquals(48, db.airaDiscussionDao().get(id)!!.paragraphCount)
            assertTrue(beats.get() > 5); assertTrue("Main-thread gap ${gaps.get()} ms", gaps.get() <= 250)
            assertFalse(requests.any { it.toString().contains("UNREAD-SCENE") })
            Log.i("SceneStability", "coldMs=$cold maxMainGapMs=${gaps.get()} beats=${beats.get()} loads=${store.diagnostics().loads - before}")
        } finally { ticker.cancelAndJoin() }
    }

    @Test fun deadlineKeepsRequestedTextAndExactSourcesWithoutGuessing(): Unit = runBlocking {
        val structure = EpubTextExtractor(context).structure(file.path)
        val doc = structure.documents[1]
        Log.i("SceneStability", "diagnostic selector=${doc.selector("#q30")} anchor=${doc.anchor("q30")} quote=${doc.text.indexOf(selection)} exact=${doc.exactRange(current)} boundary=${structure.exactReadingBoundary(current)}")
        val result = withContext(Dispatchers.Main) { SceneEvidence.build(structure, selection,
            ReaderRequestContext(current, current), listOf("Mara"), lookupBudgetMillis = 0) { assertNotEquals(Looper.getMainLooper(), Looper.myLooper()) } } as EvidenceResult.Ready
        assertNotNull(result.retrievalNotice); assertEquals(24, result.sources.size)
        assertTrue(result.text.contains(selection)); assertFalse(result.text.contains("UNREAD-SCENE"))
        result.sources.forEach { source ->
            val locator = parseLocator(source.locatorJson)!!
            assertTrue(structure.isPrecise(locator))
            assertTrue(structure.readingBoundary(locator) <= structure.readingBoundary(current))
        }
        val ambiguous = current.copy(locations = Locator.Locations(), text = Locator.Text(highlight = "Mara and Ivo"))
        assertNull(structure.documents[1].exactRange(ambiguous))
        val brokenEnd = current.copy(locations = current.locations.copy(otherLocations = current.locations.otherLocations +
            mapOf("quillReadingEndCss" to "#missing", "quillReadingEndText" to "Unresolved end")))
        assertNull(structure.exactReadingBoundary(brokenEnd))
        assertTrue(SceneEvidence.build(structure, null, ReaderRequestContext(brokenEnd), emptyList()) is EvidenceResult.Missing)
    }

    @Test fun cancelledFreshSceneKeepsCompletedDiscussionAndCannotSaveLateAnswer(): Unit = runBlocking {
        val owner = service()
        owner.scene(id, selection, ReaderRequestContext(current, current), true).toList()
        val completed = db.airaDiscussionDao().get(id)!!
        val count = db.airaMessageDao().getMessagesForBookOnce(id).size
        delayAnswer = 10000
        val old = launch(Dispatchers.Main) { owner.scene(id, null, ReaderRequestContext(current), false).collect() }
        withTimeout(10000) { while (requests.size < 2) delay(20) }
        old.cancelAndJoin()
        assertEquals(completed, db.airaDiscussionDao().get(id))
        assertEquals(count, db.airaMessageDao().getMessagesForBookOnce(id).size)
        delayAnswer = 0
        assertTrue(owner.ask(id, "Why did he say that?").toList().last() is AiraResponse.Success)
    }

    @Test fun readerPanelRemainsInteractiveDuringLookupAndStopRetry(): Unit = runBlocking {
        val owner = service(); val repo = AiraChatRepositoryImpl(owner, db.airaMessageDao())
        val quick = QuickChatRepositoryImpl(context, db.bookDao(), db.visualDao(), db.airaMessageDao(), EpubTextExtractor(context),
            QuickChatService(gateway), VisualizeService(gateway), owner)
        val vm = withContext(Dispatchers.Main) { QuickViewModel(repo, quick, GlobalContext.get().get<ReaderBookRepository>(), FeatureAccess(), gateway) }
        loadKoinModules(module { viewModel { vm } })
        delayAnswer = 10000
        try {
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.createIntent(context, id, "OEBPS/one.xhtml#r23990")).use { scenario ->
                compose.waitUntil(30000) {
                    var loaded = false
                    scenario.onActivity { activity -> loaded = androidx.lifecycle.ViewModelProvider(activity)[com.yugentech.quill.reader.viewmodel.reader.ReaderViewModel::class.java].uiState.value is com.yugentech.quill.reader.viewmodel.reader.ReaderUiState.Success }
                    loaded
                }
                compose.mainClock.advanceTimeBy(2000)
                var navigator: org.readium.r2.navigator.epub.EpubNavigatorFragment? = null
                compose.waitUntil(30000) {
                    scenario.onActivity { activity -> navigator = activity.supportFragmentManager.fragments
                        .filterIsInstance<org.readium.r2.navigator.epub.EpubNavigatorFragment>().firstOrNull { it.isResumed } }
                    navigator != null
                }
                val nav = navigator!!
                withTimeout(60000) {
                    while (withContext(Dispatchers.Main) { nav.evaluateJavascript("!!document.getElementById('r23990') && document.fonts.status === 'loaded'") } != "true") {
                        withContext(Dispatchers.Main) { compose.mainClock.advanceTimeByFrame() }
                        delay(100)
                    }
                }
                withContext(Dispatchers.Main) {
                    nav.go(Locator(Url("OEBPS/one.xhtml")!!, MediaType.XHTML,
                        locations = Locator.Locations(fragments = listOf("r23990"))), animated = false)
                }
                try { compose.waitUntil(30000) {
                    compose.mainClock.advanceTimeByFrame()
                    val position = runBlocking { db.bookDao().getBookEntity(id)?.lastLocatorJson?.let(::parseLocator) }
                    position?.href?.toString() == "OEBPS/one.xhtml" && position.locations.otherLocations.containsKey("domRange") &&
                        (position.locations.totalProgression ?: 0.0) > 0.98
                } } catch (error: Throwable) {
                    Log.i("SceneStability", "readerPosition=${db.bookDao().getBookEntity(id)?.lastLocatorJson}")
                    throw error
                }
                if (compose.onAllNodesWithText("Ask Aira").fetchSemanticsNodes().isEmpty()) compose.onRoot().performTouchInput { click(center) }
                compose.waitUntil(30000) { compose.onAllNodesWithText("Ask Aira").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Ask Aira").performClick()
                compose.waitUntil(15000) { compose.onAllNodesWithText("Catch me up here").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Catch me up here").performClick()
                compose.waitUntil(10000) { vm.uiState.value.isLoading }
                scenario.onActivity { activity -> androidx.lifecycle.ViewModelProvider(activity)[com.yugentech.quill.reader.viewmodel.reader.ReaderViewModel::class.java].onVolumeDown() }
                compose.onNodeWithText("Aira").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(0f, -120f), durationMillis = 100) }
                val start = SystemClock.uptimeMillis()
                compose.onNodeWithContentDescription("Stop").performClick()
                compose.waitUntil(1000) { !vm.uiState.value.isLoading }
                assertTrue("Stop ${SystemClock.uptimeMillis() - start} ms", SystemClock.uptimeMillis() - start <= 500)
                delayAnswer = 0
                withContext(Dispatchers.Main) { vm.handleQuickPrompt(id, QuickPrompt.ExplainConversation(selection), ReaderRequestContext(current, current)) }
                compose.waitUntil(15000) { !vm.uiState.value.isLoading && vm.uiState.value.response?.contains("repair the lamp") == true }
                val answer = vm.uiState.value.response
                delayAnswer = 10000
                withContext(Dispatchers.Main) { vm.handleQuickPrompt(id, QuickPrompt.IncludeMoreContext) }
                compose.waitUntil(5000) { vm.uiState.value.isLoading }
                withContext(Dispatchers.Main) { vm.stopGeneration() }
                assertEquals(answer, vm.uiState.value.response)
                scenario.recreate()
                compose.waitUntil(15000) { vm.uiState.value.response == answer }
                assertFalse(requests.last().toString().contains("UNREAD-SCENE"))
            }
        } finally { withContext(Dispatchers.Main) { vm.stopGeneration() }; loadKoinModules(readerModule) }
    }
}
