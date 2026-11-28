package com.example.videodownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback
import dev.ffmpegkit_maintained.ytdlp.LogCallback
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutionException
import kotlin.collections.filter
import kotlin.collections.maxByOrNull
import kotlin.collections.orEmpty
private const val TAG = "VideoDownloader"

internal fun downloadOneFormat(
    context: Context,
    url: String,
    directory: File,
    filePrefix: String,
    formatSelector: String,
    onProgress: (
        Float,
        Long?,
        DownloadTransferMetrics?
    ) -> Unit
): File {
    val outputTemplate = File(directory, "$filePrefix.%(ext)s").absolutePath
    val request = YtDlpRequest(url)
        .setOutputTemplate(outputTemplate)
        .addOption("-f", formatSelector)
        .addOption("--no-playlist")
        .addOption("--retries", "infinite")
        .addOption("--fragment-retries", "infinite")
        .addOption("--retry-sleep", "http:exp=1:20")
        .addOption("--retry-sleep", "fragment:exp=1:20")
        .addOption("--abort-on-unavailable-fragments")
    if (isYouTubeUrl(url)) {
        request.addOption(
            "--js-runtimes",
            "quickjs:${quickJsExecutablePath(context)}"
        )
        request.addOption(
            "--extractor-args",
            "youtube:player_client=default,-visionos"
        )
    }
    val diagnosticLines = Collections.synchronizedList(mutableListOf<String>())

    val logger = LogCallback { level, message ->
        diagnosticLines.add("[$level] $message")
        while (diagnosticLines.size > 20) {
            diagnosticLines.removeAt(0)
        }
        Log.i(TAG, "yt-dlp $level: $message")
    }

    val progressCallback = DownloadProgressCallback { progress, eta, line ->

        val safeProgress = if (progress.isFinite()) {
            progress.coerceIn(0f, 100f)
        } else {
            0f
        }

        val metrics = DownloadTransferMetrics.fromCallback(
            callbackEtaSeconds = eta,
            line = line
        )

        Log.d(
            TAG,
            "YTDLP_PROGRESS " +
                    "progress=$safeProgress " +
                    "etaMs=${metrics.estimatedRemainingMs()} " +
                    "speed=${metrics.speedBytesPerSecond} " +
                    "downloaded=${metrics.downloadedBytes} " +
                    "total=${metrics.totalBytes ?: metrics.totalBytesEstimate} " +
                    "fragments=${metrics.fragmentIndex}/${metrics.fragmentCount}"
        )

        onProgress(
            safeProgress,
            metrics.estimatedRemainingMs(),
            metrics
        )
    }
    val future = YtDlp.executeDebug(
        request,
        logger,
        progressCallback
    )

    val response = try {
        future.get()

    } catch (error: InterruptedException) {

        future.cancel(true)

        Log.w(
            TAG,
            "YTDLP_CANCEL_REQUESTED format=$formatSelector"
        )

        Thread.currentThread().interrupt()

        throw IOException(
            "تم طلب إيقاف تنزيل الصيغة $formatSelector.",
            error
        )

    } catch (error: ExecutionException) {

        throw IOException(
            "yt-dlp لم يستطع تنزيل الصيغة $formatSelector: " +
                    errorDetails(error),
            error.cause ?: error
        )
    }

    if (!response.isSuccess) {
        val logs = synchronized(diagnosticLines) {
            diagnosticLines.takeLast(8).joinToString(" ; ")
        }
        throw IOException(
            "yt-dlp أعاد Exit Code ${response.exitCode} للصيغة $formatSelector. $logs"
        )
    }

    return directory.listFiles()
        .orEmpty()
        .filter { file ->
            file.isFile &&
                    file.name.startsWith("$filePrefix.") &&
                    !file.name.endsWith(".part") &&
                    file.length() > 0L
        }
        .maxByOrNull { it.lastModified() }
        ?: throw IOException("اكتمل yt-dlp دون العثور على ملف الصيغة $formatSelector.")
}

internal fun saveVideoToMovies(
    context: Context,
    source: File,
    title: String,
    onCopyProgress: (Float) -> Unit
): Uri {

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw IOException(
            "الحفظ المباشر في المعرض بهذه النسخة يتطلب Android 10 أو أحدث."
        )
    }

    if (!source.isFile || source.length() <= 0L) {
        throw IOException("ملف المصدر غير موجود أو فارغ.")
    }

    val sourceBytes = source.length()

    val safeTitle = title
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        .trim()
        .take(70)
        .ifBlank { "video" }

    val extension = source.extension
        .lowercase(Locale.ROOT)
        .ifBlank { "mp4" }

    val mimeType = when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        else -> "video/*"
    }

    val values = ContentValues().apply {
        put(
            MediaStore.MediaColumns.DISPLAY_NAME,
            "${safeTitle}_${System.currentTimeMillis()}.$extension"
        )

        put(
            MediaStore.MediaColumns.MIME_TYPE,
            mimeType
        )

        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            "${Environment.DIRECTORY_MOVIES}/Video Downloader"
        )

        put(
            MediaStore.MediaColumns.IS_PENDING,
            1
        )
    }

    val resolver = context.contentResolver

    val destinationUri = resolver.insert(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        values
    ) ?: throw IOException(
        "تعذر إنشاء ملف الفيديو داخل مجلد Movies."
    )

    try {

        val output = resolver.openOutputStream(
            destinationUri,
            "w"
        ) ?: throw IOException(
            "تعذر فتح ملف الفيديو للحفظ."
        )

        var copiedBytes = 0L

        output.use { destination ->

            source.inputStream().use { input ->

                val buffer = ByteArray(64 * 1024)

                var lastReportedPercent = -1

                while (true) {

                    if (Thread.currentThread().isInterrupted) {
                        throw IOException("تم إيقاف حفظ الفيديو.")
                    }

                    val bytesRead = input.read(buffer)

                    if (bytesRead == -1) break

                    destination.write(
                        buffer,
                        0,
                        bytesRead
                    )

                    copiedBytes += bytesRead

                    val percent = (
                            copiedBytes.toDouble() /
                                    sourceBytes * 100.0
                            ).toInt().coerceIn(0, 100)

                    if (percent != lastReportedPercent) {

                        onCopyProgress(percent.toFloat())

                        lastReportedPercent = percent
                    }
                }
            }
        }

        if (copiedBytes != sourceBytes) {
            throw IOException(
                "لم يكتمل نسخ الفيديو. " +
                        "المطلوب: $sourceBytes، المنسوخ: $copiedBytes."
            )
        }

        val publishValues = ContentValues().apply {
            put(
                MediaStore.MediaColumns.IS_PENDING,
                0
            )
        }

        val updatedRows = resolver.update(
            destinationUri,
            publishValues,
            null,
            null
        )

        Log.i(
            TAG,
            "SAVE_VERIFY " +
                    "copiedBytes=$copiedBytes, " +
                    "expectedBytes=$sourceBytes, " +
                    "updatedRows=$updatedRows"
        )

        if (updatedRows <= 0) {
            throw IOException(
                "تم نسخ الفيديو، لكن تعذر نشره داخل المعرض."
            )
        }

        return destinationUri

    } catch (error: Exception) {

        resolver.delete(destinationUri, null, null)

        throw error
    }
}

