package com.yugentech.quill.aira.service

import com.yugentech.quill.aira.chat.bookChat.repository.BookChatRepository
import com.yugentech.quill.aira.chat.generalChat.repository.GeneralChatRepository
import com.yugentech.quill.aira.intent.model.Intent
import com.yugentech.quill.aira.intent.repository.IntentDetectionRepository
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.database.dao.AiraMessageDao
import com.yugentech.quill.database.dao.BookDao
import com.yugentech.quill.database.entity.AiraMessageEntity
import com.yugentech.quill.database.entity.AiraMessageRole
import com.yugentech.quill.domain.LocalProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

class AiraChatService(
    private val intentDetectionRepository: IntentDetectionRepository,
    private val generalChatRepository: GeneralChatRepository,
    private val bookChatRepository: BookChatRepository,
    private val bookDao: BookDao,
    private val airaMessageDao: AiraMessageDao,
    private val localProfile: LocalProfile
) {

    fun ask(bookId: String, query: String, selectedText: String? = null): Flow<AiraResponse> = flow {
        val book = bookDao.getBookEntity(bookId)!!
        val recentHistory = airaMessageDao.getMessagesForBookOnce(bookId)
            .filter { !book.spoilerLockEnabled || (it.chapterIndex?.let { chapter -> chapter <= book.lastChapterIndex } == true) }.takeLast(6)
        val userName = localProfile.currentUserName

        try {
            val intent = intentDetectionRepository.detectIntent(
                query = query,
                title = book.title,
                author = book.author,
                selectedText = selectedText
            )
            Timber.d(
                "AiraChat intent for query=\"$query\" selectedText=${
                    selectedText?.let { "\"${it.take(60)}\"" } ?: "null"
                } -> $intent"
            )

            val responseFlow = when (intent) {
                is Intent.BookRelated -> {
                    bookChatRepository.handle(
                        question = query,
                        route = intent,
                        history = recentHistory,
                        book = book,
                        selectedText = selectedText,
                        userName = userName
                    )
                }

                is Intent.General -> {
                    generalChatRepository.handle(
                        question = query,
                        history = recentHistory,
                        book = book,
                        selectedText = selectedText,
                        userName = userName
                    )
                }
            }

            responseFlow.collect { response ->
                if (response is AiraResponse.Success) {
                    airaMessageDao.insertMessages(listOf(
                        AiraMessageEntity(
                            bookId = bookId,
                            role = AiraMessageRole.USER,
                            content = query.trim(), chapterIndex = book.lastChapterIndex
                        )
                    ,
                        AiraMessageEntity(
                            bookId = bookId,
                            role = AiraMessageRole.AIRA,
                            content = response.text.trim(), chapterIndex = book.lastChapterIndex
                        )
                    ))
                }
                emit(response)
            }

        } catch (e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            emit(AiraResponse.Error(resolveErrorMessage(e)))
        }
    }

    private fun resolveErrorMessage(e: Exception): String = when {
        e.message?.contains("MAX_TOKENS") == true ->
            "The answer was too long. Please try asking for a summary."

        e.message?.contains("resource-exhausted") == true ->
            "This provider's usage limit has been reached."

        else -> (e as? com.yugentech.quill.domain.ai.AiException)?.message ?: "Something went wrong. Please try again."
    }
}
