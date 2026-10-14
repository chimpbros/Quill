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
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.yugentech.quill.aira.rag.BookStructureStore

class BookChatRepositoryImpl(private val chatService: BookChatService,
    private val epubTextExtractor: EpubTextExtractor,
    private val structures: BookStructureStore = BookStructureStore()) : BookChatRepository {
    override fun handle(question: String, route: Intent.BookRelated, history: List<AiraMessageEntity>,
        book: BookEntity, selectedText: String?, userName: String?, request: ReaderRequestContext): Flow<AiraResponse> = channelFlow {
        send(AiraResponse.Preparing("Preparing scene…"))
        val evidence = BookEvidence(epubTextExtractor, structures).build(book.localFilePath, book.title, book.spoilerLockEnabled,
            route, selectedText, request, book.id) { send(AiraResponse.Preparing(it)) }
        currentCoroutineContext().ensureActive()
        when (evidence) {
            is EvidenceResult.Clarify -> send(AiraResponse.Clarification(evidence.resolution.question, evidence.resolution.choices))
            is EvidenceResult.Missing -> send(AiraResponse.Error(evidence.message))
            is EvidenceResult.Ready -> {
                val permittedHistory = if (!book.spoilerLockEnabled || route.plan.permissionGranted || route.plan.explicitSpoilers) history
                    else history.filter { !it.requiresSpoilerPermission }
                val prefix = evidence.retrievalNotice?.let { "$it\n\n" }.orEmpty()
                chatService.bookChat(BookChatPayload(question,
                    "REQUESTED SCOPE: ${evidence.scopeLabel}\nTASK: ${route.intent.name}\nBOOK EVIDENCE:\n${evidence.text}",
                    book.title, book.author, ChatUtils.formatHistory(permittedHistory), route.intent.name.lowercase(), selectedText,
                    userName)).let { source -> source.map { response ->
                        if (response is AiraResponse.Success) response.copy(text = prefix + response.text,
                            requiresSpoilerPermission = evidence.requiresSpoilerPermission, sources = evidence.sources)
                        else if (response is AiraResponse.Partial) response.copy(text = prefix + response.text) else response
                    } }.collect { currentCoroutineContext().ensureActive(); send(it) }
            }
        }
    }
}
