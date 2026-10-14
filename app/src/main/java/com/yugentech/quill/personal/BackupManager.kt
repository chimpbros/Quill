package com.yugentech.quill.personal

import android.content.Context
import android.net.Uri
import android.database.Cursor
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.work.*
import androidx.lifecycle.asFlow
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.aira.provider.ConnectionStore
import com.yugentech.quill.aira.rag.BookEmbeddingWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

object BackupPaths {
 fun resolve(root:File,path:String):File {
  require(path.isNotBlank() && !path.contains('\\') && !path.startsWith('/') && !path.contains(':')) { "Unsafe archive path" }
  val file=File(root,path).canonicalFile
  require(file.path.startsWith(root.canonicalPath+File.separator)) { "Archive path escapes backup directory" }
  return file
 }
}

class BackupManager(private val context:Context,private val db:AppDatabase,private val connections:ConnectionStore,
 private val stores:Map<String,DataStore<Preferences>>,private val retriever:com.yugentech.quill.aira.rag.RagRetriever?=null, private val structures:com.yugentech.quill.aira.rag.BookStructureStore?=null) {
 private val mutex=Mutex()
 private val tables=listOf("books","user_categories","users","highlights","aira_messages","reading_sessions","visuals","bookmarks","aira_discussions")
 private val journal=File(context.filesDir,"restore-journal.json")
 private val sharedNames=listOf("ai_settings","local_profile")
 private val pathColumns=mapOf("books" to setOf("localFilePath","coverUrl"),"visuals" to setOf("imagePath"))
 init { runBlocking(Dispatchers.IO) { recover() } }
 private fun digest(file:File):String {
  val hash=MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val count=input.read(buffer);if(count<0)break;hash.update(buffer,0,count) } }
  return hash.digest().joinToString("") { "%02x".format(it) }
 }
 private fun dump():JSONObject {
  val data=JSONObject()
  val sql=db.openHelper.writableDatabase
  sql.beginTransaction()
  try {
  for(table in tables) {
   val rows=JSONArray()
   sql.query("SELECT * FROM `$table`").use { cursor ->
    while(cursor.moveToNext()) {
     val row=JSONObject()
     cursor.columnNames.forEachIndexed { index,column -> row.put(column,when(cursor.getType(index)) {
      Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
      Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
      Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
      Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
      else -> error("Unsupported backup column")
     }) }
     rows.put(row)
    }
   }
   data.put(table,rows)
  }
  sql.setTransactionSuccessful()
  return data
  } finally { sql.endTransaction() }
 }
 private fun typed(value:Any?):JSONObject=JSONObject().apply {
  val kind=when(value) { is Boolean->"bool";is Int->"int";is Long->"long";is Float->"float";is Double->"double";is Set<*>->"strings";else->"string" }
  put("kind",kind);put("value",if(value is Set<*>) JSONArray(value.toList()) else value)
 }
 private suspend fun settings():JSONObject {
  val data=JSONObject();val ds=JSONObject();val shared=JSONObject()
  for((name,store) in stores) {
   val values=JSONObject();store.data.first().asMap().forEach { (key,value)->values.put(key.name,typed(value)) };ds.put(name,values)
  }
  for(name in sharedNames) {
   val values=JSONObject();context.getSharedPreferences(name,0).all.forEach { (key,value)->values.put(key,typed(value)) };shared.put(name,values)
  }
  data.put("datastores",ds);data.put("shared",shared);return data
 }
 private suspend fun applySettings(data:JSONObject) {
  val ds=data.getJSONObject("datastores")
  for((name,store) in stores) {
   val values=ds.optJSONObject(name) ?: JSONObject()
   store.edit { prefs ->
    prefs.clear()
    for(key in values.keys()) {
     val item=values.getJSONObject(key)
     when(item.getString("kind")) {
      "bool"->prefs[booleanPreferencesKey(key)]=item.getBoolean("value")
      "int"->prefs[intPreferencesKey(key)]=item.getInt("value")
      "long"->prefs[longPreferencesKey(key)]=item.getLong("value")
      "float"->prefs[floatPreferencesKey(key)]=item.getDouble("value").toFloat()
      "double"->prefs[doublePreferencesKey(key)]=item.getDouble("value")
      "strings"->prefs[stringSetPreferencesKey(key)]=(0 until item.getJSONArray("value").length()).map { item.getJSONArray("value").getString(it) }.toSet()
      "string"->prefs[stringPreferencesKey(key)]=item.getString("value")
      else->error("Unsupported settings type")
     }
    }
   }
  }
  val shared=data.getJSONObject("shared")
  for(name in sharedNames) {
   val edit=context.getSharedPreferences(name,0).edit().clear();val values=shared.optJSONObject(name) ?: JSONObject()
   for(key in values.keys()) {
    val item=values.getJSONObject(key)
    when(item.getString("kind")) {
     "bool"->edit.putBoolean(key,item.getBoolean("value"));"int"->edit.putInt(key,item.getInt("value"))
     "long"->edit.putLong(key,item.getLong("value"));"float"->edit.putFloat(key,item.getDouble("value").toFloat())
     "strings"->edit.putStringSet(key,(0 until item.getJSONArray("value").length()).map { item.getJSONArray("value").getString(it) }.toSet())
     "string"->edit.putString(key,item.getString("value"));else->error("Unsupported preferences type")
    }
   }
   check(edit.commit()) { "Unable to restore settings" }
  }
  connections.changed()
 }
 suspend fun export(uri:Uri)=mutex.withLock { withContext(Dispatchers.IO) {
  val data=dump();val assets=linkedMapOf<String,File>();val files=JSONArray()
  for(table in tables) {
   val rows=data.getJSONArray(table)
   for(i in 0 until rows.length()) {
    val row=rows.getJSONObject(i)
    for(column in pathColumns[table].orEmpty()) {
     if(row.isNull(column))continue
     val original=row.getString(column)
     if(original.startsWith("http://") || original.startsWith("https://") || original.isBlank())continue
     val file=File(original.removePrefix("file://")).canonicalFile
     require(file.path.startsWith(context.dataDir.canonicalPath+File.separator)) { "Library contains a file outside app storage; reimport it first." }
     require(file.isFile) { "A library file is missing. Download or remove the missing book before exporting." }
     val existing=assets.entries.firstOrNull { it.value==file }?.key
     val asset=existing ?: "assets/${UUID.randomUUID()}/${file.name}".also { assets[it]=file }
     row.put(column,(if(original.startsWith("file://")) "quill-asset-uri:" else "quill-asset:")+asset)
    }
   }
  }
  assets.forEach { (path,file)->files.put(JSONObject().put("path",path).put("size",file.length()).put("sha256",digest(file))) }
  val manifest=JSONObject().put("format",1).put("databaseVersion",db.openHelper.readableDatabase.version).put("tables",data).put("settings",settings()).put("files",files)
  val output=context.contentResolver.openOutputStream(uri) ?: error("Unable to open backup destination")
  ZipOutputStream(output.buffered()).use { zip ->
   zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString().toByteArray(Charsets.UTF_8));zip.closeEntry()
   assets.forEach { (path,file)->zip.putNextEntry(ZipEntry(path));file.inputStream().use { it.copyTo(zip) };zip.closeEntry() }
  }
 } }
 private fun columns(table:String):Set<String> = db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { c -> buildSet { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
 private fun validateData(data:JSONObject) {
  require(data.keys().asSequence().toSet()==tables.toSet()) { "Unsupported library tables" }
  for(table in tables) {
   val allowed=columns(table);val rows=data.getJSONArray(table)
   for(i in 0 until rows.length()) {
    val row=rows.getJSONObject(i)
    require(row.keys().asSequence().toSet()==allowed) { "Backup schema does not match this version" }
    for(column in row.keys()) require(row[column]===JSONObject.NULL || row[column] is String || row[column] is Number) { "Invalid library value" }
   }
  }
  val books=data.getJSONArray("books")
  val bookIds=(0 until books.length()).map { books.getJSONObject(it).getString("id") }
  require(bookIds.size==bookIds.toSet().size) { "Duplicate book identifiers" }
  for(table in listOf("highlights","aira_messages","reading_sessions","visuals","bookmarks","aira_discussions")) {
   val rows=data.getJSONArray(table)
   for(i in 0 until rows.length()) require(rows.getJSONObject(i).getString("bookId") in bookIds) { "Backup refers to a missing book" }
  }
 }
 private fun replaceData(data:JSONObject) {
  validateData(data)
  val sql=db.openHelper.writableDatabase
  sql.beginTransaction()
  try {
   sql.execSQL("DELETE FROM book_indexing_state");sql.execSQL("DELETE FROM book_chunks")
   tables.asReversed().forEach { sql.execSQL("DELETE FROM `$it`") }
   for(table in tables) {
    val rows=data.getJSONArray(table)
    for(i in 0 until rows.length()) {
     val row=rows.getJSONObject(i);val keys=row.keys().asSequence().toList()
     val insert="INSERT INTO `$table` (${keys.joinToString(",") { "`$it`" }}) VALUES (${keys.joinToString(",") { "?" }})"
     sql.compileStatement(insert).use { statement ->
      keys.forEachIndexed { index,key-> val value=row[key];when(value) {
       JSONObject.NULL->statement.bindNull(index+1)
       is Float,is Double->statement.bindDouble(index+1,(value as Number).toDouble())
       is Number->statement.bindLong(index+1,value.toLong())
       else->statement.bindString(index+1,value.toString())
      } };statement.executeInsert()
     }
    }
   }
   sql.setTransactionSuccessful()
  } finally { sql.endTransaction();db.invalidationTracker.refreshAsync() }
 }
 private fun upgradeVersion4(data:JSONObject):JSONObject {
  require(data.keys().asSequence().toSet()==(tables.toSet()-setOf("bookmarks", "aira_discussions"))) { "Unsupported version-4 library tables" }
  val messages=data.getJSONArray("aira_messages")
  for(index in 0 until messages.length()) {
   val row=messages.getJSONObject(index)
   require(!row.has("requiresSpoilerPermission")) { "Unexpected version-4 message schema" }
   row.put("requiresSpoilerPermission",1)
  }
  data.put("bookmarks",JSONArray())
  return data
 }
 private fun upgradeLibrary(data:JSONObject,version:Int):JSONObject {
  if(version==4) upgradeVersion4(data)
  if(version<=5) {
   require(data.keys().asSequence().toSet()==tables.toSet()-"aira_discussions") { "Unsupported older library tables" }
   val messages=data.getJSONArray("aira_messages")
   for(i in 0 until messages.length()) {
    val row=messages.getJSONObject(i)
    for(column in listOf("topicId","boundaryLocatorJson","sourcesJson")) {
     require(!row.has(column)) { "Unexpected older message schema" };row.put(column,JSONObject.NULL)
    }
   }
   data.put("aira_discussions",JSONArray())
  }
  if(version<=6) {
   val highlights=data.getJSONArray("highlights")
   for(i in 0 until highlights.length()) for(column in listOf("segmentsJson","selectedText")) {
    val row=highlights.getJSONObject(i)
    require(!row.has(column)) { "Unexpected older highlight schema" };row.put(column,JSONObject.NULL)
   }
  }
  return data
 }
 suspend fun recover()=withContext(Dispatchers.IO) {
  if(!journal.exists())return@withContext
  structures?.invalidateAll()
  val old=JSONObject(journal.readText())
  val recovered=old.getJSONObject("tables")
  val highlights=recovered.getJSONArray("highlights")
  val hasSegments=(0 until highlights.length()).any { highlights.getJSONObject(it).has("segmentsJson") }
  val version=old.optInt("databaseVersion",if(hasSegments) 7 else if(recovered.has("aira_discussions")) 6 else if(recovered.has("bookmarks")) 5 else 4)
  replaceData(upgradeLibrary(recovered,version));applySettings(old.getJSONObject("settings"));retriever?.invalidate();check(journal.delete())
  reindex()
 }
 suspend fun restore(uri:Uri)=mutex.withLock { withContext(Dispatchers.IO) {
  val stage=File(context.filesDir,"restored/${UUID.randomUUID()}").apply { mkdirs() }
  var keep=false
  try {
   val input=context.contentResolver.openInputStream(uri) ?: error("Unable to open backup")
   val manifest=ZipInputStream(input.buffered()).use { zip ->
    require(zip.nextEntry?.name=="manifest.json") { "This is not a Quill Personal backup" }
    val buffer=java.io.ByteArrayOutputStream()
    val chunk=ByteArray(65536)
    while(true) { val count=zip.read(chunk);if(count<0)break;require(buffer.size()+count<=64*1024*1024) { "Backup manifest is too large" };buffer.write(chunk,0,count) }
    val bytes=buffer.toByteArray()
    val m=JSONObject(String(bytes,Charsets.UTF_8))
    require(m.getInt("format")==1 && m.getInt("databaseVersion") in setOf(4,5,6,db.openHelper.readableDatabase.version)) { "Unsupported backup version" }
    upgradeLibrary(m.getJSONObject("tables"),m.getInt("databaseVersion"))
    val files=m.getJSONArray("files");val expected=linkedMapOf<String,JSONObject>()
    var total=0L
    for(i in 0 until files.length()) {
     val info=files.getJSONObject(i);val path=info.getString("path")
     require(path.startsWith("assets/"));BackupPaths.resolve(stage,path)
     require(!expected.containsKey(path) && info.getLong("size")>=0)
     expected[path]=info;total=Math.addExact(total,info.getLong("size"))
    }
    require(total+16*1024*1024<context.filesDir.usableSpace) { "Not enough storage to restore this library" }
    val seen=mutableSetOf<String>()
    while(true) {
     val entry=zip.nextEntry ?: break
     require(!entry.isDirectory && expected.containsKey(entry.name) && seen.add(entry.name)) { "Unexpected or duplicate backup entry" }
     val file=BackupPaths.resolve(stage,entry.name);file.parentFile!!.mkdirs()
     val limit=expected.getValue(entry.name).getLong("size");var written=0L
     file.outputStream().use { output -> val buffer=ByteArray(65536);while(true) { val count=zip.read(buffer);if(count<0)break;written+=count;require(written<=limit) { "Backup file exceeds its declared size" };output.write(buffer,0,count) } }
     require(written==limit && digest(file)==expected.getValue(entry.name).getString("sha256")) { "Backup file is damaged" }
    }
    require(seen==expected.keys) { "Backup files are missing" };m
   }
   val data=manifest.getJSONObject("tables");validateData(data)
   for(table in tables) {
    val rows=data.getJSONArray(table)
    for(i in 0 until rows.length()) {
     val row=rows.getJSONObject(i)
     for(column in pathColumns[table].orEmpty()) {
      if(row.isNull(column))continue
      val value=row.getString(column)
      if(value.startsWith("quill-asset:" ) || value.startsWith("quill-asset-uri:")) {
       val file=BackupPaths.resolve(stage,value.substringAfter(':'));require(file.isFile)
       row.put(column,(if(value.startsWith("quill-asset-uri:")) "file://" else "")+file.absolutePath)
      } else require(value.startsWith("https://") || value.startsWith("http://") || value.isEmpty()) { "Invalid backup file reference" }
     }
    }
   }
   connections.changed()
   val cancellation=WorkManager.getInstance(context).cancelAllWorkByTag("AI_INDEXING")
   cancellation.state.asFlow().first { it is Operation.State.SUCCESS || it is Operation.State.FAILURE }.let {
    check(it is Operation.State.SUCCESS) { "Unable to pause book indexing for restore" }
   }
   val old=JSONObject().put("databaseVersion",db.openHelper.readableDatabase.version).put("tables",dump()).put("settings",settings())
   android.util.AtomicFile(journal).let { atomic -> val out=atomic.startWrite();try { out.write(old.toString().toByteArray());atomic.finishWrite(out) } catch(e:Exception) { atomic.failWrite(out);throw e } }
   try {
    structures?.invalidateAll()
    replaceData(data);applySettings(manifest.getJSONObject("settings"));retriever?.invalidate();check(journal.delete());keep=true
    cleanupReplacedMedia(old.getJSONObject("tables"),data)
   } catch(e:Exception) { withContext(NonCancellable) { recover() };throw e }
   reindex()
   rescheduleReminders()
  } finally { if(!keep) stage.deleteRecursively() }
 } }
 private fun cleanupReplacedMedia(old:JSONObject,current:JSONObject) {
  fun refs(data:JSONObject):Set<File> = buildSet {
   for((table,columns) in pathColumns) {
    val rows=data.getJSONArray(table)
    for(i in 0 until rows.length())for(column in columns) {
     val value=rows.getJSONObject(i).optString(column,"")
     if(value.startsWith("/") || value.startsWith("file://"))add(File(value.removePrefix("file://")).canonicalFile)
    }
   }
  }
  val retained=refs(current)
  for(file in refs(old)-retained) {
   if(file.path.startsWith(context.dataDir.canonicalPath+File.separator) && file.isFile &&
    file.extension.lowercase() in setOf("epub","png","jpg","jpeg","webp"))runCatching { file.delete() }
  }
 }
 private suspend fun rescheduleReminders() {
  val values=stores["user"]?.data?.first() ?: return
  val manager=com.yugentech.quill.notification.ScheduledNotificationManager(context)
  manager.cancelReminders();manager.cancelPlayfulReminders()
  if(values[booleanPreferencesKey("notifications_enabled")]==true) {
   if(values[booleanPreferencesKey("reading_reminders_enabled")]==true && manager.canScheduleExactAlarms())
    manager.scheduleReminder(values[intPreferencesKey("reminder_hour")] ?: 20,values[intPreferencesKey("reminder_minute")] ?: 0)
   if(values[booleanPreferencesKey("playful_reminders_enabled")]==true)manager.schedulePlayfulReminders()
  }
 }
 private suspend fun reindex() {
  val work=WorkManager.getInstance(context)
  db.openHelper.readableDatabase.query("SELECT id FROM books WHERE localFilePath IS NOT NULL").use { cursor ->
   while(cursor.moveToNext()) {
    val id=cursor.getString(0)
    val request=OneTimeWorkRequestBuilder<BookEmbeddingWorker>().setInputData(workDataOf(BookEmbeddingWorker.KEY_BOOK_ID to id)).addTag("index_$id").addTag("AI_INDEXING").build()
    work.enqueueUniqueWork("index_$id",ExistingWorkPolicy.REPLACE,request)
   }
  }
 }
}
