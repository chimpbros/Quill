package com.yugentech.quill.ui.shared.airaChat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.yugentech.quill.aira.message.AiraMessage
import com.yugentech.quill.aira.repository.AiraChatRepository
import com.yugentech.quill.ui.shared.airaChat.state.AiraUiState
import com.yugentech.quill.bookDetails.repository.BookDetailsRepository
import com.yugentech.quill.aira.rag.BookEmbeddingWorker
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.domain.FeatureAccess
import com.yugentech.theme.tokens.AppConstants.EMPTY
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiraViewModel(
    val bookId: String,
    private val airaChatRepository: AiraChatRepository,
    private val bookRepository: BookDetailsRepository,
    private val workManager: WorkManager,
    private val featureAccess: FeatureAccess,
    private val gateway: com.yugentech.quill.aira.provider.DirectAiGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiraUiState())
    val uiState = _uiState.asStateFlow()

    private var settlingJob: Job? = null
    private var requestGeneration = 0L
    private var isCurrentlyStreaming = false
    private var generationJob: Job? = null


    init {
        gateway.settings.revision.onEach { _uiState.update { it.copy(canSendQuery=gateway.configured()) } }.launchIn(viewModelScope)
        observeBilling()
        observeBookAndIndexing()
        observeChatHistory()
        viewModelScope.launch {
            airaChatRepository.observeDiscussion(bookId).collectLatest { discussion ->
                _uiState.update { it.copy(discussion = discussion) }
            }
        }
    }

    private fun observeBilling() {
        viewModelScope.launch {
            featureAccess.indexingEnabled.collectLatest { proStatus ->
                _uiState.update { it.copy(isPro = proStatus) }
            }
        }
    }

    private fun observeBookAndIndexing() {
        viewModelScope.launch {
            val book = bookRepository.getBookOnce(bookId)
            _uiState.update {
                it.copy(
                    bookTitle = book?.title ?: EMPTY,
                    bookAuthor = book?.author ?: EMPTY,
                    lastChapterTitle = book?.lastChapterTitle,
                    hasStartedReading = book?.lastChapterTitle != null,
                    spoilerLockEnabled = book?.spoilerLockEnabled ?: true,
                    readingLocatorJson = book?.lastLocatorJson
                )
            }
        }

        workManager
            .getWorkInfosByTagLiveData("index_$bookId")
            .asFlow()
            .onEach { workInfoList ->
                val info = workInfoList.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: workInfoList.lastOrNull()

                when {
                    info == null -> {
                        val ready = bookRepository.isReady(bookId)
                        _uiState.update { it.copy(isReady = ready, isIndexing = !ready) }
                    }

                    info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED -> {
                        val progress = info.progress.getInt(BookEmbeddingWorker.KEY_PROGRESS, 0)
                        _uiState.update {
                            it.copy(
                                isReady = false,
                                isIndexing = true,
                                indexingProgress = progress
                            )
                        }
                    }

                    info.state == WorkInfo.State.SUCCEEDED -> {
                        _uiState.update {
                            it.copy(
                                isReady = true,
                                isIndexing = false,
                                indexingProgress = 100
                            )
                        }
                    }

                    info.state == WorkInfo.State.FAILED -> {
                        _uiState.update {
                            it.copy(
                                isReady = false,
                                isIndexing = false,
                                error = "Indexing failed. Please try again."
                            )
                        }
                    }

                    info.state == WorkInfo.State.CANCELLED -> {
                        _uiState.update { it.copy(isIndexing = false) }
                    }

                    else -> Unit
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeChatHistory() {
        viewModelScope.launch {
            airaChatRepository.getMessagesForBook(bookId).collectLatest { messages ->
                if (!isCurrentlyStreaming) {
                    _uiState.update { it.copy(messages = messages) }
                }
            }
        }
    }

    fun ask(question: String) {
        if (question.isBlank() || _uiState.value.isLoading) return



        val userMessage = AiraMessage(AiraMessage.Role.USER, question.trim())
        val initialAiraMessage = AiraMessage(AiraMessage.Role.AIRA, "")

        isCurrentlyStreaming = true
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage + initialAiraMessage,
                isLoading = true,
                isStreaming = false,
                error = null,
                clarification = null
            )
        }

        val requestId = ++requestGeneration
        generationJob = viewModelScope.launch {
            settlingJob?.join(); settlingJob = null
            var hasConsumedQuota = false

            try {
                airaChatRepository.ask(bookId = bookId, query = question).collect { response ->



                    if (requestId == requestGeneration) handleStreamResponse(response)
                }
            } finally {
                if (requestId == requestGeneration) {
                    isCurrentlyStreaming = false
                    _uiState.update { it.copy(isLoading = false, isStreaming = false, preparationMessage = null) }
                }
            }
        }
    }

    fun newTopic() {
        resetStreamingStates()
        viewModelScope.launch { airaChatRepository.newTopic(bookId) }
    }

    fun expandContext() {
        if (_uiState.value.isLoading) return
        isCurrentlyStreaming = true
        _uiState.update { it.copy(isLoading = true, error = null,
            messages = it.messages + AiraMessage(AiraMessage.Role.USER, "Include more preceding context.") + AiraMessage(AiraMessage.Role.AIRA, "")) }
        val requestId = ++requestGeneration
        generationJob = viewModelScope.launch {
            settlingJob?.join(); settlingJob = null
            try { airaChatRepository.expandContext(bookId).collect { if (requestId == requestGeneration) handleStreamResponse(it) } }
            finally { if (requestId == requestGeneration) { isCurrentlyStreaming = false; _uiState.update { it.copy(isLoading = false, isStreaming = false, preparationMessage = null) } } }
        }
    }

    fun stopGeneration() {
        val wasPending = _uiState.value.isLoading || _uiState.value.isStreaming
        resetStreamingStates()
        if (wasPending) _uiState.update { state -> state.copy(messages = state.messages.dropLast(2)) }
    }

    fun toggleSpoilerLock() {
        val newValue = !_uiState.value.spoilerLockEnabled

        _uiState.update { it.copy(spoilerLockEnabled = newValue) }
        viewModelScope.launch {
            bookRepository.setSpoilerLock(bookId, newValue)
        }
    }

    fun clearChat() {
        resetStreamingStates()
        _uiState.update { it.copy(messages = emptyList(), error = null, clarification = null) }
        viewModelScope.launch {
            airaChatRepository.clearMessagesForBook(bookId)
        }
    }

    fun clearError() {
        _uiState.update { airaUiState ->
            airaUiState.copy(
                error = null
            )
        }
    }



    private fun handleStreamResponse(response: AiraResponse) {
        when (response) {
            is AiraResponse.Preparing -> _uiState.update { it.copy(preparationMessage = response.message, isLoading = true, isStreaming = false) }
            is AiraResponse.Clarification -> _uiState.update { it.copy(clarification = response, isLoading = false,
                isStreaming = false, messages = it.messages.dropLast(1) + AiraMessage(AiraMessage.Role.AIRA, response.question)) }
            is AiraResponse.Partial, is AiraResponse.Success -> {
                val responseText = if(response is AiraResponse.Partial) response.text else (response as AiraResponse.Success).text
                _uiState.update { airaUiState ->
                    val updatedMessages = airaUiState.messages.toMutableList()

                    if (updatedMessages.isNotEmpty() && updatedMessages.last().role == AiraMessage.Role.AIRA) {
                        updatedMessages[updatedMessages.lastIndex] = updatedMessages.last().copy(
                                    content = responseText,
                                    sources = if (response is AiraResponse.Success) response.sources else emptyList()
                        )
                    }

                    airaUiState.copy(
                        messages = updatedMessages,
                        preparationMessage = null,
                        isLoading = response is AiraResponse.Partial,
                        isStreaming = response is AiraResponse.Partial
                    )
                }
            }

            is AiraResponse.Error -> {
                _uiState.update { airaUiState ->
                    airaUiState.copy(
                        messages = airaUiState.messages.dropLast(1),
                        error = response.message
                    )
                }
            }

            // General/book chat never produces an image -- that's exclusive to the
            // quick-action "Visualize this" flow, which doesn't go through this ViewModel.
            is AiraResponse.ImageSuccess -> Unit
        }
    }

    private fun resetStreamingStates() {
        requestGeneration++
        settlingJob = generationJob ?: settlingJob
        generationJob?.cancel()
        generationJob = null
        isCurrentlyStreaming = false
        _uiState.update { airaUiState ->
            airaUiState.copy(
                preparationMessage = null,
                isLoading = false,
                isStreaming = false
            )
        }
    }

    fun chooseScope(choiceId: String) {
        val pending = _uiState.value.clarification ?: return
        isCurrentlyStreaming = true
        _uiState.update { it.copy(clarification = null, isLoading = true, error = null,
            messages = it.messages.dropLast(1) + AiraMessage(AiraMessage.Role.AIRA, "")) }
        val requestId = ++requestGeneration
        generationJob = viewModelScope.launch {
            settlingJob?.join(); settlingJob = null
            try { airaChatRepository.continueRequest(pending.requestId, choiceId).collect { if (requestId == requestGeneration) handleStreamResponse(it) } }
            finally { if (requestId == requestGeneration) { isCurrentlyStreaming = false; _uiState.update { it.copy(isLoading = false, isStreaming = false, preparationMessage = null) } } }
        }
    }
    fun dismissClarification() { _uiState.update { it.copy(clarification = null) } }
}
