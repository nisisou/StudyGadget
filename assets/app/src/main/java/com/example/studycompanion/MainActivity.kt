package com.example.studycompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.studycompanion.data.SettingsStore
import com.example.studycompanion.ui.MainScreen
import com.example.studycompanion.ui.theme.StudyCompanionTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val darkThemeOption by settingsStore.darkThemeOptionFlow.collectAsState(initial = "SYSTEM")

            StudyCompanionTheme(darkThemeOption = darkThemeOption) {
                MainScreen(darkThemeOption = darkThemeOption)
            }
        }
    }
}
