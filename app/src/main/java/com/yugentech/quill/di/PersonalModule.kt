package com.yugentech.quill.di
import com.yugentech.quill.aira.provider.*
import com.yugentech.quill.domain.*
import com.yugentech.quill.personal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
val personalModule=module {
 single { CredentialStore(androidContext()) }
 single { ConnectionStore(androidContext()) }
 single(named("ai_http")) { HttpClient(Android) {
  install(HttpTimeout) { requestTimeoutMillis=180000;connectTimeoutMillis=15000;socketTimeoutMillis=120000 };expectSuccess=false
 } }
 single { ChatGptOAuth(androidContext(),get(named("ai_http")),get()) }
 single { HttpAiProvider(get(named("ai_http")),CredentialResolver { provider ->
  if(provider==com.yugentech.quill.domain.ai.AiProvider.CHATGPT) get<ChatGptOAuth>().accessToken()
  else get<CredentialStore>().get(provider.name)?.takeIf { it.isNotBlank() }
   ?: throw com.yugentech.quill.domain.ai.AiException(com.yugentech.quill.domain.ai.AiErrorKind.SETUP,"Save a key for ${provider.label} in AI Connections first.")
 }) }
 single { DirectAiGateway(get(),get(),get()) }
 single { FeatureAccess() }
 single<LocalProfile> { object:LocalProfile {
  override val currentUser="local"
  override val currentUserName:String? get()=androidContext().getSharedPreferences("local_profile",0).getString("name",null)
 } }
 single(createdAtStart=true) { BackupManager(androidContext(),get(),get(),mapOf(
  "theme" to get(named("theme")),"user" to get(named("user")),"reader" to get(named("reader"))
 ),get()) }
 viewModel { PersonalSettingsViewModel(get(),get(),get(),get(),get()) }
}
