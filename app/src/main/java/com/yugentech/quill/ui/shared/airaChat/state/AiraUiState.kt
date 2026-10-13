package com.yugentech.quill.ui.shared.airaChat.state

import com.yugentech.quill.aira.message.AiraMessage
import com.yugentech.theme.tokens.AppConstants.EMPTY

data class AiraUiState(
    val discussion: com.yugentech.quill.aira.context.DiscussionContext? = null,
    val readingLocatorJson: String? = null,
    val bookTitle: String = EMPTY,
    val bookAuthor: String = EMPTY,
    val lastChapterTitle: String? = null,
    val isReady: Boolean = false,
    val isIndexing: Boolean = true,
    val indexingProgress: Int = 0,
    val hasStartedReading: Boolean = false,
    val messages: List<AiraMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val spoilerLockEnabled: Boolean = true,
    val error: String? = null,
    val canSendQuery: Boolean = true,
    val isPro: Boolean = false,
    val clarification: com.yugentech.quill.aira.response.AiraResponse.Clarification? = null
)
