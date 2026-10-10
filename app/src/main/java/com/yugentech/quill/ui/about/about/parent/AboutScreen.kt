package com.yugentech.quill.ui.about.about.parent
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AboutScreen(onNavigateBack:()->Unit,onNavigateToLicenses:()->Unit,onNavigateToMoreApps:()->Unit) {
 Scaffold(topBar={TopAppBar(title={Text("Quill Personal")})}) { padding ->
 Column(Modifier.padding(padding).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
 Text("Personal edition of Quill by Mohammad Ali Ustad / Yugen Tech. Apache License 2.0.")
 Text("Books and reading data stay on your device. AI requests go directly to your chosen provider.")
 Button(onClick=onNavigateToLicenses){Text("Open-source licenses")}
 TextButton(onClick=onNavigateBack){Text("Back")}
 }
 }
}
