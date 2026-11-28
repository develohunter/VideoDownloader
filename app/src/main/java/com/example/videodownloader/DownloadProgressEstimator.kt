package com.example.videodownloader

import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

internal enum class DownloadStage {
    VIDEO_DOWNLOAD,
    AUDIO_DOWNLOAD,
    PROCESSING,
    VALIDATING,
    SAVING
}

internal data class DownloadProgressSnapshot(
    val stage: DownloadStage,
    val progress: Float,
    val etaRemainingMs: Long?,
    val waitingForNetwork: Boolean
)

internal data class DownloadTransferMetrics(
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val totalBytesEstimate: Long? = null,
    val speedBytesPerSecond: Double? = null,
    val etaMs: Long? = null,
    val fragmentIndex: Int? = null,
    val fragmentCount: Int? = null
) {

    fun estimatedRemainingMs(): Long? {
        etaMs?.let { return it }

        val total = totalBytes ?: totalBytesEstimate ?: return null
        val downloaded = downloadedBytes ?: return null
        val speed = speedBytesPerSecond
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return null

        val remainingBytes = (total - downloaded).coerceAtLeast(0L)
        val result = remainingBytes.toDouble() / speed * 1000.0

        return result
            .takeIf { it.isFinite() && it >= 0.0 }
            ?.roundToLong()
    }

    companion object {

        fun fromCallback(
            callbackEtaSeconds: Long,
            line: String?
        ): DownloadTransferMetrics {

            val json = line
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching { JSONObject(it) }.getOrNull()
                }

            fun readLong(key: String): Long? {
                if (json == null || !json.has(key) || json.isNull(key)) {
                    return null
                }

                return json.optLong(key).takeIf { it >= 0L }
            }

            fun readInt(key: String): Int? {
                if (json == null || !json.has(key) || json.isNull(key)) {
                    return null
                }

                return json.optInt(key).takeIf { it >= 0 }
            }

            fun readDouble(key: String): Double? {
                if (json == null || !json.has(key) || json.isNull(key)) {
                    return null
                }

                return json.optDouble(key).takeIf { it.isFinite() }
            }

            val jsonEta = readDouble("eta")

            val etaMs = when {
                jsonEta != null ->
                    (jsonEta * 1000.0).roundToLong().coerceAtLeast(0L)

                callbackEtaSeconds > 0L ->
                    callbackEtaSeconds * 1000L

                else -> null
            }

            return DownloadTransferMetrics(
                downloadedBytes = readLong("downloaded_bytes"),
                totalBytes = readLong("total_bytes"),
                totalBytesEstimate = readLong("total_bytes_estimate"),
                speedBytesPerSecond = readDouble("speed")
                    ?.takeIf { it > 0.0 },
                etaMs = etaMs,
                fragmentIndex = readInt("fragment_index"),
                fragmentCount = readInt("fragment_count")
            )
        }
    }
}

internal class DownloadProgressEstimator(format: VideoFormat) {

    private val stageBudgets: LongArray

    private val totalBudgetMs: Long

    private var currentIndex = 0
    private var currentStageProgress = 0f
    private var completedBudgetMs = 0L

    private var currentEtaMs: Long
    private var etaUpdatedAt = SystemClock.elapsedRealtime()

    private var lastRealProgressAt = 0L

    private var displayedProgress = 0f

    private var lastSnapshotAt = SystemClock.elapsedRealtime()
    private var activeElapsedMs = 0L

    private var waitingForNetwork = false
    private var finished = false

    init {
        val fallbackTotal = (
                format.estimatedTotalMs ?: 60_000L
                ).coerceAtLeast(1_000L)

        val retryRisk = (
                format.estimatedRetryRiskMs ?: 0L
                ).coerceAtLeast(0L)

        val videoBudget = (
                (
                        format.estimatedVideoDownloadMs
                            ?: (fallbackTotal * 45L / 100L)
                        )
                ).coerceAtLeast(100L) + retryRisk / 2L

        val audioBudget = if (format.hasAudio) {
            0L
        } else {
            (
                    (
                            format.estimatedAudioDownloadMs
                                ?: (fallbackTotal * 12L / 100L)
                            )
                    ).coerceAtLeast(100L) + retryRisk - retryRisk / 2L
        }

        val processingBudget = (
                (
                        format.estimatedProcessingMs
                            ?: (fallbackTotal * 30L / 100L)
                        )
                ).coerceAtLeast(100L)

        val validationBudget = (
                (
                        format.estimatedValidationMs
                            ?: (fallbackTotal * 5L / 100L)
                        )
                ).coerceAtLeast(100L)

        val saveBudget = (
                (
                        format.estimatedSaveMs
                            ?: (fallbackTotal * 8L / 100L)
                        )
                ).coerceAtLeast(100L)

        stageBudgets = longArrayOf(
            videoBudget,
            audioBudget,
            processingBudget,
            validationBudget,
            saveBudget
        )

        totalBudgetMs = stageBudgets
            .fold(0L) { sum, value ->
                (sum + value).coerceAtMost(Long.MAX_VALUE / 4L)
            }
            .coerceAtLeast(1_000L)

        currentEtaMs = stageBudgets[0]
    }
    @Synchronized
    fun update(
        stage: DownloadStage,
        stagePercent: Float,
        etaMs: Long? = null,
        metrics: DownloadTransferMetrics? = null
    ): DownloadProgressSnapshot {

        val now = SystemClock.elapsedRealtime()
        val nextIndex = stage.ordinal

        if (nextIndex > currentIndex) {
            moveToStage(nextIndex, now)
        }

        if (nextIndex < currentIndex) {
            return snapshotAt(now)
        }

        val safePercent = if (stagePercent.isFinite()) {
            stagePercent.coerceIn(0f, 100f)
        } else {
            currentStageProgress * 100f
        }

        val previousProgress = currentStageProgress

        currentStageProgress = max(
            currentStageProgress,
            safePercent / 100f
        )

        val liveEta = etaMs ?: metrics?.estimatedRemainingMs()

        if (liveEta != null) {
            currentEtaMs = liveEta.coerceAtLeast(0L)
            etaUpdatedAt = now

        } else {
            currentEtaMs = (
                    stageBudgets[currentIndex] *
                            (1f - currentStageProgress)
                    ).roundToLong().coerceAtLeast(0L)

            etaUpdatedAt = now
        }

        if (
            currentStageProgress > previousProgress ||
            metrics?.speedBytesPerSecond != null ||
            liveEta != null
        ) {
            lastRealProgressAt = now
        }

        return snapshotAt(now)
    }

    fun completeStage(stage: DownloadStage): DownloadProgressSnapshot {
        return update(
            stage = stage,
            stagePercent = 100f,
            etaMs = 0L
        )
    }
    @Synchronized
    fun setWaitingForNetwork(waiting: Boolean) {
        if (waitingForNetwork == waiting) return

        waitingForNetwork = waiting

        val now = SystemClock.elapsedRealtime()

        // Waiting time must not consume the remaining-time estimate.
        lastSnapshotAt = now
        etaUpdatedAt = now
    }
    @Synchronized
    fun snapshot(): DownloadProgressSnapshot {
        return snapshotAt(SystemClock.elapsedRealtime())
    }
    @Synchronized
    fun finish(): DownloadProgressSnapshot {
        finished = true
        displayedProgress = 100f

        return DownloadProgressSnapshot(
            stage = DownloadStage.SAVING,
            progress = 100f,
            etaRemainingMs = 0L,
            waitingForNetwork = false
        )
    }

    private fun moveToStage(index: Int, now: Long) {
        for (i in currentIndex until index) {
            completedBudgetMs = (
                    completedBudgetMs + stageBudgets[i]
                    ).coerceAtMost(Long.MAX_VALUE / 4L)
        }

        currentIndex = index
        currentStageProgress = 0f

        currentEtaMs = stageBudgets[index]
        etaUpdatedAt = now
        lastRealProgressAt = 0L
    }

    private fun snapshotAt(now: Long): DownloadProgressSnapshot {

        if (finished) {
            return DownloadProgressSnapshot(
                stage = DownloadStage.SAVING,
                progress = 100f,
                etaRemainingMs = 0L,
                waitingForNetwork = false
            )
        }

        val deltaMs = (now - lastSnapshotAt).coerceAtLeast(0L)
        lastSnapshotAt = now

        val currentStage = DownloadStage.entries[currentIndex]

        val isTransferStage =
            currentStage == DownloadStage.VIDEO_DOWNLOAD ||
                    currentStage == DownloadStage.AUDIO_DOWNLOAD

        val staleTransfer = isTransferStage &&
                lastRealProgressAt > 0L &&
                now - lastRealProgressAt > 12_000L

        val frozen = waitingForNetwork || staleTransfer

        if (!frozen) {
            activeElapsedMs = (
                    activeElapsedMs + deltaMs
                    ).coerceAtMost(Long.MAX_VALUE / 4L)
        }

        val currentRemaining = if (frozen) {
            null
        } else {
            (
                    currentEtaMs - (now - etaUpdatedAt)
                    ).coerceAtLeast(0L)
        }

        var futureRemaining = 0L

        for (i in currentIndex + 1 until stageBudgets.size) {
            futureRemaining = (
                    futureRemaining + stageBudgets[i]
                    ).coerceAtMost(Long.MAX_VALUE / 4L)
        }

        val remainingMs = if (frozen) {
            null
        } else {
            (
                    (currentRemaining ?: 0L) + futureRemaining
                    ).coerceAtMost(Long.MAX_VALUE / 4L)
        }

        val weightedCompleted = (
                completedBudgetMs.toDouble() +
                        stageBudgets[currentIndex].toDouble() *
                        currentStageProgress.toDouble()
                )

        val weightedProgress = (
                weightedCompleted / totalBudgetMs.toDouble() * 100.0
                ).toFloat().coerceIn(0f, 99.4f)

        val timeProgress = if (
            remainingMs != null &&
            activeElapsedMs + remainingMs > 0L
        ) {
            (
                    activeElapsedMs.toDouble() /
                            (activeElapsedMs + remainingMs).toDouble() *
                            100.0
                    ).toFloat().coerceIn(0f, 99.4f)
        } else {
            weightedProgress
        }

        val targetProgress = if (frozen) {
            displayedProgress
        } else {
            max(weightedProgress, timeProgress).coerceIn(0f, 99.4f)
        }

        val maximumAdvance = max(
            0.4f,
            deltaMs / 1000f * 18f
        )

        if (!frozen) {
            displayedProgress = min(
                targetProgress,
                displayedProgress + maximumAdvance
            ).coerceAtMost(99.4f)
        }

        return DownloadProgressSnapshot(
            stage = currentStage,
            progress = displayedProgress,
            etaRemainingMs = remainingMs,
            waitingForNetwork = waitingForNetwork
        )
    }
}

internal fun formatRemainingTime(etaRemainingMs: Long?): String {

    if (etaRemainingMs == null) {
        return "جارٍ حساب الوقت"
    }

    val seconds = ceil(
        etaRemainingMs.coerceAtLeast(0L) / 1000.0
    ).toLong()

    if (seconds <= 3L) {
        return "ثوانٍ قليلة"
    }

    if (seconds < 60L) {
        return "$seconds ثانية"
    }

    val minutes = seconds / 60L
    val remainingSeconds = seconds % 60L

    if (minutes < 60L) {
        return if (remainingSeconds == 0L) {
            "$minutes دقيقة"
        } else {
            "$minutes د و $remainingSeconds ث"
        }
    }

    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L

    return if (remainingMinutes == 0L) {
        "$hours س"
    } else {
        "$hours س و $remainingMinutes د"
    }
}
