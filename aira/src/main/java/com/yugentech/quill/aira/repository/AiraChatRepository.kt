package com.yugentech.quill.aira.repository

import com.yugentech.quill.aira.message.AiraMessage
import com.yugentech.quill.aira.response.AiraResponse
import kotlinx.coroutines.flow.Flow

interface AiraChatRepository {
    fun observeDiscussion(bookId: String): Flow<com.yugentech.quill.aira.context.DiscussionContext?> = kotlinx.coroutines.flow.flowOf(null)
    suspend fun newTopic(bookId: String) {}
    suspend fun selectPassage(bookId: String, text: String, request: com.yugentech.quill.aira.context.ReaderRequestContext) {}
    fun expandContext(bookId: String): Flow<AiraResponse> = kotlinx.coroutines.flow.flowOf(AiraResponse.Error("Start a discussion first."))
    suspend fun ask(bookId: String, query: String, selectedText: String? = null,
        request: com.yugentech.quill.aira.context.ReaderRequestContext = com.yugentech.quill.aira.context.ReaderRequestContext()): Flow<AiraResponse>
    fun continueRequest(requestId: String, choiceId: String): Flow<AiraResponse>
    suspend fun getMessagesForBook(bookId: String): Flow<List<AiraMessage>>
    suspend fun clearMessagesForBook(bookId: String)
}
