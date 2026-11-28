package com.example.videodownloader

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.Python
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import java.io.IOException
import java.util.Locale

private const val TAG = "VideoDownloader"
internal object DownloadEngineState {

    var engineReady: Boolean by mutableStateOf(false)
        private set

    var isInitializing: Boolean by mutableStateOf(false)
        private set

    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (engineReady || isInitializing) return
            isInitializing = true
        }

        Thread({
            val ready = try {
                YtDlp.init(context.applicationContext)
                Log.i(TAG, "yt-dlp initialized")
                true
            } catch (error: YtDlpException) {
                Log.e(TAG, "yt-dlp initialization failed", error)
                false
            }

            if (ready) {
                try {
                    val embeddedVersion = Python.getInstance()
                        .getModule("yt_dlp.version")["__version__"]
                        .toString()

                    Log.i(TAG, "EMBEDDED_YTDLP_VERSION=$embeddedVersion")
                } catch (error: Exception) {
                    Log.e(TAG, "Could not read yt-dlp version", error)
                }

                Thread(
                    { checkYtDlpOptionMapping() },
                    "YtDlpOptionDiagnostic"
                ).start()
            }

            runCatching { checkFfmpegEncoders() }
                .onFailure {
                    Log.e(TAG, "FFmpeg encoder diagnostic failed", it)
                }

            Thread(
                {
                    checkAndroidAvcEncoders()
                    checkAndroidAv1Decoders()
                },
                "AndroidCodecDiagnostic"
            ).start()

            Handler(Looper.getMainLooper()).post {
                synchronized(lock) {
                    engineReady = ready
                    isInitializing = false
                }
            }
        }, "VideoDownloaderEngineInit").start()
    }
}

private fun checkFfmpegEncoders() {

    Log.i(TAG, "FFMPEG_ENCODER_CHECK_START")

    FFmpegKit.executeWithArgumentsAsync(
        arrayOf("-hide_banner", "-encoders")
    ) { session ->

        val output = session.output.orEmpty()
        val returnCode = session.getReturnCode()

        Log.i(
            TAG,
            "FFMPEG_ENCODER_CHECK_RETURN=$returnCode"
        )

        if (!ReturnCode.isSuccess(returnCode)) {

            Log.e(
                TAG,
                "FFMPEG_ENCODER_CHECK_ERROR=$output"
            )

        } else {

            val encoders = output.lineSequence()
                .filter {
                    it.contains("mediacodec", ignoreCase = true) ||
                            it.contains("openh264", ignoreCase = true) ||
                            it.contains("aac", ignoreCase = true)
                }
                .toList()

            if (encoders.isEmpty()) {
                Log.w(
                    TAG,
                    "FFMPEG_ENCODER_CHECK_NO_MATCH"
                )
            } else {
                encoders.forEach {
                    Log.i(TAG, "FFMPEG_ENCODER_CHECK=$it")
                }
            }
        }
    }
}

private fun checkYtDlpOptionMapping() {
    try {
        val runner = Python.getInstance()
            .getModule("ytdlp_runner")

        val flagMap = runner["_FLAG_MAP"]
            ?: throw IOException("yt-dlp flag map is unavailable.")

        val flags = listOf(
            "--retries",
            "--fragment-retries",
            "--retry-sleep",
            "--abort-on-unavailable-fragments",
            "--js-runtimes",
            "--extractor-args"
        )

        flags.forEach { flag ->
            val mapping = flagMap.callAttr("get", flag)

            val supported =
                mapping != null && mapping.toString() != "None"

            Log.i(
                TAG,
                "YTDLP_OPTION_MAPPING " +
                        "flag=$flag, " +
                        "supported=$supported, " +
                        "mapping=$mapping"
            )
        }

    } catch (error: Exception) {
        Log.e(
            TAG,
            "YTDLP_OPTION_MAPPING_ERROR",
            error
        )
    }
}
private fun checkAndroidAvcEncoders() {

    Log.i(
        TAG,
        "ANDROID_AVC_ENCODER_CHECK_START API=${Build.VERSION.SDK_INT}"
    )

    try {

        val codecInfos = MediaCodecList(
            MediaCodecList.REGULAR_CODECS
        ).codecInfos

        val avcEncoders = codecInfos.filter { info ->

            info.isEncoder &&
                    info.supportedTypes.any {
                        it.equals("video/avc", ignoreCase = true)
                    }
        }

        Log.i(
            TAG,
            "ANDROID_AVC_ENCODER_COUNT=${avcEncoders.size}"
        )

        if (avcEncoders.isEmpty()) {
            Log.w(
                TAG,
                "ANDROID_AVC_ENCODER_NONE"
            )
            return
        }

        avcEncoders.forEach { info ->

            val hardware = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isHardwareAccelerated.toString()
            } else {
                "unknown"
            }

            val software = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isSoftwareOnly.toString()
            } else {
                "unknown"
            }

            val vendor = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isVendor.toString()
            } else {
                "unknown"
            }

            try {

                val capabilities = info.getCapabilitiesForType(
                    "video/avc"
                )

                val videoCapabilities = capabilities.videoCapabilities
                    ?: throw IllegalStateException(
                        "Video capabilities unavailable for ${info.name}"
                    )

                val supports1080p30 = runCatching {
                    videoCapabilities.areSizeAndRateSupported(
                        1920,
                        1080,
                        30.0
                    )
                }.getOrDefault(false)

                val colorFormats =
                    capabilities.colorFormats.joinToString(",") {
                        "0x%08X".format(Locale.US, it)
                    }

                Log.i(
                    TAG,
                    "ANDROID_AVC_ENCODER " +
                            "name=${info.name}, " +
                            "hardware=$hardware, " +
                            "software=$software, " +
                            "vendor=$vendor, " +
                            "supports1080p30=$supports1080p30, " +
                            "colorFormats=[$colorFormats]"
                )

                Log.i(
                    TAG,
                    "ANDROID_AVC_ENCODER_LIMITS " +
                            "name=${info.name}, " +
                            "width=${videoCapabilities.supportedWidths}, " +
                            "height=${videoCapabilities.supportedHeights}, " +
                            "fps=${videoCapabilities.supportedFrameRates}"
                )

            } catch (error: Exception) {

                Log.e(
                    TAG,
                    "ANDROID_AVC_ENCODER_CAPABILITY_ERROR " +
                            "name=${info.name}",
                    error
                )
            }
        }

    } catch (error: Exception) {

        Log.e(
            TAG,
            "ANDROID_AVC_ENCODER_CHECK_ERROR",
            error
        )
    }
}


private fun checkAndroidAv1Decoders() {
    val mimeType = "video/av01"

    Log.i(
        TAG,
        "ANDROID_AV1_DECODER_CHECK_START API=${Build.VERSION.SDK_INT}"
    )

    try {
        val decoders = MediaCodecList(
            MediaCodecList.REGULAR_CODECS
        ).codecInfos.filter { info ->
            !info.isEncoder &&
                    info.supportedTypes.any {
                        it.equals(mimeType, ignoreCase = true)
                    }
        }

        Log.i(
            TAG,
            "ANDROID_AV1_DECODER_COUNT=${decoders.size}"
        )

        if (decoders.isEmpty()) {
            Log.w(TAG, "ANDROID_AV1_DECODER_NONE")
            return
        }

        decoders.forEach { info ->
            val hardware = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isHardwareAccelerated.toString()
            } else {
                "unknown"
            }

            val software = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isSoftwareOnly.toString()
            } else {
                "unknown"
            }

            val vendor = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isVendor.toString()
            } else {
                "unknown"
            }

            try {
                val capabilities = info.getCapabilitiesForType(
                    mimeType
                )

                val videoCapabilities = capabilities.videoCapabilities
                    ?: throw IllegalStateException(
                        "AV1 video capabilities unavailable for ${info.name}"
                    )

                val supports1080p30 = runCatching {
                    videoCapabilities.areSizeAndRateSupported(
                        1920,
                        1080,
                        30.0
                    )
                }.getOrDefault(false)

                val profiles = capabilities.profileLevels.joinToString(",") {
                    "profile=${it.profile}:level=${it.level}"
                }

                val colorFormats = capabilities.colorFormats.joinToString(",") {
                    "0x%08X".format(Locale.US, it)
                }

                Log.i(
                    TAG,
                    "ANDROID_AV1_DECODER " +
                            "name=${info.name}, " +
                            "hardware=$hardware, " +
                            "software=$software, " +
                            "vendor=$vendor, " +
                            "supports1080p30=$supports1080p30, " +
                            "profiles=[$profiles], " +
                            "colorFormats=[$colorFormats]"
                )

                Log.i(
                    TAG,
                    "ANDROID_AV1_DECODER_LIMITS " +
                            "name=${info.name}, " +
                            "width=${videoCapabilities.supportedWidths}, " +
                            "height=${videoCapabilities.supportedHeights}, " +
                            "fps=${videoCapabilities.supportedFrameRates}"
                )

            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "ANDROID_AV1_DECODER_CAPABILITY_ERROR name=${info.name}",
                    error
                )
            }
        }

    } catch (error: Exception) {
        Log.e(
            TAG,
            "ANDROID_AV1_DECODER_CHECK_ERROR",
            error
        )
    }
}