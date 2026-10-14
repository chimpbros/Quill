package com.yugentech.quill.reader.viewmodel.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yugentech.quill.database.entity.HighlightEntity
import com.yugentech.quill.reader.settings.model.ReaderSettings
import com.yugentech.quill.reader.settings.repository.ReaderSettingsRepository
import com.yugentech.quill.reader.repository.book.ReaderBookRepository
import com.yugentech.quill.reader.sound.repository.BackgroundSoundRepository
import com.yugentech.quill.reader.repository.session.ReadingSessionRepository
import com.yugentech.quill.reader.ui.components.engine.ReaderDefaults
import com.yugentech.quill.reader.sound.model.BackgroundSound
import com.yugentech.theme.service.HapticService
import com.yugentech.quill.aira.rag.locatorAtHref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.util.UUID

sealed class ReaderCommand {
    object NextPage : ReaderCommand()
    object PreviousPage : ReaderCommand()
}

class ReaderViewModel(
    application: Application,
    private val readerRepository: ReaderBookRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val preferencesRepository: ReaderSettingsRepository,
    private val backgroundSoundRepository: BackgroundSoundRepository,
    private val hapticService: HapticService,
    private val bookmarkDao: com.yugentech.quill.database.dao.BookmarkDao,
    private val structures: com.yugentech.quill.aira.rag.BookStructureStore
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _highlights = MutableStateFlow<List<HighlightEntity>>(emptyList())
    val highlights = _highlights.asStateFlow()
    private val _bookmarks = MutableStateFlow<List<com.yugentech.quill.database.entity.BookmarkEntity>>(emptyList())
    val bookmarks = _bookmarks.asStateFlow()
    private var bookmarksJob: Job? = null
    private var structureLease: com.yugentech.quill.aira.rag.BookStructureStore.Lease? = null
    private var loadJob: Job? = null
    init {
        viewModelScope.launch {
            structures.invalidations.collect { bookId ->
                if ((_uiState.value as? ReaderUiState.Success)?.bookId == bookId) {
                    loadJob?.cancel(); cancelRange(); structureLease?.close(); structureLease = null
                    structure = null; publication?.close(); publication = null
                    _uiState.value = ReaderUiState.Error("The book text changed. Reopen the book to reload it.")
                }
            }
        }
    }
    private var structure: com.yugentech.quill.aira.rag.PublicationStructure? = null
    private val _rangeState = MutableStateFlow(com.yugentech.quill.reader.ui.components.range.ReaderRangeState())
    val rangeState = _rangeState.asStateFlow()
    private var rangeJob: Job? = null

    fun cancelRange() { rangeJob?.cancel(); _rangeState.value = com.yugentech.quill.reader.ui.components.range.ReaderRangeState() }
    fun adjustRangeStart() { _rangeState.value = _rangeState.value.copy(range = null, adjustingStart = true, error = null) }
    fun adjustRangeEnd() { _rangeState.value = _rangeState.value.copy(range = null, adjustingStart = false, error = null) }
    fun setRangeNote(note: String) { _rangeState.value = _rangeState.value.copy(note = note) }
    fun setRangeColor(color: Int) { _rangeState.value = _rangeState.value.copy(colorInt = color) }
    fun selectRangeEndpoint(locator: Locator) {
        rangeJob?.cancel()
        val previous = _rangeState.value
        val start = if (previous.start == null || previous.adjustingStart) locator else previous.start
        val end = if (previous.start == null || previous.adjustingStart) previous.end else locator
        val next = previous.copy(start = start, end = end, range = null, adjustingStart = false, error = null)
        _rangeState.value = next
        rangeJob = viewModelScope.launch {
            try {
                val navigation = structure ?: error("The book is still loading. Select the endpoint again.")
                withContext(Dispatchers.Default) {
                    val spine = navigation.resources.indexOf(com.yugentech.quill.aira.rag.PublicationStructure.normalizeHref(locator.href.toString()))
                    require(spine >= 0 && navigation.documents[spine].exactRange(locator) != null) { "The exact endpoint could not be resolved. Select the words again." }
                }
                if (end != null) {
                    val resolved = withContext(Dispatchers.Default) { com.yugentech.quill.aira.rag.TextRangeResolver.resolve(navigation, start, end) }
                    _rangeState.value = next.copy(range = resolved)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) { _rangeState.value = next.copy(
                adjustingStart = previous.start == null || previous.adjustingStart,
                error = error.message ?: "Unable to resolve that range.") }
        }
    }
    fun saveRangeHighlight(bookId: String) {
        val selected = _rangeState.value
        val range = selected.range ?: return
        if (selected.saving) return
        _rangeState.value = selected.copy(saving = true)
        rangeJob = viewModelScope.launch {
            try {
                readerRepository.saveHighlight(HighlightEntity(UUID.randomUUID().toString(), bookId,
                    range.startLocator.toJSON().toString(), selected.colorInt, selected.note.trim().takeIf { it.isNotEmpty() },
                    segmentsJson = range.segmentsJson(), selectedText = range.text))
                _rangeState.value = com.yugentech.quill.reader.ui.components.range.ReaderRangeState()
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) { _rangeState.value = selected.copy(error = error.message ?: "Unable to save the highlight.") }
        }
    }

    private val _activeSound = MutableStateFlow<BackgroundSound>(BackgroundSound.NONE)
    val activeSound = _activeSound.asStateFlow()

    val readerPreferences = preferencesRepository.readerSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ReaderSettings(ReaderDefaults.getPreferences())
    )

    private val _commands = Channel<ReaderCommand>(Channel.BUFFERED)
    val commands = _commands.receiveAsFlow()

    private var publication: Publication? = null
    private var saveJob: Job? = null
    private var highlightsJob: Job? = null
    private var tocMap: Map<String, String> = emptyMap()

    private var sessionStartTime: Long = 0L

    fun loadBook(bookId: String, initialHref: String?, locatorJson: String? = null) {
        val loaded = _uiState.value as? ReaderUiState.Success
        if (loaded?.bookId == bookId) {
            // Configuration recreation retains the open publication in this ViewModel.
            val locator = locatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            _uiState.value = loaded.copy(initialLocator = locator ?: loaded.initialLocator)
            return
        }
        loadJob?.cancel()
        structureLease?.close(); structureLease = null; structure = null
        publication?.close(); publication = null
        loadJob = viewModelScope.launch {
            var opened: Publication? = null
            var acquired: com.yugentech.quill.aira.rag.BookStructureStore.Lease? = null
            _uiState.value = ReaderUiState.Idle
            cancelRange()

            withContext(Dispatchers.IO) {
                try {
                    val bookEntity = readerRepository.getBook(bookId).firstOrNull()
                    val path = bookEntity?.localFilePath
                    val totalPages = bookEntity?.totalPages ?: 0

                    if (path == null) {
                        _uiState.value = ReaderUiState.Error("Book path not found.")
                        return@withContext
                    }

                    val file = File(path)
                    val context = getApplication<Application>()
                    val httpClient = DefaultHttpClient()
                    val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
                    val parser = DefaultPublicationParser(context, httpClient, assetRetriever, null)
                    val publicationOpener =
                        PublicationOpener(parser, emptyList(), onCreatePublication = {})

                    val asset = assetRetriever.retrieve(file)
                        .getOrElse { error -> throw Exception("Failed to retrieve asset: $error") }

                    val pub = publicationOpener.open(asset, allowUserInteraction = false)
                        .getOrElse { error -> throw Exception("Failed to open publication: $error") }

                    opened = pub
                    val positions = pub.positions()
                    val lease = structures.acquire(bookId, path) { com.yugentech.quill.aira.rag.PublicationStructure.load(pub) }
                    acquired = lease
                    val navigation = lease.structure

                    val targetLocator = locatorJson?.let { jsonStr ->
                        try {
                            Locator.fromJSON(JSONObject(jsonStr))
                        } catch (_: Exception) {
                            null
                        }
                    }

                    val explicitLocator = initialHref?.let { href ->
                        pub.locatorAtHref(href, navigation.sections.firstOrNull { it.href == href }?.title)
                    }

                    val savedLocator = bookEntity.lastLocatorJson?.let { jsonStr ->
                        try {
                            Locator.fromJSON(JSONObject(jsonStr))
                        } catch (_: Exception) {
                            null
                        }
                    }

                    val finalLocator = targetLocator ?: explicitLocator ?: savedLocator

                    withContext(Dispatchers.Main.immediate) {
                    publication = pub
                    structureLease = lease
                    structure = navigation
                    tocMap = flattenToc(pub.tableOfContents)
                    sessionStartTime = System.currentTimeMillis()

                    _uiState.value = ReaderUiState.Success(
                        bookId = bookId,
                        publication = pub,
                        totalPages = totalPages,
                        allPositions = positions,
                        initialLocator = finalLocator,
                        structure = navigation
                    )

                    observeHighlights(bookId)
                    bookmarksJob?.cancel()
                    bookmarksJob = viewModelScope.launch { bookmarkDao.observe(bookId).collect { _bookmarks.value = it } }

                    // Auto-play sound if enabled
                    viewModelScope.launch {
                        val prefs = preferencesRepository.readerSettings.first()
                        if (prefs.soundEnabled && prefs.autoPlaySound && prefs.lastSelectedSound != BackgroundSound.NONE) {
                            playBackgroundSound(prefs.lastSelectedSound)
                        }
                    }

                    }
                } catch (e: Exception) {
                    acquired?.close(); opened?.close()
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    withContext(Dispatchers.Main.immediate) { _uiState.value = ReaderUiState.Error(e.message ?: "Failed to open book") }
                }
            }
        }
    }

    private fun observeHighlights(bookId: String) {
        highlightsJob?.cancel()
        highlightsJob = viewModelScope.launch {
            readerRepository.getHighlights(bookId).collect { highlightList ->
                _highlights.value = highlightList
            }
        }
    }

    fun addHighlight(
        bookId: String,
        locatorJson: String,
        colorInt: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val newEntity = HighlightEntity(
                id = UUID.randomUUID().toString(),
                bookId = bookId,
                locatorJson = locatorJson,
                colorInt = colorInt,
                createdAt = System.currentTimeMillis()
            )
            readerRepository.saveHighlight(newEntity)
        }
    }

    fun deleteHighlight(highlightId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            readerRepository.deleteHighlight(highlightId)
        }
    }

    fun saveProgress(bookId: String, locator: Locator) {
        saveJob?.cancel()

        saveJob = viewModelScope.launch {
            delay(2000)

            val pub = publication ?: return@launch

            val currentHref = locator.href.toString().substringBefore("#")
            val chapterIndex = pub.readingOrder
                .indexOfFirst { it.href.toString().substringBefore("#") == currentHref }
                .coerceAtLeast(0)

            val totalProgress = locator.locations.totalProgression?.toFloat() ?: 0f

            val chapterTitle = structure?.active(locator)?.title ?: tocMap[locator.href.toString()]
                ?: pub.readingOrder.getOrNull(chapterIndex)?.title

            val locatorJson = locator.toJSON().toString()

            readerRepository.saveProgress(
                bookId = bookId,
                progress = totalProgress,
                chapterTitle = chapterTitle,
                chapterIndex = chapterIndex,
                locatorJson = locatorJson
            )
        }
    }

    fun updateEpubPreferences(newPreferences: EpubPreferences) {
        viewModelScope.launch {
            preferencesRepository.saveEpubPreferences(newPreferences)
        }
    }
    fun toggleBookmark(bookId: String, locator: Locator) {
        val key = structure?.locationKey(locator) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val old = bookmarkDao.atLocation(bookId, key)
            if (old != null) bookmarkDao.delete(old.id)
            else bookmarkDao.insert(com.yugentech.quill.database.entity.BookmarkEntity(
                id = UUID.randomUUID().toString(), bookId = bookId,
                locatorJson = locator.copy(title = structure?.active(locator)?.title ?: locator.title).toJSON().toString(), locationKey = key))
        }
    }
    fun removeBookmark(bookmark: com.yugentech.quill.database.entity.BookmarkEntity) {
        viewModelScope.launch(Dispatchers.IO) { bookmarkDao.delete(bookmark.id) }
    }
    fun restoreBookmark(bookmark: com.yugentech.quill.database.entity.BookmarkEntity) {
        viewModelScope.launch(Dispatchers.IO) { bookmarkDao.insert(bookmark) }
    }
    fun renameBookmark(id: String, label: String) {
        viewModelScope.launch(Dispatchers.IO) { bookmarkDao.rename(id, label.trim().takeIf { it.isNotEmpty() }) }
    }

    fun updateVolumeNavigation(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.saveVolumeNavigation(enabled)
        }
    }

    fun updateNightLight(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.saveNightLight(enabled)
        }
    }

    fun updateAutoPlaySound(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.saveAutoPlaySound(enabled)
        }
    }

    fun onVolumeUp() {
        viewModelScope.launch {
            _commands.send(ReaderCommand.PreviousPage)
        }
    }

    fun onVolumeDown() {
        viewModelScope.launch {
            _commands.send(ReaderCommand.NextPage)
        }
    }

    fun toggleBackgroundSound(sound: BackgroundSound) {
        if (_activeSound.value == sound) {
            stopBackgroundSound()
        } else {
            playBackgroundSound(sound)
        }
    }

    fun previewSound(sound: BackgroundSound) {
        viewModelScope.launch {
            preferencesRepository.saveLastSelectedSound(sound)
        }

        if (sound == BackgroundSound.NONE) {
            stopBackgroundSound()
            return
        }

        if (_activeSound.value != BackgroundSound.NONE) {
            playBackgroundSound(sound)
            return
        }

        backgroundSoundRepository.playPreview(sound, 1.0f)
    }

    fun stopPreview() {
        if (_activeSound.value == BackgroundSound.NONE) {
            backgroundSoundRepository.stop()
        }
    }

    private fun playBackgroundSound(sound: BackgroundSound) {
        backgroundSoundRepository.play(sound, 1.0f)
        _activeSound.value = sound
        if (sound != BackgroundSound.NONE) {
            viewModelScope.launch {
                preferencesRepository.saveLastSelectedSound(sound)
            }
        }
    }

    private fun stopBackgroundSound() {
        backgroundSoundRepository.stop()
        _activeSound.value = BackgroundSound.NONE
    }

    // Master switch for the ambient sound feature -- it never starts real playback (that's the
    // overlay sound button's job). Turning it on previews the selected sound, same as tapping its
    // card; turning it off stops anything playing or previewing.
    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.saveSoundEnabled(enabled)
        }
        if (enabled) {
            // Older builds could persist NONE via the removed "None" card; fall back to the default.
            val lastSound = readerPreferences.value.lastSelectedSound
                .takeIf { it != BackgroundSound.NONE } ?: BackgroundSound.RAIN
            previewSound(lastSound)
        } else {
            stopBackgroundSound()
        }
    }

    fun quickToggleSound() {
        if (!readerPreferences.value.soundEnabled) return
        // Older builds could persist NONE via the removed "None" card; fall back to the default.
        val lastSound = readerPreferences.value.lastSelectedSound
            .takeIf { it != BackgroundSound.NONE } ?: BackgroundSound.RAIN
        if (_activeSound.value == BackgroundSound.NONE) {
            toggleBackgroundSound(lastSound)
        } else {
            toggleBackgroundSound(_activeSound.value)
        }
    }

    private fun flattenToc(links: List<Link>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        fun recurse(list: List<Link>) {
            for (link in list) {
                link.title?.let { title ->
                    map[link.href.toString()] = title
                }
                recurse(link.children)
            }
        }
        recurse(links)
        return map
    }

    override fun onCleared() {
        saveReadingSession()
        backgroundSoundRepository.stop()
        super.onCleared()
        structureLease?.close(); structureLease = null; structure = null
        publication?.close()
    }

    private fun saveReadingSession() {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success && sessionStartTime > 0L) {
            val endTime = System.currentTimeMillis()
            val duration = endTime - sessionStartTime

            if (duration > 10_000L) {
                CoroutineScope(Dispatchers.IO).launch {
                    sessionRepository.insertSession(
                        bookId = currentState.bookId,
                        startTime = sessionStartTime,
                        endTime = endTime
                    )
                }
            }
        }
    }
}
