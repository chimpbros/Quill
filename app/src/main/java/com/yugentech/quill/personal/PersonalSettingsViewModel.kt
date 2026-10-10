package com.yugentech.quill.personal
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugentech.quill.aira.provider.*
import com.yugentech.quill.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
data class PersonalSettingsState(val busy:Boolean=false,val message:String?=null,val models:List<AiModel> = emptyList(),val revision:Long=0)
class PersonalSettingsViewModel(val settings:ConnectionStore,private val vault:CredentialStore,
 private val provider:HttpAiProvider,private val oauth:ChatGptOAuth,private val backups:BackupManager):ViewModel() {
 private val mutable=MutableStateFlow(PersonalSettingsState())
 val state=mutable.asStateFlow()
 private var job:Job?=null
 init { settings.revision.onEach { rev -> mutable.update { it.copy(revision=rev) } }.launchIn(viewModelScope) }
 fun saved(provider:AiProvider)=if(provider==AiProvider.CHATGPT) runCatching { oauth.connected() }.getOrDefault(false) else runCatching { !vault.get(provider.name).isNullOrBlank() }.getOrDefault(false)
 fun accountLabel()=runCatching { oauth.accountLabel() }.getOrNull()
 private fun action(block:suspend ()->String) {
  if(mutable.value.busy)return
  job=viewModelScope.launch {
   mutable.update { it.copy(busy=true,message=null) }
   try { val message=withContext(Dispatchers.IO) { block() };mutable.update { it.copy(message=message) } }
   catch(e:TimeoutCancellationException) { mutable.update { it.copy(message="Sign-in timed out. Try again.") } }
   catch(e:CancellationException) { mutable.update { it.copy(message="Cancelled.") };throw e }
   catch(e:AiException) { mutable.update { it.copy(message=e.message) } }
   catch(e:Exception) { mutable.update { it.copy(message=if(e is IllegalArgumentException) e.message ?: "Invalid connection or backup settings." else "Operation could not finish. Check connection settings or the backup file.") } }
   finally { mutable.update { it.copy(busy=false) } }
  }
 }
 fun saveKey(provider:AiProvider,key:String)=action {
  require(provider!=AiProvider.CHATGPT && key.isNotBlank()) { "Enter a provider API key." }
  vault.put(provider.name,key.trim());settings.changed();"Key saved securely on this device."
 }
 fun signIn()=action { oauth.signIn();settings.changed();"ChatGPT connected. Fetch models to choose a text model." }
 fun disconnect(provider:AiProvider)=action {
  settings.disconnect(provider)
  val revoked=if(provider==AiProvider.CHATGPT) oauth.disconnect() else { vault.remove(provider.name);true }
  mutable.update { it.copy(models=emptyList()) }
  if(revoked) "${provider.label} disconnected. Your books and chats are unchanged."
  else "Disconnected locally. Remote revocation was not confirmed; disconnect QuillPersonal in ChatGPT Settings too."
 }
 fun models(providerId:AiProvider)=action {
  val models=provider.models(AiConnection(providerId,""));mutable.update { it.copy(models=models) };"${models.size} models loaded. A successful test confirms access."
 }
 fun select(slot:String,connection:AiConnection)=action {
  require(saved(connection.provider)) { "Save credentials or sign in first." }
  if(connection.provider==AiProvider.CHATGPT) require(mutable.value.models.any { it.id==connection.model }) { "Fetch and select a model available to your ChatGPT account." }
  settings.select(slot,connection);"${if(slot=="text") "Text" else "Images"} will use ${connection.provider.label} / ${connection.model}."
 }
 fun test(connection:AiConnection)=action { provider.test(connection);"Connection verified with a small AI request." }
 fun export(uri:Uri)=action { backups.export(uri);"Complete library backup saved. Keys and login tokens were excluded." }
 fun restore(uri:Uri)=action { backups.restore(uri);"Library restored. Reading indexes are rebuilding. Reopen the reader to resume." }
 fun cancel() { job?.cancel() }
}
