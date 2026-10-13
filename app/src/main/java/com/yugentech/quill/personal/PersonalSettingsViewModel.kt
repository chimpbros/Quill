package com.yugentech.quill.personal
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugentech.quill.aira.provider.*
import com.yugentech.quill.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
data class PersonalSettingsState(val busy:Boolean=false,val message:String?=null,val models:List<AiModel> = emptyList(),val modelsProvider:AiProvider?=null,val revision:Long=0)
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
 fun signIn()=action { oauth.signIn();mutable.update { it.copy(models=emptyList(),modelsProvider=null) };settings.changed();"ChatGPT connected. Fetch models to choose a text model." }
 fun disconnect(provider:AiProvider)=action {
  settings.disconnect(provider)
  val revoked=if(provider==AiProvider.CHATGPT) oauth.disconnect() else { vault.remove(provider.name);true }
  mutable.update { it.copy(models=emptyList(),modelsProvider=null) }
  if(revoked) "${provider.label} disconnected. Your books and chats are unchanged."
  else "Disconnected locally. Remote revocation was not confirmed; disconnect QuillPersonal in ChatGPT Settings too."
 }
 fun models(providerId:AiProvider)=action {
  mutable.update { it.copy(models=emptyList(),modelsProvider=providerId) }
  val models=provider.models(AiConnection(providerId,""));settings.refreshModels(providerId,models)
  mutable.update { it.copy(models=models) };"${models.size} models loaded. A successful test confirms access."
 }
 fun automaticContext(providerId:AiProvider,model:String):Int {
  val id=model.trim()
  if(mutable.value.modelsProvider==providerId) mutable.value.models.firstOrNull { it.id==id }?.let { return it.contextTokens }
  for(slot in listOf("text","image")) {
   val selected=settings.selected(slot)
   if(selected?.provider==providerId && selected.model==id) return settings.contextSettings(slot)!!.automaticTokens
  }
  return ContextCapacity.resolve(providerId,id)
 }
 private fun connection(providerId:AiProvider,model:String,mode:ContextMode,manualText:String):AiConnection {
  require(model.isNotBlank()) { "Choose a model first." }
  val tokens=if(mode==ContextMode.AUTO) automaticContext(providerId,model) else ContextCapacity.requireManual(manualText)
  return AiConnection(providerId,model.trim(),contextTokens=tokens)
 }
 fun select(slot:String,providerId:AiProvider,model:String,mode:ContextMode,manualText:String)=action {
  val connection=connection(providerId,model,mode,manualText)
  require(saved(connection.provider)) { "Save credentials or sign in first." }
  ConnectionSelection.validate(connection,settings.selected(slot),mutable.value.modelsProvider,mutable.value.models)
  settings.select(slot,connection,mode,automaticContext(providerId,model));"${if(slot=="text") "Text" else "Images"} will use ${connection.provider.label} / ${connection.model}."
 }
 fun saveContext(providerId:AiProvider,model:String,mode:ContextMode,manualText:String)=action {
  val connection=connection(providerId,model,mode,manualText)
  val selected=settings.selected()
  require(selected?.provider==providerId && selected.model==connection.model) { "Choose Use for text to select this connection first." }
  settings.select("text",connection,mode,automaticContext(providerId,model))
  "Context settings saved: ${connection.contextTokens} tokens (${if(mode==ContextMode.AUTO) "automatic" else "manual"})."
 }
 fun test(providerId:AiProvider,model:String,mode:ContextMode,manualText:String)=action {
  provider.test(connection(providerId,model,mode,manualText));"Connection verified with a small AI request."
 }
 fun export(uri:Uri)=action { backups.export(uri);"Complete library backup saved. Keys and login tokens were excluded." }
 fun restore(uri:Uri)=action { backups.restore(uri);"Library restored. Reading indexes are rebuilding. Reopen the reader to resume." }
 fun cancel() { job?.cancel() }
}
