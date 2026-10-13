package com.yugentech.quill.personal

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.reader.ui.components.aira.AiraPeekBar
import com.yugentech.quill.reader.viewmodel.quick.QuickUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiraPanelResizeTest {
    @get:Rule val compose = createComposeRule()
    private val response = mutableStateOf(QuickUiState(response = "A short explanation."))
    private val availableHeightDp = mutableStateOf<Int?>(null)
    private var keyboardVisible = false
    private lateinit var keyboardDriver: EditText

    @OptIn(ExperimentalLayoutApi::class)
    private fun showPanel() {
        compose.setContent {
            val imeVisible = WindowInsets.isImeVisible
            SideEffect { keyboardVisible = imeVisible }
            val configuration = Configuration(LocalConfiguration.current).apply {
                availableHeightDp.value?.let { screenHeightDp = it }
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    Box {
                        // A native editor opens the real IME independently of Compose's text test APIs.
                        AndroidView(factory = { context -> EditText(context).also { keyboardDriver = it } },
                            modifier = Modifier.height(48.dp))
                        AiraPeekBar(true, airaUiState = response.value, onQuickAction = {},
                            onSendMessage = {}, onClearSelection = {}, onDismiss = {}, onStop = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(4000)
    }

    private fun resize(distance: Float) {
        compose.onNodeWithText("Aira").performTouchInput {
            swipe(center, center + Offset(0f, distance), durationMillis = 300)
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
    }

    @Test fun shortExplanationAndFollowUpCanBeResized() {
        showPanel()
        resize(-60f)
        resize(60f)
        compose.runOnIdle { response.value = QuickUiState(response = "", isLoading = true) }
        compose.mainClock.advanceTimeBy(1000)
        resize(-60f)
        compose.runOnIdle { response.value = QuickUiState(response = "Yes.") }
        compose.mainClock.advanceTimeBy(4000)
        resize(60f)
        compose.onNodeWithText("Yes.").assertIsDisplayed()
    }

    @Test fun resizeSurvivesLessSpaceThanPreferredMinimum() {
        availableHeightDp.value = 160
        showPanel()
        resize(-40f)
        resize(40f)
    }

    @Test fun expandedPanelFitsWhenSpaceShrinksAndKeyboardOpens() {
        response.value = QuickUiState(response = "A longer explanation. ".repeat(120))
        showPanel()
        resize(-200f)
        val expandedTop = compose.onNodeWithText("Aira").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { availableHeightDp.value = 300 }
        compose.mainClock.advanceTimeBy(1000)
        val constrainedTop = compose.onNodeWithText("Aira").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The expanded panel must shrink with the available height", constrainedTop > expandedTop)
        compose.runOnIdle {
            availableHeightDp.value = null
            keyboardDriver.requestFocus()
        }
        // Android registers the served editor after the focus event reaches the window.
        compose.waitUntil(timeoutMillis = 10000) {
            compose.runOnIdle {
                keyboardDriver.hasWindowFocus() && keyboardDriver.context
                    .getSystemService(InputMethodManager::class.java).isActive(keyboardDriver)
            }
        }
        compose.runOnIdle {
            keyboardDriver.context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(keyboardDriver, InputMethodManager.SHOW_IMPLICIT)
        }
        compose.waitUntil(timeoutMillis = 10000) { keyboardVisible }
        compose.mainClock.advanceTimeBy(1000)
        resize(60f)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "aira-panel-resize-keyboard.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
