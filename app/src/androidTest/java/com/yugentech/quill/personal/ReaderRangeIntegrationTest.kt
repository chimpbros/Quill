package com.yugentech.quill.personal

import androidx.compose.ui.test.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.rag.locatorAtHref
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.database.entity.BookEntity
import com.yugentech.quill.database.model.BookSource
import com.yugentech.quill.database.model.DownloadStatus
import com.yugentech.quill.reader.ReaderActivity
import com.yugentech.quill.reader.viewmodel.reader.ReaderUiState
import com.yugentech.quill.reader.viewmodel.reader.ReaderViewModel
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONTokener
import org.koin.core.context.GlobalContext
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderRangeIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun navigator(scenario: ActivityScenario<ReaderActivity>): EpubNavigatorFragment {
        var nav: EpubNavigatorFragment? = null
        compose.waitUntil(20000) { scenario.onActivity { activity ->
            nav = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull { it.isResumed }
        }; nav != null }
        return nav!!
    }
    private fun select(nav: EpubNavigatorFragment, id: String) = runBlocking {
        try { withTimeout(15000) {
            while (withContext(Dispatchers.Main) { nav.evaluateJavascript("!!document.getElementById('$id') && !!window.__quillCaptureDomRange") } != "true") {
                withContext(Dispatchers.Main) { compose.mainClock.advanceTimeByFrame() }
                delay(100)
            }
        } } catch (e: TimeoutCancellationException) {
            val state = withContext(Dispatchers.Main) { nav.evaluateJavascript("JSON.stringify({url:location.href,id:!!document.getElementById('$id'),capture:!!window.__quillCaptureDomRange})") }
            throw AssertionError("Selection $id was not ready: $state", e)
        }
        withContext(Dispatchers.Main) { nav.evaluateJavascript("""
            (function(){var p=document.getElementById('$id'),r=document.createRange();r.selectNodeContents(p);
            var s=getSelection();s.removeAllRanges();s.addRange(r);document.dispatchEvent(new Event('selectionchange'));})();
        """.trimIndent()) }
    }
    private fun screenshot(name: String) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val image = instrument.uiAutomation.takeScreenshot()
        File(instrument.targetContext.getExternalFilesDir(null), name).outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
    private fun tapHighlight(scenario: ActivityScenario<ReaderActivity>, nav: EpubNavigatorFragment) = runBlocking {
        val json = withContext(Dispatchers.Main) { nav.evaluateJavascript("""
            (function(){var i=readium.getDecorations('user_highlights').items[1];
            var r=i.container.firstElementChild.getBoundingClientRect();
            return JSON.stringify({x:(r.left+r.right)/2,y:(r.top+r.bottom)/2,width:innerWidth});})();
        """.trimIndent()) }!!
        val rect = JSONObject(JSONTokener(json).nextValue() as String)
        fun views(view: View): Sequence<View> = sequence {
            yield(view);if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(views(view.getChildAt(i)))
        }
        var x = 0f;var y = 0f
        scenario.onActivity { activity ->
            val web = views(activity.window.decorView).filterIsInstance<WebView>().first {
                it.isShown && it.getGlobalVisibleRect(android.graphics.Rect())
            }
            val origin = IntArray(2);web.getLocationOnScreen(origin)
            val scale = web.width / rect.getDouble("width")
            x = (origin[0] + rect.getDouble("x") * scale).toFloat()
            y = (origin[1] + rect.getDouble("y") * scale).toFloat()
        }
        val instrument = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        instrument.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        instrument.sendPointerSync(MotionEvent.obtain(now, now + 70, MotionEvent.ACTION_UP, x, y, 0))
    }
    @Test fun nativeSelectionDraftSurvivesRecreationAndSavesRenderedCrossFileGroup(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "native-range-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$id.epub").also(::writeDiscussionFixture)
        val db = GlobalContext.get().get<AppDatabase>()
        db.bookDao().insertBook(BookEntity(id, "Range fixture", "Test", null, downloadUrl = "", source = BookSource.USER_IMPORTED,
            localFilePath = file.absolutePath, downloadStatus = DownloadStatus.DOWNLOADED, totalPages = 20))
        try {
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.createIntent(context, id, "OEBPS/one.xhtml#unicode")).use { scenario ->
                select(navigator(scenario), "unicode")
                compose.waitUntil(15000) { compose.onAllNodesWithText("Start here").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Start here").performClick()
                lateinit var vm: ReaderViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[ReaderViewModel::class.java] }
                compose.waitUntil(15000) { vm.rangeState.value.start != null }
                assertNotNull(vm.rangeState.value.start!!.locations.otherLocations["domRange"])
                val start = vm.rangeState.value.start
                scenario.recreate()
                navigator(scenario)
                scenario.onActivity { assertSame(vm, ViewModelProvider(it)[ReaderViewModel::class.java]) }
                assertEquals(start, vm.rangeState.value.start)
                val state = vm.uiState.value as ReaderUiState.Success
                val recreatedNav = navigator(scenario)
                val oldPreferences = vm.readerPreferences.value.epub
                scenario.onActivity { vm.updateEpubPreferences(oldPreferences.copy(fontSize = 1.4)) }
                compose.waitUntil(10000) { vm.readerPreferences.value.epub.fontSize == 1.4 }
                assertEquals(start, vm.rangeState.value.start)
                withContext(Dispatchers.Main) { recreatedNav.go(state.publication.locatorAtHref("OEBPS/two.xhtml#q1")!!, animated = false) }
                select(navigator(scenario), "q1")
                compose.waitUntil(15000) { compose.onAllNodesWithText("End here").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("End here").performClick()
                compose.waitUntil(15000) { vm.rangeState.value.range != null || vm.rangeState.value.error != null }
                assertNull(vm.rangeState.value.error)
                assertTrue(vm.rangeState.value.range!!.text.contains("Café — 雨 🌙, said Mara."))
                assertEquals(2, vm.rangeState.value.range!!.segments.map { it.href.toString() }.distinct().size)
                compose.onNodeWithText("Selected range").assertIsDisplayed()
                compose.waitForIdle()
                screenshot("range-preview.png")
                compose.onNodeWithText("Save highlight").performScrollTo().performClick()
                withTimeout(15000) { while (db.highlightDao().getHighlightsForBook(id).isEmpty()) delay(100) }
                val saved = db.highlightDao().getHighlightsForBook(id).single()
                assertNotNull(saved.segmentsJson)
                val nav = navigator(scenario)
                try { withTimeout(15000) {
                    while (withContext(Dispatchers.Main) { nav.evaluateJavascript("readium.getDecorations('user_highlights').items.length") }?.toIntOrNull() != 2) {
                        withContext(Dispatchers.Main) { compose.mainClock.advanceTimeByFrame() };delay(100)
                    }
                } } catch (e: TimeoutCancellationException) {
                    val state = withContext(Dispatchers.Main) { nav.evaluateJavascript("JSON.stringify({url:location.href,count:readium.getDecorations('user_highlights').items.length})") }
                    throw AssertionError("Saved range not rendered: $state; observed highlights=${vm.highlights.value.size}; segments=${saved.segmentsJson}", e)
                }
                // Each locator is actually resolved by Readium and has an independently rendered container.
                val rendered = withContext(Dispatchers.Main) { nav.evaluateJavascript("readium.getDecorations('user_highlights').items.every(function(i){return !!i.range && !!i.container && i.container.children.length>0})") }
                assertEquals("true", rendered)
                compose.waitUntil(15000) { compose.onAllNodesWithText("Save highlight").fetchSemanticsNodes().isEmpty() }
                withContext(Dispatchers.Main) { nav.go(start!!, animated = false) }
                withTimeout(15000) { while (withContext(Dispatchers.Main) {
                    nav.evaluateJavascript("!!document.getElementById('unicode') && readium.getDecorations('user_highlights').items.some(function(i){return !!i.container && i.range.toString().includes('Café — 雨 🌙, said Mara.')})")
                } != "true") delay(100) }
                screenshot("range-unicode-rendered.png")
                withContext(Dispatchers.Main) { nav.go(com.yugentech.quill.aira.rag.TextRangeResolver.segments(saved.segmentsJson, saved.locatorJson).last(), animated = false) }
                withTimeout(15000) { while (withContext(Dispatchers.Main) {
                    nav.evaluateJavascript("!!document.getElementById('q1') && readium.getDecorations('user_highlights').items.length===2")
                } != "true") delay(100) }
                screenshot("range-rendered.png")
                tapHighlight(scenario, nav)
                compose.waitUntil(15000) { compose.onAllNodesWithText("Delete Highlight").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Delete").performClick()
                scenario.onActivity { vm.updateEpubPreferences(oldPreferences) }
                withTimeout(15000) { while (db.highlightDao().getHighlightsForBook(id).isNotEmpty()) delay(100) }
                withTimeout(15000) { while (withContext(Dispatchers.Main) { nav.evaluateJavascript("readium.getDecorations('user_highlights').items.length") } != "0") {
                    withContext(Dispatchers.Main) { compose.mainClock.advanceTimeByFrame() };delay(100)
                } }
                // Leaving the reader cancels the draft; it is deliberately not stored in Room.
                select(nav, "q1")
                compose.waitUntil(15000) { compose.onAllNodesWithText("Start here").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Start here").performClick()
                assertNotNull(vm.rangeState.value.start)
            }
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.createIntent(context, id)).use { reopened ->
                navigator(reopened)
                reopened.onActivity { assertNull(ViewModelProvider(it)[ReaderViewModel::class.java].rangeState.value.start) }
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id=?", arrayOf(id));file.delete()
        }
    }
}
