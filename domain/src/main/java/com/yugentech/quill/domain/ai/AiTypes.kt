package com.yugentech.quill.domain.ai

import kotlinx.coroutines.flow.Flow

enum class AiProvider(val label: String) {
    OPENROUTER("OpenRouter"), GEMINI("Gemini"), OPENAI("OpenAI API"), CHATGPT("ChatGPT subscription")
}
data class AiConnection(val provider: AiProvider, val model: String, val credentialRef: String = provider.name, val contextTokens: Int = 32768)
data class AiModel(val id: String, val name: String = id, val contextTokens: Int = 32768, val imageOutput: Boolean = false)
data class AiMessage(val role: String, val text: String)
data class TextRequest(val instructions: String, val messages: List<AiMessage>)
data class ProviderCapabilities(val text: Boolean = true, val images: Boolean = false, val speech: Boolean = false)
enum class AiErrorKind { SETUP, AUTHENTICATION, LIMIT, MODEL, NETWORK, CONTEXT, INCOMPLETE, PROVIDER }
class AiException(val kind: AiErrorKind, override val message: String) : Exception(message)
sealed interface AiEvent {
    data class Partial(val text: String) : AiEvent
    data class Completed(val text: String) : AiEvent
    data class Failed(val error: AiException) : AiEvent
    data object Cancelled : AiEvent
}
data class ImageArtifact(val bytes: ByteArray, val mimeType: String)
interface TextProvider {
    suspend fun models(connection: AiConnection): List<AiModel>
    fun generate(connection: AiConnection, request: TextRequest): Flow<AiEvent>
    suspend fun classify(connection: AiConnection, request: TextRequest): String
    suspend fun test(connection: AiConnection): String
}
interface ImageProvider { suspend fun generateImage(connection: AiConnection, prompt: String): ImageArtifact }
interface SpeechProvider { suspend fun speak(text: String); fun stop(); fun close() }
