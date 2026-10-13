package com.yugentech.quill.aira.repository

import com.yugentech.quill.aira.message.AiraMessage
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.aira.service.AiraChatService
import com.yugentech.quill.database.dao.AiraMessageDao
import com.yugentech.quill.database.entity.AiraMessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AiraChatRepositoryImpl(
    private val airaService: AiraChatService,
    private val airaMessageDao: AiraMessageDao
) : AiraChatRepository {
    override fun observeDiscussion(bookId: String) = airaService.observeDiscussion(bookId)
    override suspend fun newTopic(bookId: String) = airaService.newTopic(bookId)
    override suspend fun selectPassage(bookId: String, text: String, request: com.yugentech.quill.aira.context.ReaderRequestContext) {
        airaService.prepareDiscussion(bookId, text, request.copy(freshTopic = true))
    }
    override fun expandContext(bookId: String) = airaService.expandContext(bookId)

    override suspend fun ask(bookId: String, query: String, selectedText: String?,
        request: com.yugentech.quill.aira.context.ReaderRequestContext): Flow<AiraResponse> =
        airaService.ask(bookId, query, selectedText, request)

    override fun continueRequest(requestId: String, choiceId: String): Flow<AiraResponse> =
        airaService.continueRequest(requestId, choiceId)

    override suspend fun getMessagesForBook(bookId: String): Flow<List<AiraMessage>> =
        airaMessageDao.getMessagesForBook(bookId).map { entities ->
            entities.map { entity ->
                AiraMessage(
                    role = when (entity.role) {
                        AiraMessageRole.USER -> AiraMessage.Role.USER
                        AiraMessageRole.AIRA -> AiraMessage.Role.AIRA
                    },
                    content = entity.content,
                    timestamp = entity.timestamp,
                    sources = com.yugentech.quill.aira.context.SourceExcerpt.decode(entity.sourcesJson)
                )
            }
        }

    override suspend fun clearMessagesForBook(bookId: String) {
        airaService.newTopic(bookId)
        airaMessageDao.clearMessagesForBook(bookId)
    }
}
