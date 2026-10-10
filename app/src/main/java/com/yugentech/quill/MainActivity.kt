package com.yugentech.quill

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.yugentech.quill.navigation.host.AppNavHost
import com.yugentech.quill.theme.viewmodel.ThemeViewModel
import com.yugentech.theme.QuillTheme
import com.yugentech.theme.models.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : FragmentActivity() {

    private var shouldNavigateToHome by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        val animationReady = MutableStateFlow(false)
        lifecycleScope.launch {
            delay(1000.milliseconds)
            animationReady.value = true
        }

        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            !animationReady.value
        }

        setContent {
            val navController = rememberNavController()

            val showOnboarding = false


            val themeViewModel: ThemeViewModel = koinViewModel()
            val themeConfiguration by themeViewModel.themeConfiguration.collectAsStateWithLifecycle()
            val darkTheme = when (themeConfiguration.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            enableEdgeToEdge(
                statusBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(scrim = Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(scrim = Color.TRANSPARENT, darkScrim = Color.TRANSPARENT)
                },
                navigationBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(scrim = Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(scrim = Color.TRANSPARENT, darkScrim = Color.TRANSPARENT)
                }
            )

            QuillTheme(
                themeConfiguration = themeConfiguration
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {


                    if (true) {
                        AppNavHost(
                            navController = navController,
                            themeViewModel = themeViewModel,
                            showOnboarding = showOnboarding,
                            onOnboardingComplete = {
                                Unit
                            },
                            shouldNavigateToHome = shouldNavigateToHome,
                            onNavigatedToHome = {
                                shouldNavigateToHome = false
                            }
                        )
                    }
                }
            }
        }
    }
}
