package com.yugentech.quill.aira.provider

import com.yugentech.quill.domain.ai.*
import kotlinx.serialization.json.*

object ContextBudget {
    // UTF-8 bytes are a conservative upper bound, avoiding a Latin-text-only token estimate.
    fun fit(connection: AiConnection, request: TextRequest): TextRequest {
        val messages = request.messages.toMutableList()
        val budget = connection.contextTokens - 4096
        fun size() = request.instructions.toByteArray(Charsets.UTF_8).size + messages.sumOf { it.text.toByteArray(Charsets.UTF_8).size + 32 }
        while (size() > budget && messages.size > 1) messages.removeAt(0)
        if (size() > budget) throw AiException(AiErrorKind.CONTEXT, "This chapter or passage exceeds the model's input budget. Choose a model with a larger context window.")
        return request.copy(messages = messages)
    }
}

object WireProtocol {
    fun textBody(connection: AiConnection, request: TextRequest): JsonObject = buildJsonObject {
        if (connection.provider != AiProvider.GEMINI) put("model", connection.model)
        when (connection.provider) {
            AiProvider.GEMINI -> {
                put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", request.instructions) }) }) })
                put("contents", buildJsonArray { request.messages.forEach { message -> add(buildJsonObject {
                    put("role", if (message.role == "assistant") "model" else "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", message.text) }) })
                }) } })
            }
            AiProvider.OPENROUTER -> {
                put("stream", true)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", request.instructions) })
                    request.messages.forEach { add(buildJsonObject { put("role", it.role); put("content", it.text) }) }
                })
            }
            else -> {
                put("store", false); put("stream", true); put("instructions", request.instructions)
                put("input", buildJsonArray { request.messages.forEach { add(buildJsonObject { put("role", it.role); put("content", it.text) }) } })
            }
        }
    }
    fun error(status: Int, code: String? = null): AiException = when {
        status == 401 || status == 403 -> AiException(AiErrorKind.AUTHENTICATION, "This connection is not authorized. Check its key or sign in again.")
        status == 402 || status == 429 || code?.contains("subscription_sharing_usage") == true || code == "insufficient_quota" -> AiException(AiErrorKind.LIMIT, "This provider's usage limit has been reached or subscription usage is unavailable. Review the connection before retrying.")
        code?.contains("context") == true -> AiException(AiErrorKind.CONTEXT, "The request exceeds this model's context window. Choose a larger-context model.")
        status == 404 || code?.contains("model") == true -> AiException(AiErrorKind.MODEL, "This model is unavailable to the connection. Select another model.")
        status in 500..599 -> AiException(AiErrorKind.PROVIDER, "The provider is temporarily unavailable. Retry later.")
        else -> AiException(AiErrorKind.PROVIDER, "The provider rejected the request. Check the selected model and its supported features.")
    }
}

fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
