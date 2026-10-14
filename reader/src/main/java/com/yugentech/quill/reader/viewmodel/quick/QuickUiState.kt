package com.yugentech.quill.reader.viewmodel.quick

data class QuickUiState(
    val discussion: com.yugentech.quill.aira.context.DiscussionContext? = null,
    val sources: List<com.yugentech.quill.aira.context.SourceExcerpt> = emptyList(),
    val isReady: Boolean = false,
    val isPro: Boolean = false,
    val response: String? = null,
    val imagePath: String? = null,
    val isGeneratingImage: Boolean = false,
    val preparationMessage: String? = null,
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val canSendQuery: Boolean = true,
    val error: String? = null,
    val clarification: com.yugentech.quill.aira.response.AiraResponse.Clarification? = null
)
