package com.yugentech.quill.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugentech.quill.aira.context.*
import com.yugentech.quill.reader.ui.components.aira.AiraPeekBar
import com.yugentech.quill.reader.viewmodel.quick.QuickUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

@RunWith(AndroidJUnit4::class)
class DiscussionUiTest {
    @get:Rule val compose = createComposeRule()
    private val locator = Locator(Url("one.xhtml")!!, MediaType.XHTML, text = Locator.Text(highlight = "Mara promised to return."))
    private val source = SourceExcerpt("S1", "The promise", "Mara promised to return.", locator.toJSON().toString())
    private val discussion = DiscussionContext("topic", "Conversation · The promise", "Keep your promise.",
        ReaderRequestContext(locator, locator), "CONVERSATION", "He is reminding her of the promise. [S1] [S999]", listOf(source))

    @Test fun closingAndReopeningPanelKeepsExplanationAndSourceJump() {
        val visible = mutableStateOf(true)
        var jumped: SourceExcerpt? = null
        compose.setContent { MaterialTheme {
            AiraPeekBar(visible.value, airaUiState = QuickUiState(response = discussion.lastResponse,
                discussion = discussion, sources = discussion.sources), onQuickAction = {}, onSendMessage = {},
                onClearSelection = {}, onDismiss = { visible.value = false }, onStop = {}, onSourceJump = { jumped = it })
        } }
        compose.mainClock.advanceTimeBy(4000)
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText(discussion.lastResponse!!).assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText(discussion.lastResponse!!).assertIsDisplayed()
        compose.onNodeWithText("Sources").performClick()
        compose.onNodeWithText("[S1] The promise").assertIsDisplayed()
        compose.onNodeWithText("[S999] The promise").assertDoesNotExist()
        compose.onNodeWithText("View in book").performClick()
        compose.runOnIdle { assertEquals(source, jumped) }
    }

    @Test fun newTopicAndContextExpansionHaveSeparateActions() {
        var expanded = false
        var reset = false
        compose.setContent { MaterialTheme {
            com.yugentech.quill.reader.ui.components.aira.DiscussionControls(discussion, discussion.sources, false,
                { reset = true }, { expanded = true })
        } }
        compose.onNodeWithText("Include more context").performClick()
        compose.runOnIdle { assertTrue(expanded); assertFalse(reset) }
        compose.onNodeWithText("New topic").performClick()
        compose.runOnIdle { assertTrue(reset) }
    }
}
