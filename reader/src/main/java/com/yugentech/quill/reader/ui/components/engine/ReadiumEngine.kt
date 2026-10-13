package com.yugentech.quill.reader.ui.components.engine
import com.yugentech.quill.aira.rag.locatorAtHref

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yugentech.quill.reader.ui.components.highlightSheet.luminance
import com.yugentech.quill.reader.viewmodel.reader.ReaderCommand
import com.yugentech.theme.service.HapticService
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.compose.koinInject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import kotlin.math.roundToInt

private fun Color.toCssRgba(): String {
    val r = (red * 255).roundToInt()
    val g = (green * 255).roundToInt()
    val b = (blue * 255).roundToInt()
    return "rgba($r, $g, $b, $alpha)"
}

private const val TOOLBAR_HEIGHT_DP = 66f

@OptIn(ExperimentalReadiumApi::class)
@Composable
fun ReadiumEngine(
    modifier: Modifier = Modifier,
    publication: Publication,
    structure: com.yugentech.quill.aira.rag.PublicationStructure? = null,
    bookId: String,
    isPro: Boolean = false,
    initialLocation: Locator?,
    targetJumpHref: String?,
    targetSeekProgress: Double?,
    targetLocator: Locator? = null,
    allPositions: List<Locator>,
    preferences: EpubPreferences,
    isAiraReady: Boolean = false,
    decorations: List<Decoration> = emptyList(),
    commands: Flow<ReaderCommand>? = null,
    onTap: () -> Unit,
    onAskAira: (String, Locator?) -> Unit = { _, _ -> },
    onSelectionAction: (Locator) -> Unit = {},
    rangeActionLabel: String = "Start here",
    hasRangeDraft: Boolean = false,
    onRangeSelection: (Locator) -> Unit = {},
    onDecorationTapped: (Decoration) -> Unit = {},
    onJumpComplete: () -> Unit,
    onSeekComplete: () -> Unit,
    onTargetLocatorComplete: () -> Unit = {},
    onLocatorChange: (Locator) -> Unit
) {
    val haptic = koinInject<HapticService>()
    val lifecycleOwner = LocalLifecycleOwner.current
    val fragmentTag = remember(bookId, preferences.scroll) {
        "readium_${bookId}_${if (preferences.scroll == true) "scroll" else "paged"}"
    }

    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    val cssColorString = remember(selectionColor) { selectionColor.toCssRgba() }

    // True when the ebook background is light (white/sepia/cream) so the toolbar can
    // switch to a more opaque style that stays visible on pale page backgrounds.
    val readerBgIsLight = remember(preferences.backgroundColor) {
        (preferences.backgroundColor?.let { Color(it.int) } ?: Color.White).luminance() > 0.5f
    }

    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var selectionInfo by remember { mutableStateOf<SelectionInfo?>(null) }
    var lastHapticText by remember { mutableStateOf<String?>(null) }
    var lastBridgeText by remember { mutableStateOf("") }
    var isClearingSelection by remember { mutableStateOf(false) }
    var clearNativeSelectionRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var toolbarY by remember { mutableStateOf(0.dp) }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    val scope = rememberCoroutineScope()
    val rangeDraftActive by rememberUpdatedState(hasRangeDraft)

    suspend fun clearSelection() {
        val nav = navigator ?: return
        if (isClearingSelection) return
        isClearingSelection = true
        
        selectionInfo = null
        lastBridgeText = ""
        
        try {
            // 1. Force-finish the native Android ActionMode to hide handles instantly.
            clearNativeSelectionRequest?.invoke()

            // 2. Use the navigator's built-in clearSelection and augment with JS to ensure
            // the bridge data and native selection are fully purged.
            nav.clearSelection()
            val clearJs = """
                (function() {
                    window.__quillSelData = null;
                    window.__quillSelChanging = false;
                    var sel = window.getSelection();
                    if (sel) {
                        if (sel.empty) sel.empty();
                        if (sel.removeAllRanges) sel.removeAllRanges();
                    }
                    if (document.activeElement && document.activeElement.blur) {
                        document.activeElement.blur();
                    }
                })()
            """.trimIndent()
            nav.evaluateJavascript(clearJs)
            // A brief delay then a second clear attempt handles cases where the
            // first call was ignored due to an ongoing handle animation.
            delay(100)
            nav.evaluateJavascript(clearJs)
        } catch (_: Exception) {}
        
        // Keep the flag active for a brief cooldown to ensure the polling loop 
        // doesn't catch stale data during the WebView's asynchronous clear process.
        delay(150)
        isClearingSelection = false
    }

    suspend fun syncSelection(jsonStr: String?) {
        val nav = navigator ?: return
        if (jsonStr == null || jsonStr == "null" || jsonStr == "undefined") {
            selectionInfo = null
            return
        }

        try {
            val obj = JSONObject(jsonStr)
            val text = obj.optString("text")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            val rects = obj.optJSONArray("rects")
            val original = nav.currentSelection()?.locator ?: nav.currentLocator.value
            val dom = obj.optJSONObject("domRange")
            val resource = structure?.resources?.firstOrNull {
                obj.optString("resource").endsWith(com.yugentech.quill.aira.rag.PublicationStructure.normalizeHref(it))
            }
            val base = if (resource != null) original.copy(href = Url(resource)!!) else original
            val locator = if (dom != null) Locator.fromJSON(base.toJSON().apply {
                getJSONObject("locations").put("domRange", dom)
                put("text", (optJSONObject("text") ?: JSONObject()).put("highlight", obj.optString("text")))
            }) else base

            if (rects != null && rects.length() > 0 && text.isNotEmpty()) {
                val first = rects.getJSONObject(0)
                val last = rects.getJSONObject(rects.length() - 1)
                
                // Haptic feedback removed here to avoid overlap with the bridge's 
                // zero-lag feedback. lastHapticText updated for consistency.
                lastHapticText = text

                selectionInfo = SelectionInfo(
                    text = text,
                    locator = locator,
                    rectTop = first.getDouble("top").toFloat(),
                    rectBottom = last.getDouble("bottom").toFloat()
                )
                toolbarY = computeToolbarY(selectionInfo!!.rectTop, selectionInfo!!.rectBottom, screenHeightDp)
            } else {
                selectionInfo = null
            }
        } catch (e: Exception) {
            selectionInfo = null
        }
    }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val hazeState = remember { HazeState() }

    Box(modifier = modifier.fillMaxSize()) {
        ReadiumFragmentHost(
            publication = publication,
            fragmentTag = fragmentTag,
            initialLocation = initialLocation,
            preferences = preferences,
            isPro = isPro,
            isAiraReady = isAiraReady,
            onAskAira = { text, locator ->
                onAskAira(text, locator)
                scope.launch { clearSelection() }
            },
            onHighlightRequest = { locator ->
                onSelectionAction(locator)
                scope.launch { clearSelection() }
            },
            onSelectionStarted = {
                // Haptics removed to avoid double-triggering with onSelectionChanged
            },
            onSelectionEnded = {
                lastHapticText = null
                lastBridgeText = ""
            },
            onSelectionChanged = { text ->
                if (text.isNotBlank() && text != lastBridgeText) {
                    haptic.performTickHaptic()
                    lastBridgeText = text
                }
            },
            onClearSelection = { request ->
                clearNativeSelectionRequest = request
            },
            onTap = {
                if (selectionInfo != null) {
                    scope.launch { clearSelection() }
                } else {
                    onTap()
                }
            },
            onNavigatorReady = { nav ->
                navigator = nav

                (nav as? DecorableNavigator)?.addDecorationListener(
                    "user_highlights",
                    object : DecorableNavigator.Listener {
                        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                            onDecorationTapped(event.decoration)
                            return true
                        }
                    }
                )
            },
            modifier = Modifier.hazeSource(hazeState)
        )

        val currentSelection = selectionInfo

        // Keep last non-null selection/position so the exit animation has content to render.
        var exitSelection by remember { mutableStateOf<SelectionInfo?>(null) }
        var exitToolbarY by remember { mutableStateOf(0.dp) }
        if (currentSelection != null) {
            exitSelection = currentSelection
            exitToolbarY = toolbarY
        }

        AnimatedVisibility(
            visible = currentSelection != null,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.88f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.88f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, exitToolbarY.roundToPx()) }
        ) {
            exitSelection?.let { sel ->
                SelectionToolbar(
                    rangeActionLabel = rangeActionLabel,
                    onRangeSelection = { sel.locator?.let(onRangeSelection); scope.launch { clearSelection() } },
                    selectionInfo = sel,
                    isAiraReady = isAiraReady,
                    hazeState = hazeState,
                    readerBgIsLight = readerBgIsLight,
                    onHighlight = {
                        sel.locator?.let { onSelectionAction(it) }
                        scope.launch { clearSelection() }
                    },
                    onAskAira = { text ->
                        onAskAira(text, sel.locator)
                        scope.launch { clearSelection() }
                    },
                    onCopy = { text ->
                        clipboardManager.setText(AnnotatedString(text))
                        scope.launch { clearSelection() }
                    },
                    onShare = { text ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, null))
                        scope.launch { clearSelection() }
                    }
                )
            }
        }
    }

    LaunchedEffect(decorations, navigator) {
        val decorableNav = navigator as? DecorableNavigator
        // Readium resumes after preparing decoration changes on a worker. Its WebView
        // updates must return to the Android UI dispatcher, including under test clocks.
        withContext(Dispatchers.Main.immediate) {
            decorableNav?.applyDecorations(decorations, "user_highlights")
        }
    }

    LaunchedEffect(commands, navigator) {
        val nav = navigator ?: return@LaunchedEffect
        commands?.collect { command ->
            when (command) {
                ReaderCommand.NextPage -> nav.goForward(animated = true)
                ReaderCommand.PreviousPage -> nav.goBackward(animated = true)
            }
        }
    }

    LaunchedEffect(navigator, initialLocation) {
        val nav = navigator ?: return@LaunchedEffect
        val initial = initialLocation ?: return@LaunchedEffect
        // Saved readings and excerpt/range jumps already carry exact text locations.
        // Only fragment-only chapter anchors need this additional font-layout retry.
        if (initial.locations.fragments.isEmpty() || initial.text.highlight != null) return@LaunchedEffect
        // A bundled font can reflow columns after Readium's initial fragment jump.
        // Restore the exact locator once the initial resource and its fonts are ready.
        withContext(Dispatchers.Main.immediate) {
            val resource = com.yugentech.quill.aira.rag.PublicationStructure.normalizeHref(initial.href.toString())
            val ready = withTimeoutOrNull(5000) {
                while (!nav.isResumed || nav.evaluateJavascript("decodeURIComponent(location.pathname).endsWith(${JSONObject.quote(resource)}) && (!document.fonts || document.fonts.status === 'loaded')") != "true") delay(100)
                delay(100) // Let the font's final column layout reach Readium.
                true
            } == true
            if (ready && !rangeDraftActive && selectionInfo == null && nav.currentSelection() == null &&
                com.yugentech.quill.aira.rag.PublicationStructure.normalizeHref(nav.currentLocator.value.href.toString()) == resource) {
                nav.go(initial, animated = false)
            }
        }
    }

    LaunchedEffect(targetLocator, navigator) {
        val nav = navigator ?: return@LaunchedEffect
        targetLocator?.let { loc ->
            val smoothScrollJs = """
                (function() {
                    document.documentElement.style.scrollBehavior = 'smooth';
                    document.body.style.scrollBehavior = 'smooth';
                })();
            """.trimIndent()

            try { nav.evaluateJavascript(smoothScrollJs) } catch (e: Exception) {}

            delay(50)
            nav.go(loc, animated = false)
            delay(400)

            val resetJs = """
                (function() {
                    document.documentElement.style.scrollBehavior = 'auto';
                    document.body.style.scrollBehavior = 'auto';
                })();
            """.trimIndent()

            try { nav.evaluateJavascript(resetJs) } catch (e: Exception) {}

            onTargetLocatorComplete()
        }
    }

    LaunchedEffect(preferences, navigator) {
        val nav = navigator ?: return@LaunchedEffect
        // Inject the new background colour directly into the WebView before Readium's
        // own preference update reaches the renderer. This closes the timing gap that
        // causes a "two-piece" flicker in paged mode: the Compose outer Box repaints
        // instantly while the WebView normally waits for the Readium CSS pipeline.
        preferences.backgroundColor?.let { readiumColor ->
            try {
                val cssColor = Color(readiumColor.int).toCssRgba()
                nav.evaluateJavascript(
                    "(function(){var c='$cssColor';var e=document.documentElement,b=document.body;" +
                        "if(e)e.style.backgroundColor=c;if(b)b.style.backgroundColor=c;})();"
                )
            } catch (_: Exception) {}
        }
        nav.submitPreferences(preferences)
    }

    LaunchedEffect(targetJumpHref, navigator) {
        val nav = navigator ?: return@LaunchedEffect
        targetJumpHref?.let { href ->
            publication.locatorAtHref(href)?.let { nav.go(it, animated = false) }
            onJumpComplete()
        }
    }

    LaunchedEffect(targetSeekProgress, navigator) {
        val nav = navigator ?: return@LaunchedEffect
        targetSeekProgress?.let { progress ->
            if (allPositions.isNotEmpty()) {
                val index = (progress * (allPositions.size - 1)).roundToInt()
                    .coerceIn(0, allPositions.size - 1)
                nav.go(allPositions[index], animated = false)
            }
            onSeekComplete()
        }
    }

    // Polling Loop for Selection Info (Transparent native selection tracking)
    LaunchedEffect(navigator) {
        val nav = navigator ?: return@LaunchedEffect

        while (true) {
            delay(100)
            val json = try {
                nav.evaluateJavascript("""
                    (function() {
                        // Handles are being dragged — don't update the toolbar position yet.
                        if (window.__quillSelChanging) return null;

                        var sel = window.getSelection();
                        var liveActive = sel && !sel.isCollapsed && sel.rangeCount > 0 && sel.toString();
                        if (liveActive) {
                            var range = sel.getRangeAt(0);
                            var rects = range.getClientRects();
                            var filteredRects = [];
                            var pageTol = window.innerWidth * 0.1;
                            for (var i = 0; i < rects.length; i++) {
                                var r = rects[i];
                                if (r.left >= -pageTol && r.left < window.innerWidth + pageTol) {
                                    filteredRects.push({top: r.top, bottom: r.bottom, left: r.left, right: r.right});
                                }
                            }
                            // Fallback: focus is always on the current page
                            if (filteredRects.length === 0 && sel.focusNode) {
                                try {
                                    var fr = document.createRange();
                                    var fo = Math.min(sel.focusOffset, sel.focusNode.nodeType === 3 ? sel.focusNode.length : sel.focusNode.childNodes.length);
                                    fr.setStart(sel.focusNode, fo);
                                    fr.collapse(true);
                                    var fRect = fr.getBoundingClientRect();
                                    if (fRect.height > 0) {
                                        filteredRects.push({top: fRect.top, bottom: fRect.bottom, left: fRect.left, right: fRect.right});
                                    }
                                } catch(e) {}
                            }
                            if (filteredRects.length > 0) {
                                var liveData = JSON.stringify({ text: sel.toString(), rects: filteredRects,
                                    domRange: window.__quillCaptureDomRange ? window.__quillCaptureDomRange(range) : null,
                                    resource: decodeURIComponent(location.pathname) });
                                window.__quillSelData = liveData;
                                return liveData;
                            }
                        }
                        // If live selection is gone or produced no rects, use stored capture from
                        // selectionchange — handles the case where Readium collapsed the selection
                        // before the poll ran (cross-page anchor on a continuation paragraph).
                        var captured = window.__quillSelData;
                        return (typeof captured === 'string') ? captured : JSON.stringify(captured) || null;
                    })()
                """.trimIndent())
            } catch (_: Exception) { null }

            val processedJson = json?.let { value ->
                if (value.startsWith('"')) runCatching { org.json.JSONTokener(value).nextValue() as? String }.getOrNull()
                else value
            }

            if (isClearingSelection) {
                // Ignore poll results while a manual clear is in progress
                continue
            }

            if (processedJson == null || processedJson == "null" || processedJson == "undefined" || processedJson == "{}") {
                selectionInfo = null
            } else if (selectionInfo == null) {
                // JS debounce already held back data while handles were moving;
                // show the toolbar immediately on the first stable result.
                syncSelection(processedJson)
            } else {
                // Selection exists, update position if it moved slightly (e.g. page resize)
                // but usually syncSelection will be called by stable logic.
            }
        }
    }

    LaunchedEffect(navigator, cssColorString) {
        val nav = navigator ?: return@LaunchedEffect

        suspend fun injectSelectionStyles() {
            // Restore the native selection highlight for zero-lag visual feedback.
            // We use the CSS ::selection selector to apply our brand color snappy.
            val bgColor = cssColorString
            val js = """
                (function() {
                    var id = 'quill-selection-style';
                    var existing = document.getElementById(id);
                    if (existing) {
                        existing.textContent = '::selection { background-color: $bgColor !important; }';
                        return;
                    }
                    var s = document.createElement('style');
                    s.id = id;
                    s.textContent = '::selection { background-color: $bgColor !important; }';
                    document.head.appendChild(s);
                })();
            """.trimIndent()

            try {
                nav.evaluateJavascript(js)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        suspend fun injectSelectionHandler() {
            val js = """
                (function() {
                    var scrollEl = document.scrollingElement || document.documentElement;

                    // Clear stored selection data on every page navigation so stale data
                    // from the previous page never leaks into the new page's toolbar.
                    window.__quillSelData = null;

                    // Always refresh page-start fallback on every page render/navigation.
                    // Prefer a node whose offset-0 is on the current page (not a continuation from the
                    // previous column), because Readium resolves the locator from the node start.
                    // If all visible text is a continuation, fall back to the caret position.
                    (function() {
                        var tol = window.innerWidth * 0.1;
                        function nodeStartOnPage(node) {
                            if (!node || node.nodeType !== 3) return false;
                            try {
                                var r = document.createRange();
                                r.setStart(node, 0); r.collapse(true);
                                var rect = r.getBoundingClientRect();
                                return rect.left > -tol && rect.left < window.innerWidth + tol;
                            } catch(e) { return false; }
                        }
                        var freshNode = null, fallbackNode = null;
                        for (var ty = 4; ty < window.innerHeight && !freshNode; ty += 24) {
                            var r0 = document.caretRangeFromPoint(4, ty);
                            if (!r0) continue;
                            if (!fallbackNode) {
                                fallbackNode = { node: r0.startContainer, offset: r0.startOffset };
                            }
                            if (nodeStartOnPage(r0.startContainer)) {
                                freshNode = { node: r0.startContainer, offset: 0 };
                            }
                        }
                        var found = freshNode || fallbackNode;
                        if (found) {
                            window.__quillPageStartNode   = found.node;
                            window.__quillPageStartOffset = found.offset;
                        }
                    })();

                    window.__quillCaptureDomRange = function(range) {
                        function path(element) {
                            if (/^[A-Za-z][A-Za-z0-9_-]*$/.test(element.id) && document.querySelectorAll('#' + element.id).length === 1) return '#' + element.id;
                            var parts = [];
                            while (element && element.nodeType === 1) {
                                var tag = element.localName, index = 1, sibling = element.previousElementSibling;
                                while (sibling) { if (sibling.localName === tag) index++; sibling = sibling.previousElementSibling; }
                                parts.unshift(tag + ':nth-of-type(' + index + ')');
                                if (tag === 'body') break;
                                element = element.parentElement;
                            }
                            return parts.join(' > ');
                        }
                        function point(node, offset, end) {
                            if (node.nodeType !== 3) {
                                var child = node.childNodes[end ? offset - 1 : offset];
                                if (!child) return null;
                                if (child.nodeType === 3) { node = child; offset = end ? child.length : 0; }
                                else {
                                    var walker = document.createTreeWalker(child, NodeFilter.SHOW_TEXT), found = walker.nextNode(), last = found;
                                    if (end) { while (found) { last = found; found = walker.nextNode(); } found = last; }
                                    if (!found) return null;
                                    node = found; offset = end ? found.length : 0;
                                }
                            }
                            var parent = node.parentElement;
                            return {cssSelector:path(parent), textNodeIndex:Array.from(parent.childNodes).filter(function(n){return n.nodeType === 3;}).indexOf(node), charOffset:offset};
                        }
                        var start = point(range.startContainer, range.startOffset, false), end = point(range.endContainer, range.endOffset, true);
                        return start && end ? {start:start, end:end} : null;
                    };
                    if (window.__quillScrollLockInstalled) return;
                    window.__quillScrollLockInstalled = true;
                    window.__quillSelectionActive = false;
                    window.__quillSelChanging = false;

                    var lockedScrollLeft = 0;
                    var rafId            = null;
                    var selChangeTimer   = null;
                    var lastCapture      = 0;
                    var lastBridgeText   = "";

                    // Use the raw descriptor to bypass any overrides and avoid triggering
                    // Readium's own scroll listeners when we restore position.
                    var sd = Object.getOwnPropertyDescriptor(Element.prototype,    'scrollLeft')
                          || Object.getOwnPropertyDescriptor(HTMLElement.prototype,'scrollLeft');
                    function getScroll() { return sd ? sd.get.call(scrollEl) : scrollEl.scrollLeft; }
                    function setScroll(v) { if (sd && sd.set) sd.set.call(scrollEl, v); else scrollEl.scrollLeft = v; }

                    function rafGuard() {
                        if (!window.__quillSelectionActive) { rafId = null; return; }
                        if (Math.abs(getScroll() - lockedScrollLeft) > 50) setScroll(lockedScrollLeft);
                        rafId = requestAnimationFrame(rafGuard);
                    }

                    document.addEventListener('selectionchange', function() {
                        var sel = window.getSelection();

                        // ── Selection ended ──────────────────────────────────────────────────
                        if (!sel || sel.isCollapsed || sel.rangeCount === 0 || !sel.toString().trim()) {
                            window.__quillSelData = null;
                            window.__quillSelChanging = false;
                            lastBridgeText = "";
                            if (selChangeTimer) { clearTimeout(selChangeTimer); selChangeTimer = null; }
                            if (window.__quillSelectionActive) {
                                window.__quillSelectionActive = false;
                            }
                            return;
                        }

                        // ── Selection started ─────────────────────────────────────────────────
                        if (!window.__quillSelectionActive) {
                            lockedScrollLeft = getScroll();
                            window.__quillSelectionActive = true;
                            if (!rafId) rafId = requestAnimationFrame(rafGuard);
                        }

                        // ── Bridge Trigger (Zero-lag Haptics) ───────────────────────────────
                        var text = sel.toString();
                        if (text && text !== lastBridgeText) {
                            lastBridgeText = text;
                            // Check if the bridge is available before calling
                            if (window.quillSelection && window.quillSelection.onTextChange) {
                                window.quillSelection.onTextChange(text);
                            }
                        }

                        // ── Capture visible rects for the polling loop (throttled) ───────────
                        // getClientRects() forces a synchronous layout flush. Calling it on
                        // every selectionchange (dozens per second during a handle drag) causes
                        // layout thrashing that stalls the renderer and produces text flickering.
                        // One flush per 100 ms keeps __quillSelData fresh for the fallback.
                        var now = Date.now();
                        if (now - lastCapture >= 100) {
                            lastCapture = now;
                            try {
                                var capRange = sel.getRangeAt(0);
                                var capRects = capRange.getClientRects();
                                var capTol = window.innerWidth * 0.1;
                                var capVis = [];
                                for (var ci = 0; ci < capRects.length; ci++) {
                                    var cr = capRects[ci];
                                    if (cr.left >= -capTol && cr.left < window.innerWidth + capTol) {
                                        capVis.push({top: cr.top, bottom: cr.bottom, left: cr.left, right: cr.right});
                                    }
                                }
                                // Focus is always on the current page — use it as fallback rect
                                if (capVis.length === 0 && sel.focusNode) {
                                    var cfr2 = document.createRange();
                                    var cfo2 = Math.min(sel.focusOffset, sel.focusNode.nodeType === 3 ? sel.focusNode.length : sel.focusNode.childNodes.length);
                                    cfr2.setStart(sel.focusNode, cfo2); cfr2.collapse(true);
                                    var cfRect2 = cfr2.getBoundingClientRect();
                                    if (cfRect2.height > 0) capVis.push({top: cfRect2.top, bottom: cfRect2.bottom, left: cfRect2.left, right: cfRect2.right});
                                }
                                if (capVis.length > 0) {
                                    window.__quillSelData = {text: sel.toString(), rects: capVis,
                                        domRange: window.__quillCaptureDomRange(capRange), resource: decodeURIComponent(location.pathname)};
                                }
                            } catch(capErr) {}
                        }

                        // ── Debounce: mark selection as actively changing ──────────────────────
                        // The toolbar is only shown once this flag clears (300 ms of no changes).
                        window.__quillSelChanging = true;
                        if (selChangeTimer) clearTimeout(selChangeTimer);
                        selChangeTimer = setTimeout(function() {
                            window.__quillSelChanging = false;
                        }, 300);
                    });
                })();
            """.trimIndent()
            try { nav.evaluateJavascript(js) } catch (_: Exception) {}
        }

        injectSelectionStyles()
        injectSelectionHandler()

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            nav.currentLocator.collectLatest { locator ->
                injectSelectionStyles()
                injectSelectionHandler()
                delay(120)
                onLocatorChange(structure?.let { ReaderLocatorTracker.capture(nav, locator, it) } ?: locator)
            }
        }
    }
}

private fun computeToolbarY(rectTop: Float, rectBottom: Float, screenHeight: Float): Dp {
    // Require 50dp of breathing room beyond the toolbar height before placing above/below.
    // If the available space is even slightly marginal, overlay over the selection instead.
    val comfortThreshold = TOOLBAR_HEIGHT_DP + 50f
    val gapAbove = 8f
    val gapBelow = 4f
    val hasRoomAbove = rectTop >= comfortThreshold
    val hasRoomBelow = (screenHeight - rectBottom) >= comfortThreshold
    return when {
        hasRoomAbove -> (rectTop - TOOLBAR_HEIGHT_DP - gapAbove).dp
        hasRoomBelow -> (rectBottom + gapBelow).dp
        // Not enough comfortable space above or below: overlay over the selection centre
        else -> ((rectTop + rectBottom - TOOLBAR_HEIGHT_DP) / 2f)
            .coerceIn(gapAbove, screenHeight - TOOLBAR_HEIGHT_DP - gapAbove).dp
    }
}
