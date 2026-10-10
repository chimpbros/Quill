package com.yugentech.quill.navigation.host
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.yugentech.quill.navigation.navgraph.*
import com.yugentech.quill.navigation.screen.AppScreen
import com.yugentech.quill.theme.viewmodel.ThemeViewModel
import com.yugentech.quill.personal.PersonalSettingsScreen
@Composable
fun AppNavHost(navController:NavHostController, themeViewModel:ThemeViewModel, showOnboarding:Boolean,
 onOnboardingComplete:()->Unit, shouldNavigateToHome:Boolean=false, onNavigatedToHome:()->Unit={}) {
 val context=LocalContext.current
 NavHost(navController,startDestination=AppScreen.Main.route) {
  mainGraph(navController,context); sourceGraph(navController); bookFeatureGraph(navController,context)
  accountGraph(navController); settingsGraph(navController,themeViewModel); aboutGraph(navController)
  composable("ai_connections") { PersonalSettingsScreen(onBack={ navController.popBackStack(); Unit }) }
 }
}
