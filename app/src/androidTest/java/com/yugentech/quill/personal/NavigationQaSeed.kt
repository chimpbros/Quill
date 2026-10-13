package com.yugentech.quill.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.database.entity.BookEntity
import com.yugentech.quill.database.model.BookSource
import com.yugentech.quill.database.model.DownloadStatus
import com.yugentech.quill.aira.rag.EpubTextExtractor
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly invoked only on the disposable QA emulator, never part of normal test runs. */
@RunWith(AndroidJUnit4::class)
class NavigationQaSeed {
    @Test fun prepareSyntheticReaderBook() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val epub = File(context.filesDir, "navigation-qa.epub"); writeNavigationFixture(epub)
        val pub = EpubTextExtractor(context).structure(epub.absolutePath)
        val db = GlobalContext.get().get<AppDatabase>()
        db.bookDao().insertBook(BookEntity("navigation-qa", "Navigation QA Omnibus", "Synthetic fixture", null,
            downloadUrl = "", source = BookSource.USER_IMPORTED, localFilePath = epub.absolutePath,
            downloadStatus = DownloadStatus.DOWNLOADED, totalPages = 20,
            chapters = pub.sections.mapIndexed { index, section -> com.yugentech.quill.database.model.Chapter(section.title, section.href, index, section.depth) }))
    }
}
