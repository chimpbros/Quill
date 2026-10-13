package com.yugentech.quill.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugentech.quill.database.entity.BookmarkEntity
import com.yugentech.quill.reader.ui.components.bookmark.BookmarkList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkSnackbarTest {
    @get:Rule val compose = createComposeRule()
    private val bookmark = BookmarkEntity("snackbar-test", "test-book", "{}", "test-location", "Test bookmark")

    @Test fun removalMessageExpiresWithoutRestoringBookmark() {
        val bookmarks = mutableStateOf(listOf(bookmark))
        var restored = false
        compose.setContent {
            MaterialTheme {
                BookmarkList(bookmarks.value, {}, { _, _ -> }, { bookmarks.value = emptyList() },
                    { restored = true; bookmarks.value = listOf(it) })
            }
        }
        compose.onNodeWithContentDescription("Remove bookmark").performClick()
        compose.onNodeWithText("Bookmark removed").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(6000)
        compose.onNodeWithText("Bookmark removed").assertDoesNotExist()
        compose.runOnIdle { assertTrue(bookmarks.value.isEmpty()); assertFalse(restored) }
    }

    @Test fun undoRestoresBookmarkAndDismissesMessage() {
        val bookmarks = mutableStateOf(listOf(bookmark))
        compose.setContent {
            MaterialTheme {
                BookmarkList(bookmarks.value, {}, { _, _ -> }, { bookmarks.value = emptyList() },
                    { bookmarks.value = listOf(it) })
            }
        }
        compose.onNodeWithContentDescription("Remove bookmark").performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Bookmark removed").assertDoesNotExist()
        compose.onNodeWithText("Test bookmark").assertIsDisplayed()
    }
}
