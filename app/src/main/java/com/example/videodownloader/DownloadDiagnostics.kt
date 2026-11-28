package com.example.videodownloader

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.collections.filter
import kotlin.collections.orEmpty
import kotlin.collections.sortedByDescending

private const val TAG = "VideoDownloader"

internal fun errorDetails(error: Throwable): String {
    val messages = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .toList()
    return messages.joinToString(" | ").ifBlank { error.javaClass.simpleName }
}

// تقرير تشخيص لكل عملية تنزيل: أزمنة المراحل والأحجام والسرعات وقرار FFmpeg.
// يُكتب في المِلف reports/last-report.txt داخل مجلد التطبيق، وفي Logcat بالوسم DL_REPORT.
// لا يحتوي على رابط الفيديو الكامل، فقط اسم الموقع.
internal class DownloadReport(
    context: Context,
    private val sourceHost: String
) {

    private val startedAt = SystemClock.elapsedRealtime()
    private val lines = mutableListOf<String>()
    private var errorText: String? = null
    private val environmentAtStart = snapshot(context)

    private fun offsetMs(): Long = SystemClock.elapsedRealtime() - startedAt

    private fun fmt(value: Double?): String {
        return if (value == null) {
            "n/a"
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }

    private fun snapshot(context: Context): String {
        return runCatching {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager

            val thermal = if (Build.VERSION.SDK_INT >= 29) {
                powerManager?.currentThermalStatus
            } else {
                null
            }

            "powerSaveMode=${powerManager?.isPowerSaveMode} thermalStatus=$thermal"
        }.getOrDefault("powerSaveMode=n/a thermalStatus=n/a")
    }

    // معلومة عامة دون مدة.
    fun info(key: String, value: Any?) {
        lines.add("[+${offsetMs()}ms] $key: $value")
    }

    //هذه مرحلة لها بداية ونهاية.
    fun step(name: String, stepStartedAt: Long, details: String = "") {
        val took = SystemClock.elapsedRealtime() - stepStartedAt
        lines.add("[+${offsetMs()}ms] $name: took=${took}ms $details")
    }

    // مرحلة نقل مِلف (تنزيل أو حفظ) مع السرعة.
    // startupMs = الزمن من بَدء الأمر حتى أول تقدم، ويظهر غالبًا استخراج بيانات الرابط.
    fun transfer(
        name: String,
        stepStartedAt: Long,
        firstProgressAt: Long,
        bytes: Long,
        details: String = ""
    ) {
        val now = SystemClock.elapsedRealtime()
        val totalMs = now - stepStartedAt

        val startupMs = if (firstProgressAt > 0L) {
            firstProgressAt - stepStartedAt
        } else {
            null
        }

        val transferMs = if (firstProgressAt > 0L) {
            now - firstProgressAt
        } else {
            totalMs
        }

        val mib = bytes / 1048576.0

        val speed = if (transferMs > 0L) {
            mib / (transferMs / 1000.0)
        } else {
            null
        }

        lines.add(
            "[+${offsetMs()}ms] $name: total=${totalMs}ms " +
                    "startupBeforeFirstProgress=${startupMs ?: "n/a"}ms " +
                    "transfer=${transferMs}ms size=${fmt(mib)}MiB " +
                    "speed=${fmt(speed)}MiB/s $details"
        )
    }

    fun ffmpeg(
        ffmpegStartedAt: Long,
        videoSeconds: Double?,
        totalFrames: Int,
        outputBytes: Long
    ) {
        val elapsedMs = SystemClock.elapsedRealtime() - ffmpegStartedAt
        val seconds = elapsedMs / 1000.0

        val speedVsRealtime = if (videoSeconds != null && seconds > 0.0) {
            videoSeconds / seconds
        } else {
            null
        }

        val framesPerSecond = if (seconds > 0.0) {
            totalFrames / seconds
        } else {
            null
        }

        lines.add(
            "[+${offsetMs()}ms] FFMPEG_RUN: took=${elapsedMs}ms " +
                    "videoSeconds=${fmt(videoSeconds)} " +
                    "speedVsRealtime=${fmt(speedVsRealtime)}x " +
                    "avgFramesPerSecond=${fmt(framesPerSecond)} " +
                    "outputSize=${fmt(outputBytes / 1048576.0)}MiB"
        )
    }

    fun fail(error: Throwable) {
        errorText = (error.message ?: error.toString()).take(600)
    }

    fun finish(context: Context) {
        runCatching {
            val totalMs = offsetMs()
            val environmentAtEnd = snapshot(context)

            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(java.util.Date())

            val text = buildString {
                appendLine("=== DOWNLOAD REPORT ===")
                appendLine("time: $stamp")
                appendLine("status: ${if (errorText == null) "SUCCESS" else "FAILED"}")
                appendLine("total: ${totalMs}ms")
                errorText?.let { appendLine("error: $it") }
                appendLine("source: $sourceHost")
                appendLine(
                    "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                            "android=${Build.VERSION.SDK_INT}, " +
                            "abi=${Build.SUPPORTED_ABIS.firstOrNull()}, " +
                            "cpuCores=${Runtime.getRuntime().availableProcessors()}"
                )
                appendLine("stateAtStart: $environmentAtStart")
                appendLine("stateAtEnd: $environmentAtEnd")
                lines.forEach { appendLine(it) }
                appendLine("=== END REPORT ===")
            }

            text.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { Log.i(TAG, "DL_REPORT | $it") }

            val reportsDirectory = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "reports"
            )

            reportsDirectory.mkdirs()

            File(reportsDirectory, "last-report.txt").writeText(text)
            File(reportsDirectory, "report-$stamp.txt").writeText(text)

            // نحتفظ بآخر 10 تقارير فقط.
            reportsDirectory.listFiles()
                .orEmpty()
                .filter { it.name.startsWith("report-") }
                .sortedByDescending { it.name }
                .drop(10)
                .forEach { it.delete() }
        }.onFailure {
            Log.w(TAG, "DL_REPORT_WRITE_FAILED", it)
        }
    }
}