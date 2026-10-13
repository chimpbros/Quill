package com.yugentech.quill.reader.viewmodel.quick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugentech.quill.aira.repository.AiraChatRepository
import com.yugentech.quill.reader.repository.book.ReaderBookRepository
import com.yugentech.quill.aira.chat.quickChat.prompt.QuickPrompt
import com.yugentech.quill.aira.chat.quickChat.repository.QuickChatRepository
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.domain.FeatureAccess
import com.yugentech.theme.tokens.AppConstants.EMPTY
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class QuickViewModel(
    private val airaChatRepository: AiraChatRepository,
    private val quickRepository: QuickChatRepository,
    private val readerRepository: ReaderBookRepository,
    private val featureAccess: FeatureAccess,
    private val gateway: com.yugentech.quill.aira.provider.DirectAiGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickUiState())
    val uiState = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var discussionJob: Job? = null
    private var selectionJob: Job? = null

    fun observeDiscussion(bookId: String) {
        discussionJob?.cancel()
        discussionJob = viewModelScope.launch {
            airaChatRepository.observeDiscussion(bookId).collect { discussion ->
                _uiState.update { state -> state.copy(discussion = discussion,
                    response = if (!state.isLoading) discussion?.lastResponse else state.response,
                    sources = discussion?.sources.orEmpty()) }
            }
        }
    }

    fun selectPassage(bookId: String, text: String, request: com.yugentech.quill.aira.context.ReaderRequestContext) {
        clearResponse()
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch { airaChatRepository.selectPassage(bookId, text, request) }
    }

    fun newTopic(bookId: String) {
        clearResponse()
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch { airaChatRepository.newTopic(bookId) }
    }

    init {

        viewModelScope.launch {
            featureAccess.indexingEnabled.collectLatest { isPro ->
                _uiState.update { it.copy(isPro = isPro) }
            }
        }
    }

    fun observeIndexingStatus(bookId: String) {
        viewModelScope.launch {
            readerRepository.observeIsReady(bookId).collectLatest { isIndexed ->
                _uiState.update { it.copy(isReady = isIndexed) }
            }
        }
    }

    fun ask(bookId: String, query: String, selectedText: String? = null,
        request: com.yugentech.quill.aira.context.ReaderRequestContext = com.yugentech.quill.aira.context.ReaderRequestContext()) {
        if(!gateway.configured()) { _uiState.update { it.copy(error="Choose a text connection in More → AI Connections.") };return }
        if (query.isBlank() || _uiState.value.isLoading) return
        if (!checkQuota()) return

        prepareForNewQuery()

        activeJob = viewModelScope.launch {
            var hasConsumedQuota = false
            try {
                selectionJob?.join()
                airaChatRepository.ask(bookId, query, selectedText, request).collect { response ->
                    handleResponseStream(response) {

                    }
                }
            } finally {
                _uiState.update { it.copy(isLoading = false, isStreaming = false) }
            }
        }
    }

    fun handleQuickPrompt(bookId: String, intent: QuickPrompt,
        request: com.yugentech.quill.aira.context.ReaderRequestContext = com.yugentech.quill.aira.context.ReaderRequestContext()) {
        val slot=if(intent is QuickPrompt.VisualizeThis) "image" else "text"
        if(!gateway.configured(slot)) { _uiState.update { it.copy(error="Choose a $slot connection in More → AI Connections.") };return }
        if (_uiState.value.isLoading) return
        if (!checkQuota()) return

        prepareForNewQuery(isGeneratingImage = intent is QuickPrompt.VisualizeThis)

        activeJob = viewModelScope.launch {
            var hasConsumedQuota = false
            try {
                selectionJob?.join()
                quickRepository.ask(bookId, intent, request).collect { response ->
                    handleResponseStream(response) {
                        if (!hasConsumedQuota) {
                            hasConsumedQuota = true
                            Unit
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(isLoading = false, isStreaming = false) }
            }
        }
    }

    private fun handleResponseStream(response: AiraResponse, onFirstSuccess: () -> Unit) {
        when (response) {
            is AiraResponse.Clarification -> _uiState.update { it.copy(clarification = response, response = response.question,
                isLoading = false, isStreaming = false) }
            is AiraResponse.Partial, is AiraResponse.Success -> {
                val responseText = if(response is AiraResponse.Partial) response.text else (response as AiraResponse.Success).text
                onFirstSuccess()
                _uiState.update {
                    it.copy(
                        isLoading = response is AiraResponse.Partial,
                        isStreaming = response is AiraResponse.Partial,
                        response = responseText,
                        imagePath = null,
                        error = null,
                        sources = if (response is AiraResponse.Success) response.sources else it.sources
                    )
                }
            }

            is AiraResponse.ImageSuccess -> {
                onFirstSuccess()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isStreaming = false,
                        response = null,
                        imagePath = response.imagePath,
                        error = null
                    )
                }
            }

            is AiraResponse.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isStreaming = false,
                        error = response.message
                    )
                }
            }
        }
    }

    private fun prepareForNewQuery(isGeneratingImage: Boolean = false) {
        activeJob?.cancel()
        _uiState.update {
            it.copy(
                isLoading = true,
                isStreaming = false,
                response = EMPTY,
                imagePath = null,
                isGeneratingImage = isGeneratingImage,
                error = null,
                sources = emptyList(),
                clarification = null
            )
        }
    }

    private fun checkQuota(): Boolean = true

    fun chooseScope(choiceId: String) {
        val pending = _uiState.value.clarification ?: return
        prepareForNewQuery()
        activeJob = viewModelScope.launch {
            try { airaChatRepository.continueRequest(pending.requestId, choiceId).collect { handleResponseStream(it) {} } }
            finally { _uiState.update { it.copy(isLoading = false, isStreaming = false) } }
        }
    }
    fun dismissClarification() { _uiState.update { it.copy(clarification = null) } }



    fun stopGeneration() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update { state ->
            val stoppedMsg = if (state.response.isNullOrBlank()) {
                "Stopped on your request."
            } else state.response

            state.copy(
                isLoading = false,
                isStreaming = false,
                response = stoppedMsg
            )
        }
    }

    fun clearResponse() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update {
            it.copy(
                isLoading = false,
                isStreaming = false,
                response = null,
                clarification = null,
                imagePath = null,
                error = null
            )
        }
    }
}
