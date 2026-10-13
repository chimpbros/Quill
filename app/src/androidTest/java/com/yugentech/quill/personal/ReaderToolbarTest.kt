package com.yugentech.quill.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugentech.quill.reader.ui.components.overlay.components.topBar.ReaderTopBar
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderToolbarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openOptionsSurviveToolbarAutoHide() {
        val visible = mutableStateOf(true)
        var settingsOpened = false
        compose.setContent {
            MaterialTheme {
                ReaderTopBar(visible.value, "A long omnibus title", {}, {}, {},
                    { settingsOpened = true }, false, {}, {})
            }
        }
        compose.onNodeWithContentDescription("Reader options").performClick()
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Reading settings").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(settingsOpened) }
    }
}
