package com.yugentech.quill.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.rag.*
import com.yugentech.quill.reader.ui.components.range.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RangePreviewTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var file: File
    private lateinit var range: ResolvedTextRange
    @Before fun prepare() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "preview-${UUID.randomUUID()}.epub").also(::writeDiscussionFixture)
        val structure = EpubTextExtractor(context).structure(file.absolutePath)
        fun locator(resource: String, id: String, quote: String) = Locator(Url(resource)!!, MediaType.XHTML,
            locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#$id")), text = Locator.Text(highlight = quote))
        range = TextRangeResolver.resolve(structure,
            locator("OEBPS/one.xhtml", "unicode", "Café — 雨 🌙, said Mara."),
            locator("OEBPS/two.xhtml", "q1", "Mara and Ivo waited beside the harbor. Conversation line 1."))
    }
    @After fun cleanup() { file.delete() }

    @Test fun previewSavesOneRangeWithChosenColorAndNote() {
        val state = mutableStateOf(ReaderRangeState(range = range))
        var saved: ReaderRangeState? = null
        compose.setContent { MaterialTheme { RangePreview(state.value, {}, {}, {}, {}, { saved = state.value },
            { state.value = state.value.copy(note = it) }, { state.value = state.value.copy(colorInt = it) }) } }
        compose.onNodeWithText("Selected range").assertIsDisplayed()
        compose.onNodeWithText(range.text).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Remember this promise — 雨")
        compose.onNodeWithContentDescription("Highlight color 3").performScrollTo().performClick()
        compose.onNodeWithText("Save highlight").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(range, saved!!.range); assertEquals("Remember this promise — 雨", saved!!.note)
            assertEquals(0xFF90CAF9.toInt(), saved!!.colorInt)
        }
    }
    @Test fun previewCanExplainOrAdjustEitherEndpoint() {
        var explained: ResolvedTextRange? = null
        var adjustedStart = false
        var adjustedEnd = false
        compose.setContent { MaterialTheme { RangePreview(ReaderRangeState(range = range), {},
            { adjustedStart = true }, { adjustedEnd = true }, { explained = it }, {}, {}, {}) } }
        compose.onNodeWithText("Adjust start").performClick()
        compose.onNodeWithText("Adjust end").performClick()
        compose.onNodeWithText("Explain").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(adjustedStart); assertTrue(adjustedEnd); assertEquals(range, explained) }
    }
    @Test fun selectionBarShowsCorrectStepAndCorrectionMessage() {
        var canceled = false
        compose.setContent { MaterialTheme { RangeSelectionBar(ReaderRangeState(adjustingStart = true,
            error = "The end must come after the start."), { canceled = true }, {}) } }
        compose.onNodeWithText("Selecting a range · choose its start").assertIsDisplayed()
        compose.onNodeWithText("The end must come after the start.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(canceled) }
    }
}
