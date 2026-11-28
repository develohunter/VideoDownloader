package com.example.videodownloader

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import java.util.Locale
import kotlin.math.roundToLong

private const val TAG = "VideoDownloader"
private fun pyString(obj: PyObject, key: String): String? {
    val value = obj.callAttr("get", key) ?: return null
    return value.toString().takeUnless {
        it == "None" || it == "null" || it.isBlank()
    }
}

private fun pyInt(obj: PyObject, key: String): Int? {
    val value = obj.callAttr("get", key) ?: return null
    if (value.toString() == "None") return null
    return runCatching { value.toInt() }.getOrNull()
}

private fun pyLong(obj: PyObject, key: String): Long? {
    val value = obj.callAttr("get", key) ?: return null
    if (value.toString() == "None") return null
    return runCatching { value.toLong() }.getOrNull()
}

private fun pyDouble(obj: PyObject, key: String): Double? {
    val value = obj.callAttr("get", key) ?: return null
    if (value.toString() == "None") return null
    return runCatching { value.toDouble() }.getOrNull()
}


private data class SizeEstimate(
    val bytes: Long?,
    val isApproximate: Boolean
)

private fun estimateBytesFromBitrate(
    durationSeconds: Double?,
    bitrateKbps: Double?
): Long? {

    if (
        durationSeconds == null ||
        bitrateKbps == null ||
        !durationSeconds.isFinite() ||
        !bitrateKbps.isFinite() ||
        durationSeconds <= 0.0 ||
        bitrateKbps <= 0.0
    ) {
        return null
    }

    val bytes = durationSeconds * bitrateKbps * 1000.0 / 8.0

    if (!bytes.isFinite() || bytes <= 0.0 ||
        bytes > Long.MAX_VALUE.toDouble()
    ) {
        return null
    }

    return bytes.roundToLong().coerceAtLeast(1L)
}


private fun estimateFormatSize(
    item: PyObject,
    durationSeconds: Double?,
    bitrateKbps: Double?
): SizeEstimate {

    val exactSize = pyLong(item, "filesize")
        ?.takeIf { it > 0L }

    if (exactSize != null) {
        return SizeEstimate(exactSize, false)
    }

    val approximateSize = pyLong(item, "filesize_approx")
        ?.takeIf { it > 0L }

    if (approximateSize != null) {
        return SizeEstimate(approximateSize, true)
    }

    return SizeEstimate(
        estimateBytesFromBitrate(durationSeconds, bitrateKbps),
        true
    )
}


private fun addEstimatedSizes(
    first: Long?,
    second: Long?
): Long? {

    if (first == null || second == null) return null

    if (first < 0L || second < 0L) return null

    if (Long.MAX_VALUE - first < second) return null

    return first + second
}


private fun isAacCodec(codec: String?): Boolean {
    val normalized = codec.orEmpty().lowercase(Locale.ROOT)

    return normalized == "aac" || normalized.startsWith("mp4a")
}


// 0 = المسار الأصلي، 1 = غير معروف، 2 = مدبلج تلقائيًا.
// كلمة (default) وحدها غير موثوقة: بعض المسارات المدبلجة تحملها أيضًا.
internal fun audioTrackRank(format: AudioFormat): Int {
    val note = format.formatNote.orEmpty().lowercase(Locale.ROOT)

    return when {
        note.contains("dubbed") -> 2
        note.contains("original") -> 0
        else -> 1
    }
}


private fun containerPreference(extension: String): Int {
    return when (extension.lowercase(Locale.ROOT)) {
        "mp4" -> 0
        "m4v" -> 1
        "webm" -> 2
        else -> 3
    }
}


private fun videoCodecPreference(codec: String?): Int {
    val normalized = codec.orEmpty().lowercase(Locale.ROOT)

    return when {
        normalized.contains("avc") ||
                normalized.contains("h264") -> 0

        normalized.contains("vp9") -> 1

        normalized.contains("av01") ||
                normalized.contains("av1") -> 2

        else -> 3
    }
}


private fun protocolPreference(protocol: String?): Int {
    val normalized = protocol.orEmpty().lowercase(Locale.ROOT)

    return when {
        normalized == "https" || normalized == "http" -> 0
        normalized.contains("m3u8") -> 1
        normalized.contains("dash") -> 2
        else -> 3
    }
}


private fun fallbackVideoBitrateKbps(tier: QualityTier): Double {
    return when (tier) {
        QualityTier.WEAK -> 120.0
        QualityTier.MEDIUM -> 500.0
        QualityTier.STRONG -> 2000.0
        QualityTier.ULTRA -> 5000.0
    }
}


private fun pyBoolean(obj: PyObject): Boolean {
    val value = obj.callAttr("get", "has_drm") ?: return false
    return value.toString().equals("True", ignoreCase = true)
}

internal fun extractVideoInfo(url: String, context: Context): VideoInfo {

    val python = Python.getInstance()
    val ytDlpModule = python.getModule("yt_dlp")
    val options = python.builtins.callAttr("dict")
    val optionsMap = options.asMap()


    optionsMap[PyObject.fromJava("quiet")] = PyObject.fromJava(false)
    optionsMap[PyObject.fromJava("no_warnings")] = PyObject.fromJava(false)
    optionsMap[PyObject.fromJava("verbose")] = PyObject.fromJava(true)
    optionsMap[PyObject.fromJava("skip_download")] = PyObject.fromJava(true)
    optionsMap[PyObject.fromJava("noplaylist")] = PyObject.fromJava(true)
    if (isYouTubeUrl(url)) {
        optionsMap[PyObject.fromJava("js_runtimes")] =
            createQuickJsRuntimeOptions(python, context)

        optionsMap[PyObject.fromJava("extractor_args")] =
            createYouTubeExtractorArgs(python)
    }


    val downloader = ytDlpModule.callAttr("YoutubeDL", options)

    try {
        val info = downloader.callAttr("extract_info", url, false)

        if (isYouTubeUrl(url)) {
            try {
                val stored = python.getModule("ytdlp_runner")
                    .callAttr("cache_info", url, info)
                Log.i(TAG, "YTDLP_INFO_CACHE_STORE=$stored")
            } catch (error: Exception) {
                Log.w(
                    TAG,
                    "YTDLP_INFO_CACHE_STORE_FAILED: ${errorDetails(error)}"
                )
            }
        }

        val title = pyString(info, "title") ?: "Video"
        val duration = pyDouble(info, "duration")

        val formatsObject = info.callAttr("get", "formats")

        if (formatsObject == null || formatsObject.toString() == "None") {
            return VideoInfo(
                title = title,
                formats = QualityTier.entries.map { tier ->
                    VideoFormat(
                        formatId = "",
                        height = 0,
                        extension = "",
                        fileSize = null,
                        sizeIsEstimate = true,
                        hasAudio = false,
                        fps = null,
                        qualityTier = tier,
                        videoCodec = null,
                        audioCodec = null,
                        protocol = null
                    )
                },
                audioFormats = emptyList()
            )
        }

        val availableFormats = formatsObject.asList()

        // أولًا: تجهيز مسارات الصوت المنفصلة.
        val audioFormats = availableFormats.mapNotNull { item ->

            val formatId = pyString(item, "format_id")
                ?: return@mapNotNull null

            val videoCodec = pyString(item, "vcodec")
            val audioCodec = pyString(item, "acodec")

            if (
                !videoCodec.equals("none", ignoreCase = true) ||
                audioCodec.isNullOrBlank() ||
                audioCodec.equals("none", ignoreCase = true) ||
                pyBoolean(item)
            ) {
                return@mapNotNull null
            }

            val formatDuration =
                pyDouble(item, "duration") ?: duration

            val bitrate =
                pyDouble(item, "abr")
                    ?: pyDouble(item, "tbr")
                    ?: 0.0

            val size = estimateFormatSize(
                item = item,
                durationSeconds = formatDuration,
                bitrateKbps = bitrate
            )

            AudioFormat(
                formatId = formatId,
                bitrate = bitrate,
                extension = pyString(item, "ext") ?: "unknown",
                fileSize = size.bytes,
                codec = audioCodec,
                sizeIsEstimate = size.isApproximate,
                language = pyString(item, "language"),
                formatNote = pyString(item, "format_note"),
                languagePreference = pyInt(item, "language_preference"),
                protocol = pyString(item, "protocol")
            )

        }.distinctBy { it.formatId }
            .sortedWith(
                compareBy<AudioFormat> {
                    audioTrackRank(it)
                }.thenByDescending {
                    it.languagePreference ?: -1000
                }.thenBy {
                    if (isAacCodec(it.codec)) 0 else 1
                }.thenBy {
                    if (it.extension == "m4a") 0 else 1
                }.thenByDescending {
                    it.bitrate
                }
            )

        audioFormats.forEach {
            Log.i(
                TAG,
                "AUDIO_CANDIDATE id=${it.formatId} rank=${audioTrackRank(it)} " +
                        "lang=${it.language} langPref=${it.languagePreference} " +
                        "note=${it.formatNote} codec=${it.codec} " +
                        "abr=${it.bitrate} ext=${it.extension}"
            )
        }

        // نستخدم نفس مسار الصوت لتقدير الحجم الإجمالي لكل فيديو منفصل.
        val preferredAudio = audioFormats.firstOrNull()

        // ثانيًا: استخراج صيغ الفيديو الصالحة.
        val videoCandidates = availableFormats.mapNotNull { item ->

            val formatId = pyString(item, "format_id")
                ?: return@mapNotNull null

            val videoCodec = pyString(item, "vcodec")

            if (
                videoCodec.isNullOrBlank() ||
                videoCodec.equals("none", ignoreCase = true) ||
                pyBoolean(item)
            ) {
                return@mapNotNull null
            }

            val resolution = pyString(item, "resolution")

            val height = pyInt(item, "height")
                ?: resolution?.let {
                    Regex(
                        "[xp](\\d{3,4})",
                        RegexOption.IGNORE_CASE
                    ).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                ?: return@mapNotNull null

            if (height <= 0) return@mapNotNull null

            val tier = QualityTier.fromHeight(height)
                ?: return@mapNotNull null

            val audioCodec = pyString(item, "acodec")

            val hasAudio =
                !audioCodec.isNullOrBlank() &&
                        !audioCodec.equals("none", ignoreCase = true)

            val formatDuration =
                pyDouble(item, "duration") ?: duration

            val bitrate = if (hasAudio) {
                pyDouble(item, "tbr")
                    ?: run {
                        val videoRate = pyDouble(item, "vbr")
                        val audioRate = pyDouble(item, "abr")

                        if (videoRate != null && audioRate != null) {
                            videoRate + audioRate
                        } else {
                            videoRate ?: audioRate
                        }
                    }
            } else {
                pyDouble(item, "vbr")
                    ?: pyDouble(item, "tbr")
            }

            val videoSize = estimateFormatSize(
                item = item,
                durationSeconds = formatDuration,
                bitrateKbps = bitrate
            )

            val audioSize = if (hasAudio) {
                null
            } else {
                preferredAudio?.let {
                    SizeEstimate(it.fileSize, it.sizeIsEstimate)
                } ?: estimateBytesFromBitrate(
                    formatDuration,
                    128.0
                )?.let {
                    SizeEstimate(it, true)
                }
            }

            val fallbackAudioRate =
                preferredAudio?.bitrate?.takeIf { it > 0.0 }
                    ?: 128.0

            val fallbackVideoSize = if (
                videoSize.bytes == null &&
                formatDuration != null
            ) {

                val fallbackRate = if (hasAudio) {
                    fallbackVideoBitrateKbps(tier) + fallbackAudioRate
                } else {
                    fallbackVideoBitrateKbps(tier)
                }

                estimateBytesFromBitrate(
                    formatDuration,
                    fallbackRate
                )

            } else {
                null
            }

            val fallbackAudioSize = if (
                !hasAudio &&
                audioSize?.bytes == null &&
                formatDuration != null
            ) {

                estimateBytesFromBitrate(
                    formatDuration,
                    fallbackAudioRate
                )

            } else {
                null
            }

            val resolvedVideoSize =
                videoSize.bytes ?: fallbackVideoSize

            val resolvedAudioSize = if (hasAudio) {
                null
            } else {
                audioSize?.bytes ?: fallbackAudioSize
            }

            val totalSize = if (hasAudio) {
                resolvedVideoSize
            } else {
                addEstimatedSizes(
                    resolvedVideoSize,
                    resolvedAudioSize
                )
            }

            val sizeIsEstimate =
                videoSize.isApproximate ||
                        fallbackVideoSize != null ||
                        (
                                !hasAudio &&
                                        (
                                                audioSize?.isApproximate == true ||
                                                        fallbackAudioSize != null
                                                )
                                )

            VideoFormat(
                formatId = formatId,
                height = height,
                extension = pyString(item, "ext") ?: "unknown",
                fileSize = totalSize,
                sizeIsEstimate = sizeIsEstimate,
                hasAudio = hasAudio,
                fps = pyDouble(item, "fps"),
                qualityTier = tier,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                protocol = pyString(item, "protocol"),
                videoDownloadSizeBytes = resolvedVideoSize,
                audioDownloadSizeBytes = resolvedAudioSize,
                videoBitrateKbps = pyDouble(item, "vbr") ?: pyDouble(item, "tbr")
            )

        }.distinctBy { it.formatId }

        videoCandidates.forEach { candidate ->
            Log.i(
                TAG,
                "VIDEO_CANDIDATE " +
                        "id=${candidate.formatId} " +
                        "height=${candidate.height} " +
                        "tier=${candidate.qualityTier.label} " +
                        "codec=${candidate.videoCodec} " +
                        "ext=${candidate.extension} " +
                        "sizeBytes=${candidate.fileSize} " +
                        "estimated=${candidate.sizeIsEstimate} " +
                        "hasAudio=${candidate.hasAudio} " +
                        "fps=${candidate.fps} " +
                        "protocol=${candidate.protocol}"
            )
        }

        // ثالثًا: نختار الدِّقة الأقرب للمستوى المطلوب،
        // ثم نختار أقل حجم بين الصيغ التي تقدم الدِّقَّة نفسها.
        fun chooseFormat(tier: QualityTier): VideoFormat? {

            val candidates = videoCandidates.filter {
                it.qualityTier == tier
            }

            if (candidates.isEmpty()) return null

            val preferredHeight = when (tier) {
                QualityTier.WEAK -> candidates.maxOfOrNull { it.height }
                QualityTier.MEDIUM -> candidates.map { it.height }
                    .distinct()
                    .minWithOrNull(compareBy<Int> { kotlin.math.abs(it - 360) }.thenByDescending { it })
                QualityTier.STRONG -> candidates.map { it.height }
                    .distinct()
                    .minWithOrNull(compareBy<Int> { kotlin.math.abs(it - 720) }.thenByDescending { it })
                QualityTier.ULTRA -> candidates.minOfOrNull { it.height }
            } ?: return null

            val matchingCandidates = candidates.filter { it.height == preferredHeight }

            // Keep the best original/language track preference. Within that track,
            // compare codec, transfer size, conversion and save costs together.
            val bestTrackRank = audioFormats.minOfOrNull { audioTrackRank(it) }
            val bestTrack = audioFormats.filter { bestTrackRank == null || audioTrackRank(it) == bestTrackRank }
            val bestLanguagePreference = bestTrack.mapNotNull { it.languagePreference }.maxOrNull()
            val routeAudioCandidates = if (bestLanguagePreference != null) {
                bestTrack.filter { it.languagePreference == bestLanguagePreference }
            } else {
                bestTrack
            }

            data class RouteChoice(
                val video: VideoFormat,
                val audio: AudioFormat?,
                val estimate: DownloadRoutePlanner.RouteEstimate
            )

            val choices = mutableListOf<RouteChoice>()

            matchingCandidates.forEach { candidate ->
                val audioChoices: List<AudioFormat?> = when {
                    candidate.hasAudio -> listOf(null)
                    routeAudioCandidates.isNotEmpty() -> routeAudioCandidates.map { it }
                    else -> listOf(null) // yt-dlp bestaudio fallback
                }

                audioChoices.forEach { audio ->
                    val estimate = DownloadRoutePlanner.estimate(
                        context = context,
                        sourceUrl = url,
                        video = candidate,
                        audio = audio,
                        durationSeconds = duration
                    )
                    choices += RouteChoice(candidate, audio, estimate)
                }
            }

            val best = choices.minWithOrNull(
                compareBy<RouteChoice> { it.estimate.totalMs }
                    .thenBy { videoCodecPreference(it.video.videoCodec) }
                    .thenBy { containerPreference(it.video.extension) }
                    .thenBy { it.estimate.totalDownloadBytes }
                    .thenBy { protocolPreference(it.video.protocol) }
            ) ?: return null

            val chosenAudioId = when {
                best.video.hasAudio -> null
                best.audio != null -> best.audio.formatId
                else -> "bestaudio"
            }

            return best.video.copy(
                fileSize = best.estimate.totalDownloadBytes,
                sizeIsEstimate = best.video.sizeIsEstimate || best.estimate.sizeIsApproximate,
                audioDownloadSizeBytes = best.estimate.audioDownloadBytes,
                plannedAudioFormatId = chosenAudioId,
                estimatedTotalMs = best.estimate.totalMs,
                mediaDurationSeconds = duration,
                estimatedVideoDownloadMs = best.estimate.videoDownloadMs,
                estimatedAudioDownloadMs = best.estimate.audioDownloadMs,
                estimatedProcessingMs = best.estimate.processingMs,
                estimatedValidationMs = best.estimate.validationMs,
                estimatedSaveMs = best.estimate.saveMs,
                estimatedRetryRiskMs = best.estimate.retryRiskMs
            )
        }

        val qualityOptions = QualityTier.entries.map { tier ->

            chooseFormat(tier) ?: VideoFormat(
                formatId = "",
                height = 0,
                extension = "",
                fileSize = null,
                sizeIsEstimate = true,
                hasAudio = false,
                fps = null,
                qualityTier = tier,
                videoCodec = null,
                audioCodec = null,
                protocol = null
            )
        }
        qualityOptions.forEach { option ->

            Log.i(
                TAG,
                "QUALITY_OPTION " +
                        "label=${option.qualityTier.label}, " +
                        "available=${option.isAvailable}, " +
                        "height=${option.height}, " +
                        "formatId=${option.formatId}, " +
                        "extension=${option.extension}, " +
                        "estimatedSize=${option.fileSize}, " +
                        "sizeIsEstimate=${option.sizeIsEstimate}, " +
                        "plannedAudio=${option.plannedAudioFormatId}, " +
                        "estimatedTotalMs=${option.estimatedTotalMs}"
            )
        }
        return VideoInfo(
            title = title,
            formats = qualityOptions,
            audioFormats = audioFormats
        )

    } finally {
        runCatching {
            downloader.callAttr("close")
        }
    }
}