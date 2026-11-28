package com.example.videodownloader

import android.os.SystemClock
import android.util.Log
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong
private const val TAG = "VideoDownloader"

internal data class MediaProbe(
    val formatNames: String,
    val durationSeconds: Double?,
    val videoCodec: String?,
    val audioCodec: String?,
    val videoProfile: String?,
    val videoPixelFormat: String?,
    val audioProfile: String?,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val frameRate: Double?,
    val videoBitrateBps: Long?,
    val videoFrameCount: Int?
)

private fun parseFrameRate(value: String?): Double? {
    if (value.isNullOrBlank()) return null

    val parts = value.split("/")

    val result = if (parts.size == 2) {
        val numerator = parts[0].toDoubleOrNull()
            ?: return null

        val denominator = parts[1].toDoubleOrNull()
            ?: return null

        if (denominator == 0.0) return null

        numerator / denominator
    } else {
        value.toDoubleOrNull() ?: return null
    }

    return result.takeIf {
        it.isFinite() && it > 0.0
    }
}

internal fun probeMediaFile(
    file: File,
    countFrames: Boolean = false
): MediaProbe {

    if (!file.exists() || file.length() <= 0L) {
        throw IOException("ملف الوسائط غير موجود أو فارغ.")
    }

    val arguments = mutableListOf(
        "-v", "error"
    )

    if (countFrames) {
        arguments.add("-count_frames")
    }

    arguments.addAll(
        listOf(
            "-show_entries",
            "format=format_name,duration:" +
                    "stream=index,codec_type,codec_name,profile,pix_fmt," +
                    "width,height,avg_frame_rate," +
                    "duration,bit_rate,nb_read_frames,nb_frames",
            "-of", "json",
            file.absolutePath
        )
    )

    val session = FFprobeKit.executeWithArguments(
        arguments.toTypedArray()
    )

    val output = session.output.orEmpty()

    if (!ReturnCode.isSuccess(session.getReturnCode())) {
        throw IOException(
            "FFprobe failed: ${session.getReturnCode()} $output"
        )
    }

    val root = try {
        JSONObject(output)
    } catch (error: Exception) {
        throw IOException("تعذر تحليل بيانات FFprobe.", error)
    }

    val streamsArray = root.optJSONArray("streams")
        ?: throw IOException("FFprobe لم يُرجع قائمة المسارات.")

    val streams = (0 until streamsArray.length())
        .mapNotNull { streamsArray.optJSONObject(it) }

    val videoStream = streams.firstOrNull {
        it.optString("codec_type") == "video"
    }

    val audioStream = streams.firstOrNull {
        it.optString("codec_type") == "audio"
    }

    val format = root.optJSONObject("format")

    fun readPositiveLong(
        source: JSONObject?,
        key: String
    ): Long? {
        return source?.optString(key)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    fun readPositiveInt(
        source: JSONObject?,
        key: String
    ): Int? {
        return source?.optString(key)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }
    fun readStreamString(
        source: JSONObject?,
        key: String
    ): String? {
        val value = source?.optString(key)?.trim()
            ?: return null

        return value.takeIf {
            it.isNotEmpty() &&
                    !it.equals("N/A", ignoreCase = true) &&
                    !it.equals("null", ignoreCase = true)
        }
    }
    val duration = videoStream
        ?.optString("duration")
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: format?.optString("duration")
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }

    return MediaProbe(
        formatNames = format
            ?.optString("format_name")
            .orEmpty(),

        durationSeconds = duration,

        videoCodec = videoStream
            ?.optString("codec_name")
            ?.takeIf { it.isNotBlank() && it != "N/A" },

        audioCodec = audioStream
            ?.optString("codec_name")
            ?.takeIf { it.isNotBlank() && it != "N/A" },

        videoProfile = readStreamString(
            videoStream,
            "profile"
        ),

        videoPixelFormat = readStreamString(
            videoStream,
            "pix_fmt"
        ),

        audioProfile = readStreamString(
            audioStream,
            "profile"
        ),

        videoWidth = readPositiveInt(videoStream, "width"),

        videoHeight = readPositiveInt(videoStream, "height"),

        frameRate = parseFrameRate(
            videoStream?.optString("avg_frame_rate")
        ),

        videoBitrateBps = readPositiveLong(
            videoStream,
            "bit_rate"
        ),

        videoFrameCount =
            readPositiveInt(videoStream, "nb_read_frames")
                ?: readPositiveInt(videoStream, "nb_frames")
    )
}

internal fun estimateVideoFrameCount(
    file: File,
    fallbackFps: Double?
): Int {

    val startedAt = SystemClock.elapsedRealtime()

    val session = FFprobeKit.executeWithArguments(
        arrayOf(
            "-v", "error",
            "-select_streams", "v:0",
            "-show_entries",
            "stream=codec_type,codec_name,avg_frame_rate,duration:format=duration,size",
            "-of", "json",
            file.absolutePath
        )
    )

    val output = session.output.orEmpty()

    Log.i(TAG, "SOURCE_METADATA[VIDEO_INPUT]=$output")

    val estimatedFrames = if (
        ReturnCode.isSuccess(session.getReturnCode())
    ) {
        runCatching {
            val root = JSONObject(output)
            val streams = root.optJSONArray("streams")
                ?: throw IOException("لم يتم العثور على بيانات الفيديو.")

            val videoStream = (0 until streams.length())
                .asSequence()
                .mapNotNull { streams.optJSONObject(it) }
                .firstOrNull {
                    it.optString("codec_type") == "video"
                }
                ?: throw IOException("مسار الفيديو غير موجود.")

            val streamDuration = videoStream
                .optString("duration")
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }

            val formatDuration = root
                .optJSONObject("format")
                ?.optString("duration")
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }

            val duration = streamDuration
                ?: formatDuration
                ?: throw IOException("مدة الفيديو غير متاحة.")

            val frameRateParts = videoStream
                .optString("avg_frame_rate")
                .split("/")

            val probedFps = if (frameRateParts.size == 2) {
                val numerator = frameRateParts[0].toDoubleOrNull()
                val denominator = frameRateParts[1].toDoubleOrNull()

                if (
                    numerator != null &&
                    denominator != null &&
                    denominator != 0.0
                ) {
                    numerator / denominator
                } else {
                    null
                }
            } else {
                frameRateParts.firstOrNull()?.toDoubleOrNull()
            }

            val fps = probedFps
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: fallbackFps?.takeIf {
                    it.isFinite() && it > 0.0
                }
                ?: throw IOException("معدل الفريمات غير متاح.")

            val estimatedTotal = duration * fps

            if (!estimatedTotal.isFinite() || estimatedTotal <= 0.0) {
                throw IOException("تعذر تقدير عدد الفريمات.")
            }

            ceil(estimatedTotal)
                .toLong()
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()

        }.getOrNull()

    } else {
        null
    }

    val totalFrames = estimatedFrames ?: run {
        Log.w(
            TAG,
            "SOURCE_FRAME_ESTIMATE_FALLBACK=exact_count"
        )
        countVideoFrames(file)
    }

    Log.i(TAG, "SOURCE_FRAME_ESTIMATE=$totalFrames")

    Log.i(
        TAG,
        "SOURCE_FRAME_ESTIMATE_TIME_MS=${SystemClock.elapsedRealtime() - startedAt}"
    )

    return totalFrames
}



internal fun logMediaProbe(
    file: File
): MediaProbe {

    val startedAt = SystemClock.elapsedRealtime()

    val probe = probeMediaFile(
        file = file,
        countFrames = false
    )
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_CONTAINER=${probe.formatNames}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_VIDEO_CODEC=${probe.videoCodec}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_AUDIO_CODEC=${probe.audioCodec}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_VIDEO_PROFILE=${probe.videoProfile}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_VIDEO_PIXEL_FORMAT=${probe.videoPixelFormat}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_AUDIO_PROFILE=${probe.audioProfile}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_WIDTH=${probe.videoWidth}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_HEIGHT=${probe.videoHeight}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_FPS=${probe.frameRate}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_DURATION=${probe.durationSeconds}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_VIDEO_FRAMES=${probe.videoFrameCount}")

    Log.i(
        TAG,
        "MEDIA_PROBE[FINAL_OUTPUT]_TIME_MS=" +
                (SystemClock.elapsedRealtime() - startedAt)
    )

    return probe
}
internal fun isMp4CompatibleH264(
    probe: MediaProbe
): Boolean {

    val profile = probe.videoProfile
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return false

    val pixelFormat = probe.videoPixelFormat
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return false

    val supportedProfiles = setOf(
        "66",
        "578",
        "77",
        "100",
        "baseline",
        "constrained baseline",
        "main",
        "high"
    )

    return probe.videoCodec.equals(
        "h264",
        ignoreCase = true
    ) &&
            profile in supportedProfiles &&
            pixelFormat == "yuv420p"
}


internal fun isMp4CompatibleAacLc(
    probe: MediaProbe
): Boolean {

    val profile = probe.audioProfile
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return false

    return probe.audioCodec.equals(
        "aac",
        ignoreCase = true
    ) &&
            profile in setOf("1", "lc")
}
internal fun chooseH264BitrateKbps(
    probe: MediaProbe
): Int {

    val fallbackKbps = when (probe.videoHeight ?: 720) {
        in 2160..Int.MAX_VALUE -> 12000
        in 1440..2159 -> 8000
        in 1080..1439 -> 4000
        in 720..1079 -> 2200
        in 480..719 -> 1200
        else -> 600
    }

    val sourceKbps = probe.videoBitrateBps
        ?.takeIf { it > 0L }
        ?.div(1000.0)

    val targetKbps = sourceKbps
        ?.times(1.35)
        ?: fallbackKbps.toDouble()

    return targetKbps
        .coerceIn(350.0, 16000.0)
        .roundToLong()
        .toInt()
}

internal fun countVideoFrames(file: File): Int {
    Log.i(TAG, "FRAME_PROBE_EXISTS=${file.exists()}")
    Log.i(TAG, "FRAME_PROBE_SIZE=${file.length()}")
    Log.i(TAG, "FRAME_PROBE_PATH=${file.absolutePath}")

    val session = FFprobeKit.executeWithArguments(
        arrayOf(
            "-v", "error",
            "-select_streams", "v:0",
            "-count_frames",
            "-show_entries", "stream=nb_read_frames",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath
        )
    )

    val output = session.output.orEmpty()
    val returnCode = session.getReturnCode()
    val probeLogs = session.allLogsAsString.orEmpty()

    Log.i(TAG, "FRAME_PROBE_RETURN_CODE=$returnCode")
    Log.i(TAG, "FRAME_PROBE_OUTPUT=[$output]")
    Log.i(TAG, "FRAME_PROBE_LOGS=[$probeLogs]")

    if (!ReturnCode.isSuccess(returnCode)) {
        throw IOException(
            "FFprobe failed. ReturnCode=$returnCode Output=$output"
        )
    }

    val frameCount = output
        .lineSequence()
        .mapNotNull { it.trim().toIntOrNull() }
        .firstOrNull { it > 0 }

    return frameCount
        ?: throw IOException(
            "تعذر تحديد عدد فريمات الفيديو. FFprobe Output=[$output]"
        )
}

internal fun describeProbe(probe: MediaProbe): String {
    return "container=${probe.formatNames} " +
            "vcodec=${probe.videoCodec} vprofile=${probe.videoProfile} " +
            "pixelFormat=${probe.videoPixelFormat} " +
            "size=${probe.videoWidth}x${probe.videoHeight} " +
            "fps=${probe.frameRate} " +
            "vbitrateKbps=${probe.videoBitrateBps?.div(1000)} " +
            "acodec=${probe.audioCodec} aprofile=${probe.audioProfile} " +
            "durationSec=${probe.durationSeconds} frames=${probe.videoFrameCount}"
}
