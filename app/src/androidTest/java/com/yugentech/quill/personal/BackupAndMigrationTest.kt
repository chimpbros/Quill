package com.yugentech.quill.personal
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.yugentech.quill.database.database.*
import com.yugentech.quill.database.entity.*
import com.yugentech.quill.database.model.BookSource
import com.yugentech.quill.aira.provider.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.*
@RunWith(AndroidJUnit4::class)
class BackupAndMigrationTest {
 private val instrument=InstrumentationRegistry.getInstrumentation()
 private val context:Context=instrument.targetContext.createDeviceProtectedStorageContext()
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private lateinit var db:AppDatabase
 private lateinit var backups:BackupManager
 private val files=mutableListOf<File>()
 @Before fun setup() {
  db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
  val stores=listOf("theme","user","reader").associateWith { name ->
   val file=File(context.cacheDir,"$name-${UUID.randomUUID()}.preferences_pb");files.add(file)
   PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
  }
  backups=BackupManager(context,db,ConnectionStore(context),stores)
 }
 @After fun cleanup() { db.close();scope.cancel();files.forEach { it.delete() } }
 private suspend fun fixture():BookEntity {
  val epub=File(context.filesDir,"fixture-${UUID.randomUUID()}.epub");files.add(epub)
  instrument.context.assets.open("sample.epub").use { input -> epub.outputStream().use { input.copyTo(it) } }
  val image=File(context.filesDir,"fixture-${UUID.randomUUID()}.png");files.add(image);image.writeBytes(byteArrayOf(1,2,3,4))
  val book=BookEntity(id="test-book",title="Lantern at the Bridge",author="Test Author",coverUrl=image.absolutePath,
   progressPercent=42f,lastChapterIndex=1,lastLocatorJson="{\"href\":\"chapter2.xhtml\"}",downloadUrl="",source=BookSource.USER_IMPORTED,localFilePath=epub.absolutePath)
  db.bookDao().insertBooks(listOf(book))
  db.airaMessageDao().insertMessage(AiraMessageEntity(bookId=book.id,role=AiraMessageRole.USER,content="Who carries the lantern?",chapterIndex=1,requiresSpoilerPermission=true))
  db.bookmarkDao().insert(BookmarkEntity("bookmark-fixture",book.id,book.lastLocatorJson!!,"position:2","Lantern"))
  db.airaDiscussionDao().save(AiraDiscussionEntity(book.id,"backup-topic","A lantern",book.lastLocatorJson,
   book.lastLocatorJson,"PASSAGE","Selected lantern",24,"Saved explanation","[]",1))
  db.visualDao().insertVisual(VisualEntity(id="visual-test",bookId=book.id,chapterIndex=1,chapterTitle="The Bridge",sourceText="A lantern",imagePath=image.absolutePath,locatorJson=book.lastLocatorJson))
  db.openHelper.writableDatabase.execSQL("INSERT INTO highlights(id,bookId,locatorJson,colorInt,note,createdAt) VALUES('highlight-test','test-book','{}',42,'A note',1)")
  db.openHelper.writableDatabase.execSQL("UPDATE highlights SET segmentsJson=?,selectedText=? WHERE id='highlight-test'",
   arrayOf("[{\"href\":\"one.xhtml\",\"type\":\"application/xhtml+xml\"},{\"href\":\"two.xhtml\",\"type\":\"application/xhtml+xml\"}]", "Complete range — 雨 🌙"))
  db.openHelper.writableDatabase.execSQL("INSERT INTO reading_sessions(bookId,startTime,endTime,durationMillis) VALUES('test-book',1,100,99)")
  return book
 }
 private fun archive():File=File(context.cacheDir,"backup-${UUID.randomUUID()}.zip").also { files.add(it) }
 @Test fun completeBackupRoundTripsWithoutCredentials()=runBlocking(Dispatchers.IO) {
  val book=fixture();val file=archive();val vault=CredentialStore(context)
  vault.put("test_backup_secret","SECRET-MUST-NOT-BE-EXPORTED")
  try {
   backups.export(Uri.fromFile(file))
   ZipFile(file).use { zip ->
    val manifest=zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()
    assertFalse(manifest.contains("SECRET-MUST-NOT-BE-EXPORTED"));assertFalse(manifest.contains("access_token"));assertFalse(manifest.contains("chatgpt_host"))
   }
   db.openHelper.writableDatabase.execSQL("UPDATE books SET title='Changed',progressPercent=99 WHERE id='test-book'")
   backups.restore(Uri.fromFile(file))
   val restored=db.bookDao().getBookEntity(book.id)!!
   assertEquals(book.title,restored.title);assertEquals(42f,restored.progressPercent)
   assertTrue(File(restored.localFilePath!!).isFile);assertNotEquals(book.localFilePath,restored.localFilePath)
   assertEquals(1,db.airaMessageDao().getMessagesForBookOnce(book.id).single().chapterIndex)
   assertTrue(db.airaMessageDao().getMessagesForBookOnce(book.id).single().requiresSpoilerPermission)
   assertEquals("Lantern",db.bookmarkDao().atLocation(book.id,"position:2")!!.label)
   assertEquals("Saved explanation",db.airaDiscussionDao().get(book.id)!!.lastResponse)
   assertEquals("SECRET-MUST-NOT-BE-EXPORTED",vault.get("test_backup_secret"))
   db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM visuals").use { it.moveToFirst();assertEquals(1,it.getInt(0)) }
   db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM highlights").use { it.moveToFirst();assertEquals(1,it.getInt(0)) }
   val highlight=db.highlightDao().getHighlightsForBook(book.id).single()
   assertEquals("Complete range — 雨 🌙",highlight.selectedText);assertEquals(2,JSONArray(highlight.segmentsJson).length())
   assertEquals("A note",highlight.note)
   db.openHelper.readableDatabase.query("SELECT durationMillis FROM reading_sessions").use { it.moveToFirst();assertEquals(99L,it.getLong(0)) }
  } finally { vault.remove("test_backup_secret") }
 }
 @Test fun corruptArchiveLeavesLibraryIntact()=runBlocking(Dispatchers.IO) {
  val book=fixture();val file=archive();file.writeText("not a backup")
  try { backups.restore(Uri.fromFile(file));fail("Expected rejection") } catch(_:IllegalArgumentException) { }
  assertEquals(book.title,db.bookDao().getBookEntity(book.id)!!.title);assertTrue(File(book.localFilePath!!).isFile)
 }
 @Test fun traversalIsRejected() {
  val root=File(context.cacheDir,"path-test")
  for(path in listOf("../escape","/escape","C:/escape","assets\\..\\escape")) {
   try { BackupPaths.resolve(root,path);fail("Unsafe path accepted") } catch(_:IllegalArgumentException) { }
  }
 }
 @Test fun interruptedRestoreRecoversPriorLibraryOnRestart()=runBlocking(Dispatchers.IO) {
  val book=fixture();val original=archive();backups.export(Uri.fromFile(original))
  val manifest=ZipFile(original).use { zip -> JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()) }
  val tables=manifest.getJSONObject("tables")
  val savedBook=tables.getJSONArray("books").getJSONObject(0)
  savedBook.put("localFilePath",book.localFilePath);savedBook.put("coverUrl",book.coverUrl)
  tables.getJSONArray("visuals").getJSONObject(0).put("imagePath",book.coverUrl)
  val journal=File(context.filesDir,"restore-journal.json")
  journal.writeText(JSONObject().put("tables",tables).put("settings",manifest.getJSONObject("settings")).toString())
  db.openHelper.writableDatabase.execSQL("UPDATE books SET title='Interrupted replacement',progressPercent=99 WHERE id='test-book'")
  backups.recover()
  assertEquals(book.title,db.bookDao().getBookEntity(book.id)!!.title)
  assertEquals(42f,db.bookDao().getBookEntity(book.id)!!.progressPercent)
  assertTrue(File(book.localFilePath!!).exists());assertFalse(journal.exists())
 }
 @Test fun interruptedOlderRestoreJournalsRecoverAfterUpgrade()=runBlocking(Dispatchers.IO) {
  val book=fixture();val original=archive();backups.export(Uri.fromFile(original))
  val manifest=ZipFile(original).use { zip -> JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()) }
  val journal=File(context.filesDir,"restore-journal.json").also { files.add(it) }
  for(version in listOf(4,5,6)) {
   val tables=JSONObject(manifest.getJSONObject("tables").toString())
   tables.getJSONArray("books").getJSONObject(0).put("localFilePath",book.localFilePath).put("coverUrl",book.coverUrl)
   tables.getJSONArray("visuals").getJSONObject(0).put("imagePath",book.coverUrl)
   if(version==4)tables.remove("bookmarks")
   if(version<=5)tables.remove("aira_discussions")
   val messages=tables.getJSONArray("aira_messages")
   for(i in 0 until messages.length()) {
    val row=messages.getJSONObject(i)
    if(version==4)row.remove("requiresSpoilerPermission")
    if(version<=5)for(column in listOf("topicId","boundaryLocatorJson","sourcesJson"))row.remove(column)
   }
   val highlights=tables.getJSONArray("highlights")
   for(i in 0 until highlights.length())for(column in listOf("segmentsJson","selectedText"))highlights.getJSONObject(i).remove(column)
   // Older journals did not record a version. Recovery must infer it conservatively.
   journal.writeText(JSONObject().put("tables",tables).put("settings",manifest.getJSONObject("settings")).toString())
   db.openHelper.writableDatabase.execSQL("UPDATE books SET title='Interrupted replacement' WHERE id='test-book'")
   backups.recover()
   assertEquals(book.title,db.bookDao().getBookEntity(book.id)!!.title)
   assertNull(db.highlightDao().getHighlightsForBook(book.id).single().segmentsJson)
   assertEquals(version==6,db.airaDiscussionDao().get(book.id)!=null)
   assertFalse(journal.exists())
  }
 }
 @Test fun failedDatabaseImportRollsBackOriginalLibrary()=runBlocking(Dispatchers.IO) {
  val book=fixture();val original=archive();backups.export(Uri.fromFile(original));val damaged=archive()
  ZipFile(original).use { source -> ZipOutputStream(damaged.outputStream()).use { output ->
   for(entry in source.entries().asSequence()) {
    output.putNextEntry(ZipEntry(entry.name))
    val bytes=source.getInputStream(entry).readBytes()
    if(entry.name=="manifest.json") {
     val manifest=JSONObject(String(bytes));manifest.getJSONObject("tables").getJSONArray("highlights").getJSONObject(0).put("bookId","missing-book")
     output.write(manifest.toString().toByteArray())
    } else output.write(bytes)
    output.closeEntry()
   }
  } }
  try { backups.restore(Uri.fromFile(damaged));fail("Expected constraint failure") } catch(_:Exception) { }
  assertEquals(book.title,db.bookDao().getBookEntity(book.id)!!.title)
  assertEquals(book.localFilePath,db.bookDao().getBookEntity(book.id)!!.localFilePath)
  assertTrue(File(book.localFilePath!!).isFile);assertFalse(File(context.filesDir,"restore-journal.json").exists())
 }
 @Test fun migrationPreservesV3DataAndAddsChapterProvenance()=runBlocking(Dispatchers.IO) {
  val name="migration-${UUID.randomUUID()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
  val schema=JSONObject(instrument.context.assets.open("room-v4.json").bufferedReader().readText()).getJSONObject("database")
  SQLiteDatabase.openOrCreateDatabase(path,null).use { legacy ->
   val entities=schema.getJSONArray("entities")
   for(i in 0 until entities.length()) {
    val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
    var sql=entity.getString("createSql").replace("\${TABLE_NAME}",table)
    if(table=="aira_messages")sql=sql.replace(", `chapterIndex` INTEGER","")
    legacy.execSQL(sql)
    val indices=entity.optJSONArray("indices") ?: JSONArray()
    for(j in 0 until indices.length())legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
   }
   val views=schema.getJSONArray("views")
   for(i in 0 until views.length()) { val view=views.getJSONObject(i);legacy.execSQL(view.getString("createSql").replace("\${VIEW_NAME}",view.getString("viewName"))) }
   legacy.execSQL("CREATE TABLE quotas (userId TEXT PRIMARY KEY)")
   legacy.execSQL("INSERT INTO users(userId,name,isPro) VALUES('local','Reader',0)");legacy.version=3
  }
  val migrated=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7).build()
  try {
   assertEquals("Reader",migrated.userDao().getUser("local")!!.name)
   migrated.openHelper.readableDatabase.query("PRAGMA table_info(aira_messages)").use { cursor ->
    var found=false;while(cursor.moveToNext())if(cursor.getString(cursor.getColumnIndexOrThrow("name"))=="chapterIndex")found=true;assertTrue(found)
   }
   migrated.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name='quotas'").use { assertEquals(0,it.count) }
  } finally { migrated.close();context.deleteDatabase(name) }
 }
 @Test fun version4BackupRestoresWithoutBookmarksOrNewMessageColumn()=runBlocking(Dispatchers.IO) {
  val book=fixture();val original=archive();backups.export(Uri.fromFile(original));val legacy=archive()
  ZipFile(original).use { source -> ZipOutputStream(legacy.outputStream()).use { output ->
   for(entry in source.entries().asSequence()) {
    output.putNextEntry(ZipEntry(entry.name));val bytes=source.getInputStream(entry).readBytes()
    if(entry.name=="manifest.json") {
     val manifest=JSONObject(String(bytes));manifest.put("databaseVersion",4)
     val tables=manifest.getJSONObject("tables");tables.remove("bookmarks");tables.remove("aira_discussions")
     val highlights=tables.getJSONArray("highlights")
     for(i in 0 until highlights.length())for(column in listOf("segmentsJson","selectedText"))highlights.getJSONObject(i).remove(column)
     val messages=tables.getJSONArray("aira_messages")
     for(i in 0 until messages.length())for(column in listOf("requiresSpoilerPermission","topicId","boundaryLocatorJson","sourcesJson"))messages.getJSONObject(i).remove(column)
     output.write(manifest.toString().toByteArray())
    } else output.write(bytes)
    output.closeEntry()
   }
  } }
  backups.restore(Uri.fromFile(legacy))
  assertEquals(book.title,db.bookDao().getBookEntity(book.id)!!.title)
  assertNull(db.bookmarkDao().atLocation(book.id,"position:2"))
  assertTrue(db.airaMessageDao().getMessagesForBookOnce(book.id).single().requiresSpoilerPermission)
  assertNull(db.airaDiscussionDao().get(book.id))
 }
 @Test fun version5BackupRestoresWithoutDiscussionData()=runBlocking(Dispatchers.IO) {
  val book=fixture();val original=archive();backups.export(Uri.fromFile(original));val legacy=archive()
  ZipFile(original).use { source -> ZipOutputStream(legacy.outputStream()).use { output ->
   for(entry in source.entries().asSequence()) {
    output.putNextEntry(ZipEntry(entry.name));val bytes=source.getInputStream(entry).readBytes()
    if(entry.name=="manifest.json") {
     val manifest=JSONObject(String(bytes));manifest.put("databaseVersion",5)
     val tables=manifest.getJSONObject("tables");tables.remove("aira_discussions")
     val highlights=tables.getJSONArray("highlights")
     for(i in 0 until highlights.length())for(column in listOf("segmentsJson","selectedText"))highlights.getJSONObject(i).remove(column)
     val messages=tables.getJSONArray("aira_messages")
     for(i in 0 until messages.length())for(column in listOf("topicId","boundaryLocatorJson","sourcesJson"))messages.getJSONObject(i).remove(column)
     output.write(manifest.toString().toByteArray())
    } else output.write(bytes)
    output.closeEntry()
   }
  } }
  backups.restore(Uri.fromFile(legacy))
  assertEquals(book.title,db.bookDao().getBookEntity(book.id)!!.title)
  assertNotNull(db.bookmarkDao().atLocation(book.id,"position:2"))
  assertNull(db.airaDiscussionDao().get(book.id))
 }
 @Test fun version6BackupRetainsDiscussionAndRestoresLegacyHighlight()=runBlocking(Dispatchers.IO) {
  val book=fixture();val original=archive();backups.export(Uri.fromFile(original));val legacy=archive()
  ZipFile(original).use { source -> ZipOutputStream(legacy.outputStream()).use { output ->
   for(entry in source.entries().asSequence()) {
    output.putNextEntry(ZipEntry(entry.name));val bytes=source.getInputStream(entry).readBytes()
    if(entry.name=="manifest.json") {
     val manifest=JSONObject(String(bytes));manifest.put("databaseVersion",6)
     val highlights=manifest.getJSONObject("tables").getJSONArray("highlights")
     for(i in 0 until highlights.length())for(column in listOf("segmentsJson","selectedText"))highlights.getJSONObject(i).remove(column)
     output.write(manifest.toString().toByteArray())
    } else output.write(bytes)
    output.closeEntry()
   }
  } }
  backups.restore(Uri.fromFile(legacy))
  assertEquals("Saved explanation",db.airaDiscussionDao().get(book.id)!!.lastResponse)
  val highlight=db.highlightDao().getHighlightsForBook(book.id).single()
  assertNull(highlight.segmentsJson);assertNull(highlight.selectedText);assertEquals("A note",highlight.note)
 }
 @Test fun migrationFromVersion6PreservesDiscussionMessagesAndSingleHighlight()=runBlocking(Dispatchers.IO) {
  val book=fixture();val name="migration-v6-${UUID.randomUUID()}.db"
  val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
  val schema=JSONObject(instrument.context.assets.open("room-v6.json").bufferedReader().readText()).getJSONObject("database")
  SQLiteDatabase.openOrCreateDatabase(path,null).use { legacy ->
   val entities=schema.getJSONArray("entities")
   for(i in 0 until entities.length()) {
    val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
    legacy.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}",table))
    val indices=entity.optJSONArray("indices") ?: JSONArray()
    for(j in 0 until indices.length())legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}",table))
   }
   val views=schema.getJSONArray("views")
   for(i in 0 until views.length()) { val view=views.getJSONObject(i);legacy.execSQL(view.getString("createSql").replace("${'$'}{VIEW_NAME}",view.getString("viewName"))) }
   for(table in listOf("books","aira_messages","aira_discussions","highlights")) {
    db.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
     val columns=cursor.columnNames.filter { it !in setOf("segmentsJson","selectedText") }
     while(cursor.moveToNext()) {
      val args=columns.map<String, Any?> { column -> val index=cursor.getColumnIndexOrThrow(column)
       when(cursor.getType(index)) {
        android.database.Cursor.FIELD_TYPE_NULL -> null
        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        else -> cursor.getString(index)
       }
      }.toTypedArray()
      legacy.execSQL("INSERT INTO $table (${columns.joinToString(",")}) VALUES (${columns.joinToString(",") { "?" }})",args)
     }
    }
   }
   legacy.version=6
  }
  val upgraded=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(MIGRATION_6_7).build()
  try {
   assertEquals(book.lastLocatorJson,upgraded.bookDao().getBookEntity(book.id)!!.lastLocatorJson)
   assertEquals("Saved explanation",upgraded.airaDiscussionDao().get(book.id)!!.lastResponse)
   assertEquals(1,upgraded.airaMessageDao().getMessagesForBookOnce(book.id).size)
   val highlight=upgraded.highlightDao().getHighlightsForBook(book.id).single()
   assertNull(highlight.segmentsJson);assertEquals("A note",highlight.note)
  } finally { upgraded.close();context.deleteDatabase(name) }
 }
 @Test fun migrationFromVersion4PreservesLibraryAndMarksUnknownChatScope()=runBlocking(Dispatchers.IO) {
  val book=fixture()
  val name="migration-v4-${UUID.randomUUID()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
  val schema=JSONObject(instrument.context.assets.open("room-v4.json").bufferedReader().readText()).getJSONObject("database")
  SQLiteDatabase.openOrCreateDatabase(path,null).use { legacy ->
   val entities=schema.getJSONArray("entities")
   for(i in 0 until entities.length()) {
    val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
    legacy.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}",table))
    val indices=entity.optJSONArray("indices") ?: JSONArray()
    for(j in 0 until indices.length())legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}",table))
   }
   val views=schema.getJSONArray("views")
   for(i in 0 until views.length()) { val view=views.getJSONObject(i);legacy.execSQL(view.getString("createSql").replace("${'$'}{VIEW_NAME}",view.getString("viewName"))) }
   for(table in listOf("books","aira_messages")) {
    db.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
     val columns=cursor.columnNames.filter { it !in setOf("requiresSpoilerPermission","topicId","boundaryLocatorJson","sourcesJson") }
     while(cursor.moveToNext()) {
      val args=columns.map { column -> val index=cursor.getColumnIndexOrThrow(column)
       when(cursor.getType(index)) {
        android.database.Cursor.FIELD_TYPE_NULL -> null
        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
        else -> cursor.getString(index)
       }
      }.toTypedArray()
      legacy.execSQL("INSERT INTO $table (${columns.joinToString(",")}) VALUES (${columns.joinToString(",") { "?" }})",args)
     }
    }
   }
   legacy.version=4
  }
  val upgraded=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7).build()
  try {
   assertEquals(book.lastLocatorJson,upgraded.bookDao().getBookEntity(book.id)!!.lastLocatorJson)
   assertTrue(upgraded.airaMessageDao().getMessagesForBookOnce(book.id).single().requiresSpoilerPermission)
   assertNull(upgraded.bookmarkDao().atLocation(book.id,"position:2"))
  } finally { upgraded.close();context.deleteDatabase(name) }
 }
}
