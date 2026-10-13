package com.yugentech.quill.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.context.SourceExcerpt
import com.yugentech.quill.aira.rag.*
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.database.entity.*
import com.yugentech.quill.database.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File

/** Explicitly invoked only on the disposable emulator. No provider or real book is used. */
@RunWith(AndroidJUnit4::class)
class RangeQaSeed {
    @Test fun prepareSyntheticSceneAndRange(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "scene-range-qa.epub").also(::writeDiscussionFixture)
        val structure = EpubTextExtractor(context).structure(file.absolutePath)
        fun loc(file: String, id: String, text: String) = Locator(Url(file)!!, MediaType.XHTML,
            locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#$id")), text = Locator.Text(highlight = text))
        val start = loc("OEBPS/one.xhtml", "unicode", "Café — 雨 🌙, said Mara.")
        val end = loc("OEBPS/two.xhtml", "q1", "Mara and Ivo waited beside the harbor. Conversation line 1.")
        val range = TextRangeResolver.resolve(structure, start, end)
        val db = GlobalContext.get().get<AppDatabase>()
        db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id='scene-range-qa'")
        db.bookDao().insertBook(BookEntity("scene-range-qa", "Scene and Range QA", "Synthetic fixture", null,
            downloadUrl = "", source = BookSource.USER_IMPORTED, localFilePath = file.absolutePath,
            downloadStatus = DownloadStatus.DOWNLOADED, totalPages = 20, lastChapterTitle = "The promise",
            lastLocatorJson = range.startLocator.toJSON().toString(), chapters = structure.sections.mapIndexed { i, s -> Chapter(s.title, s.href, i, s.depth) }))
        db.highlightDao().insertHighlight(HighlightEntity("qa-range", "scene-range-qa", range.startLocator.toJSON().toString(),
            0xFF90CAF9.toInt(), "One highlight across two files", segmentsJson = range.segmentsJson(), selectedText = range.text))
        val source = SourceExcerpt("S1", "The promise", "Mara promised Ivo she would repair the lamp before the boat returned.",
            loc("OEBPS/one.xhtml", "promise", "Mara promised Ivo she would repair the lamp before the boat returned.").toJSON().toString())
        val answer = "Stored QA answer: Ivo is reminding Mara of her promise to repair the lamp. [S1]"
        db.airaDiscussionDao().save(AiraDiscussionEntity("scene-range-qa", "qa-topic", range.text,
            range.startLocator.toJSON().toString(), range.endLocator.toJSON().toString(), "CONVERSATION", "Conversation · The promise", 24,
            answer, SourceExcerpt.encode(listOf(source)), 1))
        db.airaMessageDao().insertMessages(listOf(
            AiraMessageEntity(bookId = "scene-range-qa", role = AiraMessageRole.USER, content = "Explain this conversation", topicId = "qa-topic"),
            AiraMessageEntity(bookId = "scene-range-qa", role = AiraMessageRole.AIRA, content = answer, topicId = "qa-topic", sourcesJson = SourceExcerpt.encode(listOf(source)))))
    }
}
