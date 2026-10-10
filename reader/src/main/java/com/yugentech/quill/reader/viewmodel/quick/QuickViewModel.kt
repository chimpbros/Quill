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

    fun ask(bookId: String, query: String) {
        if(!gateway.configured()) { _uiState.update { it.copy(error="Choose a text connection in More → AI Connections.") };return }
        if (query.isBlank() || _uiState.value.isLoading) return
        if (!checkQuota()) return

        prepareForNewQuery()

        activeJob = viewModelScope.launch {
            var hasConsumedQuota = false
            try {
                airaChatRepository.ask(bookId, query).collect { response ->
                    handleResponseStream(response) {

                    }
                }
            } finally {
                _uiState.update { it.copy(isLoading = false, isStreaming = false) }
            }
        }
    }

    fun handleQuickPrompt(bookId: String, intent: QuickPrompt) {
        val slot=if(intent is QuickPrompt.VisualizeThis) "image" else "text"
        if(!gateway.configured(slot)) { _uiState.update { it.copy(error="Choose a $slot connection in More → AI Connections.") };return }
        if (_uiState.value.isLoading) return
        if (!checkQuota()) return

        prepareForNewQuery(isGeneratingImage = intent is QuickPrompt.VisualizeThis)

        activeJob = viewModelScope.launch {
            var hasConsumedQuota = false
            try {
                quickRepository.ask(bookId, intent).collect { response ->
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
            is AiraResponse.Partial, is AiraResponse.Success -> {
                val responseText = if(response is AiraResponse.Partial) response.text else (response as AiraResponse.Success).text
                onFirstSuccess()
                _uiState.update {
                    it.copy(
                        isLoading = response is AiraResponse.Partial,
                        isStreaming = response is AiraResponse.Partial,
                        response = responseText,
                        imagePath = null,
                        error = null
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
                error = null
            )
        }
    }

    private fun checkQuota(): Boolean = true



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
                imagePath = null,
                error = null
            )
        }
    }
}
