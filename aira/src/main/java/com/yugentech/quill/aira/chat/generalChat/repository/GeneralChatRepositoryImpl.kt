package com.yugentech.quill.aira.chat.generalChat.repository

import com.yugentech.quill.aira.chat.generalChat.model.GeneralChatPayload
import com.yugentech.quill.aira.chat.generalChat.service.GeneralChatService
import com.yugentech.quill.aira.util.ChatUtils
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.database.entity.AiraMessageEntity
import com.yugentech.quill.database.entity.BookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

class GeneralChatRepositoryImpl(
    private val chatService: GeneralChatService
) : GeneralChatRepository {

    override fun handle(
        question: String,
        history: List<AiraMessageEntity>,
        book: BookEntity,
        selectedText: String?,
        userName: String?
    ): Flow<AiraResponse> = flow {
        val payload =
            GeneralChatPayload(
                query = question,
                bookTitle = book.title,
                bookAuthor = book.author,
                history = ChatUtils.formatHistory(history),
                selectedText = selectedText,
                userName = userName
            )

        chatService.getChatResponse(payload).collect { emit(it) }
    }
}
