package com.yugentech.quill.ui.shared.airaChat.components

data class ChatMessage(
    val text: String,
    val isFromAira: Boolean,
    val isNew: Boolean,
    val stableKey: String,
    val sources: List<com.yugentech.quill.aira.context.SourceExcerpt> = emptyList()
)
