package com.example.videodownloader

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.filter
import kotlin.collections.forEach
import kotlin.collections.orEmpty
import kotlin.math.ceil
import kotlin.math.roundToLong
private const val TAG = "VideoDownloader"

internal fun downloadSelectedVideo(
    context: Context,
    url: String,
    title: String,
    selectedFormat: VideoFormat,
    audioFormats: List<AudioFormat>,
    progressEstimator: DownloadProgressEstimator,
    onProgress: (Float, String, Long?) -> Unit
): Uri {

    val report = DownloadReport(
        context,
        runCatching { url.toUri().host }.getOrNull() ?: "unknown"
    )

    report.info(
        "SELECTED_VIDEO",
        "id=${selectedFormat.formatId} height=${selectedFormat.height} " +
                "fps=${selectedFormat.fps} ext=${selectedFormat.extension} " +
                "vcodec=${selectedFormat.videoCodec} " +
                "acodec=${selectedFormat.audioCodec} " +
                "protocol=${selectedFormat.protocol} " +
                "hasAudio=${selectedFormat.hasAudio} " +
                "plannedAudio=${selectedFormat.plannedAudioFormatId} " +
                "estimatedTotalMs=${selectedFormat.estimatedTotalMs} " +
                "estimatedSizeMiB=${selectedFormat.fileSize?.div(1048576L)} " +
                "sizeIsEstimate=${selectedFormat.sizeIsEstimate}"
    )

    val baseDirectory = context.getExternalFilesDir(
        Environment.DIRECTORY_MOVIES
    ) ?: context.cacheDir

    val workDir = File(
        baseDirectory,
        "video-download-${System.currentTimeMillis()}"
    )

    if (!DownloadWorkspaceRegistry.createAndRegister(workDir)) {
        throw IOException("تعذر إنشاء مجلد العمل.")
    }

    // إذا استمر FFmpeg بعد طلب الإلغاء، نؤجل حذف الملفات
    // حتى يصل Callback انتهاء العملية.
    val cleanupAfterFfmpeg = AtomicBoolean(false)

    var videoDownloadAttempted = false
    var videoDownloadSucceeded = false
    fun reportProgress(
        stage: DownloadStage,
        stagePercent: Float,
        message: String,
        etaMs: Long? = null,
        metrics: DownloadTransferMetrics? = null
    ) {
        val snapshot = progressEstimator.update(
            stage = stage,
            stagePercent = stagePercent,
            etaMs = etaMs,
            metrics = metrics
        )

        onProgress(
            snapshot.progress,
            message,
            snapshot.etaRemainingMs
        )

        Log.d(
            TAG,
            "DOWNLOAD_PROGRESS_SNAPSHOT " +
                    "stage=${snapshot.stage} " +
                    "progress=${snapshot.progress} " +
                    "etaMs=${snapshot.etaRemainingMs} " +
                    "waiting=${snapshot.waitingForNetwork}"
        )
    }
    try {

        val availableBytes = runCatching {
            StatFs(workDir.absolutePath).availableBytes
        }.getOrNull()

        report.info("FREE_SPACE_MiB", availableBytes?.div(1048576L))

        if (
            availableBytes != null &&
            availableBytes < 100L * 1024L * 1024L
        ) {
            throw IOException(
                "المساحة المتاحة أقل من 100 ميجابايت."
            )
        }

        // -------------------------------------------------
        // 1. تنزيل صيغة الفيديو المختارة
        // -------------------------------------------------

        val videoPrefix = if (selectedFormat.hasAudio) {
            "complete"
        } else {
            "video"
        }

        reportProgress(
            stage = DownloadStage.VIDEO_DOWNLOAD,
            stagePercent = 0f,
            message = "جاري تنزيل الفيديو..."
        )

        val videoStartedAt = SystemClock.elapsedRealtime()
        var videoFirstProgressAt = 0L
        videoDownloadAttempted = true

        val videoFile = downloadOneFormat(
            context = context,
            url = url,
            directory = workDir,
            filePrefix = videoPrefix,
            formatSelector = selectedFormat.formatId
        ) { progress, etaMs, metrics ->

            if (videoFirstProgressAt == 0L) {
                videoFirstProgressAt = SystemClock.elapsedRealtime()
            }

            reportProgress(
                stage = DownloadStage.VIDEO_DOWNLOAD,
                stagePercent = progress,
                message = "تنزيل الفيديو: ${progress.toInt()}%",
                etaMs = etaMs,
                metrics = metrics
            )
        }

        videoDownloadSucceeded = true
        reportProgress(
            DownloadStage.VIDEO_DOWNLOAD,
            100f,
            "اكتمل تنزيل الفيديو.",
            0L
        )
        report.transfer(
            "VIDEO_DOWNLOAD",
            videoStartedAt,
            videoFirstProgressAt,
            videoFile.length(),
            "format=${selectedFormat.formatId}"
        )
        DownloadRoutePlanner.recordTransferSample(
            context = context,
            sourceUrl = url,
            protocol = selectedFormat.protocol,
            bytes = videoFile.length(),
            totalElapsedMs = SystemClock.elapsedRealtime() - videoStartedAt,
            startupElapsedMs = if (videoFirstProgressAt > 0L) videoFirstProgressAt - videoStartedAt else null
        )

        // -------------------------------------------------
        // 2. فحص مسار الفيديو والصوت المدمج فعليًا
        // -------------------------------------------------

        val videoProbeStartedAt = SystemClock.elapsedRealtime()
        val videoProbe = probeMediaFile(videoFile)

        report.step(
            "VIDEO_PROBE",
            videoProbeStartedAt,
            describeProbe(videoProbe)
        )

        if (videoProbe.videoCodec.isNullOrBlank()) {
            throw IOException(
                "الملف الذي تم تنزيله لا يحتوي على مسار فيديو صالح."
            )
        }

        var audioFile: File? = null
        var audioProbe: MediaProbe? = null

        // لا تعتمد على بينات التحليل وحدها
        // نفحص المِلف الحقيقي لمعرفة وجود الصوت.
        if (!videoProbe.audioCodec.isNullOrBlank()) {

            audioFile = videoFile
            audioProbe = videoProbe

            report.info(
                "AUDIO_SOURCE",
                "embedded codec=${videoProbe.audioCodec}"
            )

            Log.i(
                TAG,
                "DOWNLOAD_AUDIO_SOURCE=embedded " +
                        "codec=${videoProbe.audioCodec}"
            )

        } else {

            // -------------------------------------------------
            // 3. تنزيل الصوت عند عدم وجوده داخل المِلف
            // -------------------------------------------------

            val bestAudioFallback = AudioFormat(
                formatId = "bestaudio",
                bitrate = 0.0,
                extension = "auto",
                fileSize = null
            )
            val plannedAudio = when (selectedFormat.plannedAudioFormatId) {
                "bestaudio" -> bestAudioFallback
                null -> null
                else -> audioFormats.firstOrNull { it.formatId == selectedFormat.plannedAudioFormatId }
            }
            val rankedAudioCandidates = audioFormats
                .filter { audioTrackRank(it) < 2 }
                .ifEmpty { audioFormats }
            val candidates = (
                    listOfNotNull(plannedAudio) +
                            rankedAudioCandidates
                                .filterNot { it.formatId == plannedAudio?.formatId }
                                .take(if (plannedAudio == null) 3 else 2) +
                            bestAudioFallback
                    ).distinctBy { it.formatId }

            report.info(
                "AUDIO_CANDIDATES",
                candidates.joinToString(" , ") {
                    "${it.formatId}(rank=${audioTrackRank(it)}, lang=${it.language})"
                }
            )

            reportProgress(
                DownloadStage.AUDIO_DOWNLOAD,
                0f,
                "جاري تنزيل الصوت..."
            )

            val audioErrors = mutableListOf<String>()

            for ((index, candidate) in candidates.withIndex()) {

                val attemptPrefix = "audio_${index + 1}"

                reportProgress(
                    DownloadStage.AUDIO_DOWNLOAD,
                    0f,
                    "جاري تجربة صيغة الصوت ${index + 1}/${candidates.size}..."
                )

                val audioStartedAt = SystemClock.elapsedRealtime()
                var audioFirstProgressAt = 0L
                var candidateDownloadCompleted = false

                try {

                    val candidateFile = downloadOneFormat(
                        context = context,
                        url = url,
                        directory = workDir,
                        filePrefix = attemptPrefix,
                        formatSelector = candidate.formatId
                    ) { progress, etaMs, metrics ->

                        if (audioFirstProgressAt == 0L) {
                            audioFirstProgressAt = SystemClock.elapsedRealtime()
                        }

                        reportProgress(
                            stage = DownloadStage.AUDIO_DOWNLOAD,
                            stagePercent = progress,
                            message = "تنزيل الصوت ${candidate.extension}: ${progress.toInt()}%",
                            etaMs = etaMs,
                            metrics = metrics
                        )
                    }

                    candidateDownloadCompleted = true
                    report.transfer(
                        "AUDIO_DOWNLOAD",
                        audioStartedAt,
                        audioFirstProgressAt,
                        candidateFile.length(),
                        "format=${candidate.formatId} " +
                                "language=${candidate.language} " +
                                "note=${candidate.formatNote}"
                    )
                    DownloadRoutePlanner.recordTransferSample(
                        context = context,
                        sourceUrl = url,
                        protocol = candidate.protocol ?: selectedFormat.protocol,
                        bytes = candidateFile.length(),
                        totalElapsedMs = SystemClock.elapsedRealtime() - audioStartedAt,
                        startupElapsedMs = if (audioFirstProgressAt > 0L) audioFirstProgressAt - audioStartedAt else null
                    )

                    val candidateProbeStartedAt = SystemClock.elapsedRealtime()
                    val candidateProbe = probeMediaFile(candidateFile)

                    report.step(
                        "AUDIO_PROBE",
                        candidateProbeStartedAt,
                        "acodec=${candidateProbe.audioCodec} " +
                                "aprofile=${candidateProbe.audioProfile}"
                    )

                    if (candidateProbe.audioCodec.isNullOrBlank()) {
                        candidateFile.delete()

                        throw IOException(
                            "الصيغة ${candidate.formatId} لا تحتوي على مسار صوت."
                        )
                    }

                    audioFile = candidateFile
                    audioProbe = candidateProbe
                    reportProgress(
                        DownloadStage.AUDIO_DOWNLOAD,
                        100f,
                        "اكتمل تنزيل الصوت.",
                        0L
                    )

                    report.info(
                        "AUDIO_SOURCE",
                        "separate format=${candidate.formatId} " +
                                "language=${candidate.language} " +
                                "note=${candidate.formatNote} " +
                                "codec=${candidateProbe.audioCodec}"
                    )

                    Log.i(
                        TAG,
                        "DOWNLOAD_AUDIO_SELECTED " +
                                "format=${candidate.formatId}, " +
                                "codec=${candidateProbe.audioCodec}"
                    )

                    break

                } catch (error: Exception) {

                    // لا تواصل المحاولات إذا كانت العملية قد أُلغيت.
                    if (Thread.currentThread().isInterrupted) {
                        throw IOException(
                            "تم إيقاف تنزيل الصوت.",
                            error
                        )
                    }

                    val detail = errorDetails(error)

                    report.step(
                        "AUDIO_ATTEMPT_FAILED",
                        audioStartedAt,
                        "format=${candidate.formatId} error=${detail.take(300)}"
                    )

                    if (!candidateDownloadCompleted) {
                        DownloadRoutePlanner.recordAttemptFailure(
                            context = context,
                            sourceUrl = url,
                            protocol = candidate.protocol ?: selectedFormat.protocol
                        )
                    }
                    audioErrors.add(
                        "${candidate.formatId}: $detail"
                    )

                    workDir.listFiles()
                        .orEmpty()
                        .filter {
                            it.isFile &&
                                    it.name.startsWith("$attemptPrefix.")
                        }
                        .forEach {
                            it.delete()
                        }

                    Log.w(
                        TAG,
                        "Audio format ${candidate.formatId} failed.",
                        error
                    )

                    val lowerDetail = detail.lowercase(Locale.ROOT)

                    if (
                        lowerDetail.contains("no space left") ||
                        lowerDetail.contains("errno 28") ||
                        lowerDetail.contains("not enough space")
                    ) {
                        throw IOException(
                            "لا توجد مساحة كافية لمتابعة تنزيل الصوت: $detail",
                            error
                        )
                    }
                }
            }

            if (audioFile == null) {
                throw IOException(
                    "فشل تنزيل الصوت بعد تجربة ${candidates.size} صيغ. " +
                            audioErrors.joinToString(" || ")
                )
            }
        }

        // -------------------------------------------------
        // 4. تأكيد وجود مساري الفيديو والصوت
        // -------------------------------------------------

        val finalAudioFile = audioFile

        val finalAudioProbe = audioProbe
            ?: throw IOException("تعذر فحص ملف الصوت.")

        if (finalAudioProbe.audioCodec.isNullOrBlank()) {
            throw IOException("ملف الصوت لا يحتوي على مسار صوت صالح.")
        }

        val sameInputFile =
            videoFile.absolutePath == finalAudioFile.absolutePath

        val audioInputIndex = if (sameInputFile) {
            0
        } else {
            1
        }

        // -------------------------------------------------
        // 5. تحديد هل نحتاج لإعادة الترميز أم Stream Copy
        // -------------------------------------------------

        val copyVideo = isMp4CompatibleH264(
            videoProbe
        )

        val copyAudio = isMp4CompatibleAacLc(
            finalAudioProbe
        )

        val targetVideoBitrate = if (copyVideo) {
            null
        } else {
            chooseH264BitrateKbps(videoProbe)
        }

        Log.i(
            TAG,
            "FFMPEG_PLAN " +
                    "sourceVideo=${videoProbe.videoCodec}, " +
                    "sourceVideoProfile=${videoProbe.videoProfile}, " +
                    "sourcePixelFormat=${videoProbe.videoPixelFormat}, " +
                    "sourceAudio=${finalAudioProbe.audioCodec}, " +
                    "sourceAudioProfile=${finalAudioProbe.audioProfile}, " +
                    "videoAction=${if (copyVideo) "copy" else "encode"}, " +
                    "audioAction=${if (copyAudio) "copy" else "encode"}, " +
                    "videoBitrateKbps=$targetVideoBitrate"
        )

        // -------------------------------------------------
        // 6. تقدير عدد فريمات المصدر
        // -------------------------------------------------

        report.info(
            "FFMPEG_PLAN",
            "videoAction=${if (copyVideo) "copy" else "encode"} " +
                    "audioAction=${if (copyAudio) "copy" else "encode"} " +
                    "videoBitrateKbps=$targetVideoBitrate " +
                    "sameInputFile=$sameInputFile"
        )

        val frameEstimateStartedAt = SystemClock.elapsedRealtime()

        val totalVideoFrames = estimateVideoFrameCount(
            videoFile,
            selectedFormat.fps
        )

        report.step(
            "SOURCE_FRAME_ESTIMATE",
            frameEstimateStartedAt,
            "frames=$totalVideoFrames"
        )

        reportProgress(
            DownloadStage.PROCESSING,
            0f,
            "جاري تجهيز ملف MP4 النهائي..."
        )

        val mergedFile = File(
            workDir,
            "merged.mp4"
        )

        // -------------------------------------------------
        // 7. تجهيز أوامر FFmpeg
        // -------------------------------------------------

        val ffmpegArguments = mutableListOf(
            "-y",
            "-i",
            videoFile.absolutePath
        )

        if (!sameInputFile) {
            ffmpegArguments.addAll(
                listOf(
                    "-i",
                    finalAudioFile.absolutePath
                )
            )
        }

        ffmpegArguments.addAll(
            listOf(
                "-map", "0:v:0",
                "-map", "$audioInputIndex:a:0",
                "-map_metadata", "0"
            )
        )

        if (copyVideo) {

            ffmpegArguments.addAll(
                listOf(
                    "-c:v", "copy"
                )
            )

        } else {

            ffmpegArguments.addAll(
                listOf(
                    "-c:v", "libopenh264",
                    "-profile:v", "main",
                    "-b:v", "${targetVideoBitrate}k",
                    "-pix_fmt", "yuv420p",
                    "-fps_mode:v", "passthrough"
                )
            )
        }

        if (copyAudio) {

            // نقل الصوت كما هو دون إعادة ترميز.
            ffmpegArguments.addAll(
                listOf(
                    "-c:a", "copy"
                )
            )

        } else {

            // تحويل الصوت غير المتوافق إلى AAC-LC.
            ffmpegArguments.addAll(
                listOf(
                    "-c:a", "aac",
                    "-profile:a", "aac_low",
                    "-b:a", "128k"
                )
            )
        }

        ffmpegArguments.addAll(
            listOf(
                "-movflags", "+faststart",
                "-f", "mp4",
                mergedFile.absolutePath
            )
        )

        // -------------------------------------------------
        // 8. تنفيذ FFmpeg ومتابعة التقدم
        // -------------------------------------------------

        val mergeFinished = CountDownLatch(1)

        var completedFfmpegSession: FFmpegSession? = null

        val ffmpegStartedAt = SystemClock.elapsedRealtime()

        val runningFfmpegSession =
            FFmpegKit.executeWithArgumentsAsync(

                ffmpegArguments.toTypedArray(),

                { session ->

                    completedFfmpegSession = session

                    mergeFinished.countDown()

                    // لو تم إلغاء العملية ولم يصل Callback
                    // إلا بعد انتهاء مهلة الانتظار، ننظف المجلد هنا.
                    if (cleanupAfterFfmpeg.get()) {

                        try {
                            val deleted = workDir.deleteRecursively()
                            Log.i(
                                TAG,
                                "FFMPEG_TEMP_CLEANUP_AFTER_CANCEL=$deleted"
                            )
                        } finally {
                            DownloadWorkspaceRegistry.unregister(workDir)
                        }
                    }
                },

                { log ->

                    Log.i(
                        TAG,
                        "FFMPEG_TRANSCODE_LOG: ${log.message}"
                    )
                },

                { statistics ->

                    val durationMs = (
                            (videoProbe.durationSeconds ?: 0.0) * 1000.0
                            )

                    // statistics.time is already expressed in milliseconds.
                    val processedMs = statistics.time
                        .coerceAtLeast(0.0)

                    val frameProgress = if (totalVideoFrames > 0) {
                        statistics.videoFrameNumber.toFloat() /
                                totalVideoFrames.toFloat()
                    } else {
                        0f
                    }

                    val processingProgress = if (
                        durationMs > 0.0 &&
                        processedMs >= 0.0 &&
                        processedMs <= durationMs * 1.02
                    ) {
                        (processedMs / durationMs * 100.0)
                            .toFloat()
                            .coerceIn(0f, 100f)
                    } else {
                        frameProgress * 100f
                    }

                    val speed = statistics.speed
                        .takeIf { it.isFinite() && it > 0.0 }

                    val processingEtaMs = speed?.let {
                        (
                                (durationMs - processedMs)
                                    .coerceAtLeast(0.0) / it
                                ).roundToLong()
                    }

                    reportProgress(
                        stage = DownloadStage.PROCESSING,
                        stagePercent = processingProgress,
                        message = "جاري معالجة الفيديو...",
                        etaMs = processingEtaMs
                    )

                    Log.d(
                        TAG,
                        "FFMPEG_PROGRESS: " +
                                "frames=${statistics.videoFrameNumber}/$totalVideoFrames " +
                                "timeMs=$processedMs durationMs=$durationMs " +
                                "speed=$speed etaMs=$processingEtaMs"
                    )
                }
            )

        // المهلة تعتمد على مدة الفيديو، بحد أدنى 10 دقائق
        // وحد أقصى 3 ساعات.
        val timeoutMs = (
                (videoProbe.durationSeconds ?: 60.0) * 30_000.0
                ).coerceIn(
                600_000.0,
                10_800_000.0
            ).toLong()

        Log.i(
            TAG,
            "FFMPEG_TIMEOUT_MS=$timeoutMs"
        )

        try {

            val finishedInTime = mergeFinished.await(
                timeoutMs,
                TimeUnit.MILLISECONDS
            )

            if (!finishedInTime) {

                FFmpegKit.cancel(
                    runningFfmpegSession.getSessionId()
                )

                val stopped = mergeFinished.await(
                    30,
                    TimeUnit.SECONDS
                )

                if (!stopped) {

                    cleanupAfterFfmpeg.set(true)

                    if (mergeFinished.count == 0L) {
                        try {
                            workDir.deleteRecursively()
                        } finally {
                            DownloadWorkspaceRegistry.unregister(workDir)
                        }
                    }
                }

                throw IOException(
                    "تجاوزت معالجة الفيديو المهلة الزمنية المحددة. " +
                            "تم طلب إيقاف FFmpeg."
                )
            }

        } catch (error: InterruptedException) {

            runCatching {
                FFmpegKit.cancel(
                    runningFfmpegSession.getSessionId()
                )
            }

            val stopped = try {
                mergeFinished.await(
                    30,
                    TimeUnit.SECONDS
                )
            } catch (_: InterruptedException) {
                false
            }

            if (!stopped) {

                cleanupAfterFfmpeg.set(true)

                if (mergeFinished.count == 0L) {
                    try {
                        workDir.deleteRecursively()
                    } finally {
                        DownloadWorkspaceRegistry.unregister(workDir)
                    }
                }
            }

            Thread.currentThread().interrupt()

            throw IOException(
                "تم إيقاف عملية معالجة الفيديو.",
                error
            )
        }

        Log.i(
            TAG,
            "FFMPEG_TRANSCODE_TIME_MS=" +
                    (SystemClock.elapsedRealtime() - ffmpegStartedAt)
        )

        report.ffmpeg(
            ffmpegStartedAt,
            videoProbe.durationSeconds,
            totalVideoFrames,
            mergedFile.length()
        )

        val ffmpegSession = completedFfmpegSession
            ?: throw IOException(
                "لم يتم استلام نتيجة عملية FFmpeg."
            )

        Log.i(
            TAG,
            "FFMPEG_TRANSCODE_RETURN=${ffmpegSession.getReturnCode()}"
        )

        if (
            !ReturnCode.isSuccess(ffmpegSession.getReturnCode()) ||
            !mergedFile.exists() ||
            mergedFile.length() <= 0L
        ) {

            val details = ffmpegSession.getFailStackTrace()
                ?: ffmpegSession.output
                ?: "لم يُرجع FFmpeg تفاصيل إضافية."

            throw IOException(
                "فشل تحويل الفيديو: $details"
            )
        }
        DownloadRoutePlanner.recordProcessingSample(
            context = context,
            sourceVideoCodec = videoProbe.videoCodec,
            height = videoProbe.videoHeight,
            sourceAudioCodec = finalAudioProbe.audioCodec,
            videoNeedsEncode = !copyVideo,
            audioNeedsEncode = !copyAudio,
            durationSeconds = videoProbe.durationSeconds,
            elapsedMs = SystemClock.elapsedRealtime() - ffmpegStartedAt,
            encoderMode = if (!copyVideo) "openh264" else "aac"
        )
        // -------------------------------------------------
        // 9. التحقق من مواصفات المِلف النهائي
        // -------------------------------------------------
        reportProgress(
            DownloadStage.VALIDATING,
            0f,
            "جاري التحقق من سلامة الفيديو..."
        )
        val finalProbeStartedAt = SystemClock.elapsedRealtime()

        val outputProbe = logMediaProbe(
            file = mergedFile
        )

        report.step(
            "FINAL_PROBE_WITH_FULL_DECODE",
            finalProbeStartedAt,
            describeProbe(outputProbe)
        )

        if (
            !mergedFile.extension.equals("mp4", ignoreCase = true) ||
            !outputProbe.formatNames.contains("mp4", ignoreCase = true)
        ) {
            throw IOException(
                "الملف الناتج ليس حاوية MP4 صالحة."
            )
        }

        if (!isMp4CompatibleH264(outputProbe)) {
            throw IOException(
                "مواصفات الفيديو النهائي غير متوافقة. " +
                        "Codec=${outputProbe.videoCodec}, " +
                        "Profile=${outputProbe.videoProfile}, " +
                        "PixelFormat=${outputProbe.videoPixelFormat}."
            )
        }

        if (!isMp4CompatibleAacLc(outputProbe)) {
            throw IOException(
                "مواصفات الصوت النهائي غير متوافقة. " +
                        "Codec=${outputProbe.audioCodec}, " +
                        "Profile=${outputProbe.audioProfile}."
            )
        }

        val mergedVideoFrames = outputProbe.videoFrameCount ?: run {
            Log.w(
                TAG,
                "FINAL_FRAME_COUNT_METADATA_MISSING; using full frame scan"
            )

            val fallbackStartedAt = SystemClock.elapsedRealtime()
            val frames = countVideoFrames(mergedFile)

            report.step(
                "FINAL_FRAME_COUNT_FALLBACK",
                fallbackStartedAt,
                "frames=$frames"
            )

            frames
        }

        val minimumRequiredFrames = ceil(
            totalVideoFrames * 0.98
        ).toInt().coerceAtLeast(1)

        if (mergedVideoFrames < minimumRequiredFrames) {
            throw IOException(
                "الفيديو الناتج غير مكتمل. " +
                        "الفريمات الأصلية المقدرة: $totalVideoFrames، " +
                        "الفريمات الناتجة: $mergedVideoFrames."
            )
        }

        val sourceDuration = videoProbe.durationSeconds
        val outputDuration = outputProbe.durationSeconds

        if (
            sourceDuration != null &&
            outputDuration != null &&
            outputDuration < sourceDuration * 0.98
        ) {
            throw IOException(
                "مدة الفيديو الناتج أقصر من مدة المصدر بصورة غير مقبولة."
            )
        }

        Log.i(
            TAG,
            "FINAL_OUTPUT_VALIDATION=SUCCESS"
        )
        reportProgress(
            DownloadStage.VALIDATING,
            100f,
            "اكتمل التحقق من الفيديو.",
            0L
        )

        reportProgress(
            DownloadStage.SAVING,
            0f,
            "جاري حفظ الفيديو..."
        )

        // -------------------------------------------------
        // 10. حفظ المِلف النهائي في Movies
        // -------------------------------------------------

        val saveStartedAt = SystemClock.elapsedRealtime()

        val savedUri = saveVideoToMovies(
            context,
            mergedFile,
            title
        ) { copyProgress ->

            val saveEtaMs = selectedFormat.estimatedSaveMs?.let {
                (
                        it.toDouble() *
                                (100f - copyProgress) / 100.0
                        ).roundToLong()
            }

            reportProgress(
                stage = DownloadStage.SAVING,
                stagePercent = copyProgress,
                message = "جاري حفظ الفيديو: ${copyProgress.toInt()}%",
                etaMs = saveEtaMs
            )
        }

        report.transfer(
            "SAVE_TO_MOVIES",
            saveStartedAt,
            0L,
            mergedFile.length()
        )
        DownloadRoutePlanner.recordSaveSample(
            context = context,
            bytes = mergedFile.length(),
            elapsedMs = SystemClock.elapsedRealtime() - saveStartedAt
        )

        progressEstimator.finish()

        onProgress(
            100f,
            "تم الحفظ بنجاح.",
            0L
        )

        return savedUri

    } catch (error: Throwable) {

        if (videoDownloadAttempted && !videoDownloadSucceeded && !Thread.currentThread().isInterrupted) {
            DownloadRoutePlanner.recordAttemptFailure(
                context = context,
                sourceUrl = url,
                protocol = selectedFormat.protocol
            )
        }
        report.fail(error)

        throw error

    } finally {

        // لا تحذف الملفات إذا كان FFmpeg لا يزال يعمل بعد الإلغاء.
        // Callback الخاص به سيتولى التنظيف عند انتهاء العملية.
        if (!cleanupAfterFfmpeg.get()) {
            try {
                workDir.deleteRecursively()
            } finally {
                DownloadWorkspaceRegistry.unregister(workDir)
            }
        }

        report.finish(context)
    }
}
