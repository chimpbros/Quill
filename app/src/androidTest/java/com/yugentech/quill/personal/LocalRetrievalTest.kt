package com.yugentech.quill.personal
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.rag.*
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.database.entity.*
import com.yugentech.quill.database.model.BookSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class LocalRetrievalTest {
 private val instrument=InstrumentationRegistry.getInstrumentation()
 private val context=instrument.targetContext
 private lateinit var db:AppDatabase
 private lateinit var engine:EmbeddingEngine
 private lateinit var epub:File
 @Before fun setup() {
  db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();engine=EmbeddingEngine(context)
  epub=File(context.cacheDir,"retrieval-${UUID.randomUUID()}.epub")
  instrument.context.assets.open("sample.epub").use { input->epub.outputStream().use { input.copyTo(it) } }
 }
 @After fun cleanup() { engine.close();db.close();epub.delete() }
 @Test fun localModelEmbedsRealEpubAndPreservesChapterBoundaries()=runBlocking(Dispatchers.IO) {
  val extractor=EpubTextExtractor(context);val chapters=extractor.extractStream(epub.absolutePath).toList()
  assertEquals(3,chapters.size);assertTrue(chapters.first().text.contains("Mira"));assertFalse(chapters.first().text.contains("mayor"))
  val embedding=engine.embed(chapters.first().text)!!
  assertEquals(384,embedding.size);assertTrue(embedding.all { it.isFinite() })
  assertTrue(kotlin.math.abs(embedding.sumOf { (it*it).toDouble() }-1.0)<0.01)
  assertTrue(ChunkingStrategy.chunk(chapters.first()).all { it.chapterIndex==0 })
 }
 @Test fun spoilerLockExcludesUnreadChaptersAndCanBeReleased()=runBlocking(Dispatchers.IO) {
  val chapters=EpubTextExtractor(context).extractStream(epub.absolutePath).toList()
  val book=BookEntity(id="retrieval-test",title="Lantern",author="Test",coverUrl=null,progressPercent=25f,lastChapterIndex=0,downloadUrl="",source=BookSource.USER_IMPORTED,localFilePath=epub.absolutePath)
  db.bookDao().insertBooks(listOf(book))
  db.bookChunkDao().insertChunks(chapters.map { chapter -> BookChunkEntity(bookId=book.id,chapterIndex=chapter.chapterIndex,chapterTitle=chapter.chapterTitle,chunkIndex=0,text=chapter.text,embedding=engine.embed(chapter.text)!!) })
  val retriever=RagRetriever(db.bookChunkDao(),db.bookDao(),engine)
  val locked=retriever.retrieve(book.id,"Mira",entities=listOf("Mira"),spoilerLockEnabled=true)
  assertTrue(locked.isNotEmpty());assertTrue(locked.all { it.chapterIndex==0 });assertFalse(locked.any { it.text.contains("mayor") })
  val unlocked=retriever.retrieve(book.id,"Mira",entities=listOf("Mira"),topPassages=10,spoilerLockEnabled=false)
  assertTrue(unlocked.any { it.chapterIndex==2 })
  retriever.invalidate()
  assertTrue(retriever.retrieve(book.id,"Mira",entities=listOf("Mira"),spoilerLockEnabled=true).all { it.chapterIndex==0 })
  db.bookDao().insertBooks(listOf(book.copy(progressPercent=0f,lastReadTime=42L,lastChapterTitle="The Lantern")))
  assertTrue(retriever.retrieve(book.id,"Mira",entities=listOf("Mira"),spoilerLockEnabled=true).isNotEmpty())
 }
}
