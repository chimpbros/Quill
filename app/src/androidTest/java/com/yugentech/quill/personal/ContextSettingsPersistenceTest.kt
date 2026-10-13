package com.yugentech.quill.personal

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.provider.*
import com.yugentech.quill.database.database.AppDatabase
import com.yugentech.quill.domain.ai.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContextSettingsPersistenceTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var context:Context
 private lateinit var settings:ConnectionStore
 private lateinit var vault:CredentialStore
 private lateinit var db:AppDatabase
 private lateinit var client:HttpClient
 private lateinit var files:File
 private val preferences=mutableSetOf<String>()
 private val viewModels=mutableListOf<PersonalSettingsViewModel>()

 @Before fun setup() {
  val base=InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
  val name="context-test-${UUID.randomUUID()}"
  files=File(base.cacheDir,name).apply { mkdirs() }
  context=object:ContextWrapper(base) {
   override fun getSharedPreferences(key:String,mode:Int):android.content.SharedPreferences {
    val actual="$name-$key";preferences.add(actual);return base.getSharedPreferences(actual,mode)
   }
   override fun getFilesDir()=File(files,"files").apply { mkdirs() }
   override fun getCacheDir()=File(files,"cache").apply { mkdirs() }
  }
  settings=ConnectionStore(context);vault=CredentialStore(context)
  vault.put("CHATGPT","{}") // Synthetic connection; no real OAuth/network access.
  db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
  client=HttpClient(MockEngine { respond("{\"models\":[{\"slug\":\"gpt-6-luna\",\"visibility\":\"list\",\"context_length\":200000}]}",headers=headersOf(HttpHeaders.ContentType,"application/json")) })
 }
 @After fun cleanup() {
  viewModels.forEach { it.viewModelScope.cancel() };client.close();db.close()
  val base=InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
  preferences.forEach { base.getSharedPreferences(it,0).edit().clear().commit() }
  files.deleteRecursively()
 }
 private fun vm():PersonalSettingsViewModel=PersonalSettingsViewModel(settings,vault,HttpAiProvider(client,CredentialResolver { "fixture-credential" }),ChatGptOAuth(context,client,vault),BackupManager(context,db,settings,emptyMap())).also { viewModels.add(it) }
 private suspend fun complete(vm:PersonalSettingsViewModel,action:()->Unit) {
  withContext(Dispatchers.Main) { action() }
  withTimeout(10000) { vm.state.first { !it.busy && it.message!=null } }
 }
 private fun selectManual()=settings.select("text",AiConnection(AiProvider.CHATGPT,"gpt-6-luna",contextTokens=64000),ContextMode.MANUAL)

 @Test fun legacyDefaultMigratesWithoutChangingConnectionOrCredentials() {
  settings.preferences.edit().putString("text_provider","CHATGPT").putString("text_model","gpt-6-luna").putInt("text_context",32768).commit()
  val restarted=ConnectionStore(context)
  assertEquals(1050000,restarted.selected()!!.contextTokens)
  assertEquals(ContextMode.AUTO,restarted.contextSettings()!!.mode)
  assertEquals("gpt-6-luna",restarted.selected()!!.model)
  assertEquals("{}",vault.get("CHATGPT"))
 }
 @Test fun legacyCustomContextRemainsManualAcrossStoreRecreation() {
  settings.preferences.edit().putString("text_provider","CHATGPT").putString("text_model","gpt-6-luna").putInt("text_context",64000).commit()
  assertEquals(ContextMode.MANUAL,settings.contextSettings()!!.mode)
  assertEquals(64000,ConnectionStore(context).selected()!!.contextTokens)
 }
 @Test fun contextOnlySaveWorksWithEmptyCatalogAfterViewModelRecreation()=runBlocking {
  selectManual();val recreated=vm()
  assertTrue(recreated.state.value.models.isEmpty())
  complete(recreated) { recreated.saveContext(AiProvider.CHATGPT,"gpt-6-luna",ContextMode.MANUAL,"96000") }
  assertEquals(96000,ConnectionStore(context).selected()!!.contextTokens)
  complete(recreated) { recreated.select("text",AiProvider.CHATGPT,"gpt-6-luna",ContextMode.MANUAL,"128000") }
  assertEquals(128000,settings.selected()!!.contextTokens)
 }
 @Test fun modelRefreshPreservesManualOverrideAndCachesAutomaticCapacity()=runBlocking {
  selectManual();val model=vm()
  complete(model) { model.models(AiProvider.CHATGPT) }
  val restarted=ConnectionStore(context)
  assertEquals(64000,restarted.selected()!!.contextTokens)
  assertEquals(ContextMode.MANUAL,restarted.contextSettings()!!.mode)
  assertEquals(200000,restarted.contextSettings()!!.automaticTokens)
  complete(model) { model.saveContext(AiProvider.CHATGPT,"gpt-6-luna",ContextMode.AUTO,"") }
  assertEquals(200000,settings.selected()!!.contextTokens)
 }
 @Test fun newChatGptModelAndInvalidContextCannotReplaceSavedSettings()=runBlocking {
  selectManual();val model=vm()
  complete(model) { model.select("text",AiProvider.CHATGPT,"unlisted",ContextMode.AUTO,"") }
  assertTrue(model.state.value.message!!.contains("Fetch and select"))
  assertEquals("gpt-6-luna",settings.selected()!!.model)
  complete(model) { model.saveContext(AiProvider.CHATGPT,"gpt-6-luna",ContextMode.MANUAL,"2147483648") }
  assertEquals(ContextCapacity.MANUAL_ERROR,model.state.value.message)
  assertEquals(64000,settings.selected()!!.contextTokens)
 }
 @Test fun backupRestoresModeOverrideAndAutomaticCache()=runBlocking(Dispatchers.IO) {
  selectManual();settings.refreshModels(AiProvider.CHATGPT,listOf(AiModel("gpt-6-luna",contextTokens=200000)))
  val backup=BackupManager(context,db,settings,emptyMap());val file=File(context.cacheDir,"settings.zip")
  backup.export(Uri.fromFile(file))
  settings.select("text",AiConnection(AiProvider.CHATGPT,"gpt-6-luna"),ContextMode.AUTO)
  backup.restore(Uri.fromFile(file))
  val restarted=ConnectionStore(context)
  assertEquals(64000,restarted.selected()!!.contextTokens)
  assertEquals(ContextMode.MANUAL,restarted.contextSettings()!!.mode)
  assertEquals(200000,restarted.contextSettings()!!.automaticTokens)
 }
 @Test fun screenValidatesSavesAndReopensTheManualOverride() {
  settings.select("text",AiConnection(AiProvider.CHATGPT,"gpt-6-luna"))
  var current by mutableStateOf(vm())
  compose.setContent { MaterialTheme { key(current) { PersonalSettingsScreen(onBack={},vm=current) } } }
  compose.onNodeWithText("Manual override").performScrollTo().performClick()
  val field=hasSetTextAction() and hasText("Context window (tokens)")
  compose.onNode(field).performTextReplacement("2147483648")
  compose.onNodeWithText("Save context settings").performScrollTo().assertIsNotEnabled()
  compose.onNode(field).performScrollTo().performTextReplacement("64000")
  compose.onNodeWithText("Save context settings").performScrollTo().performClick()
  compose.waitUntil(10000) { settings.contextSettings()!!.mode==ContextMode.MANUAL && settings.selected()!!.contextTokens==64000 }
  compose.runOnIdle { current=vm() }
  compose.onNode(field).performScrollTo().assertTextContains("64000")
  assertEquals(64000,ConnectionStore(context).selected()!!.contextTokens)
 }
}
