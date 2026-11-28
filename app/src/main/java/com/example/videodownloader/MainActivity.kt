package com.example.videodownloader


import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.videodownloader.ui.theme.VideoDownloaderTheme
import androidx.core.content.edit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DownloadStorageMaintenance.runOnce(applicationContext)

        val welcomePreferences = getSharedPreferences(
            "video_downloader_preferences",
            MODE_PRIVATE
        )
        val welcomeCompletedAtStartup =
            welcomePreferences.getBoolean("welcome_completed", false)

        // Returning users skip onboarding. Initialization is process-scoped,
        // so a configuration change cannot start a second initialization.
        if (welcomeCompletedAtStartup) {
            DownloadEngineState.initialize(applicationContext)
        }

        setContent {
            VideoDownloaderTheme {
                var showWelcome by remember {
                    mutableStateOf(!welcomeCompletedAtStartup)
                }

                // These observable values live outside the Activity and survive
                // screen rotation while the Activity is recreated.
                val isInitializing = DownloadEngineState.isInitializing
                val engineReady = DownloadEngineState.engineReady

                if (showWelcome) {
                    WelcomeIntroScreen(
                        isLaunching = isInitializing,
                        onStart = {
                            welcomePreferences.edit {
                                putBoolean("welcome_completed", true)
                            }
                            showWelcome = false
                            DownloadEngineState.initialize(applicationContext)
                        }
                    )
                } else {
                    VideoDownloaderRoot(
                        engineReady = engineReady,
                        context = this@MainActivity
                    )
                }
            }
        }
    }
}