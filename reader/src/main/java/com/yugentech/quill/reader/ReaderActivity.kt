package com.yugentech.quill.reader

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yugentech.quill.reader.ui.parent.ReaderScreen
import com.yugentech.quill.reader.viewmodel.reader.ReaderViewModel
import com.yugentech.theme.QuillTheme
import com.yugentech.theme.ThemeRepository
import com.yugentech.theme.models.ThemeConfiguration
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class ReaderActivity : AppCompatActivity() {

    private val viewModel: ReaderViewModel by viewModel()
    private val themeRepository: ThemeRepository by inject()
    private lateinit var insetsController: WindowInsetsControllerCompat
    private var latestLocatorJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Readium's FragmentFactory needs the asynchronously loaded publication. Let
        // Compose recreate the navigator after loading it instead of restoring its
        // fragment before the factory exists. Restore the reading locator explicitly.
        super.onCreate(null)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        val initialChapterHref = intent.getStringExtra(EXTRA_INITIAL_HREF)
        val locatorJson = savedInstanceState?.getString(STATE_READING_LOCATOR)
            ?: intent.getStringExtra(EXTRA_LOCATOR_JSON)
        latestLocatorJson = locatorJson
        setupWindow()
        viewModel.loadBook(bookId, initialChapterHref, locatorJson)

        val statusBarHeightPx = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val preferences by viewModel.readerPreferences.collectAsState()
            val currentThemeConfig by themeRepository.themeConfiguration.collectAsState(
                initial = ThemeConfiguration()
            )

            val density = androidx.compose.ui.platform.LocalDensity.current
            val statusBarHeightDp = with(density) { statusBarHeightPx.toDp() }

            QuillTheme(themeConfiguration = currentThemeConfig) {
                ReaderScreen(
                    viewModel = viewModel,
                    uiState = uiState,
                    onBackClick = { finish() },
                    returnLocator = com.yugentech.quill.aira.context.parseLocator(intent.getStringExtra(EXTRA_RETURN_LOCATOR)),
                    preferences = preferences,
                    statusBarHeight = statusBarHeightDp,
                    onPreferencesChange = { viewModel.updateEpubPreferences(it) },
                    onLocatorChange = { locator ->
                        latestLocatorJson = locator.toJSON().toString()
                        viewModel.saveProgress(bookId, locator)
                    },
                    onMenuVisibilityChange = { visible -> setSystemBarsVisible(visible) }
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        latestLocatorJson?.let { outState.putString(STATE_READING_LOCATOR, it) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.readerPreferences.value.volumeNavigation) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    viewModel.onVolumeUp()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    viewModel.onVolumeDown()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setupWindow() {
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        insetsController = WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun setSystemBarsVisible(visible: Boolean) {
        if (visible) insetsController.show(WindowInsetsCompat.Type.systemBars())
        else insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        private const val EXTRA_BOOK_ID = "extra_book_id"
        private const val EXTRA_INITIAL_HREF = "extra_initial_href"
        private const val EXTRA_LOCATOR_JSON = "extra_locator_json"
        private const val EXTRA_RETURN_LOCATOR = "extra_return_locator_json"
        private const val STATE_READING_LOCATOR = "reader_saved_locator"

        fun createIntent(
            context: Context,
            bookId: String,
            initialChapterHref: String? = null,
            locatorJson: String? = null,
            returnLocatorJson: String? = null
        ): Intent =
            Intent(context, ReaderActivity::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_INITIAL_HREF, initialChapterHref)
                putExtra(EXTRA_LOCATOR_JSON, locatorJson)
                putExtra(EXTRA_RETURN_LOCATOR, returnLocatorJson)
            }
    }
}
