package com.yugentech.quill.aira.chat.quickChat.repository

import android.content.Context
import android.util.Base64
import com.yugentech.quill.aira.chat.quickChat.model.QuickChatPayload
import com.yugentech.quill.aira.chat.quickChat.model.QuickChatType
import com.yugentech.quill.aira.chat.quickChat.prompt.QuickPrompt
import com.yugentech.quill.aira.chat.quickChat.service.QuickChatService
import com.yugentech.quill.aira.chat.quickChat.service.VisualizeService
import com.yugentech.quill.aira.rag.EpubTextExtractor
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.aira.service.AiraChatService
import com.yugentech.quill.aira.context.*
import com.yugentech.quill.aira.intent.model.*
import com.yugentech.quill.database.dao.AiraMessageDao
import com.yugentech.quill.database.dao.BookDao
import com.yugentech.quill.database.dao.VisualDao
import com.yugentech.quill.database.entity.AiraMessageEntity
import com.yugentech.quill.database.entity.AiraMessageRole
import com.yugentech.quill.database.entity.VisualEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.File
import java.util.UUID

class QuickChatRepositoryImpl(
    private val context: Context,
    private val bookDao: BookDao,
    private val visualDao: VisualDao,
    private val airaMessageDao: AiraMessageDao,
    private val epubTextExtractor: EpubTextExtractor,
    private val actionService: QuickChatService,
    private val visualizeService: VisualizeService,
    private val airaChatService: AiraChatService
) : QuickChatRepository {

    override suspend fun ask(bookId: String, quickPrompt: QuickPrompt, request: ReaderRequestContext): Flow<AiraResponse> = flow {
        val book = bookDao.getBookEntity(bookId)
        if (book == null) {
            emit(AiraResponse.Error("Book not found."))
            return@flow
        }
        if (quickPrompt is QuickPrompt.IncludeMoreContext) {
            emitAll(airaChatService.expandContext(bookId)); return@flow
        }
        if (quickPrompt is QuickPrompt.CatchMeUp || quickPrompt is QuickPrompt.ExplainConversation) {
            emitAll(airaChatService.scene(bookId, (quickPrompt as? QuickPrompt.ExplainConversation)?.text,
                request, quickPrompt is QuickPrompt.ExplainConversation)); return@flow
        }
        val quickSelection = (quickPrompt as? QuickPrompt.ExplainThis)?.text
        val topic = if (quickPrompt !is QuickPrompt.CustomQuestion) airaChatService.prepareDiscussion(bookId,
            quickSelection, request.copy(freshTopic = quickSelection == null)) else null

        // Grounded chips share precise reading boundaries and literal retrieval with typed chat.
        val task = when (quickPrompt) {
            is QuickPrompt.WhoAreTheCharacters, is QuickPrompt.RecallCharacter -> QueryIntent.CHARACTER_PROFILE
            is QuickPrompt.RecentRole -> QueryIntent.CHARACTER_RECENT
            is QuickPrompt.JourneySoFar -> QueryIntent.CHARACTER_ARC
            is QuickPrompt.WhatAreTheThemes -> QueryIntent.THEME_ANALYSIS
            is QuickPrompt.WhatIsTheSignificance -> QueryIntent.SIGNIFICANCE
            else -> null
        }
        if (task != null) {
            val name = when (quickPrompt) {
                is QuickPrompt.RecallCharacter -> quickPrompt.name
                is QuickPrompt.RecentRole -> quickPrompt.name
                is QuickPrompt.JourneySoFar -> quickPrompt.name
                else -> null
            }
            val selected = (quickPrompt as? QuickPrompt.WhatIsTheSignificance)?.text
            val scope = when (quickPrompt) {
                is QuickPrompt.WhoAreTheCharacters -> RequestedScope.READ_SO_FAR
                is QuickPrompt.WhatAreTheThemes -> RequestedScope.CURRENT_CHAPTER
                is QuickPrompt.WhatIsTheSignificance -> RequestedScope.PASSAGE
                else -> RequestedScope.RELEVANT_PASSAGES
            }
            val query = quickPrompt.toHistoryStatement()
            emitAll(airaChatService.askBook(bookId, query, selected, request,
                Intent.BookRelated(listOf(selected ?: name ?: query), listOfNotNull(name), emptyList(), task,
                    characterName = name, plan = ContextPlan(scope))))
            return@flow
        }
        if (quickPrompt is QuickPrompt.SummarizeChapter) {
            val locator = request.currentLocator ?: (quickPrompt.locatorJson ?: book.lastLocatorJson)?.let { json ->
                runCatching { org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(json)) }.getOrNull()
            }
            val navigation = book.localFilePath?.let { epubTextExtractor.structure(it) }
            if (navigation != null && !navigation.isPrecise(locator) && navigation.active(locator)?.let { active ->
                navigation.sections.any { it.parentId == active.id }
            } == true) {
                emitAll(airaChatService.askBook(bookId, "Can you summarize this chapter?", null, request.copy(currentLocator = locator),
                    Intent.BookRelated(listOf("Current chapter"), emptyList(), emptyList(), QueryIntent.CHAPTER_SUMMARY,
                        plan = ContextPlan(RequestedScope.CURRENT_CHAPTER))))
                return@flow
            }
        }

        val payload: QuickChatPayload = when (quickPrompt) {
            is QuickPrompt.CatchMeUp, is QuickPrompt.ExplainConversation, is QuickPrompt.IncludeMoreContext -> error("Scene action must be routed above")
            is QuickPrompt.SummarizeChapter -> {
                val localFilePath = book.localFilePath
                val chapter = localFilePath?.let {
                    val locator = request.currentLocator ?: (quickPrompt.locatorJson ?: book.lastLocatorJson)?.let { json ->
                        runCatching { org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(json)) }.getOrNull()
                    }
                    val structure = epubTextExtractor.structure(it)
                    val section = structure.active(locator)
                    if (section != null) com.yugentech.quill.aira.rag.ChapterText(section.start.spine, section.title, structure.text(section))
                    else epubTextExtractor.extractChapter(it, quickPrompt.chapterIndex)
                }
                if (chapter == null) {
                    emit(AiraResponse.Error("No content found for this chapter."))
                    return@flow
                }
                QuickChatPayload(
                    actionType = QuickChatType.SUMMARIZE_CHAPTER,
                    bookTitle = book.title,
                    bookAuthor = book.author,
                    context = chapter.text
                )
            }

            is QuickPrompt.WhoAreTheCharacters, is QuickPrompt.RecallCharacter, is QuickPrompt.RecentRole,
            is QuickPrompt.JourneySoFar, is QuickPrompt.WhatAreTheThemes, is QuickPrompt.WhatIsTheSignificance ->
                error("Grounded action must be routed above")

            is QuickPrompt.DefineWord -> QuickChatPayload(
                actionType = QuickChatType.DEFINE_WORD,
                bookTitle = book.title,
                bookAuthor = book.author,
                context = "",
                query = quickPrompt.word
            )

            is QuickPrompt.WhatIsThis -> {
                val locator = request.currentLocator ?: (quickPrompt.locatorJson ?: book.lastLocatorJson)?.let { json ->
                    runCatching { org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(json)) }.getOrNull()
                }
                emitAll(airaChatService.askBook(bookId, "What is ${quickPrompt.word} in this book?", quickPrompt.word,
                    request.copy(currentLocator = locator),
                    com.yugentech.quill.aira.intent.model.Intent.BookRelated(listOf(quickPrompt.word), emptyList(), emptyList(),
                        com.yugentech.quill.aira.intent.model.QueryIntent.TERM_EXPLANATION,
                        plan = com.yugentech.quill.aira.context.ContextPlan(term = quickPrompt.word))))
                return@flow
            }

            is QuickPrompt.ExplainThis -> QuickChatPayload(
                actionType = QuickChatType.EXPLAIN_THIS,
                bookTitle = book.title,
                bookAuthor = book.author,
                context = quickPrompt.text
            )

            is QuickPrompt.VisualizeThis -> {
                try {
                    val result = visualizeService.visualize(
                        bookTitle = book.title,
                        bookAuthor = book.author,
                        passage = quickPrompt.text
                    )

                    val imageBytes = Base64.decode(result.imageBase64, Base64.DEFAULT)
                    val visualsDir = File(context.filesDir, "visuals").also { it.mkdirs() }
                    val id = UUID.randomUUID().toString()
                    val extension = if (result.mimeType.contains("png")) "png" else "jpg"
                    val imageFile = File(visualsDir, "$id.$extension")
                    imageFile.writeBytes(imageBytes)

                    val chapterTitle = book.localFilePath
                        ?.let { epubTextExtractor.extractChapter(it, quickPrompt.chapterIndex) }
                        ?.chapterTitle
                        ?: "Chapter ${quickPrompt.chapterIndex + 1}"

                    visualDao.insertVisual(
                        VisualEntity(
                            id = id,
                            bookId = bookId,
                            chapterIndex = quickPrompt.chapterIndex,
                            chapterTitle = chapterTitle,
                            sourceText = quickPrompt.text,
                            imagePath = imageFile.absolutePath,
                            locatorJson = quickPrompt.locatorJson
                        )
                    )

                    emit(AiraResponse.ImageSuccess(imageFile.absolutePath))
                } catch (e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
                    Timber.e(e, "VisualizeThis failed")
                    val errorMsg = when {
                        e.message?.contains("resource-exhausted") == true ->
                            "This provider's usage limit has been reached."
                        // A clean model decline (nothing visual in the passage) rather than a
                        // technical failure -- the function already puts a friendly, specific
                        // message here, so just pass it through instead of the generic fallback.

                        else -> (e as? com.yugentech.quill.domain.ai.AiException)?.message ?: "Something went wrong generating that image. Please try again."
                    }
                    emit(AiraResponse.Error(errorMsg))
                }

                return@flow
            }

            // Isolated flow, like VisualizeThis -- delegates straight into the same
            // intent-detection + book/general chat pipeline AiraChatScreen uses, with the
            // highlighted text threaded through as extra anchor context. That's what gives it
            // entity extraction, multi-query retrieval, and conversation history, instead of
            // the single thin generic search this used to run on its own.
            is QuickPrompt.CustomQuestion -> {
                emitAll(
                    airaChatService.ask(
                        bookId = bookId,
                        query = quickPrompt.query,
                        selectedText = quickPrompt.selectedText,
                        request = request
                    )
                )
                return@flow
            }
        }

        try {
            actionService.getQuickChatResponse(payload).collect { event ->
            if (event is AiraResponse.Partial) { emit(event); return@collect }
            if (event !is AiraResponse.Success) { emit(event); return@collect }
            val responseText = event.text

            if (responseText.isBlank()) {
                emit(AiraResponse.Error("Aira didn't have a response."))
            } else {
                // Quick actions (chips) don't come from the user typing a real question, so they
                // get recorded under a premade stand-in statement -- this is what makes them show
                // up in the full chat history/transcript alongside typed messages, instead of
                // vanishing once the peek bar closes.
                airaChatService.rememberQuick(bookId, quickPrompt.toHistoryStatement(), responseText.trim(), quickSelection,
                    request, quickPrompt is QuickPrompt.SummarizeChapter, topic)
                val anchorJson = topic?.anchorLocatorJson
                val sources = if (quickSelection != null && anchorJson != null)
                    listOf(SourceExcerpt("S1", "Selected passage", quickSelection, anchorJson)) else emptyList()
                emit(AiraResponse.Success(responseText.trim(), sources = sources))
            }

            }
        } catch (e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            val errorMsg = when {
                e.message?.contains("resource-exhausted") == true ->
                    "This provider's usage limit has been reached."
                else -> (e as? com.yugentech.quill.domain.ai.AiException)?.message ?: "Something went wrong. Please try again."
            }
            emit(AiraResponse.Error(errorMsg))
        }
    }

    // A chip tap never produces a real typed question, so one is stood in for it here purely
    // for the chat history transcript -- CustomQuestion is the one exception, since it already
    // carries the user's own typed text.
    private fun QuickPrompt.toHistoryStatement(): String = when (this) {
        is QuickPrompt.CatchMeUp -> "Catch me up here."
        is QuickPrompt.ExplainConversation -> "Explain this conversation: $text"
        is QuickPrompt.IncludeMoreContext -> "Include more preceding context."
        is QuickPrompt.WhoAreTheCharacters -> "Who are the characters in this book?"
        is QuickPrompt.WhatAreTheThemes -> "What are the themes of this chapter?"
        is QuickPrompt.RecallCharacter -> "Who is $name?"
        is QuickPrompt.RecentRole -> "What has $name been doing recently?"
        is QuickPrompt.JourneySoFar -> "What has $name's journey been so far?"
        is QuickPrompt.WhatIsTheSignificance -> "What's the significance of \"$text\"?"
        is QuickPrompt.SummarizeChapter -> "Can you summarize this chapter?"
        is QuickPrompt.DefineWord -> "What does $word mean?"
        is QuickPrompt.WhatIsThis -> "What is $word?"
        is QuickPrompt.ExplainThis -> "Can you explain \"$text\"?"
        is QuickPrompt.VisualizeThis -> "Can you visualize \"$text\"?"
        is QuickPrompt.CustomQuestion -> query
    }
}
