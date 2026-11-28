package com.example.videodownloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.Locale
import kotlin.collections.filter
import kotlin.time.Duration.Companion.seconds
private const val TAG = "VideoDownloader"
private fun hasValidatedInternet(context: Context): Boolean {
    val manager = context.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as? ConnectivityManager ?: return false

    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false

    return capabilities.hasCapability(
        NetworkCapabilities.NET_CAPABILITY_INTERNET
    ) && capabilities.hasCapability(
        NetworkCapabilities.NET_CAPABILITY_VALIDATED
    )
}

private fun readableSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "الحجم غير متاح"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

@Composable
private fun DownloadQueueTaskCard(
    task: DownloadQueueEntity,
    auroraText: Color,
    auroraMuted: Color,
    auroraAccent: Color,
    auroraAccentSoft: Color,
    auroraTrack: Color,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    liveProgress: Float? = null,
    etaRemainingMs: Long? = null,
    isDragging: Boolean = false,
    dragHandleModifier: Modifier? = null
) {
    val active = task.status == DownloadTaskUiState.STATUS_QUEUED ||
            task.status == DownloadTaskUiState.STATUS_RUNNING ||
            task.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
    val currentProgress = (liveProgress ?: task.progress)
        .coerceIn(0f, 100f)
    val statusLabel = when (task.status) {
        DownloadTaskUiState.STATUS_QUEUED -> "في الانتظار"
        DownloadTaskUiState.STATUS_RUNNING -> "جاري التنزيل"
        DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK -> "في انتظار الإنترنت"
        DownloadTaskUiState.STATUS_SUCCEEDED -> "اكتمل التنزيل"
        DownloadTaskUiState.STATUS_FAILED -> "فشل التنزيل"
        DownloadTaskUiState.STATUS_CANCELLED -> "تم الإلغاء"
        else -> task.status
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val scale = if (isDragging) 1.025f else 1f
                scaleX = scale
                scaleY = scale
            }
            .background(
                color = Color(0x332E3A60),
                shape = RoundedCornerShape(15.dp)
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = task.title.ifBlank { "فيديو بدون عنوان" },
                modifier = Modifier.weight(1f),
                color = auroraText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )

            if (dragHandleModifier != null) {
                Box(
                    modifier = dragHandleModifier.size(38.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⠿",
                        color = auroraAccentSoft,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusLabel,
                color = auroraMuted,
                fontSize = 12.sp
            )

            Text(
                text = "${currentProgress.toInt()}%",
                color = auroraAccentSoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        FilmstripProgressBar(
            progress = (currentProgress / 100f).coerceIn(0f, 1f),
            active = task.status == DownloadTaskUiState.STATUS_RUNNING,
            accent = auroraAccent,
            accentSoft = auroraAccentSoft,
            track = auroraTrack,
            modifier = Modifier.fillMaxWidth()
        )
        if (active) {
            val etaText = when (task.status) {
                DownloadTaskUiState.STATUS_QUEUED ->
                    "في انتظار بدء التنزيل"

                DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK ->
                    "التقدير متوقف حتى عودة الاتصال"

                else ->
                    "الوقت المتبقي: ${formatRemainingTime(etaRemainingMs)}"
            }

            Text(
                text = etaText,
                color = auroraMuted,
                fontSize = 11.sp
            )
        }
        if (active) {
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF713D55)
                )
            ) {
                Text("إلغاء هذا التنزيل")
            }
        }
    }
}

@Composable
fun DownloaderScreen(engineReady: Boolean, context: Context) {
    val downloadUiState by DownloadUiStateStore.state.collectAsState()

    val queueRepository = remember {
        DownloadQueueRepository.getInstance(context)
    }

    val queueTasks by queueRepository.tasks.collectAsState(
        initial = emptyList()
    )

    val liveDownloadTasks by DownloadUiStateStore.tasks.collectAsState()

    val liveDownloadTasksById = remember(liveDownloadTasks) {
        liveDownloadTasks.associateBy { it.id }
    }

    val screenScope = rememberCoroutineScope()

    var queuedOrder by remember {
        mutableStateOf<List<DownloadQueueEntity>>(emptyList())
    }

    var isQueueReordering by remember {
        mutableStateOf(false)
    }

    var isSavingQueueOrder by remember {
        mutableStateOf(false)
    }

    var queueOrderBeforeDrag by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    var queueOrderMessage by remember {
        mutableStateOf<String?>(null)
    }

    val queueLazyListState = rememberLazyListState()

    LaunchedEffect(queueTasks) {
        if (!isQueueReordering && !isSavingQueueOrder) {
            queuedOrder = queueTasks.filter {
                it.status == DownloadTaskUiState.STATUS_QUEUED
            }
        }
    }

    val reorderableQueueState = rememberReorderableLazyListState(
        queueLazyListState
    ) { from, to ->
        if (
            from.index in queuedOrder.indices &&
            to.index in queuedOrder.indices &&
            from.index != to.index
        ) {
            queuedOrder = queuedOrder.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }
    }

    var isStartingDownload by remember { mutableStateOf(false) }
    val isDownloading = downloadUiState.isRunning || isStartingDownload
    var downloadMessage by remember { mutableStateOf("") }
    var prayerMessage by remember { mutableStateOf("") }

    // Midnight Aurora palette stays local to this screen.
    val auroraCard = Color(0xE61A2341)
    val auroraAccent = Color(0xFF7164D8)
    val auroraAccentSoft = Color(0xFFA99CFF)
    val auroraText = Color(0xFFF1F2FF)
    val auroraMuted = Color(0xFFAAB7D7)
    val auroraBorder = Color(0x3DADB8FF)
    val auroraTrack = Color(0xFF303B61)
    val auroraDanger = Color(0xFFFF9AAB)
    val auroraPrayer = Color(0xFFE0D8FF)
    val auroraSelected = Color(0x664F569D)

    val selectedFormat = AnalysisStateStore.formats.firstOrNull {
        it.isAvailable && it.formatId == AnalysisStateStore.selectedFormatId
    }

    val analysisInteractionSource = remember { MutableInteractionSource() }
    val analysisPressed by analysisInteractionSource.collectIsPressedAsState()
    val analysisButtonScale by animateFloatAsState(
        targetValue = if (analysisPressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "analysisButtonScale"
    )

    // Restored download button interaction and animation.
    val downloadInteractionSource = remember { MutableInteractionSource() }
    val downloadPressed by downloadInteractionSource.collectIsPressedAsState()
    val downloadButtonScale by animateFloatAsState(
        targetValue = if (downloadPressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "downloadButtonScale"
    )

    // Preserve the exact prayer phrases and the original 3-second interval.
    LaunchedEffect(isDownloading) {
        if (isDownloading) {
            val phrases = listOf(
                "اللهم صل وسلم وبارك على نبينا محمد",
                "لا حول ولا قوة إلا بالله",
                "أستغفر الله الذي لا إله إلا هو الحي القيوم وأتوب إليه",
                "لا إله إلا الله وحده لا شريك له",
                "له الملك وله الحمد وهو على كل شيء قدير",
                "الحمد لله وسبحان الله والله أكبر ولا إله إلا الله"
            )

            var phraseIndex = 0
            while (true) {
                prayerMessage = phrases[phraseIndex]
                phraseIndex = (phraseIndex + 1) % phrases.size
                delay(3.seconds)
            }
        }
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Restored foreground-service launch and notification permission flow.
    var pendingDownloadIntent by remember { mutableStateOf<Intent?>(null) }

    val launchDownloadService: (Intent?) -> Unit = { request ->
        pendingDownloadIntent = null

        if (request == null) {
            isStartingDownload = false
        } else {
            try {
                ContextCompat.startForegroundService(context, request)
            } catch (error: Exception) {
                DownloadUiStateStore.failToStart(
                    "تعذر بدء التنزيل: ${errorDetails(error)}"
                )

                downloadMessage = "تعذر بدء التنزيل: ${errorDetails(error)}"

                Log.e(TAG, "Could not start download foreground service", error)
            } finally {
                isStartingDownload = false
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Denying notification permission does not prevent the foreground service.
        launchDownloadService(pendingDownloadIntent)
    }

    val lifecycleOwner = context as? LifecycleOwner
    var activityResumed by remember {
        mutableStateOf(
            lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)
                ?: true
        )
    }

    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) {
            activityResumed = true
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { owner, _ ->
                activityResumed = owner.lifecycle.currentState
                    .isAtLeast(Lifecycle.State.RESUMED)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            activityResumed = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(queueRepository, activityResumed) {
        if (!activityResumed) return@LaunchedEffect

        try {
            val storedTasks = queueRepository.getActiveTasks()

            val hasTasksToResume = storedTasks.any { task ->
                task.status == DownloadTaskUiState.STATUS_QUEUED ||
                        task.status == DownloadTaskUiState.STATUS_RUNNING ||
                        task.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
            }

            if (hasTasksToResume) {
                ContextCompat.startForegroundService(
                    context,
                    DownloadForegroundService.createResumeIntent(context)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Could not resume download queue", error)
        }
    }

    var screenEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        screenEntered = true
    }

    LaunchedEffect(downloadUiState.finishedAt) {
        if (downloadUiState.finishedAt > 0L) {
            downloadMessage = downloadUiState.terminalMessage
        }
    }

    LaunchedEffect(downloadUiState.savedUri, activityResumed) {
        val savedUriString = downloadUiState.savedUri ?: return@LaunchedEffect
        if (!activityResumed) return@LaunchedEffect

        try {
            val savedUri = savedUriString.toUri()
            val mime = context.contentResolver.getType(savedUri) ?: "video/*"
            val playIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(savedUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(playIntent, "اختار مشغل الفيديو")
            )
            DownloadUiStateStore.markSavedUriHandled(savedUriString)
        } catch (openError: Exception) {
            Log.w(TAG, "No video player available", openError)
            DownloadUiStateStore.markOpenFailed(
                savedUriString,
                "تم حفظ الفيديو، لكن لم يتم العثور على مشغل مناسب."
            )
        }
    }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AuroraAnimatedBackground(
                modifier = Modifier.fillMaxSize(),
                isActive = activityResumed
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Header: keep the existing arrow and title unchanged.
                    AnimatedVisibility(
                        visible = screenEntered,
                        enter = fadeIn(tween(650)) + slideInVertically(
                            animationSpec = tween(
                                650,
                                easing = FastOutSlowInEasing
                            ),
                            initialOffsetY = { it / 9 }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                color = Color(0x664D5EB8),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, auroraBorder),
                                tonalElevation = 0.dp
                            ) {
                                Box(
                                    modifier = Modifier.size(58.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "↓",
                                        color = Color.White,
                                        fontSize = 34.sp,
                                        fontWeight = FontWeight.Light
                                    )
                                }
                            }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "Video Downloader",
                                    fontSize = 23.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = auroraText
                                )
                                Text(
                                    text = "نزّل فيديوهاتك بسهولة",
                                    color = auroraMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // Link card.
                    AnimatedVisibility(
                        visible = screenEntered,
                        enter = fadeIn(tween(650, delayMillis = 90)) +
                                slideInVertically(
                                    animationSpec = tween(
                                        650,
                                        delayMillis = 90,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffsetY = { it / 12 }
                                )
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = auroraCard
                            ),
                            border = BorderStroke(1.dp, auroraBorder),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = 1.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Text(
                                        text = "↗",
                                        color = auroraAccentSoft,
                                        fontSize = 21.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "رابط الفيديو",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = auroraText
                                    )
                                }

                                OutlinedTextField(
                                    value = AnalysisStateStore.videoUrl,
                                    onValueChange = { value ->
                                        AnalysisStateStore.videoUrl = value
                                        AnalysisStateStore.formats = emptyList()
                                        AnalysisStateStore.audioFormats = emptyList()
                                        AnalysisStateStore.selectedFormatId = null
                                        AnalysisStateStore.videoTitle = ""
                                        AnalysisStateStore.statusMessage = ""
                                        downloadMessage = ""
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = {
                                        Text(
                                            "https://example.com/video",
                                            color = auroraMuted
                                        )
                                    },
                                    label = { Text("ضع الرابط هنا") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    enabled = !AnalysisStateStore.isAnalyzing &&
                                            !isStartingDownload,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = auroraText,
                                        unfocusedTextColor = auroraText,
                                        cursorColor = auroraAccentSoft,
                                        focusedContainerColor = Color(0x33101830),
                                        unfocusedContainerColor = Color(0x33101830),
                                        focusedBorderColor = auroraAccentSoft,
                                        unfocusedBorderColor = auroraBorder,
                                        focusedLabelColor = auroraAccentSoft,
                                        unfocusedLabelColor = auroraMuted,
                                        disabledTextColor = auroraMuted,
                                        disabledBorderColor = auroraBorder
                                    )
                                )

                                Button(
                                    onClick = {
                                        val input = AnalysisStateStore.videoUrl.trim()
                                        val uri = runCatching {
                                            input.toUri()
                                        }.getOrNull()

                                        val validUrl = uri != null &&
                                                (uri.scheme == "https" ||
                                                        uri.scheme == "http") &&
                                                !uri.host.isNullOrBlank()

                                        when {
                                            !validUrl -> {
                                                AnalysisStateStore.statusMessage =
                                                    "أدخل رابط HTTP أو HTTPS صحيحًا."
                                                AnalysisStateStore.statusIsError = true
                                            }
                                            !hasValidatedInternet(context) -> {
                                                AnalysisStateStore.statusMessage =
                                                    "لا يوجد اتصال بالإنترنت. اتصل بالشبكة ثم حاول مجددًا."
                                                AnalysisStateStore.statusIsError = true
                                            }
                                            !engineReady -> {
                                                AnalysisStateStore.statusMessage =
                                                    "محرك yt-dlp لم يبدأ بنجاح."
                                                AnalysisStateStore.statusIsError = true
                                            }

                                            else -> {
                                                AnalysisStateStore.isAnalyzing = true
                                                AnalysisStateStore.formats = emptyList()
                                                AnalysisStateStore.audioFormats = emptyList()
                                                AnalysisStateStore.selectedFormatId = null
                                                AnalysisStateStore.videoTitle = ""
                                                downloadMessage = ""
                                                AnalysisStateStore.statusMessage =
                                                    "جارٍ تحليل الرابط واستخراج الجودات..."
                                                AnalysisStateStore.statusIsError = false

                                                AnalysisStateStore.executor.execute {
                                                    try {
                                                        val result = extractVideoInfo(input, context)
                                                        mainHandler.post {
                                                            AnalysisStateStore.isAnalyzing = false
                                                            AnalysisStateStore.videoTitle = result.title
                                                            AnalysisStateStore.formats = result.formats
                                                            AnalysisStateStore.audioFormats = result.audioFormats

                                                            val availableOptions =
                                                                result.formats.filter {
                                                                    it.isAvailable
                                                                }

                                                            AnalysisStateStore.selectedFormatId =
                                                                availableOptions.firstOrNull()?.formatId

                                                            AnalysisStateStore.statusMessage =
                                                                if (availableOptions.isEmpty()) {
                                                                    "لم تتوفر مستويات جودة مناسبة لهذا الرابط."
                                                                } else {
                                                                    "تم تجهيز اختيارات الجودة المتاحة."
                                                                }

                                                            AnalysisStateStore.statusIsError =
                                                                availableOptions.isEmpty()
                                                        }
                                                    } catch (error: Exception) {
                                                        Log.e(
                                                            TAG,
                                                            "Video analysis failed",
                                                            error
                                                        )
                                                        mainHandler.post {
                                                            AnalysisStateStore.isAnalyzing = false
                                                            AnalysisStateStore.statusMessage =
                                                                "تعذر تحليل الرابط: ${errorDetails(error)}"
                                                            AnalysisStateStore.statusIsError = true
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = AnalysisStateStore.videoUrl.isNotBlank() &&
                                            !AnalysisStateStore.isAnalyzing &&
                                            !isStartingDownload,
                                    interactionSource = analysisInteractionSource,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .graphicsLayer {
                                            scaleX = analysisButtonScale
                                            scaleY = analysisButtonScale
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = auroraAccent,
                                        contentColor = Color.White,
                                        disabledContainerColor = Color(0xFF414267),
                                        disabledContentColor = Color(0xFFB5B5CC)
                                    )
                                ) {
                                    Text(
                                        text = if (AnalysisStateStore.isAnalyzing) {
                                            "جاري التحليل..."
                                        } else {
                                            "تحليل الرابط  ↗"
                                        },
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                AnimatedVisibility(
                                    visible = AnalysisStateStore.isAnalyzing,
                                    enter = fadeIn(tween(220)) +
                                            expandVertically(
                                                expandFrom = Alignment.Top,
                                                animationSpec = tween(260)
                                            ),
                                    exit = fadeOut(tween(160)) +
                                            shrinkVertically(
                                                shrinkTowards = Alignment.Top,
                                                animationSpec = tween(200)
                                            )
                                ) {
                                    AnalysisAnimation(
                                        accent = auroraAccentSoft,
                                        muted = auroraMuted,
                                        track = auroraTrack,
                                        isActive = activityResumed
                                    )
                                }

                                AnimatedVisibility(
                                    visible = AnalysisStateStore.statusMessage.isNotBlank(),
                                    enter = fadeIn(tween(240)) +
                                            expandVertically(
                                                expandFrom = Alignment.Top,
                                                animationSpec = tween(280)
                                            ),
                                    exit = fadeOut(tween(160)) +
                                            shrinkVertically(
                                                shrinkTowards = Alignment.Top,
                                                animationSpec = tween(200)
                                            )
                                ) {
                                    AnimatedContent(
                                        targetState = AnalysisStateStore.statusMessage,
                                        transitionSpec = {
                                            (fadeIn(tween(220)) +
                                                    slideInVertically(
                                                        animationSpec = tween(220),
                                                        initialOffsetY = { it / 5 }
                                                    )) togetherWith
                                                    (fadeOut(tween(160)) +
                                                            slideOutVertically(
                                                                animationSpec = tween(160),
                                                                targetOffsetY = { -it / 6 }
                                                            ))
                                        },
                                        label = "analysisStatusText"
                                    ) { statusText ->
                                        Text(
                                            text = statusText,
                                            color = if (AnalysisStateStore.statusIsError) {
                                                auroraDanger
                                            } else {
                                                auroraAccentSoft
                                            },
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Results section.
                    AnimatedVisibility(
                        visible = AnalysisStateStore.formats.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        enter = fadeIn(tween(420, delayMillis = 70)) +
                                expandVertically(
                                    expandFrom = Alignment.Top,
                                    animationSpec = tween(
                                        480,
                                        delayMillis = 70,
                                        easing = FastOutSlowInEasing
                                    )
                                ) +
                                slideInVertically(
                                    animationSpec = tween(
                                        480,
                                        delayMillis = 70,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffsetY = { it / 14 }
                                ),
                        exit = fadeOut(tween(180)) +
                                shrinkVertically(
                                    shrinkTowards = Alignment.Top,
                                    animationSpec = tween(220)
                                )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            // Available quality options.
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = auroraCard
                                ),
                                border = BorderStroke(1.dp, auroraBorder),
                                elevation = CardDefaults.cardElevation(
                                    defaultElevation = 1.dp
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                                    ) {
                                        Text(
                                            text = "✧",
                                            color = auroraAccentSoft,
                                            fontSize = 21.sp
                                        )
                                        Text(
                                            text = "الجودات المتاحة",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = auroraText
                                        )
                                    }

                                    Text(
                                        text = AnalysisStateStore.videoTitle,
                                        color = auroraMuted,
                                        fontSize = 13.sp
                                    )

                                    AnalysisStateStore.formats.forEach { item ->
                                        key(item.formatId) {
                                            val sizeLabel = item.fileSize?.let {
                                                val prefix =
                                                    if (item.sizeIsEstimate) "تقريبًا " else ""
                                                prefix + readableSize(it)
                                            } ?: "الحجم غير متاح"

                                            val isSelected = item.isAvailable &&
                                                    AnalysisStateStore.selectedFormatId ==
                                                    item.formatId

                                            val qualityCardColor by animateColorAsState(
                                                targetValue = if (isSelected) {
                                                    auroraSelected
                                                } else {
                                                    Color(0x24101830)
                                                },
                                                animationSpec = tween(
                                                    durationMillis = 340,
                                                    easing = FastOutSlowInEasing
                                                ),
                                                label = "qualityCardColor"
                                            )

                                            val qualityBorderColor by animateColorAsState(
                                                targetValue = if (isSelected) {
                                                    auroraAccentSoft
                                                } else {
                                                    auroraBorder
                                                },
                                                animationSpec = tween(
                                                    durationMillis = 340,
                                                    easing = FastOutSlowInEasing
                                                ),
                                                label = "qualityBorderColor"
                                            )

                                            val qualityScale by animateFloatAsState(
                                                targetValue = if (isSelected) 1f else 0.985f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    stiffness = Spring.StiffnessMedium
                                                ),
                                                label = "qualityCardScale"
                                            )

                                            Surface(
                                                onClick = {
                                                    if (item.isAvailable) {
                                                        AnalysisStateStore.selectedFormatId =
                                                            item.formatId
                                                        downloadMessage = ""
                                                    }
                                                },
                                                enabled = !AnalysisStateStore.isAnalyzing &&
                                                        !isStartingDownload &&
                                                        item.isAvailable,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .graphicsLayer {
                                                        scaleX = qualityScale
                                                        scaleY = qualityScale
                                                    }
                                                    .animateContentSize(),
                                                shape = RoundedCornerShape(16.dp),
                                                color = qualityCardColor,
                                                border = BorderStroke(
                                                    1.dp,
                                                    qualityBorderColor
                                                ),
                                                tonalElevation = 0.dp
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(
                                                            horizontal = 9.dp,
                                                            vertical = 8.dp
                                                        ),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    RadioButton(
                                                        selected = isSelected,
                                                        onClick = {
                                                            if (
                                                                !AnalysisStateStore.isAnalyzing &&
                                                                !isStartingDownload &&
                                                                item.isAvailable
                                                            ) {
                                                                AnalysisStateStore.selectedFormatId =
                                                                    item.formatId
                                                                downloadMessage = ""
                                                            }
                                                        },
                                                        enabled = !AnalysisStateStore.isAnalyzing &&
                                                                !isStartingDownload &&
                                                                item.isAvailable,
                                                        colors = RadioButtonDefaults.colors(
                                                            selectedColor = auroraAccentSoft,
                                                            unselectedColor = auroraMuted,
                                                            disabledSelectedColor =
                                                                auroraAccentSoft.copy(alpha = 0.55f),
                                                            disabledUnselectedColor =
                                                                auroraMuted.copy(alpha = 0.45f)
                                                        )
                                                    )

                                                    Column(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .padding(start = 4.dp),
                                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                                    ) {
                                                        Text(
                                                            text = item.qualityTier.label,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = if (item.isAvailable) {
                                                                auroraText
                                                            } else {
                                                                auroraMuted
                                                            }
                                                        )

                                                        Text(
                                                            text = if (item.isAvailable) {
                                                                "الحجم المتوقع: $sizeLabel"
                                                            } else {
                                                                "غير متاحة لهذا الرابط"
                                                            },
                                                            color = auroraMuted,
                                                            fontSize = 12.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Restored original download card.
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = auroraCard
                                ),
                                border = BorderStroke(1.dp, auroraBorder),
                                elevation = CardDefaults.cardElevation(
                                    defaultElevation = 1.dp
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(13.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                                    ) {
                                        Text(
                                            text = "↓",
                                            color = auroraAccentSoft,
                                            fontSize = 22.sp
                                        )
                                        Text(
                                            text = "تحميل الفيديو",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = auroraText
                                        )
                                    }

                                    Text(
                                        text = selectedFormat?.let {
                                            "الجودة المختارة: ${it.qualityTier.label}"
                                        } ?: "اختار جودة متاحة أولًا.",
                                        color = auroraMuted,
                                        fontSize = 14.sp
                                    )

                                    Button(
                                        onClick = {
                                            if (isStartingDownload) return@Button

                                            if (!engineReady) {
                                                downloadMessage =
                                                    "تعذر الإضافة: محرك yt-dlp غير جاهز."
                                                return@Button
                                            }

                                            val chosen = selectedFormat ?: return@Button

                                            isStartingDownload = true

                                            screenScope.launch {
                                                try {
                                                    val existingTasks =
                                                        queueRepository.getActiveTasks()

                                                    val shouldOpenWhenFinished =
                                                        existingTasks.none { task ->
                                                            task.status ==
                                                                    DownloadTaskUiState.STATUS_QUEUED ||
                                                                    task.status ==
                                                                    DownloadTaskUiState.STATUS_RUNNING ||
                                                                    task.status ==
                                                                    DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
                                                        }

                                                    queueRepository.enqueue(
                                                        url = AnalysisStateStore.videoUrl.trim(),
                                                        title = AnalysisStateStore.videoTitle,
                                                        selectedFormat = chosen,
                                                        audioFormats = AnalysisStateStore.audioFormats.toList(),
                                                        openOnFinish = shouldOpenWhenFinished
                                                    )

                                                    downloadMessage =
                                                        "تمت إضافة الفيديو إلى قائمة التنزيلات."

                                                    val requestIntent =
                                                        DownloadForegroundService.createResumeIntent(
                                                            context
                                                        )

                                                    if (
                                                        Build.VERSION.SDK_INT >=
                                                        Build.VERSION_CODES.TIRAMISU &&
                                                        ContextCompat.checkSelfPermission(
                                                            context,
                                                            Manifest.permission.POST_NOTIFICATIONS
                                                        ) != PackageManager.PERMISSION_GRANTED
                                                    ) {
                                                        pendingDownloadIntent = requestIntent
                                                        notificationPermissionLauncher.launch(
                                                            Manifest.permission.POST_NOTIFICATIONS
                                                        )
                                                    } else {
                                                        launchDownloadService(requestIntent)
                                                    }

                                                } catch (cancelled: CancellationException) {
                                                    pendingDownloadIntent = null
                                                    isStartingDownload = false
                                                    throw cancelled

                                                } catch (error: Exception) {
                                                    pendingDownloadIntent = null
                                                    isStartingDownload = false
                                                    downloadMessage =
                                                        "فشلت إضافة الفيديو: ${errorDetails(error)}"
                                                    Log.e(
                                                        TAG,
                                                        "Could not enqueue download",
                                                        error
                                                    )
                                                }
                                            }
                                        },
                                        enabled = engineReady &&
                                                selectedFormat != null &&
                                                !isStartingDownload &&
                                                !AnalysisStateStore.isAnalyzing,
                                        interactionSource = downloadInteractionSource,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .graphicsLayer {
                                                scaleX = downloadButtonScale
                                                scaleY = downloadButtonScale
                                            },
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = auroraAccent,
                                            contentColor = Color.White,
                                            disabledContainerColor = Color(0xFF414267),
                                            disabledContentColor = Color(0xFFB5B5CC)
                                        )
                                    ) {
                                        Text(
                                            text = "إضافة إلى قائمة التنزيلات  +",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = !isDownloading &&
                                                downloadMessage.isNotBlank(),
                                        enter = fadeIn(tween(260)) +
                                                expandVertically(
                                                    expandFrom = Alignment.Top,
                                                    animationSpec = tween(300)
                                                ),
                                        exit = fadeOut(tween(180)) +
                                                shrinkVertically(
                                                    shrinkTowards = Alignment.Top,
                                                    animationSpec = tween(220)
                                                )
                                    ) {
                                        Text(
                                            text = downloadMessage,
                                            color = if (
                                                downloadMessage.startsWith("فشل") ||
                                                downloadMessage.startsWith("لا توجد") ||
                                                downloadMessage.startsWith("تعذر")
                                            ) {
                                                auroraDanger
                                            } else {
                                                auroraAccentSoft
                                            },
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }

                            // Floating prayer stays separate and keeps the original rotation.
                            AnimatedVisibility(
                                visible = isDownloading && prayerMessage.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                enter = fadeIn(tween(360)) +
                                        slideInVertically(
                                            animationSpec = tween(
                                                420,
                                                easing = FastOutSlowInEasing
                                            ),
                                            initialOffsetY = { it / 5 }
                                        ),
                                exit = fadeOut(tween(220)) +
                                        slideOutVertically(
                                            animationSpec = tween(260),
                                            targetOffsetY = { -it / 6 }
                                        )
                            ) {
                                AnimatedContent(
                                    targetState = prayerMessage,
                                    transitionSpec = {
                                        (fadeIn(tween(420)) +
                                                slideInVertically(
                                                    animationSpec = tween(
                                                        420,
                                                        easing = FastOutSlowInEasing
                                                    ),
                                                    initialOffsetY = { it / 4 }
                                                )) togetherWith
                                                (fadeOut(tween(300)) +
                                                        slideOutVertically(
                                                            animationSpec = tween(300),
                                                            targetOffsetY = { -it / 5 }
                                                        ))
                                    },
                                    label = "floatingPrayerTransition"
                                ) { phrase ->
                                    Text(
                                        text = phrase,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = 12.dp,
                                                vertical = 2.dp
                                            ),
                                        color = auroraPrayer,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // The single download queue card belongs here, after Results.
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = auroraCard
                        ),
                        border = BorderStroke(1.dp, auroraBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "قائمة التنزيلات (${queueTasks.size})",
                                    color = auroraText,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                val hasActiveTasks = queueTasks.any {
                                    it.status == DownloadTaskUiState.STATUS_QUEUED ||
                                            it.status == DownloadTaskUiState.STATUS_RUNNING ||
                                            it.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
                                }

                                if (hasActiveTasks) {
                                    Button(
                                        onClick = {
                                            context.startService(
                                                DownloadForegroundService.createCancelAllIntent(
                                                    context
                                                )
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF713D55)
                                        )
                                    ) {
                                        Text("إلغاء الكل")
                                    }
                                }
                            }

                            if (queueTasks.isEmpty()) {
                                Text(
                                    text = "لا توجد تنزيلات في القائمة حاليًا.",
                                    color = auroraMuted,
                                    fontSize = 13.sp
                                )
                            } else {
                                val fixedQueueTasks = queueTasks.filter {
                                    it.status == DownloadTaskUiState.STATUS_RUNNING ||
                                            it.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
                                }

                                // Keep the currently processing tasks fixed.
                                fixedQueueTasks.forEach { task ->
                                    DownloadQueueTaskCard(
                                        task = task,
                                        liveProgress = liveDownloadTasksById[task.id]?.progress,
                                        etaRemainingMs = liveDownloadTasksById[task.id]?.etaRemainingMs,
                                        auroraText = auroraText,
                                        auroraMuted = auroraMuted,
                                        auroraAccent = auroraAccent,
                                        auroraAccentSoft = auroraAccentSoft,
                                        auroraTrack = auroraTrack,
                                        onCancel = {
                                            context.startService(
                                                DownloadForegroundService.createCancelIntent(
                                                    context,
                                                    task.id
                                                )
                                            )
                                        }
                                    )
                                }

                                if (queuedOrder.isNotEmpty()) {
                                    Text(
                                        text = "التنزيلات المنتظرة",
                                        color = auroraAccentSoft,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Text(
                                        text = "اسحب علامة الترتيب لتحريك التنزيل إلى المكان الذي تريده.",
                                        color = auroraMuted,
                                        fontSize = 12.sp
                                    )

                                    LazyColumn(
                                        state = queueLazyListState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 420.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(
                                            items = queuedOrder,
                                            key = { task -> task.id }
                                        ) { task ->

                                            ReorderableItem(
                                                reorderableQueueState,
                                                key = task.id
                                            ) { isDragging ->

                                                val handleModifier: Modifier? =
                                                    if (isSavingQueueOrder) {
                                                        null
                                                    } else {
                                                        Modifier.draggableHandle(
                                                            onDragStarted = {
                                                                isQueueReordering = true
                                                                queueOrderBeforeDrag =
                                                                    queuedOrder.map { it.id }
                                                                queueOrderMessage = null
                                                            },
                                                            onDragStopped = {
                                                                val orderToSave =
                                                                    queuedOrder.map { it.id }

                                                                if (
                                                                    orderToSave ==
                                                                    queueOrderBeforeDrag
                                                                ) {
                                                                    isQueueReordering = false
                                                                } else {
                                                                    isSavingQueueOrder = true

                                                                    screenScope.launch {
                                                                        try {
                                                                            val saved =
                                                                                queueRepository.reorderQueuedTasks(
                                                                                    orderToSave
                                                                                )

                                                                            val latestTasks =
                                                                                queueRepository.getActiveTasks()

                                                                            queuedOrder =
                                                                                latestTasks.filter {
                                                                                    it.status ==
                                                                                            DownloadTaskUiState.STATUS_QUEUED
                                                                                }

                                                                            queueOrderMessage =
                                                                                if (saved) {
                                                                                    "تم حفظ الترتيب الجديد."
                                                                                } else {
                                                                                    "تغيّرت قائمة الانتظار أثناء السحب؛ تم تحديث القائمة."
                                                                                }

                                                                        } catch (
                                                                            cancelled: CancellationException
                                                                        ) {
                                                                            throw cancelled

                                                                        } catch (error: Exception) {
                                                                            Log.e(
                                                                                TAG,
                                                                                "Could not save download queue order",
                                                                                error
                                                                            )

                                                                            val latestTasks =
                                                                                runCatching {
                                                                                    queueRepository.getActiveTasks()
                                                                                }.getOrNull()

                                                                            if (latestTasks != null) {
                                                                                queuedOrder =
                                                                                    latestTasks.filter {
                                                                                        it.status ==
                                                                                                DownloadTaskUiState.STATUS_QUEUED
                                                                                    }
                                                                            }

                                                                            queueOrderMessage =
                                                                                "تعذر حفظ ترتيب التنزيلات: ${errorDetails(error)}"

                                                                        } finally {
                                                                            isQueueReordering = false
                                                                            isSavingQueueOrder = false
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    }

                                                DownloadQueueTaskCard(
                                                    task = task,
                                                    liveProgress = liveDownloadTasksById[task.id]?.progress,
                                                    etaRemainingMs = liveDownloadTasksById[task.id]?.etaRemainingMs,
                                                    auroraText = auroraText,
                                                    auroraMuted = auroraMuted,
                                                    auroraAccent = auroraAccent,
                                                    auroraAccentSoft = auroraAccentSoft,
                                                    auroraTrack = auroraTrack,
                                                    isDragging = isDragging,
                                                    dragHandleModifier = handleModifier,
                                                    onCancel = {
                                                        context.startService(
                                                            DownloadForegroundService.createCancelIntent(
                                                                context,
                                                                task.id
                                                            )
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isSavingQueueOrder) {
                                    Text(
                                        text = "جارٍ حفظ الترتيب...",
                                        color = auroraMuted,
                                        fontSize = 12.sp
                                    )
                                }

                                queueOrderMessage?.let { message ->
                                    Text(
                                        text = message,
                                        color = if (message.startsWith("تعذر")) {
                                            auroraDanger
                                        } else {
                                            auroraAccentSoft
                                        },
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = !engineReady,
                        enter = fadeIn(tween(260)) +
                                expandVertically(
                                    expandFrom = Alignment.Top,
                                    animationSpec = tween(300)
                                ),
                        exit = fadeOut(tween(180)) +
                                shrinkVertically(
                                    shrinkTowards = Alignment.Top,
                                    animationSpec = tween(220)
                                )
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0x443E263D),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0x66FF9AAB))
                        ) {
                            Text(
                                text = "تعذر تهيئة محرك yt-dlp",
                                modifier = Modifier.padding(14.dp),
                                color = auroraDanger,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = screenEntered,
                        enter = fadeIn(tween(850, delayMillis = 220)) +
                                slideInVertically(
                                    animationSpec = tween(
                                        850,
                                        delayMillis = 220,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffsetY = { it / 16 }
                                )
                    ) {
                        Text(
                            text = "استخدم روابط محتوى تملك حق تنزيله أو يسمح مصدره بذلك.",
                            color = auroraMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 8.dp,
                                    vertical = 4.dp
                                )
                        )
                    }
                }
            }
        }
    }
}
