package com.yugentech.quill.aira.provider

import com.yugentech.quill.domain.ai.*

enum class ContextMode { AUTO, MANUAL }

data class ContextSettings(val mode: ContextMode, val automaticTokens: Int, val manualTokens: Int) {
    val effectiveTokens: Int get() = if (mode == ContextMode.AUTO) automaticTokens else manualTokens
}

object ContextCapacity {
    const val FALLBACK = 32768
    const val MIN_MANUAL = 8192
    const val MANUAL_ERROR = "Enter a whole number from 8,192 to 2,147,483,647 tokens."

    fun resolve(provider: AiProvider, model: String, reported: Int? = null): Int =
        reported?.takeIf { it > 0 } ?: when {
            provider in setOf(AiProvider.OPENAI, AiProvider.CHATGPT) && model.trim() == "gpt-6-luna" -> 1050000
            else -> FALLBACK
        }

    fun parseManual(value: String): Int? = value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it >= MIN_MANUAL }

    fun requireManual(value: String): Int = requireNotNull(parseManual(value)) { MANUAL_ERROR }
}

/** Updating an existing choice must not depend on a transient model-picker list. */
object ConnectionSelection {
    fun validate(connection: AiConnection, existing: AiConnection?, catalogProvider: AiProvider?, catalog: List<AiModel>) {
        if (connection.provider != AiProvider.CHATGPT) return
        val unchangedModel = existing?.provider == connection.provider && existing.model == connection.model.trim()
        require(unchangedModel || (catalogProvider == connection.provider && catalog.any { it.id == connection.model.trim() })) {
            "Fetch and select a model available to your ChatGPT account."
        }
    }
}
