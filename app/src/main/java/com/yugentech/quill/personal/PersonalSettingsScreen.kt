package com.yugentech.quill.personal
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yugentech.quill.domain.ai.*
import com.yugentech.quill.aira.provider.ContextCapacity
import com.yugentech.quill.aira.provider.ContextMode
import org.koin.androidx.compose.koinViewModel
@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable fun PersonalSettingsScreen(onBack:()->Unit,vm:PersonalSettingsViewModel=koinViewModel()) {
 val state by vm.state.collectAsState()
 var provider by remember { mutableStateOf(vm.settings.selected()?.provider ?: AiProvider.OPENROUTER) }
 var key by remember { mutableStateOf("") }
 var model by remember { mutableStateOf(vm.settings.selected()?.model.orEmpty()) }
 var contextTokens by remember { mutableStateOf((vm.settings.selected()?.contextTokens ?: 32768).toString()) }
 var contextMode by remember { mutableStateOf(vm.settings.contextSettings()?.mode ?: ContextMode.AUTO) }
 var showModels by remember { mutableStateOf(false) }
 var restoreUri by remember { mutableStateOf<Uri?>(null) }
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::export) }
 val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri=it }
 val automaticTokens=vm.automaticContext(provider,model)
 val contextError=if(contextMode==ContextMode.MANUAL && ContextCapacity.parseManual(contextTokens)==null) ContextCapacity.MANUAL_ERROR else null
 val selectedTextConnection=vm.settings.selected()
 val editingSelected=selectedTextConnection?.provider==provider && selectedTextConnection.model==model.trim()
 Scaffold(topBar={TopAppBar(title={Text("AI Connections & Backups")},navigationIcon={TextButton(onClick=onBack){Text("Back")}})}) { padding ->
  LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   item { Text("Text: ${vm.settings.selected()?.let { "${it.provider.label} / ${it.model}" } ?: "Not configured"}") }
   item { Text("Images: ${vm.settings.selected("image")?.let { "${it.provider.label} / ${it.model}" } ?: "Not configured"}") }
   item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { AiProvider.entries.forEach { choice ->
    FilterChip(selected=provider==choice,onClick={if(provider!=choice){provider=choice;key="";model="";contextMode=ContextMode.AUTO;showModels=false}},label={Text(choice.label)},enabled=!state.busy)
   } } }
   item { Text(if(provider==AiProvider.CHATGPT) vm.accountLabel() ?: "No ChatGPT account connected" else if(vm.saved(provider)) "Credentials connected on this device" else "No credentials saved",style=MaterialTheme.typography.labelLarge) }
   if(provider==AiProvider.CHATGPT) item {
    Button(onClick=vm::signIn,enabled=!state.busy) { Text("Continue with ChatGPT") }
    Text("Uses eligible ChatGPT plan usage. Images and cloud speech require an API connection.",style=MaterialTheme.typography.bodySmall)
   } else {
    item { OutlinedTextField(value=key,onValueChange={key=it},label={Text("${provider.label} API key")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!state.busy) }
    item { Button(onClick={vm.saveKey(provider,key);key=""},enabled=!state.busy && key.isNotBlank()){Text("Save key securely")} }
   }
   item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick={vm.models(provider);showModels=true},enabled=!state.busy && vm.saved(provider)){Text("Fetch models")}
    TextButton(onClick={vm.disconnect(provider)},enabled=!state.busy && vm.saved(provider)){Text("Disconnect")}
   } }
   item { OutlinedTextField(value=model,onValueChange={if(it.trim()!=model.trim())contextMode=ContextMode.AUTO;model=it},label={Text("Model ID")},singleLine=true,enabled=!state.busy && provider!=AiProvider.CHATGPT,modifier=Modifier.fillMaxWidth()) }
   item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    FilterChip(selected=contextMode==ContextMode.AUTO,onClick={contextMode=ContextMode.AUTO},label={Text("Automatic context")},enabled=!state.busy)
    FilterChip(selected=contextMode==ContextMode.MANUAL,onClick={if(contextMode!=ContextMode.MANUAL)contextTokens=automaticTokens.coerceAtLeast(ContextCapacity.MIN_MANUAL).toString();contextMode=ContextMode.MANUAL},label={Text("Manual override")},enabled=!state.busy)
   } }
   item { OutlinedTextField(value=if(contextMode==ContextMode.AUTO)automaticTokens.toString() else contextTokens,onValueChange={contextTokens=it},label={Text("Context window (tokens)")},singleLine=true,enabled=!state.busy && contextMode==ContextMode.MANUAL,isError=contextError!=null,supportingText={Text(contextError ?: "The provider enforces the model's actual context limit.")},modifier=Modifier.fillMaxWidth()) }
   item { Button(onClick={vm.saveContext(provider,model,contextMode,contextTokens)},enabled=!state.busy && editingSelected && contextError==null){Text("Save context settings")} }
   item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    Button(onClick={vm.select("text",provider,model,contextMode,contextTokens)},enabled=!state.busy && contextError==null && model.isNotBlank() && vm.saved(provider)){Text("Use for text")}
    if(provider!=AiProvider.CHATGPT) OutlinedButton(onClick={vm.select("image",provider,model,contextMode,contextTokens)},enabled=!state.busy && contextError==null && model.isNotBlank() && vm.saved(provider)){Text("Use for images")}
    TextButton(onClick={vm.test(provider,model,contextMode,contextTokens)},enabled=!state.busy && contextError==null && model.isNotBlank() && vm.saved(provider)){Text("Test text connection")}
   } }
   item { Text("Tests send a small request using the selected connection. Image models need image-generation support.",style=MaterialTheme.typography.bodySmall) }
   item { HorizontalDivider();Text("Read-aloud",style=MaterialTheme.typography.titleLarge) }
   item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    FilterChip(selected=!vm.settings.cloudSpeech,onClick={vm.settings.cloudSpeech=false},label={Text("Android voice")},enabled=!state.busy)
    FilterChip(selected=vm.settings.cloudSpeech,onClick={vm.settings.cloudSpeech=true},label={Text("OpenAI API voice")},enabled=!state.busy && vm.saved(AiProvider.OPENAI))
   } }
   item { Text("Cloud read-aloud uses an OpenAI API key and gpt-4o-mini-tts.",style=MaterialTheme.typography.bodySmall) }
   if(vm.settings.cloudSpeech) item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    listOf("alloy","ash","ballad","coral","echo","fable","nova","onyx","sage","shimmer","verse","marin","cedar").forEach { voice ->
     FilterChip(selected=vm.settings.voice==voice,onClick={vm.settings.voice=voice},label={Text(voice)},enabled=!state.busy)
    }
   } }
   item { HorizontalDivider();Text("Complete library backup",style=MaterialTheme.typography.titleLarge) }
   item { Text("Includes EPUBs, reading data, chats, images, insights, and settings. Excludes keys, login tokens, and indexes. Backups are ZIP files without password encryption.") }
   item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    Button(onClick={export.launch("quill-personal-backup.zip")},enabled=!state.busy){Text("Export backup")}
    OutlinedButton(onClick={restore.launch(arrayOf("application/zip","application/octet-stream"))},enabled=!state.busy){Text("Restore backup")}
   } }
   state.message?.let { item { Text(it,color=MaterialTheme.colorScheme.primary) } }
   if(state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick=vm::cancel){Text("Cancel")} }
  }
 }
 if(showModels && !state.busy) AlertDialog(onDismissRequest={showModels=false},title={Text("Choose a model")},text={
  LazyColumn(Modifier.heightIn(max=400.dp)) { state.models.takeIf { state.modelsProvider==provider }.orEmpty().forEach { entry -> item(key=entry.id) {
   TextButton(onClick={if(model.trim()!=entry.id){contextMode=ContextMode.AUTO;contextTokens=entry.contextTokens.toString()};model=entry.id;showModels=false}) { Text("${entry.name}\n${entry.id}${if(entry.imageOutput) " · images" else ""}") }
  } } }
 },confirmButton={TextButton(onClick={showModels=false}){Text("Close")}})
 restoreUri?.let { uri -> AlertDialog(onDismissRequest={restoreUri=null},title={Text("Replace this library?")},text={Text("The backup replaces your current books and reading data. Existing provider credentials stay on this device. Export your current library first if you want to keep both.")},confirmButton={Button(onClick={restoreUri=null;vm.restore(uri)}){Text("Restore and replace")}},dismissButton={TextButton(onClick={restoreUri=null}){Text("Cancel")}}) }
}
