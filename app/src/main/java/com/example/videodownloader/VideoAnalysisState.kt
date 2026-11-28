package com.example.videodownloader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal object AnalysisStateStore {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    var videoUrl by mutableStateOf("")
    var videoTitle by mutableStateOf("")
    var formats by mutableStateOf<List<VideoFormat>>(emptyList())
    var audioFormats by mutableStateOf<List<AudioFormat>>(emptyList())
    var selectedFormatId by mutableStateOf<String?>(null)
    var statusMessage by mutableStateOf("")
    var statusIsError by mutableStateOf(false)
    var isAnalyzing by mutableStateOf(false)
}
