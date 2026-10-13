package com.yugentech.quill.aira.chat.bookChat.repository

import com.yugentech.quill.aira.chat.bookChat.payload.BookChatPayload
import com.yugentech.quill.aira.chat.bookChat.service.BookChatService
import com.yugentech.quill.aira.context.*
import com.yugentech.quill.aira.intent.model.Intent
import com.yugentech.quill.aira.rag.EpubTextExtractor
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.aira.util.ChatUtils
import com.yugentech.quill.database.entity.AiraMessageEntity
import com.yugentech.quill.database.entity.BookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class BookChatRepositoryImpl(private val chatService: BookChatService,
    private val epubTextExtractor: EpubTextExtractor) : BookChatRepository {
    override fun handle(question: String, route: Intent.BookRelated, history: List<AiraMessageEntity>,
        book: BookEntity, selectedText: String?, userName: String?, request: ReaderRequestContext): Flow<AiraResponse> = flow {
        val evidence = BookEvidence(epubTextExtractor).build(book.localFilePath, book.title, book.spoilerLockEnabled,
            route, selectedText, request)
        when (evidence) {
            is EvidenceResult.Clarify -> emit(AiraResponse.Clarification(evidence.resolution.question, evidence.resolution.choices))
            is EvidenceResult.Missing -> emit(AiraResponse.Error(evidence.message))
            is EvidenceResult.Ready -> {
                val permittedHistory = if (!book.spoilerLockEnabled || route.plan.permissionGranted || route.plan.explicitSpoilers) history
                    else history.filter { !it.requiresSpoilerPermission }
                emitAll(chatService.bookChat(BookChatPayload(question,
                    "REQUESTED SCOPE: ${evidence.scopeLabel}\nTASK: ${route.intent.name}\nBOOK EVIDENCE:\n${evidence.text}",
                    book.title, book.author, ChatUtils.formatHistory(permittedHistory), route.intent.name.lowercase(), selectedText,
                    userName)).let { source -> source.map { response ->
                        if (response is AiraResponse.Success) response.copy(requiresSpoilerPermission = evidence.requiresSpoilerPermission,
                            sources = evidence.sources) else response
                    } })
            }
        }
    }
}
