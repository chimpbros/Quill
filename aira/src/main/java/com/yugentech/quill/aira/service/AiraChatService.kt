package com.yugentech.quill.aira.service

import com.yugentech.quill.aira.chat.bookChat.repository.BookChatRepository
import com.yugentech.quill.aira.chat.generalChat.repository.GeneralChatRepository
import com.yugentech.quill.aira.context.*
import com.yugentech.quill.aira.intent.model.*
import com.yugentech.quill.aira.intent.repository.IntentDetectionRepository
import com.yugentech.quill.aira.response.AiraResponse
import com.yugentech.quill.database.dao.*
import com.yugentech.quill.database.entity.*
import com.yugentech.quill.domain.LocalProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AiraChatService(private val intentDetectionRepository: IntentDetectionRepository,
    private val generalChatRepository: GeneralChatRepository, private val bookChatRepository: BookChatRepository,
    private val bookDao: BookDao, private val airaMessageDao: AiraMessageDao, private val localProfile: LocalProfile,
    private val discussionDao: AiraDiscussionDao? = null) {

    private data class Pending(val bookId: String, val query: String, val selection: String?,
        val request: ReaderRequestContext, val route: Intent.BookRelated, val choices: List<ScopeChoice>)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val activeRequests = ConcurrentHashMap<String, Job>()
    private val requestLock = Mutex()

    fun observeDiscussion(bookId: String): Flow<DiscussionContext?> = discussionDao?.observe(bookId)
        ?.map { it?.let(DiscussionContext::from) } ?: flowOf(null)

    suspend fun newTopic(bookId: String): Unit = requestLock.withLock {
        activeRequests.remove(bookId)?.cancel(CancellationException("New discussion"))
        pending.entries.removeAll { it.value.bookId == bookId }
        discussionDao?.clear(bookId)
        Unit
    }

    suspend fun prepareDiscussion(bookId: String, selection: String?, request: ReaderRequestContext): AiraDiscussionEntity? {
        val dao = discussionDao ?: return null
        val previous = dao.get(bookId)
        val anchor = request.selectionLocator ?: request.currentLocator
        val changedSelection = !selection.isNullOrBlank() && (selection != previous?.selectionText ||
            (request.selectionLocator != null && request.selectionLocator != parseLocator(previous?.anchorLocatorJson)))
        if (previous != null && !request.freshTopic && !changedSelection) return previous
        val book = bookDao.getBookEntity(bookId) ?: return null
        val boundary = request.endLocator ?: request.selectionLocator ?: request.currentLocator ?: parseLocator(book.lastLocatorJson)
        val mode = request.discussionMode ?: if (selection.isNullOrBlank()) "BOOK" else "PASSAGE"
        val label = when (mode) {
            "SCENE" -> "Scene · ${anchor?.title ?: book.lastChapterTitle ?: book.title}"
            "CONVERSATION" -> "Conversation · ${anchor?.title ?: book.lastChapterTitle ?: book.title}"
            else -> selection?.trim()?.replace(Regex("\\s+"), " ")?.take(72) ?: "Discussion · ${book.title}"
        }
        return AiraDiscussionEntity(bookId, UUID.randomUUID().toString(), selection,
            anchor?.toJSON()?.toString(), boundary?.toJSON()?.toString(), mode, label,
            request.paragraphCount, null, "[]", System.currentTimeMillis()).also { dao.save(it) }
    }

    suspend fun rememberQuick(bookId: String, query: String, text: String, selection: String?,
        request: ReaderRequestContext, broader: Boolean = false, topic: AiraDiscussionEntity? = null) {
        val book = bookDao.getBookEntity(bookId) ?: return
        val discussion = topic ?: prepareDiscussion(bookId, selection, request)
        val anchorJson = discussion?.anchorLocatorJson
        val sources = if (!selection.isNullOrBlank() && anchorJson != null)
            listOf(SourceExcerpt("S1", "Selected passage", selection, anchorJson)) else emptyList()
        saveTurn(book, query, AiraResponse.Success(text, broader, sources), discussion)
    }

    private suspend fun saveTurn(book: BookEntity, query: String, response: AiraResponse.Success, discussion: AiraDiscussionEntity?) {
        currentCoroutineContext().ensureActive()
        val sources = SourceExcerpt.encode(response.sources)
        val messages = listOf(
            AiraMessageEntity(bookId = book.id, role = AiraMessageRole.USER, content = query.trim(),
                chapterIndex = book.lastChapterIndex, requiresSpoilerPermission = response.requiresSpoilerPermission,
                topicId = discussion?.topicId, boundaryLocatorJson = discussion?.boundaryLocatorJson),
            AiraMessageEntity(bookId = book.id, role = AiraMessageRole.AIRA, content = response.text.trim(),
                chapterIndex = book.lastChapterIndex, requiresSpoilerPermission = response.requiresSpoilerPermission,
                topicId = discussion?.topicId, boundaryLocatorJson = discussion?.boundaryLocatorJson, sourcesJson = sources))
        if (discussion != null && discussionDao != null)
            discussionDao.completeTurn(book.id, discussion.topicId, response.text.trim(), sources, System.currentTimeMillis(), messages)
        else airaMessageDao.insertMessages(messages)
    }

    fun ask(bookId: String, query: String, selectedText: String? = null,
        request: ReaderRequestContext = ReaderRequestContext()): Flow<AiraResponse> = process(bookId, query, selectedText, request)

    fun askBook(bookId: String, query: String, selection: String?, request: ReaderRequestContext,
        route: Intent.BookRelated): Flow<AiraResponse> = process(bookId, query, selection, request, route)

    fun scene(bookId: String, selection: String?, request: ReaderRequestContext, conversation: Boolean): Flow<AiraResponse> =
        process(bookId, if (conversation) "Explain this conversation and the events that set it up." else "Catch me up on the scene here.",
            selection, request.copy(freshTopic = true, discussionMode = if (conversation) "CONVERSATION" else "SCENE"), sceneRoute())

    private fun sceneRoute() = Intent.BookRelated(listOf("Recent scene"), emptyList(), emptyList(), QueryIntent.SCENE_EXPLANATION,
        plan = ContextPlan(RequestedScope.RECENT_SCENE))

    fun expandContext(bookId: String): Flow<AiraResponse> = flow {
        val dao = discussionDao
        val original = dao?.get(bookId)
        if (original == null) { emit(AiraResponse.Error("Start a passage or scene discussion first.")); return@flow }
        val expanded = original.copy(mode = if (original.mode == "PASSAGE") "CONVERSATION" else original.mode,
            paragraphCount = (original.paragraphCount.toLong() + 24L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        dao.save(expanded)
        emitAll(process(bookId, "Explain the scene using more preceding context.", original.selectionText,
            DiscussionContext.from(expanded).request, sceneRoute()))
    }.catch { error -> if (error is CancellationException) throw error; emit(AiraResponse.Error(error.message ?: "Unable to include more context.")) }

    fun continueRequest(requestId: String, choiceId: String): Flow<AiraResponse> = flow {
        val original = pending.remove(requestId)
        val choice = original?.choices?.firstOrNull { it.id == choiceId }
        if (original == null || choice == null) {
            emit(AiraResponse.Error("That clarification has expired. Please ask your question again.")); return@flow
        }
        emitAll(process(original.bookId, original.query, original.selection, original.request, original.route.copy(plan = choice.plan)))
    }

    private fun process(bookId: String, query: String, selection: String?, request: ReaderRequestContext,
        approvedRoute: Intent.BookRelated? = null): Flow<AiraResponse> = flow {
        val owner = currentCoroutineContext()[Job]!!
        var previousDiscussion: AiraDiscussionEntity? = null
        var createdDiscussion: AiraDiscussionEntity? = null
        var completed = false
        requestLock.withLock { activeRequests.put(bookId, owner)?.takeIf { it !== owner }?.cancel(CancellationException("Superseded request")) }
        try {
        val book = bookDao.getBookEntity(bookId) ?: run { emit(AiraResponse.Error("Book not found.")); return@flow }
        previousDiscussion = discussionDao?.get(bookId)
        var discussion = prepareDiscussion(bookId, selection, request)
        createdDiscussion = discussion?.takeIf { it.topicId != previousDiscussion?.topicId }
        var actualSelection = selection ?: discussion?.selectionText
        var actualRequest = discussion?.let { DiscussionContext.from(it).request }
            ?: request.copy(currentLocator = request.currentLocator ?: parseLocator(book.lastLocatorJson))
        fun eligible(all: List<AiraMessageEntity>, allowBroader: Boolean): List<AiraMessageEntity> = all.filter {
            (discussion == null || it.topicId == discussion?.topicId) &&
                (!(book.spoilerLockEnabled || discussion != null) || ((!it.requiresSpoilerPermission || allowBroader) &&
                    (if (discussion != null) it.boundaryLocatorJson == discussion?.boundaryLocatorJson
                     else it.chapterIndex?.let { chapter -> chapter <= book.lastChapterIndex } == true)))
        }
        val allHistory = airaMessageDao.getMessagesForBookOnce(bookId)
        val classificationHistory = eligible(allHistory, false).takeLast(6).map {
            com.yugentech.quill.domain.ai.AiMessage(if (it.role == AiraMessageRole.USER) "user" else "assistant", it.content)
        }
        var intent = approvedRoute ?: intentDetectionRepository.detectIntent(query, book.title, book.author, actualSelection, classificationHistory)
        if (approvedRoute == null && intent is Intent.BookRelated && intent.plan.scope in
            setOf(RequestedScope.NAMED_SECTION, RequestedScope.WHOLE_BOOK, RequestedScope.CURRENT_CHAPTER)) {
            actualSelection = null
            discussion = prepareDiscussion(bookId, null, request.copy(selectionLocator = null, endLocator = null,
                freshTopic = true, discussionMode = "BOOK"))
            actualRequest = discussion?.let { DiscussionContext.from(it).request } ?: request
        } else if (intent is Intent.BookRelated && discussion?.mode in setOf("SCENE", "CONVERSATION")) {
            intent = intent.copy(plan = intent.plan.copy(scope = RequestedScope.RECENT_SCENE))
        }
        val broaderAllowed = (intent as? Intent.BookRelated)?.plan?.let { it.permissionGranted || it.explicitSpoilers } == true
        val history = eligible(allHistory, broaderAllowed)
        val source = when (intent) {
            is Intent.BookRelated -> bookChatRepository.handle(query, intent, history,
                if (discussion != null && (actualSelection != null || discussion?.mode in setOf("SCENE", "CONVERSATION")))
                    book.copy(spoilerLockEnabled = true) else book, actualSelection,
                localProfile.currentUserName, actualRequest)
            is Intent.General -> generalChatRepository.handle(query, history, book, actualSelection, localProfile.currentUserName)
        }
        source.collect { response ->
            currentCoroutineContext().ensureActive()
            if (activeRequests[bookId] !== owner) throw CancellationException("Superseded request")
            when (response) {
                is AiraResponse.Clarification -> {
                    val route = intent as? Intent.BookRelated ?: return@collect
                    if (pending.size >= 12) pending.clear()
                    val id = UUID.randomUUID().toString()
                    pending[id] = Pending(bookId, query, actualSelection, actualRequest, route, response.choices)
                    emit(response.copy(requestId = id))
                }
                is AiraResponse.Success -> {
                    requestLock.withLock {
                        currentCoroutineContext().ensureActive()
                        if (activeRequests[bookId] !== owner) throw CancellationException("Superseded request")
                        saveTurn(book, query, response, discussion)
                    }
                    completed = true
                    emit(response)
                }
                else -> emit(response)
            }
        }
        } finally {
            withContext(NonCancellable) {
                requestLock.withLock {
                    if (activeRequests[bookId] === owner) {
                        // A cancelled fresh scene restores the last completed discussion.
                        if (!owner.isActive && !completed && createdDiscussion != null &&
                            discussionDao?.get(bookId)?.topicId == createdDiscussion?.topicId) {
                            previousDiscussion?.let { discussionDao?.save(it) } ?: discussionDao?.clear(bookId)
                        }
                        activeRequests.remove(bookId, owner)
                    }
                }
            }
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(AiraResponse.Error((error as? com.yugentech.quill.domain.ai.AiException)?.message
            ?: error.message ?: "Something went wrong. Please try again."))
    }
}
