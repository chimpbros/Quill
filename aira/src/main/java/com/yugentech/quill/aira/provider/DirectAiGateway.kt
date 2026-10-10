package com.yugentech.quill.aira.provider

import com.yugentech.quill.domain.ai.*
import com.yugentech.quill.aira.response.AiraResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*

class DirectAiGateway(val settings:ConnectionStore, val provider:HttpAiProvider,private val credentials:CredentialStore) {
    fun configured(slot:String="text"):Boolean = runCatching {
        val connection=settings.selected(slot) ?: return@runCatching false
        !credentials.get(connection.provider.name).isNullOrBlank()
    }.getOrDefault(false)
    fun text(instructions:String,query:String,history:List<Map<String,Any>> = emptyList()):Flow<AiraResponse> = channelFlow {
        val connection=settings.selected() ?: throw AiException(AiErrorKind.SETUP,"Choose a text provider and model in More → AI Connections.")
        val revision=settings.revision.value
        val messages=history.mapNotNull { h ->
            val role=h["role"]?.toString() ?: return@mapNotNull null
            val text=(h["parts"] as? List<*>)?.joinToString("") { (it as? Map<*,*>)?.get("text")?.toString().orEmpty() }.orEmpty()
            AiMessage(if(role=="model") "assistant" else role,text)
        }+AiMessage("user",query)
        val generation=launch {
        provider.generate(connection,TextRequest(instructions,messages)).collect { event ->
            if(settings.revision.value!=revision) throw CancellationException("AI connection changed")
            when(event) {
                is AiEvent.Partial -> send(AiraResponse.Partial(event.text))
                is AiEvent.Completed -> send(AiraResponse.Success(event.text))
                is AiEvent.Failed -> throw event.error
                AiEvent.Cancelled -> throw CancellationException()
            }
        }
        }
        val watcher=launch {
            settings.revision.first { it!=revision }
            generation.cancel(CancellationException("AI connection changed"))
        }
        try { generation.join() } finally { watcher.cancel() }
    }
    suspend fun classify(instructions:String,query:String):String {
        val connection=settings.selected() ?: throw AiException(AiErrorKind.SETUP,"Choose a text connection in More → AI Connections.")
        return whileUnchanged { provider.classify(connection,TextRequest(instructions,listOf(AiMessage("user",query)))) }
    }
    suspend fun image(prompt:String):ImageArtifact {
        val connection=settings.selected("image") ?: throw AiException(AiErrorKind.SETUP,"Choose an image provider and model in More → AI Connections.")
        return whileUnchanged { provider.generateImage(connection,prompt) }
    }
    private suspend fun <T> whileUnchanged(block:suspend ()->T):T=coroutineScope {
        val revision=settings.revision.value
        val work=async { block() }
        val watcher=launch { settings.revision.first { it!=revision };work.cancel(CancellationException("AI connection changed")) }
        try { work.await() } finally { watcher.cancel() }
    }
}
