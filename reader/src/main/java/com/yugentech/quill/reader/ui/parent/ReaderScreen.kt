package com.yugentech.quill.reader.ui.parent

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yugentech.quill.reader.sound.model.BackgroundSound
import com.yugentech.quill.reader.ui.components.highlightSheet.HighlightSheet
import com.yugentech.quill.reader.viewmodel.quick.QuickViewModel
import com.yugentech.quill.reader.settings.model.ReaderSettings
import com.yugentech.quill.reader.ui.components.engine.ReaderDefaults
import com.yugentech.quill.reader.ui.components.engine.ReadiumEngine
import com.yugentech.quill.reader.ui.components.overlay.parent.ReaderAction
import com.yugentech.quill.reader.ui.components.overlay.parent.ReaderMenuOverlay
import com.yugentech.quill.reader.ui.components.settingsSheet.SettingsSheet
import com.yugentech.quill.reader.ui.components.soundSheet.SoundSelectionSheet
import com.yugentech.quill.reader.ui.components.tocSheet.TocSheet
import com.yugentech.quill.reader.viewmodel.reader.ReaderUiState
import com.yugentech.quill.reader.viewmodel.reader.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.androidx.compose.koinViewModel
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

@OptIn(ExperimentalReadiumApi::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    uiState: ReaderUiState,
    onBackClick: () -> Unit,
    preferences: ReaderSettings,
    statusBarHeight: Dp = 0.dp,
    onPreferencesChange: (EpubPreferences) -> Unit,
    onLocatorChange: (Locator) -> Unit,
    onMenuVisibilityChange: (Boolean) -> Unit = {},
    returnLocator: Locator? = null
) {
    when (uiState) {
        is ReaderUiState.Idle -> Unit
        is ReaderUiState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Error: ${uiState.message}", color = Color.Red)
            }
        }

        is ReaderUiState.Success -> ReaderSuccess(
            viewModel = viewModel,
            state = uiState,
            onBackClick = onBackClick,
            preferences = preferences,
            statusBarHeight = statusBarHeight,
            onPreferencesChange = onPreferencesChange,
            onLocatorChange = onLocatorChange,
            onMenuVisibilityChange = onMenuVisibilityChange,
            returnLocator = returnLocator
        )
    }
}

@OptIn(ExperimentalReadiumApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSuccess(
    viewModel: ReaderViewModel,
    state: ReaderUiState.Success,
    onBackClick: () -> Unit,
    preferences: ReaderSettings,
    statusBarHeight: Dp,
    onPreferencesChange: (EpubPreferences) -> Unit,
    onLocatorChange: (Locator) -> Unit,
    onMenuVisibilityChange: (Boolean) -> Unit,
    returnLocator: Locator?
) {
    val quickViewModel: QuickViewModel = koinViewModel()
    val airaUiState by quickViewModel.uiState.collectAsState()
    airaUiState.clarification?.let {
        com.yugentech.quill.reader.ui.components.aira.ScopeClarificationDialog(it, quickViewModel::chooseScope, quickViewModel::dismissClarification)
    }
    val isPro = airaUiState.isPro
    val isReady = airaUiState.isReady

    val screenState = rememberReaderScreenState(
        publication = state.publication,
        allPositions = state.allPositions,
        totalPages = state.totalPages,
        initialLocator = state.initialLocator
    )

    val dbHighlights by viewModel.highlights.collectAsState()
    val rangeState by viewModel.rangeState.collectAsState()
    LaunchedEffect(state.bookId, returnLocator) {
        if (returnLocator != null) {
            screenState.searchOrigin = returnLocator
            screenState.canReturnFromSearch = true
        }
    }
    val bookmarks by viewModel.bookmarks.collectAsState()
    val currentKey = screenState.currentLocator?.let { state.structure.locationKey(it) }
    val isBookmarked = bookmarks.any { it.locationKey == currentKey }
    val bookmarkSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val bookmarkScope = androidx.compose.runtime.rememberCoroutineScope()

    val activeDecorations = remember(dbHighlights) {
        dbHighlights.flatMap { entity ->
                com.yugentech.quill.aira.rag.TextRangeResolver.segments(entity.segmentsJson, entity.locatorJson).mapIndexed { index, locator ->
                    Decoration(
                        id = "${entity.id}::segment:$index",
                        locator = locator,
                        style = Decoration.Style.Highlight(tint = entity.colorInt),
                        extras = mapOf("highlightId" to entity.id)
                    )
                }
        }
    }

    var pendingHighlightLocator by remember { mutableStateOf<Locator?>(null) }
    val sheetState = rememberModalBottomSheetState()

    var highlightToDelete by remember { mutableStateOf<Decoration?>(null) }
    LaunchedEffect(screenState.searchMatch) {
        if (screenState.searchMatch != null) { delay(8000); screenState.searchMatch = null }
    }

    LaunchedEffect(state.bookId) {
        quickViewModel.observeDiscussion(state.bookId)
        quickViewModel.observeIndexingStatus(state.bookId)
    }

    LaunchedEffect(screenState.isMenuVisible, screenState.showAiraPeek) {
        onMenuVisibilityChange(screenState.isMenuVisible || screenState.showAiraPeek)
    }

    LaunchedEffect(
        screenState.isMenuVisible,
        screenState.isBrightnessInteracting,
        screenState.isScrubbing,
        screenState.showAiraPeek
    ) {
        if (screenState.isMenuVisible && !screenState.isBrightnessInteracting && !screenState.isScrubbing && !screenState.showAiraPeek) {
            delay(4000L)
            screenState.isMenuVisible = false
        }
    }

    val isPagedMode = preferences.epub.scroll == false

    // --- MODE TRANSITION ORCHESTRATION ---
    var engineScrollMode by remember { mutableStateOf(preferences.epub.scroll ?: true) }
    var isTransitioningMode by remember { mutableStateOf(true) }

    LaunchedEffect(preferences.epub.scroll) {
        val newScroll = preferences.epub.scroll ?: true
        if (newScroll != engineScrollMode) {
            isTransitioningMode = true
            delay(350) // Wait for fade out
            engineScrollMode = newScroll
            delay(1150) // More generous buffer for engine layout change and scroll snap
            isTransitioningMode = false
        } else {
            // Initial load case: wait for the engine to settle before revealing
            // Scroll mode (vertical) usually needs more time to calculate layout and snap
            val initialDelay = if (newScroll) 1400L else 800L
            delay(initialDelay)
            isTransitioningMode = false
        }
    }

    val readerAlpha by animateFloatAsState(
        targetValue = if (isTransitioningMode) 0f else 1f,
        animationSpec = tween(300),
        label = "ReaderAlpha"
    )

    val targetBgColor = Color(
        preferences.epub.backgroundColor?.int
            ?: ReaderDefaults.getPreferences().backgroundColor!!.int
    )
    val animatedBgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = if (isPagedMode) snap() else tween(300),
        label = "ReaderBg"
    )

    // acknowledgedBgColor is updated after a delay; while it differs from targetBgColor the
    // overlay is active. Because the comparison is done IN composition (not in a LaunchedEffect)
    // the overlay becomes visible in the SAME frame where the colour changes — eliminating
    // the 1-frame two-piece flash that a LaunchedEffect-driven approach would still produce.
    var acknowledgedBgColor by remember { mutableStateOf(targetBgColor) }
    val themeOverlayOn = acknowledgedBgColor != targetBgColor
    LaunchedEffect(targetBgColor) {
        delay(120)
        acknowledgedBgColor = targetBgColor
    }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (themeOverlayOn) 1f else 0f,
        animationSpec = if (themeOverlayOn) snap() else tween(180),
        label = "ThemeOverlay"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedBgColor)
    ) {
        // Use a fixed padding in Paged mode to avoid shifting when system bars are toggled
        val engineModifier = if (engineScrollMode == false) Modifier.padding(top = statusBarHeight) else Modifier

        Box(modifier = Modifier.fillMaxSize().alpha(readerAlpha)) {
            ReadiumEngine(
                modifier = engineModifier,
                publication = state.publication,
                structure = state.structure,
                bookId = state.bookId,
                initialLocation = screenState.currentLocator ?: state.initialLocator,
                targetJumpHref = screenState.targetJumpHref,
                targetSeekProgress = screenState.pendingSeekProgress,
                targetLocator = screenState.targetLocator,
                allPositions = state.allPositions,
                preferences = preferences.epub.copy(scroll = engineScrollMode),
                commands = viewModel.commands,
                isPro = isPro,
                isAiraReady = true,
                decorations = activeDecorations + listOfNotNull(screenState.searchMatch?.let {
                    Decoration("search-match", it, Decoration.Style.Highlight(tint = android.graphics.Color.rgb(255, 193, 7)))
                }),
                onTap = { screenState.toggleMenu() },
                onAskAira = { text, locator ->
                    screenState.showAira(text, locator)
                    quickViewModel.selectPassage(state.bookId, text,
                        com.yugentech.quill.aira.context.ReaderRequestContext(screenState.currentLocator, locator))
                },
                onSelectionAction = { locator ->
                    pendingHighlightLocator = locator
                },
                rangeActionLabel = if (rangeState.start == null || rangeState.adjustingStart) "Start here" else "End here",
                hasRangeDraft = rangeState.start != null || rangeState.end != null,
                onRangeSelection = { locator -> screenState.dismissAira(); viewModel.selectRangeEndpoint(locator) },
                onDecorationTapped = { tappedDecoration ->
                    if (tappedDecoration.id != "search-match") highlightToDelete = tappedDecoration
                },
                onJumpComplete = { screenState.targetJumpHref = null },
                onSeekComplete = { screenState.pendingSeekProgress = null },
                onTargetLocatorComplete = { screenState.targetLocator = null },
                onLocatorChange = { newLocator ->
                    screenState.handleLocatorChange(newLocator)
                    onLocatorChange(newLocator)
                }
            )
        }

        // --- NIGHT LIGHT OVERLAY ---
        if (preferences.nightLight) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFE58F00).copy(alpha = 0.15f))
                    .zIndex(0.5f)
            )
        }

        ReaderMenuOverlay(
            modifier = Modifier.zIndex(1f),
            isPro = isPro,
            isVisible = screenState.isMenuVisible || screenState.showAiraPeek,
            showBottomControls = !screenState.showAiraPeek,
            showAiraPeek = screenState.showAiraPeek,
            readerOverlayState = screenState.overlayState,
            airaUiState = airaUiState,
            currentSound = viewModel.activeSound.collectAsState().value,
            isBookmarked = isBookmarked,
            lastSelectedSound = preferences.lastSelectedSound,
            soundEnabled = preferences.soundEnabled,
            onAction = { action ->
                when (action) {
                    is ReaderAction.OnBackClick -> onBackClick()
                    is ReaderAction.OnSettingsClick -> {
                        screenState.isMenuVisible = false
                        screenState.showSettingsSheet = true
                    }

                    is ReaderAction.OnTocClick -> {
                        screenState.isMenuVisible = false
                        screenState.showTocSheet = true
                    }

                    is ReaderAction.OnSoundClick -> {
                        screenState.isMenuVisible = false
                        screenState.showSoundSheet = true
                    }
                    is ReaderAction.OnSearchClick -> {
                        screenState.searchOrigin = screenState.currentLocator
                        screenState.canReturnFromSearch = false
                        screenState.showSearchSheet = true
                        screenState.isMenuVisible = false
                    }
                    is ReaderAction.OnBookmarkClick -> screenState.currentLocator?.let { locator ->
                        val existing = bookmarks.firstOrNull { it.locationKey == currentKey }
                        if (existing == null) viewModel.toggleBookmark(state.bookId, locator)
                        else {
                            viewModel.removeBookmark(existing)
                            bookmarkScope.launch {
                                if (bookmarkSnackbar.showSnackbar("Bookmark removed", "Undo", duration = androidx.compose.material3.SnackbarDuration.Short) == androidx.compose.material3.SnackbarResult.ActionPerformed)
                                    viewModel.restoreBookmark(existing)
                            }
                        }
                    }

                    is ReaderAction.OnSeek -> {
                        screenState.isExplicitJump = true
                        screenState.pendingSeekProgress = action.progress.toDouble()
                    }

                    is ReaderAction.OnScrubStart -> screenState.isScrubbing = true
                    is ReaderAction.OnScrubEnd -> screenState.isScrubbing = false
                    is ReaderAction.OnBrightnessInteraction -> screenState.isBrightnessInteracting =
                        action.isInteracting

                    is ReaderAction.OnAskAiraClick -> screenState.showAira(null)
                    is ReaderAction.OnNewTopic -> { screenState.selectedText = null; screenState.selectedTextLocator = null; quickViewModel.newTopic(state.bookId) }
                    is ReaderAction.OnSourceJump -> {
                        com.yugentech.quill.aira.context.parseLocator(action.source.locatorJson)?.let {
                            screenState.searchOrigin = screenState.currentLocator
                            screenState.isExplicitJump = true; screenState.targetLocator = it
                            screenState.canReturnFromSearch = screenState.searchOrigin != null
                            screenState.dismissAira()
                        }
                    }
                    is ReaderAction.OnAiraDismiss -> {
                        screenState.dismissAira()
                    }

                    is ReaderAction.OnAiraSend -> quickViewModel.ask(state.bookId, action.question, screenState.selectedText,
                        com.yugentech.quill.aira.context.ReaderRequestContext(screenState.currentLocator, screenState.selectedTextLocator))
                    is ReaderAction.OnQuickAction -> quickViewModel.handleQuickPrompt(
                        state.bookId,
                        when (val prompt = action.prompt) {
                            is com.yugentech.quill.aira.chat.quickChat.prompt.QuickPrompt.SummarizeChapter -> prompt.copy(locatorJson = screenState.currentLocator?.toJSON()?.toString())
                            is com.yugentech.quill.aira.chat.quickChat.prompt.QuickPrompt.WhatIsThis -> prompt.copy(locatorJson = screenState.currentLocator?.toJSON()?.toString())
                            else -> prompt
                        },
                        com.yugentech.quill.aira.context.ReaderRequestContext(screenState.currentLocator, screenState.selectedTextLocator)
                    )

                    is ReaderAction.OnStopGeneration -> quickViewModel.stopGeneration()
                    is ReaderAction.OnClearSelection -> { screenState.selectedText = null; screenState.selectedTextLocator = null }
                    is ReaderAction.OnSoundQuickToggle -> {
                        viewModel.quickToggleSound()
                    }
                }
            }
        )

        androidx.compose.material3.SnackbarHost(bookmarkSnackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).zIndex(3f))
        if (rangeState.start != null && rangeState.range == null) {
            com.yugentech.quill.reader.ui.components.range.RangeSelectionBar(rangeState, viewModel::cancelRange, viewModel::adjustRangeStart,
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 80.dp).zIndex(3f))
        }
        if (screenState.canReturnFromSearch) {
            androidx.compose.foundation.layout.Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp).zIndex(2f)) {
                androidx.compose.material3.FilledTonalButton(onClick = {
                    screenState.isExplicitJump = true; screenState.targetLocator = screenState.searchOrigin
                    screenState.canReturnFromSearch = false; screenState.searchMatch = null
                }) { Text("Return to reading") }
                androidx.compose.material3.TextButton(onClick = { screenState.canReturnFromSearch = false }) { Text("Dismiss") }
            }
        }
        // Theme-change overlay: drawn on top of everything (zIndex 2) so it covers both
        // the status-bar strip (Compose) and the WebView area during the rendering gap.
        if (overlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(targetBgColor.copy(alpha = overlayAlpha))
                    .zIndex(2f)
            )
        }
    }

    if (pendingHighlightLocator != null) {
        HighlightSheet(
            sheetState = sheetState,
            onDismiss = { pendingHighlightLocator = null },
            onSave = { colorInt ->
                val locator = pendingHighlightLocator!!

                viewModel.addHighlight(
                    bookId = state.bookId,
                    locatorJson = locator.toJSON().toString(),
                    colorInt = colorInt
                )

                pendingHighlightLocator = null
            }
        )
    }

    if (rangeState.range != null) {
        com.yugentech.quill.reader.ui.components.range.RangePreview(rangeState, viewModel::cancelRange,
            viewModel::adjustRangeStart, viewModel::adjustRangeEnd,
            onExplain = { range ->
                val request = com.yugentech.quill.aira.context.ReaderRequestContext(screenState.currentLocator, range.startLocator,
                    endLocator = range.endLocator)
                screenState.showAira(range.text, range.startLocator)
                quickViewModel.selectPassage(state.bookId, range.text, request)
                  quickViewModel.handleQuickPrompt(state.bookId,
                      com.yugentech.quill.aira.chat.quickChat.prompt.QuickPrompt.ExplainThis(range.text), request)
                viewModel.cancelRange()
            }, onSave = { viewModel.saveRangeHighlight(state.bookId) }, onNote = viewModel::setRangeNote, onColor = viewModel::setRangeColor)
    }
    if (highlightToDelete != null) {
        AlertDialog(
            onDismissRequest = { highlightToDelete = null },
            title = {
                Text(
                    text = "Delete Highlight",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove this highlight? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteHighlight(highlightToDelete!!.extras["highlightId"] as? String ?: highlightToDelete!!.id)
                        highlightToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { highlightToDelete = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (screenState.showTocSheet) {
        TocSheet(
            structure = state.structure,
            currentLocator = screenState.currentLocator,
            bookmarks = bookmarks,
            onBookmarkJump = { bookmark ->
                com.yugentech.quill.reader.ui.components.bookmark.bookmarkLocator(bookmark)?.let {
                    screenState.isExplicitJump = true; screenState.targetLocator = it; screenState.showTocSheet = false
                }
            },
            onBookmarkRename = viewModel::renameBookmark,
            onBookmarkRemove = viewModel::removeBookmark,
            onBookmarkRestore = viewModel::restoreBookmark,
            onDismiss = { screenState.showTocSheet = false },
            onTocItemClick = { href ->
                screenState.isExplicitJump = true
                screenState.targetJumpHref = href
                screenState.showTocSheet = false
            }
        )
    }

    if (screenState.showSearchSheet) {
        com.yugentech.quill.reader.ui.components.search.SearchSheet(state.publication, state.structure, screenState.searchOrigin,
            onResult = { locator ->
                screenState.isExplicitJump = true; screenState.targetLocator = locator
                screenState.searchMatch = locator; screenState.showSearchSheet = false
                screenState.canReturnFromSearch = screenState.searchOrigin != null
            }, onDismiss = { screenState.showSearchSheet = false })
    }
    if (screenState.showSoundSheet) {
        val activeSound by viewModel.activeSound.collectAsState()
        val selectedSound = if (activeSound != BackgroundSound.NONE) activeSound else preferences.lastSelectedSound

        SoundSelectionSheet(
            selectedSound = selectedSound,
            isSoundOn = preferences.soundEnabled,
            autoPlayEnabled = preferences.autoPlaySound,
            onSoundEnabledChange = { viewModel.setSoundEnabled(it) },
            onSoundToggle = { viewModel.previewSound(it) },
            onAutoPlayChange = { viewModel.updateAutoPlaySound(it) },
            onDismiss = {
                viewModel.stopPreview()
                screenState.showSoundSheet = false
            }
        )
    }

    if (screenState.showSettingsSheet) {
        SettingsSheet(
            preferences = preferences,
            onPreferencesChange = {
                onPreferencesChange(it.copy(publisherStyles = false))
            },
            onVolumeNavigationChange = { viewModel.updateVolumeNavigation(it) },
            onNightLightChange = { viewModel.updateNightLight(it) },
            onDismiss = { screenState.showSettingsSheet = false }
        )
    }
}
