package com.yugentech.quill.aira.provider

import com.yugentech.quill.domain.ai.*
import kotlinx.serialization.json.*

object ContextBudget {
    // Advisory estimate for history only. The provider enforces its actual token limit.
    fun estimateTokens(text: String): Long {
        var ascii = 0L
        var nonAsciiBytes = 0L
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            if (point < 128) ascii++
            else nonAsciiBytes += when { point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
            index += Character.charCount(point)
        }
        return (ascii + 3) / 4 + nonAsciiBytes
    }

    fun fit(connection: AiConnection, request: TextRequest): TextRequest {
        val messages = request.messages.toMutableList()
        val budget = (connection.contextTokens.toLong() - 4096L).coerceAtLeast(0L)
        var estimate = estimateTokens(request.instructions) + messages.sumOf { estimateTokens(it.text) + 32L }
        while (estimate > budget && messages.size > 1) {
            // Remove an entire oldest turn, preserving the current user message intact.
            val end = (1 until messages.lastIndex).firstOrNull { messages[it].role == "user" } ?: messages.lastIndex
            repeat(end) { estimate -= estimateTokens(messages.removeAt(0).text) + 32L }
        }
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
