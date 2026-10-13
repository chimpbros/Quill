package com.yugentech.quill.personal

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.database.entity.BookEntity
import com.yugentech.quill.database.model.BookSource
import com.yugentech.quill.database.model.DownloadStatus
import com.yugentech.quill.reader.ReaderActivity
import com.yugentech.quill.reader.viewmodel.reader.ReaderUiState
import com.yugentech.quill.reader.viewmodel.reader.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.readium.r2.shared.publication.Locator

@RunWith(AndroidJUnit4::class)
class ReaderRecreationTest {
    @Test fun unbrokenUnicodeParagraphStopsAtVisibleDomBoundary(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "unicode-boundary-${UUID.randomUUID()}"
        val original = File(context.cacheDir, "$id-original.epub").also(::writeDiscussionFixture)
        val file = File(context.cacheDir, "$id.epub")
        val text = "雨月春夏秋冬山川海空".repeat(1000) + "UNREAD-END"
        ZipFile(original).use { source -> ZipOutputStream(file.outputStream()).use { zip ->
            source.entries().asSequence().forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                if (entry.name == "OEBPS/one.xhtml") zip.write(("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Unicode</title></head><body><p id=\"unicode\">$text</p></body></html>").toByteArray())
                else source.getInputStream(entry).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        } }
        val db = GlobalContext.get().get<AppDatabase>()
        db.bookDao().insertBook(BookEntity(id, "Unicode boundary", "Test", null, downloadUrl = "", source = BookSource.USER_IMPORTED,
            localFilePath = file.absolutePath, downloadStatus = DownloadStatus.DOWNLOADED, totalPages = 20))
        try {
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.createIntent(context, id)).use { scenario ->
                withTimeout(20000) { while (db.bookDao().getBookEntity(id)?.lastLocatorJson?.contains("domRange") != true) delay(100) }
                val locator = Locator.fromJSON(JSONObject(db.bookDao().getBookEntity(id)!!.lastLocatorJson!!))!!
                scenario.onActivity {
                    val structure = (ViewModelProvider(it)[ReaderViewModel::class.java].uiState.value as ReaderUiState.Success).structure
                    assertTrue(structure.isPrecise(locator))
                    val end = structure.readingBoundary(locator)
                    assertEquals(0, end.spine)
                    assertTrue("Visible end must not include the offscreen paragraph", end.offset in 1 until text.length)
                    assertFalse(structure.documents[0].text.substring(0, end.offset).contains("UNREAD-END"))
                }
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id=?", arrayOf(id));file.delete();original.delete()
        }
    }
    @Test fun activityRecreationRetainsSharedFileReadingLocation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "recreation-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$id.epub").also(::writeNavigationFixture)
        val db = GlobalContext.get().get<AppDatabase>()
        db.bookDao().insertBook(BookEntity(id, "Recreation fixture", "Test Author", null,
            downloadUrl = "", source = BookSource.USER_IMPORTED, localFilePath = file.absolutePath,
            downloadStatus = DownloadStatus.DOWNLOADED, totalPages = 20))
        try {
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.createIntent(context, id, "OEBPS/one.xhtml#b")).use { scenario ->
                // Wait for actual Readium rendering, locator enrichment, and persisted progress.
                try { withTimeout(20000) {
                    while (true) {
                        val saved = db.bookDao().getBookEntity(id)?.lastLocatorJson?.let(::JSONObject)
                        if (saved?.optString("title") == "Night Watch" && saved.optJSONObject("locations")?.has("cssSelector") == true) break
                        delay(100)
                    }
                } } catch (e: TimeoutCancellationException) {
                    var diagnostics = "saved=${db.bookDao().getBookEntity(id)?.lastLocatorJson}"
                    var nav: org.readium.r2.navigator.epub.EpubNavigatorFragment? = null
                    scenario.onActivity {
                        val vm = ViewModelProvider(it)[ReaderViewModel::class.java]
                        diagnostics += "; initial=${(vm.uiState.value as? ReaderUiState.Success)?.initialLocator}; prefs=${vm.readerPreferences.value.epub}"
                        nav = it.supportFragmentManager.fragments.filterIsInstance<org.readium.r2.navigator.epub.EpubNavigatorFragment>().firstOrNull()
                    }
                    diagnostics += "; DOM=" + withContext(Dispatchers.Main) { nav?.evaluateJavascript("JSON.stringify({url:location.href,top:scrollY,width:innerWidth,height:innerHeight,b:document.getElementById('b').getBoundingClientRect().toJSON()})") }
                    throw AssertionError(diagnostics, e)
                }
                val before = JSONObject(db.bookDao().getBookEntity(id)!!.lastLocatorJson!!)
                assertEquals("Night Watch", before.getString("title"))
                scenario.recreate() // Previously failed while restoring EpubNavigatorFragment.
                withTimeout(20000) {
                    var attached = false
                    while (!attached) {
                        scenario.onActivity { activity ->
                            attached = activity.supportFragmentManager.fragments.any {
                                it.javaClass.name == "org.readium.r2.navigator.epub.EpubNavigatorFragment" && it.isResumed
                            }
                        }
                        if (!attached) delay(100)
                    }
                }
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[ReaderViewModel::class.java].uiState.value as ReaderUiState.Success
                    assertEquals("Night Watch", state.initialLocator?.title)
                    assertEquals(before.getJSONObject("locations").getString("cssSelector"),
                        state.initialLocator?.locations?.otherLocations?.get("cssSelector"))
                }
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = ?", arrayOf(id))
            file.delete()
        }
    }
}
