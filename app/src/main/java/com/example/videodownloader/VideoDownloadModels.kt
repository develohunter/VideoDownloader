package com.example.videodownloader
internal enum class QualityTier(
    val label: String,
    val minHeight: Int,
    val maxHeight: Int
) {
    WEAK("جودة ضعيفة", 1, 149),
    MEDIUM("جودة متوسطة", 150, 539),
    STRONG("جودة قوية", 540, 900),
    ULTRA("جودة فائقة", 901, Int.MAX_VALUE);

    companion object {
        fun fromHeight(height: Int): QualityTier? {
            return entries.firstOrNull {
                height in it.minHeight..it.maxHeight
            }
        }
    }
}

internal data class VideoFormat(
    val formatId: String,
    val height: Int,
    val extension: String,
    val fileSize: Long?,
    val sizeIsEstimate: Boolean,
    val hasAudio: Boolean,
    val fps: Double?,
    val qualityTier: QualityTier,
    val videoCodec: String?,
    val audioCodec: String?,
    val protocol: String?,
    val videoDownloadSizeBytes: Long? = null,
    val audioDownloadSizeBytes: Long? = null,
    val videoBitrateKbps: Double? = null,
    val plannedAudioFormatId: String? = null,
    val estimatedTotalMs: Long? = null,
    val mediaDurationSeconds: Double? = null,
    val estimatedVideoDownloadMs: Long? = null,
    val estimatedAudioDownloadMs: Long? = null,
    val estimatedProcessingMs: Long? = null,
    val estimatedValidationMs: Long? = null,
    val estimatedSaveMs: Long? = null,
    val estimatedRetryRiskMs: Long? = null
) {
    val isAvailable: Boolean
        get() = formatId.isNotBlank()
}

internal data class AudioFormat(
    val formatId: String,
    val bitrate: Double,
    val extension: String,
    val fileSize: Long?,
    val codec: String? = null,
    val sizeIsEstimate: Boolean = true,
    val language: String? = null,
    val formatNote: String? = null,
    val languagePreference: Int? = null,
    val protocol: String? = null
)

internal data class VideoInfo(
    val title: String,
    val formats: List<VideoFormat>,
    val audioFormats: List<AudioFormat>
)
