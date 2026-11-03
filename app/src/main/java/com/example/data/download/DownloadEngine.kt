package com.example.data.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.data.local.DownloadTaskDao
import com.example.data.media.LocalSampleMediaGenerator
import com.example.data.media.MediaProcessorEngine
import com.example.data.platform.SocialStreamExtractor
import com.example.domain.model.AnalyzedMediaMetadata
import com.example.domain.model.AudioFormat
import com.example.domain.model.AudioOutputChoice
import com.example.domain.model.AudioStreamOption
import com.example.domain.model.DiagnosticsReport
import com.example.domain.model.DownloadStatus
import com.example.domain.model.DownloadTaskEntity
import com.example.domain.model.DownloaderMode
import com.example.domain.model.StreamType
import com.example.domain.model.VideoStreamOption
import com.example.domain.validation.UrlValidationResult
import com.example.domain.validation.UrlValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class DeviceStorageSummary(
    val usedByAppBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val totalBytes: Long = 0L
)

sealed class EnqueueResult {
    data class Enqueued(val task: DownloadTaskEntity) : EnqueueResult()
    data class DuplicateDetected(val existingTask: DownloadTaskEntity) : EnqueueResult()
    data class Rejected(val reasonTitle: String, val reasonMessage: String) : EnqueueResult()
}

class DownloadEngine(
    private val context: Context,
    private val dao: DownloadTaskDao,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(SocialStreamExtractor.IPV4_FIRST_DNS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pausedOrCancelledTasks = ConcurrentHashMap<String, DownloadStatus>()
    private val taskOriginUrls = ConcurrentHashMap<String, String>()
    private val taskFallbackUrls = ConcurrentHashMap<String, List<String>>()
    private val taskDiagnosticLogs = ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>()
    private val startupRecoveryDeferred = CompletableDeferred<Unit>()
    @Volatile
    private var concurrencySemaphore = Semaphore(3)
    @Volatile
    private var customDownloadTreeUri: String = ""
    @Volatile
    private var notificationsEnabled: Boolean = true
    @Volatile
    private var wifiOnlyDownloads: Boolean = false

    val allTasksFlow: Flow<List<DownloadTaskEntity>> = dao.observeAllTasks()
    val completedVideosFlow: Flow<List<DownloadTaskEntity>> = dao.observeCompletedVideoTasks()
    val completedMusicFlow: Flow<List<DownloadTaskEntity>> = dao.observeCompletedMusicTasks()

    private val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    private fun appendTaskLog(taskId: String, message: String) {
        val deque = taskDiagnosticLogs.getOrPut(taskId) { ConcurrentLinkedDeque() }
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        deque.addLast("[$ts] $message")
        while (deque.size > 30) {
            deque.pollFirst()
        }
    }

    private fun buildTechnicalDetailsForTask(
        taskId: String,
        task: DownloadTaskEntity,
        finalError: String? = null
    ): String {
        val ytId = SocialStreamExtractor.resolveYouTubeVideoIdForTask(
            sourceUrl = task.sourceUrl,
            originPageUrl = taskOriginUrls[taskId],
            thumbnailUrl = task.thumbnailUrl
        )
        val extractorLines = if (!ytId.isNullOrBlank()) {
            SocialStreamExtractor.getDiagnosticLog(ytId)
        } else {
            SocialStreamExtractor.getDiagnosticLog(task.sourceUrl)
        }
        val taskLines = taskDiagnosticLogs[taskId]?.toList().orEmpty()
        val combined = buildList {
            add("Engine: ${SocialStreamExtractor.EXTRACTOR_ENGINE_VERSION}")
            add("Platform: ${task.platformName} • Mode: ${task.mode} • Res: ${task.resolutionLabel ?: "Audio"}")
            if (!ytId.isNullOrBlank()) {
                add("YouTube Video ID: $ytId")
                SocialStreamExtractor.getLastPlayabilityReason(ytId)?.let {
                    add("Last Playability Status: $it")
                }
            }
            addAll(extractorLines.takeLast(12))
            addAll(taskLines.takeLast(18))
            if (!finalError.isNullOrBlank()) {
                add("Final Status: $finalError")
            }
        }
        return combined.takeLast(30).joinToString("\n")
    }

    init {
        createNotificationChannel()
        externalScope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    val recovered = dao.recoverInterruptedTasksOnStartup()
                    if (recovered > 0) {
                        Log.i(TAG, "Recovered $recovered interrupted download task(s) on startup.")
                    }
                }
            } finally {
                startupRecoveryDeferred.complete(Unit)
            }
        }
    }

    fun resumeInterruptedTasks(autoResumeEnabled: Boolean) {
        if (!autoResumeEnabled) return
        externalScope.launch(Dispatchers.IO) {
            startupRecoveryDeferred.await()
            val interrupted = runCatching { dao.getInterruptedTasks() }.getOrDefault(emptyList())
            for (task in interrupted) {
                resumeDownload(task.id)
            }
        }
    }

    fun updateMaxConcurrentDownloads(maxConcurrent: Int) {
        val safeCount = maxConcurrent.coerceIn(1, 6)
        concurrencySemaphore = Semaphore(safeCount)
    }

    fun updateStorageAndNetworkSettings(
        customTreeUri: String = this.customDownloadTreeUri,
        notificationsEnabled: Boolean = this.notificationsEnabled,
        wifiOnly: Boolean = this.wifiOnlyDownloads
    ) {
        this.customDownloadTreeUri = customTreeUri.trim()
        this.notificationsEnabled = notificationsEnabled
        this.wifiOnlyDownloads = wifiOnly
    }

    fun computeStorageSummary(): DeviceStorageSummary {
        val usedBytes = runCatching {
            downloadsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        return try {
            val stat = StatFs(downloadsDir.absolutePath)
            DeviceStorageSummary(
                usedByAppBytes = usedBytes,
                availableBytes = stat.availableBytes,
                totalBytes = stat.totalBytes
            )
        } catch (_: Exception) {
            DeviceStorageSummary(usedByAppBytes = usedBytes)
        }
    }

    suspend fun enqueueVideoDownload(
        metadata: AnalyzedMediaMetadata,
        stream: VideoStreamOption,
        allowDuplicate: Boolean = false
    ): EnqueueResult = withContext(Dispatchers.IO) {
        when (val validation = UrlValidator.validateAndClassify(stream.downloadUrl)) {
            is UrlValidationResult.Invalid -> {
                return@withContext EnqueueResult.Rejected(
                    reasonTitle = validation.errorTitle,
                    reasonMessage = validation.errorMessage
                )
            }
            is UrlValidationResult.Valid -> {
                if (!validation.platform.supportsDirectDownload) {
                    return@withContext EnqueueResult.Rejected(
                        reasonTitle = "${validation.platform.displayName} Restricted",
                        reasonMessage = validation.platform.complianceSummary
                    )
                }
            }
        }

        if (!allowDuplicate) {
            val existing = dao.findDuplicateTask(
                sourceUrl = stream.downloadUrl,
                mode = DownloaderMode.VIDEO.name,
                resolutionLabel = stream.resolutionLabel,
                targetAudioFormat = ""
            )
            if (existing != null) {
                return@withContext EnqueueResult.DuplicateDetected(existing)
            }
        }

        val id = UUID.randomUUID().toString()
        val rawName = "${metadata.title}_${stream.resolutionLabel.substringBefore(' ')}"
        val safeFileName = ensureUniqueFileName(
            UrlValidator.sanitizeFileName(rawName, stream.container.extension)
        )
        val outputFile = File(downloadsDir, safeFileName)

        val codecDesc = if (stream.audioCodec != null) {
            "${stream.videoCodec} + ${stream.audioCodec}"
        } else if (stream.pairedAudioStreamUrl != null) {
            "${stream.videoCodec} + AAC-LC (Muxed)"
        } else {
            stream.videoCodec
        }

        val task = DownloadTaskEntity(
            id = id,
            sourceUrl = stream.downloadUrl,
            secondaryAudioUrl = stream.pairedAudioStreamUrl,
            title = metadata.title,
            author = metadata.authorOrDomain,
            platformName = metadata.platform.displayName,
            mode = DownloaderMode.VIDEO.name,
            thumbnailUrl = metadata.thumbnailUrl,
            outputFilePath = outputFile.absolutePath,
            fileName = safeFileName,
            mimeType = stream.container.mimeType,
            containerFormat = stream.container.label,
            codecInfo = codecDesc,
            resolutionLabel = stream.resolutionLabel,
            width = stream.resolutionWidth,
            height = stream.resolutionHeight,
            frameRate = stream.frameRate,
            isHdr = stream.isHdr,
            streamTypeLabel = stream.streamType.displayName,
            bitrateKbps = stream.bitrateKbps,
            durationMs = metadata.durationMs,
            totalBytes = stream.totalEstimatedBytes,
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED.name,
            statusDetail = "Waiting in queue...",
            supportsRangeResume = metadata.supportsRangeRequests,
            requiresStreamMerge = stream.streamType == StreamType.VIDEO_ONLY && stream.pairedAudioStreamUrl != null
        )

        if (metadata.sourceUrl.isNotBlank()) {
            taskOriginUrls[id] = metadata.sourceUrl
        }
        val altVideoUrls = metadata.videoStreams
            .filter { it.resolutionHeight == stream.resolutionHeight }
            .map { it.downloadUrl }
            .filter { SocialStreamExtractor.stripUrlFragment(it) != SocialStreamExtractor.stripUrlFragment(stream.downloadUrl) }
            .distinct()
        if (altVideoUrls.isNotEmpty()) {
            taskFallbackUrls[id] = altVideoUrls
        }
        dao.upsertTask(task)
        startTaskExecution(task.id)
        EnqueueResult.Enqueued(task)
    }

    suspend fun enqueueAudioDownload(
        metadata: AnalyzedMediaMetadata,
        sourceStream: AudioStreamOption,
        outputChoice: AudioOutputChoice,
        allowDuplicate: Boolean = false
    ): EnqueueResult = withContext(Dispatchers.IO) {
        when (val validation = UrlValidator.validateAndClassify(sourceStream.downloadUrl)) {
            is UrlValidationResult.Invalid -> {
                return@withContext EnqueueResult.Rejected(
                    reasonTitle = validation.errorTitle,
                    reasonMessage = validation.errorMessage
                )
            }
            is UrlValidationResult.Valid -> {
                if (!validation.platform.supportsDirectDownload) {
                    return@withContext EnqueueResult.Rejected(
                        reasonTitle = "${validation.platform.displayName} Restricted",
                        reasonMessage = validation.platform.complianceSummary
                    )
                }
            }
        }

        if (!allowDuplicate) {
            val existing = dao.findDuplicateTask(
                sourceUrl = sourceStream.downloadUrl,
                mode = DownloaderMode.MUSIC.name,
                resolutionLabel = "",
                targetAudioFormat = outputChoice.targetFormat.name
            )
            if (existing != null) {
                return@withContext EnqueueResult.DuplicateDetected(existing)
            }
        }

        val id = UUID.randomUUID().toString()
        val rawName = "${metadata.title}_${outputChoice.targetFormat.extension}"
        val safeFileName = ensureUniqueFileName(
            UrlValidator.sanitizeFileName(rawName, outputChoice.targetFormat.extension)
        )
        val outputFile = File(downloadsDir, safeFileName)

        val task = DownloadTaskEntity(
            id = id,
            sourceUrl = sourceStream.downloadUrl,
            title = metadata.title,
            author = metadata.authorOrDomain,
            platformName = metadata.platform.displayName,
            mode = DownloaderMode.MUSIC.name,
            thumbnailUrl = metadata.thumbnailUrl,
            outputFilePath = outputFile.absolutePath,
            fileName = safeFileName,
            mimeType = outputChoice.targetFormat.mimeType,
            containerFormat = outputChoice.targetFormat.label,
            codecInfo = outputChoice.targetFormat.codecDescription,
            sampleRateHz = sourceStream.sampleRateHz,
            channelConfig = sourceStream.channelConfigurationLabel,
            bitrateKbps = if (outputChoice.targetFormat.isLossless) 1411 else sourceStream.bitrateKbps,
            durationMs = metadata.durationMs,
            totalBytes = outputChoice.estimatedOutputBytes.coerceAtLeast(sourceStream.estimatedSizeBytes),
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED.name,
            statusDetail = "Waiting in queue...",
            supportsRangeResume = metadata.supportsRangeRequests,
            requiresAudioExtraction = sourceStream.isExtractedFromVideo,
            targetAudioFormat = outputChoice.targetFormat.name,
            isConverted = !outputChoice.isDirectStreamCopy,
            conversionNote = outputChoice.warningMessage ?: outputChoice.description
        )

        if (metadata.sourceUrl.isNotBlank()) {
            taskOriginUrls[id] = metadata.sourceUrl
        }
        val altAudioUrls = (metadata.audioStreams.map { it.downloadUrl } + metadata.videoStreams.map { it.downloadUrl })
            .filter { SocialStreamExtractor.stripUrlFragment(it) != SocialStreamExtractor.stripUrlFragment(sourceStream.downloadUrl) }
            .distinct()
        if (altAudioUrls.isNotEmpty()) {
            taskFallbackUrls[id] = altAudioUrls
        }
        dao.upsertTask(task)
        startTaskExecution(task.id)
        EnqueueResult.Enqueued(task)
    }

    fun pauseDownload(taskId: String) {
        pausedOrCancelledTasks[taskId] = DownloadStatus.PAUSED
        activeJobs.remove(taskId)?.cancel()
        externalScope.launch(Dispatchers.IO) {
            val current = dao.getTaskById(taskId) ?: return@launch
            if (current.statusEnum.isActive || current.statusEnum == DownloadStatus.PAUSED) {
                val partLen = File("${current.outputFilePath}.part").takeIf { it.exists() }?.length() ?: 0L
                val bestDownloaded = maxOf(current.downloadedBytes, partLen)
                val updated = current.copy(
                    downloadedBytes = bestDownloaded,
                    status = DownloadStatus.PAUSED.name,
                    statusDetail = "Paused at ${current.copy(downloadedBytes = bestDownloaded).progressPercent}% • Ready to resume",
                    speedBytesPerSec = 0L,
                    etaSeconds = -1L
                )
                dao.upsertTask(updated)
            }
        }
    }

    fun resumeDownload(taskId: String) {
        pausedOrCancelledTasks.remove(taskId)
        externalScope.launch(Dispatchers.IO) {
            val current = dao.getTaskById(taskId) ?: return@launch
            if (current.statusEnum == DownloadStatus.PAUSED || current.statusEnum == DownloadStatus.FAILED) {
                val partLen = File("${current.outputFilePath}.part").takeIf { it.exists() }?.length() ?: current.downloadedBytes
                dao.upsertTask(
                    current.copy(
                        downloadedBytes = partLen,
                        status = DownloadStatus.QUEUED.name,
                        statusDetail = "Resuming download...",
                        errorMessage = null
                    )
                )
                startTaskExecution(taskId)
            }
        }
    }

    fun retryDownload(taskId: String) {
        pausedOrCancelledTasks.remove(taskId)
        externalScope.launch(Dispatchers.IO) {
            val current = dao.getTaskById(taskId) ?: return@launch
            val partFile = File("${current.outputFilePath}.part")
            val audioPartFile = File("${current.outputFilePath}.audio.part")
            if (partFile.exists()) partFile.delete()
            if (audioPartFile.exists()) audioPartFile.delete()
            dao.upsertTask(
                current.copy(
                    downloadedBytes = 0L,
                    speedBytesPerSec = 0L,
                    etaSeconds = -1L,
                    status = DownloadStatus.QUEUED.name,
                    statusDetail = "Retrying from beginning...",
                    errorMessage = null
                )
            )
            startTaskExecution(taskId)
        }
    }

    fun cancelDownload(taskId: String) {
        pausedOrCancelledTasks[taskId] = DownloadStatus.CANCELLED
        activeJobs.remove(taskId)?.cancel()
        externalScope.launch(Dispatchers.IO) {
            val current = dao.getTaskById(taskId) ?: return@launch
            File("${current.outputFilePath}.part").delete()
            File("${current.outputFilePath}.audio.part").delete()
            dao.upsertTask(
                current.copy(
                    status = DownloadStatus.CANCELLED.name,
                    statusDetail = "Cancelled by user",
                    speedBytesPerSec = 0L,
                    etaSeconds = -1L
                )
            )
        }
    }

    suspend fun deleteTaskAndFile(taskId: String) = withContext(Dispatchers.IO) {
        pausedOrCancelledTasks[taskId] = DownloadStatus.CANCELLED
        activeJobs.remove(taskId)?.cancel()
        val task = dao.getTaskById(taskId)
        if (task != null) {
            runCatching { File(task.outputFilePath).delete() }
            runCatching { File("${task.outputFilePath}.part").delete() }
            runCatching { File("${task.outputFilePath}.audio.part").delete() }
            dao.deleteById(taskId)
        }
        pausedOrCancelledTasks.remove(taskId)
    }

    suspend fun renameMediaFile(taskId: String, newTitleInput: String): Boolean = withContext(Dispatchers.IO) {
        val task = dao.getTaskById(taskId) ?: return@withContext false
        val oldFile = File(task.outputFilePath)
        val ext = oldFile.extension.ifEmpty { "mp4" }
        val newSafeName = UrlValidator.sanitizeFileName(newTitleInput, ext)
        val newFile = File(downloadsDir, newSafeName)
        if (oldFile.exists() && oldFile.absolutePath != newFile.absolutePath) {
            val renamed = oldFile.renameTo(newFile)
            if (!renamed) return@withContext false
        }
        dao.updateFileMetadata(
            id = taskId,
            newTitle = newTitleInput.trim().ifEmpty { task.title },
            newFileName = newFile.name,
            newPath = newFile.absolutePath
        )
        true
    }

    suspend fun clearFinishedHistory() = withContext(Dispatchers.IO) {
        dao.clearFinishedHistory()
    }

    private fun startTaskExecution(taskId: String) {
        if (activeJobs.containsKey(taskId)) return
        val job = externalScope.launch(Dispatchers.IO) {
            concurrencySemaphore.withPermit {
                executeDownloadPipeline(taskId)
            }
        }
        activeJobs[taskId] = job
        job.invokeOnCompletion { activeJobs.remove(taskId) }
    }

    private suspend fun executeDownloadPipeline(taskId: String) = withContext(Dispatchers.IO) {
        startupRecoveryDeferred.await()
        if (pausedOrCancelledTasks.containsKey(taskId)) return@withContext

        var activeTask = dao.getTaskById(taskId) ?: return@withContext
        if (activeTask.statusEnum == DownloadStatus.PAUSED || activeTask.statusEnum == DownloadStatus.CANCELLED) {
            return@withContext
        }

        val finalFile = File(activeTask.outputFilePath)
        val primaryPartFile = File("${activeTask.outputFilePath}.part")
        val secondaryAudioPartFile = File("${activeTask.outputFilePath}.audio.part")
        val sanitizedUrl = UrlValidator.sanitizeUrlForDiagnosticLog(activeTask.sourceUrl)
        val ytVideoId = SocialStreamExtractor.resolveYouTubeVideoIdForTask(
            sourceUrl = activeTask.sourceUrl,
            originPageUrl = taskOriginUrls[taskId],
            thumbnailUrl = activeTask.thumbnailUrl
        )

        appendTaskLog(
            taskId,
            "Starting download pipeline for '${activeTask.title}' (${activeTask.platformName}, mode=${activeTask.mode})"
        )

        val maxJobAttempts = 3 // Initial attempt + up to 2 automatic retries (waiting 3s between tries)
        var lastException: Exception? = null

        for (jobAttempt in 1..maxJobAttempts) {
            try {
                currentCoroutineContext().ensureActive()
                if (pausedOrCancelledTasks.containsKey(taskId)) {
                    throw CancellationException("Task paused or cancelled")
                }

                // Pre-flight check 1: Validate URL is not mock or invalid
                when (val validation = UrlValidator.validateAndClassify(activeTask.sourceUrl)) {
                    is UrlValidationResult.Invalid -> {
                        throw IllegalArgumentException("${validation.errorTitle}: ${validation.errorMessage}")
                    }
                    is UrlValidationResult.Valid -> {
                        if (!validation.platform.supportsDirectDownload) {
                            throw IllegalArgumentException("${validation.platform.displayName} does not permit direct stream downloading.")
                        }
                    }
                }

                // Pre-flight check 2: Verify network state for remote URLs & sufficient device storage
                checkNetworkConnectivity(activeTask.sourceUrl)
                checkAvailableStorageSpace(activeTask.totalBytes)

                // If retrying (jobAttempt > 1) or if a YouTube URL is already expired before starting, refresh streams
                val primaryExpired = SocialStreamExtractor.isStreamUrlExpired(activeTask.sourceUrl)
                val secondaryExpired = !activeTask.secondaryAudioUrl.isNullOrBlank() &&
                    SocialStreamExtractor.isStreamUrlExpired(activeTask.secondaryAudioUrl!!)
                if ((jobAttempt > 1 || primaryExpired || secondaryExpired) && !ytVideoId.isNullOrBlank()) {
                    val useProgressiveFallback = jobAttempt >= 2
                    appendTaskLog(
                        taskId,
                        "Attempt $jobAttempt/$maxJobAttempts: Re-extracting fresh YouTube streams for videoId=$ytVideoId (progressiveFallback=$useProgressiveFallback)"
                    )
                    dao.updateProgress(
                        id = taskId,
                        downloadedBytes = activeTask.downloadedBytes,
                        totalBytes = activeTask.totalBytes,
                        speedBytesPerSec = 0L,
                        etaSeconds = -1L,
                        status = DownloadStatus.DOWNLOADING.name,
                        statusDetail = if (jobAttempt > 1) {
                            "Refreshing stream & retrying (attempt $jobAttempt of $maxJobAttempts)..."
                        } else {
                            "Refreshing expired YouTube stream..."
                        }
                    )
                    val freshStreams = SocialStreamExtractor.extractFreshYouTubeTaskStreams(
                        httpClient = httpClient,
                        videoId = ytVideoId,
                        isAudioMode = activeTask.mode == DownloaderMode.MUSIC.name,
                        preferredHeight = activeTask.height.takeIf { it > 0 } ?: 720,
                        preferProgressiveFallback = useProgressiveFallback
                    )
                    if (freshStreams != null) {
                        val oldItag = Regex("[?&]itag=(\\d+)(?:&|$)").find(activeTask.sourceUrl)?.groupValues?.getOrNull(1)
                        val newItag = Regex("[?&]itag=(\\d+)(?:&|$)").find(freshStreams.primaryUrl)?.groupValues?.getOrNull(1)
                        if (oldItag != newItag || freshStreams.requiresStreamMerge != activeTask.requiresStreamMerge) {
                            appendTaskLog(
                                taskId,
                                "Switched YouTube stream itag ($oldItag -> $newItag, merge=${freshStreams.requiresStreamMerge}); resetting .part files"
                            )
                            runCatching {
                                if (primaryPartFile.exists()) primaryPartFile.delete()
                                if (secondaryAudioPartFile.exists()) secondaryAudioPartFile.delete()
                            }
                        }
                        activeTask = activeTask.copy(
                            sourceUrl = freshStreams.primaryUrl,
                            secondaryAudioUrl = freshStreams.secondaryAudioUrl,
                            requiresStreamMerge = freshStreams.requiresStreamMerge,
                            requiresAudioExtraction = freshStreams.requiresAudioExtraction || activeTask.requiresAudioExtraction,
                            resolutionLabel = freshStreams.resolutionLabel ?: activeTask.resolutionLabel,
                            height = if (freshStreams.height > 0) freshStreams.height else activeTask.height,
                            width = if (freshStreams.width > 0) freshStreams.width else activeTask.width,
                            totalBytes = if (freshStreams.estimatedTotalBytes > 0L) freshStreams.estimatedTotalBytes else activeTask.totalBytes,
                            downloadedBytes = if (primaryPartFile.exists()) primaryPartFile.length() else 0L
                        )
                        dao.upsertTask(activeTask)
                    } else {
                        appendTaskLog(taskId, "Attempt $jobAttempt/$maxJobAttempts: Fresh YouTube stream extraction returned null")
                    }
                }

                val hasDualStreams = activeTask.requiresStreamMerge && !activeTask.secondaryAudioUrl.isNullOrBlank()
                val primaryExpectedTrackBytes = if (hasDualStreams) 0L else activeTask.totalBytes

                // Stage 1: Transfer primary stream to temporary .part file (with Range resume support)
                dao.updateProgress(
                    id = taskId,
                    downloadedBytes = activeTask.downloadedBytes,
                    totalBytes = activeTask.totalBytes,
                    speedBytesPerSec = 0L,
                    etaSeconds = -1L,
                    status = DownloadStatus.DOWNLOADING.name,
                    statusDetail = if (jobAttempt > 1) {
                        "Downloading stream (retry ${jobAttempt - 1}/2)..."
                    } else {
                        "Connecting to media stream..."
                    }
                )

                transferStreamToFile(
                    taskId = taskId,
                    sourceUrl = activeTask.sourceUrl,
                    targetPartFile = primaryPartFile,
                    supportsResume = activeTask.supportsRangeResume,
                    expectedTrackBytes = primaryExpectedTrackBytes,
                    overallTotalBytes = activeTask.totalBytes,
                    baseByteOffset = 0L
                )

                if (!primaryPartFile.exists() || primaryPartFile.length() == 0L) {
                    throw IOException("Empty response: Downloaded stream wrote 0 bytes to temporary file.")
                }

                // Refresh activeTask in case resolveFreshUrlForTask updated URLs during Stage 1
                activeTask = dao.getTaskById(taskId) ?: activeTask

                // Stage 2: If video-only adaptive stream requires merging with paired audio stream
                if (hasDualStreams && !activeTask.secondaryAudioUrl.isNullOrBlank()) {
                    val primaryBytes = primaryPartFile.length()
                    val overallExpected = activeTask.totalBytes.coerceAtLeast(primaryBytes)
                    appendTaskLog(
                        taskId,
                        "Stage 1 video complete (${formatBytesStatic(primaryBytes)}). Starting Stage 2 paired audio stream..."
                    )
                    dao.updateProgress(
                        id = taskId,
                        downloadedBytes = primaryBytes,
                        totalBytes = overallExpected,
                        speedBytesPerSec = 0L,
                        etaSeconds = 1L,
                        status = DownloadStatus.DOWNLOADING.name,
                        statusDetail = "Downloading paired audio stream..."
                    )

                    transferStreamToFile(
                        taskId = taskId,
                        sourceUrl = activeTask.secondaryAudioUrl!!,
                        targetPartFile = secondaryAudioPartFile,
                        supportsResume = true,
                        expectedTrackBytes = 0L,
                        overallTotalBytes = overallExpected,
                        baseByteOffset = primaryBytes
                    )

                    val combinedRawBytes = primaryPartFile.length() + secondaryAudioPartFile.length()
                    appendTaskLog(
                        taskId,
                        "Stage 2 audio complete (${formatBytesStatic(secondaryAudioPartFile.length())}). Merging via MediaMuxer..."
                    )
                    dao.updateProgress(
                        id = taskId,
                        downloadedBytes = combinedRawBytes,
                        totalBytes = combinedRawBytes,
                        speedBytesPerSec = 0L,
                        etaSeconds = 1L,
                        status = DownloadStatus.MERGING_STREAMS.name,
                        statusDetail = "Merging video + audio via Android MediaMuxer..."
                    )

                    val mergedOk = MediaProcessorEngine.mergeVideoAndAudioStreams(
                        videoFile = primaryPartFile,
                        audioFile = secondaryAudioPartFile,
                        outputFile = finalFile
                    )
                    primaryPartFile.delete()
                    secondaryAudioPartFile.delete()
                    if (!mergedOk) {
                        if (finalFile.exists()) finalFile.delete()
                        throw IllegalStateException("Stream muxing failed: MediaMuxer could not combine video and audio tracks.")
                    }
                } else if (activeTask.mode == DownloaderMode.MUSIC.name &&
                    (activeTask.requiresAudioExtraction || activeTask.isConverted)
                ) {
                    // Stage 3: Lawful Audio Extraction or Format Conversion
                    val statusStage = if (activeTask.requiresAudioExtraction && !activeTask.isConverted) {
                        DownloadStatus.EXTRACTING_AUDIO
                    } else {
                        DownloadStatus.CONVERTING_AUDIO
                    }
                    val targetFormat = runCatching {
                        AudioFormat.valueOf(activeTask.targetAudioFormat ?: "M4A")
                    }.getOrDefault(AudioFormat.M4A)

                    appendTaskLog(taskId, "Stage 3: ${statusStage.label} -> ${targetFormat.label}")
                    dao.updateProgress(
                        id = taskId,
                        downloadedBytes = primaryPartFile.length(),
                        totalBytes = activeTask.totalBytes.coerceAtLeast(primaryPartFile.length()),
                        speedBytesPerSec = 0L,
                        etaSeconds = 1L,
                        status = statusStage.name,
                        statusDetail = "${statusStage.label} -> ${targetFormat.label}..."
                    )

                    val convertedOk = MediaProcessorEngine.extractOrConvertAudio(
                        sourceFile = primaryPartFile,
                        outputFile = finalFile,
                        targetFormat = targetFormat,
                        sampleRateHz = activeTask.sampleRateHz.takeIf { it > 0 } ?: 44100,
                        channelCount = if (activeTask.channelConfig.contains("Mono")) 1 else 2
                    )
                    primaryPartFile.delete()
                    if (!convertedOk) {
                        if (finalFile.exists()) finalFile.delete()
                        throw IllegalStateException(
                            "Conversion failure: Could not convert or extract audio to ${targetFormat.label} (${targetFormat.codecDescription})."
                        )
                    }
                } else {
                    // Direct progressive stream move from temporary .part file to final target file
                    finalFile.parentFile?.mkdirs()
                    if (finalFile.exists()) finalFile.delete()
                    if (!primaryPartFile.renameTo(finalFile)) {
                        primaryPartFile.copyTo(finalFile, overwrite = true)
                        primaryPartFile.delete()
                    }
                }

                // Stage 4: File-size & integrity validation + MediaExtractor post-inspection + MediaStore / SAF Save
                dao.updateProgress(
                    id = taskId,
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length(),
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    status = DownloadStatus.VALIDATING.name,
                    statusDetail = "Verifying file integrity and saving to MediaStore..."
                )

                val integrityResult = MediaProcessorEngine.validateMediaFileIntegrity(finalFile)
                if (integrityResult.isFailure) {
                    val err = integrityResult.exceptionOrNull()
                    if (finalFile.exists()) finalFile.delete()
                    throw IllegalStateException(err?.message ?: "Downloaded file failed container integrity verification.")
                }

                val inspected = integrityResult.getOrThrow()
                val sha256 = computeSha256Short(finalFile)
                val refreshedTask = dao.getTaskById(taskId) ?: activeTask

                // Save completed file to custom SAF directory (if configured) and Android MediaStore
                val savedLocationNote = exportCompletedFileToMediaStore(finalFile, refreshedTask)
                appendTaskLog(
                    taskId,
                    "Completed: ${finalFile.name} (${formatBytesStatic(finalFile.length())}, SHA-256=$sha256)"
                )

                val finalCompleted = refreshedTask.copy(
                    totalBytes = finalFile.length(),
                    downloadedBytes = finalFile.length(),
                    durationMs = if (inspected.durationMs > 0) inspected.durationMs else refreshedTask.durationMs,
                    width = if (inspected.width > 0) inspected.width else refreshedTask.width,
                    height = if (inspected.height > 0) inspected.height else refreshedTask.height,
                    bitrateKbps = if (inspected.bitrateKbps > 0) inspected.bitrateKbps else refreshedTask.bitrateKbps,
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    status = DownloadStatus.COMPLETED.name,
                    statusDetail = "$savedLocationNote • ${formatBytesStatic(finalFile.length())} • SHA-256: $sha256",
                    checksumSha256 = sha256,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = null,
                    technicalDetails = buildTechnicalDetailsForTask(taskId, refreshedTask, "SUCCESS (0)")
                )

                dao.upsertTask(finalCompleted)
                postCompletionNotification(finalCompleted)
                return@withContext
            } catch (ce: CancellationException) {
                handlePauseOrCancelCleanup(taskId, activeTask, finalFile, primaryPartFile, secondaryAudioPartFile)
                throw ce
            } catch (e: Exception) {
                lastException = e
                if (pausedOrCancelledTasks.containsKey(taskId)) {
                    handlePauseOrCancelCleanup(taskId, activeTask, finalFile, primaryPartFile, secondaryAudioPartFile)
                    return@withContext
                }
                val currentDbTask = dao.getTaskById(taskId)
                if (currentDbTask?.statusEnum == DownloadStatus.PAUSED || currentDbTask?.statusEnum == DownloadStatus.CANCELLED) {
                    handlePauseOrCancelCleanup(taskId, activeTask, finalFile, primaryPartFile, secondaryAudioPartFile)
                    return@withContext
                }

                val errMsg = e.message.orEmpty()
                appendTaskLog(taskId, "Attempt $jobAttempt/$maxJobAttempts failed (${e.javaClass.simpleName}): $errMsg")

                val isNonRetryablePreFlight = e is IllegalArgumentException ||
                    errMsg.contains("ENOSPC", ignoreCase = true) ||
                    errMsg.contains("Insufficient storage", ignoreCase = true) ||
                    errMsg.contains("Wi-Fi Only", ignoreCase = true) ||
                    errMsg.contains("HTML webpage", ignoreCase = true) ||
                    errMsg.contains("JSON response", ignoreCase = true)

                if (!isNonRetryablePreFlight && jobAttempt < maxJobAttempts) {
                    val preserved = if (primaryPartFile.exists()) primaryPartFile.length() else activeTask.downloadedBytes
                    dao.updateProgress(
                        id = taskId,
                        downloadedBytes = preserved,
                        totalBytes = activeTask.totalBytes.coerceAtLeast(preserved),
                        speedBytesPerSec = 0L,
                        etaSeconds = -1L,
                        status = DownloadStatus.DOWNLOADING.name,
                        statusDetail = "Stream interrupted • Auto-retrying ($jobAttempt of ${maxJobAttempts - 1}) in 3s..."
                    )
                    delay(3000L)
                    continue
                }
                break
            }
        }

        val finalEx = lastException ?: IOException("Download failed after $maxJobAttempts attempts.")
        if (pausedOrCancelledTasks.containsKey(taskId)) {
            handlePauseOrCancelCleanup(taskId, activeTask, finalFile, primaryPartFile, secondaryAudioPartFile)
            return@withContext
        }
        val currentDbTask = dao.getTaskById(taskId)
        if (currentDbTask?.statusEnum == DownloadStatus.PAUSED || currentDbTask?.statusEnum == DownloadStatus.CANCELLED) {
            handlePauseOrCancelCleanup(taskId, activeTask, finalFile, primaryPartFile, secondaryAudioPartFile)
            return@withContext
        }

        // Remove incomplete output or empty/non-resumable temporary files on failure
        runCatching {
            if (finalFile.exists()) finalFile.delete()
            if (secondaryAudioPartFile.exists()) secondaryAudioPartFile.delete()
            if (primaryPartFile.exists() && (!activeTask.supportsRangeResume || primaryPartFile.length() == 0L)) {
                primaryPartFile.delete()
            }
        }
        val latest = currentDbTask ?: activeTask
        val preservedBytes = if (activeTask.supportsRangeResume && primaryPartFile.exists()) {
            primaryPartFile.length()
        } else {
            latest.downloadedBytes
        }
        val rawError = formatDownloadExceptionMessage(finalEx)
        val isYouTubeTask = !ytVideoId.isNullOrBlank() ||
            latest.platformName.equals("YouTube", ignoreCase = true) ||
            latest.sourceUrl.contains("googlevideo.com", ignoreCase = true) ||
            latest.sourceUrl.contains("youtube.com", ignoreCase = true)
        val readableError = if (isYouTubeTask &&
            (rawError.contains("403") || rawError.contains("not a bot", ignoreCase = true) || rawError.contains("private", ignoreCase = true))
        ) {
            SocialStreamExtractor.mapYouTubeFailureMessage(rawError, ytVideoId).second
        } else {
            rawError
        }
        val techDetails = buildTechnicalDetailsForTask(taskId, latest, readableError)
        Log.w(TAG, "Download failed for source=$sanitizedUrl (${finalEx::class.java.simpleName}): $readableError")
        dao.upsertTask(
            latest.copy(
                downloadedBytes = preservedBytes,
                status = DownloadStatus.FAILED.name,
                statusDetail = "Download failed",
                errorMessage = readableError,
                technicalDetails = techDetails,
                speedBytesPerSec = 0L,
                etaSeconds = -1L
            )
        )
    }

    private suspend fun handlePauseOrCancelCleanup(
        taskId: String,
        initialTask: DownloadTaskEntity,
        finalFile: File,
        primaryPartFile: File,
        secondaryAudioPartFile: File
    ) {
        val requestedState = pausedOrCancelledTasks.remove(taskId)
            ?: dao.getTaskById(taskId)?.statusEnum
        val latest = dao.getTaskById(taskId) ?: initialTask
        when (requestedState) {
            DownloadStatus.PAUSED -> {
                val partLen = primaryPartFile.takeIf { it.exists() }?.length() ?: latest.downloadedBytes
                val bestBytes = maxOf(latest.downloadedBytes, partLen)
                val tempTask = latest.copy(downloadedBytes = bestBytes)
                dao.upsertTask(
                    tempTask.copy(
                        status = DownloadStatus.PAUSED.name,
                        statusDetail = "Paused at ${tempTask.progressPercent}% • Ready to resume",
                        speedBytesPerSec = 0L,
                        etaSeconds = -1L
                    )
                )
            }
            DownloadStatus.CANCELLED -> {
                runCatching {
                    if (finalFile.exists()) finalFile.delete()
                    if (primaryPartFile.exists()) primaryPartFile.delete()
                    if (secondaryAudioPartFile.exists()) secondaryAudioPartFile.delete()
                }
                dao.upsertTask(
                    latest.copy(
                        status = DownloadStatus.CANCELLED.name,
                        statusDetail = "Cancelled by user",
                        speedBytesPerSec = 0L,
                        etaSeconds = -1L
                    )
                )
            }
            else -> Unit
        }
    }

    private fun checkNetworkConnectivity(sourceUrl: String) {
        if (sourceUrl.startsWith("file:", ignoreCase = true)) return
        if (!wifiOnlyDownloads) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val activeNetwork = cm.activeNetwork
            val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
            if (caps != null) {
                val isUnmeteredWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isUnmeteredWifiOrEthernet) {
                    throw IOException("Wi-Fi Only mode is enabled: Connect to a Wi-Fi network or disable Wi-Fi Only in Settings.")
                }
            }
        } catch (ioe: IOException) {
            throw ioe
        } catch (_: Exception) {
        }
    }

    private fun checkAvailableStorageSpace(expectedBytes: Long) {
        try {
            val stat = StatFs(downloadsDir.absolutePath)
            val available = stat.availableBytes
            val minRequired = if (expectedBytes > 0L) {
                expectedBytes + (4L * 1024L * 1024L)
            } else {
                4L * 1024L * 1024L
            }
            if (available in 1 until minRequired) {
                throw IOException(
                    "Insufficient storage space: ${formatBytesStatic(available)} available, need at least ${formatBytesStatic(minRequired)}."
                )
            }
        } catch (ioe: IOException) {
            throw ioe
        } catch (_: Exception) {
            // StatFs may not be backed by a real mount in some JVM test runners
        }
    }

    /**
     * Maps every download, network, storage, permission, or media conversion exception to a specific,
     * actionable diagnostic message without falling back to a vague generic error.
     */
    internal fun formatDownloadExceptionMessage(e: Exception): String {
        val rawMsg = e.message?.trim().takeIf { !it.isNullOrEmpty() }
            ?: e.localizedMessage?.trim().takeIf { !it.isNullOrEmpty() }

        return when (e) {
            is UnknownHostException ->
                "DNS / Network error: Could not resolve server hostname (${rawMsg ?: "check internet connection"})."
            is SocketTimeoutException ->
                "Network timeout: Server took too long to respond while streaming media. Tap Retry to resume."
            is ConnectException ->
                "Connection refused: Could not establish TCP connection to remote media server (${rawMsg ?: "unreachable host"})."
            is SSLException ->
                "TLS/SSL security error: Could not establish a secure HTTPS connection (${rawMsg ?: "certificate or handshake failure"})."
            is SecurityException ->
                "Permission denied: Android blocked storage or network access (${rawMsg ?: "check app permissions"})."
            is FileNotFoundException ->
                "Storage file error: Target or source media file could not be opened (${rawMsg ?: "file not found"})."
            is EOFException ->
                rawMsg ?: "Interrupted connection: Remote server closed the stream before the complete file was received."
            is IllegalArgumentException ->
                rawMsg ?: "Invalid media URL or unsupported parameter (${e.javaClass.simpleName})."
            is IllegalStateException ->
                rawMsg ?: "Media processing or verification failed (${e.javaClass.simpleName})."
            is IOException -> {
                if (rawMsg != null && (rawMsg.contains("ENOSPC", ignoreCase = true) || rawMsg.contains("No space left", ignoreCase = true))) {
                    "Insufficient storage space: Device storage is full. Free up space and tap Retry."
                } else {
                    rawMsg ?: "Network or file I/O failure (${e.javaClass.simpleName}) while writing media stream."
                }
            }
            else -> rawMsg ?: "Download failed due to ${e.javaClass.simpleName} during media transfer."
        }
    }

    /**
     * Saves the verified media file into:
     * 1. Custom Storage Access Framework (SAF) directory if the user selected one via OpenDocumentTree,
     * 2. Android's MediaStore (`Movies/MediaSave`, `Music/MediaSave`, or `Download/MediaSave`) on API 29+
     *    using IS_PENDING=1 -> write -> IS_PENDING=0 without requesting broad storage permissions,
     * 3. App-specific external media directories (`Movies/MediaSave`, `Music/MediaSave`, `Download/MediaSave`)
     *    with MediaScanner indexing on legacy Android (API 24-28).
     */
    internal fun exportCompletedFileToMediaStore(sourceFile: File, task: DownloadTaskEntity): String {
        val isVideo = task.mode == DownloaderMode.VIDEO.name || task.mimeType.startsWith("video/")
        val isAudio = task.mode == DownloaderMode.MUSIC.name || task.mimeType.startsWith("audio/")

        // 1. Mirror to app's external media directory (Movies/MediaSave, Music/MediaSave, or Download/MediaSave)
        val extSubDirType = when {
            isVideo -> Environment.DIRECTORY_MOVIES
            isAudio -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        var mirroredExtFile: File? = null
        runCatching {
            val extBase = context.getExternalFilesDir(extSubDirType)
                ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (extBase != null) {
                val mediaSaveExtDir = File(extBase, "MediaSave").apply { mkdirs() }
                val targetExtFile = File(mediaSaveExtDir, task.fileName)
                sourceFile.copyTo(targetExtFile, overwrite = true)
                mirroredExtFile = targetExtFile
            }
        }

        val resolver = context.contentResolver

        // 2. If user selected a custom download folder via Android Storage Access Framework (SAF), write there first
        val activeSafTree = customDownloadTreeUri
        if (activeSafTree.isNotBlank()) {
            val savedToSaf = trySaveToSafDocumentTree(
                resolver = resolver,
                treeUriString = activeSafTree,
                sourceFile = sourceFile,
                task = task
            )
            if (savedToSaf) {
                return "Saved to Custom Folder (SAF)"
            }
        }

        // 3. Save to system MediaStore on Android 10+ (API 29+) using IS_PENDING finalization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val (primaryCollection, primaryRelPath) = when {
                    isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MOVIES}/MediaSave"
                    isAudio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MUSIC}/MediaSave"
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/MediaSave"
                }

                val savedInPrimary = tryInsertIntoMediaStoreCollection(
                    resolver = resolver,
                    collectionUri = primaryCollection,
                    relativePath = primaryRelPath,
                    sourceFile = sourceFile,
                    task = task
                )
                if (savedInPrimary) {
                    return "Saved to MediaStore ($primaryRelPath)"
                }

                val fallbackRelPath = "${Environment.DIRECTORY_DOWNLOADS}/MediaSave"
                val savedInDownloads = tryInsertIntoMediaStoreCollection(
                    resolver = resolver,
                    collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    relativePath = fallbackRelPath,
                    sourceFile = sourceFile,
                    task = task
                )
                if (savedInDownloads) {
                    return "Saved to MediaStore ($fallbackRelPath)"
                }
            } catch (_: Exception) {
                // Fallback to app storage note if MediaStore provider is unavailable in headless test
            }
        } else {
            // 4. Legacy Android 7.0 - 9.0 (API 24-28): Index the mirrored external file via MediaScannerConnection
            mirroredExtFile?.let { extFile ->
                runCatching {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(extFile.absolutePath),
                        arrayOf(task.mimeType),
                        null
                    )
                }
                return "Saved to External Storage (${extSubDirType}/MediaSave)"
            }
        }

        return "Saved to MediaSave Storage"
    }

    internal fun trySaveToSafDocumentTree(
        resolver: android.content.ContentResolver,
        treeUriString: String,
        sourceFile: File,
        task: DownloadTaskEntity
    ): Boolean {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return false
        var createdDocUri: Uri? = null
        return try {
            val treeUri = Uri.parse(treeUriString)
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            val parentDocumentUri = if (!docId.isNullOrBlank()) {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            } else {
                treeUri
            }
            createdDocUri = DocumentsContract.createDocument(
                resolver,
                parentDocumentUri,
                task.mimeType,
                task.fileName
            ) ?: return false

            val writtenBytes = resolver.openOutputStream(createdDocUri)?.use { outStream ->
                FileInputStream(sourceFile).use { inStream ->
                    inStream.copyTo(outStream, bufferSize = 32 * 1024)
                }
            } ?: 0L

            if (writtenBytes == sourceFile.length() && writtenBytes > 0L) {
                true
            } else {
                runCatching { DocumentsContract.deleteDocument(resolver, createdDocUri) }
                false
            }
        } catch (_: Exception) {
            createdDocUri?.let { uri ->
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            }
            false
        }
    }

    internal fun tryInsertIntoMediaStoreCollection(
        resolver: android.content.ContentResolver,
        collectionUri: Uri,
        relativePath: String,
        sourceFile: File,
        task: DownloadTaskEntity
    ): Boolean {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return false
        var itemUri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, task.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, task.mimeType)
                put(MediaStore.MediaColumns.SIZE, sourceFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            itemUri = resolver.insert(collectionUri, values) ?: return false
            val writtenBytes = resolver.openOutputStream(itemUri)?.use { outStream ->
                FileInputStream(sourceFile).use { inStream ->
                    inStream.copyTo(outStream, bufferSize = 32 * 1024)
                }
            } ?: 0L

            if (writtenBytes == sourceFile.length() && writtenBytes > 0L) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val publishValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, publishValues, null, null)
                }
                true
            } else {
                runCatching { resolver.delete(itemUri, null, null) }
                false
            }
        } catch (_: Exception) {
            itemUri?.let { uri ->
                runCatching { resolver.delete(uri, null, null) }
            }
            false
        }
    }

    private fun resolveLocalSourceFile(sourceUrl: String): File {
        val fromJavaUri = runCatching { File(URI(sourceUrl)) }.getOrNull()
        if (fromJavaUri != null && fromJavaUri.exists()) return fromJavaUri
        val parsedPath = runCatching { Uri.parse(sourceUrl).path }.getOrNull()
        if (!parsedPath.isNullOrBlank()) {
            val fromAndroidUri = File(parsedPath)
            if (fromAndroidUri.exists()) return fromAndroidUri
        }
        val stripped = sourceUrl.removePrefix("file://").removePrefix("file:")
        return File(stripped)
    }

    private suspend fun transferStreamToFile(
        taskId: String,
        sourceUrl: String,
        targetPartFile: File,
        supportsResume: Boolean,
        expectedTrackBytes: Long,
        overallTotalBytes: Long,
        baseByteOffset: Long,
        recoveryDepth: Int = 0
    ): Unit = withContext(Dispatchers.IO) {
        targetPartFile.parentFile?.mkdirs()
        var existingBytes = if (supportsResume && targetPartFile.exists()) targetPartFile.length() else 0L

        if (sourceUrl.startsWith("file:")) {
            val sourceFile = resolveLocalSourceFile(sourceUrl)
            if (!sourceFile.exists() || !sourceFile.canRead()) {
                throw IllegalArgumentException("Authorized local source file not found: ${sourceFile.name}")
            }
            val totalFileLen = sourceFile.length()
            if (totalFileLen <= 0L) {
                throw IllegalStateException("Source local file '${sourceFile.name}' is empty (0 bytes).")
            }
            if (existingBytes > totalFileLen) {
                existingBytes = 0L
            }
            checkAvailableStorageSpace(totalFileLen - existingBytes)

            val overallTotal = if (overallTotalBytes > 0L) overallTotalBytes else (baseByteOffset + totalFileLen)
            val shouldYieldPerChunk = totalFileLen <= 512 * 1024L

            RandomAccessFile(sourceFile, "r").use { inputRaf ->
                RandomAccessFile(targetPartFile, "rw").use { outputRaf ->
                    if (existingBytes == 0L) {
                        outputRaf.setLength(0L)
                    }
                    inputRaf.seek(existingBytes)
                    outputRaf.seek(existingBytes)

                    val buffer = ByteArray(16 * 1024)
                    var copied = existingBytes
                    var startWindowTime = System.currentTimeMillis()
                    var bytesInWindow = 0L

                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (pausedOrCancelledTasks.containsKey(taskId)) {
                            throw CancellationException("Task paused or cancelled")
                        }

                        val read = inputRaf.read(buffer)
                        if (read == -1) break
                        outputRaf.write(buffer, 0, read)
                        copied += read
                        bytesInWindow += read

                        if (shouldYieldPerChunk) {
                            delay(18)
                        }

                        val now = System.currentTimeMillis()
                        val elapsedMs = (now - startWindowTime).coerceAtLeast(1L)
                        val speedBps = (bytesInWindow * 1000L) / elapsedMs
                        val remainingBytes = (overallTotal - (baseByteOffset + copied)).coerceAtLeast(0L)
                        val etaSec = if (speedBps > 0) remainingBytes / speedBps else 0L

                        if (!pausedOrCancelledTasks.containsKey(taskId)) {
                            dao.updateProgress(
                                id = taskId,
                                downloadedBytes = baseByteOffset + copied,
                                totalBytes = overallTotal.coerceAtLeast(baseByteOffset + copied),
                                speedBytesPerSec = speedBps,
                                etaSeconds = etaSec,
                                status = DownloadStatus.DOWNLOADING.name,
                                statusDetail = "Streaming bytes (${formatBytesStatic(baseByteOffset + copied)} / ${formatBytesStatic(overallTotal)})"
                            )
                        }

                        if (elapsedMs > 500L) {
                            startWindowTime = now
                            bytesInWindow = 0L
                        }
                    }

                    if (copied < totalFileLen) {
                        throw EOFException("Incomplete local stream transfer: copied $copied of $totalFileLen bytes.")
                    }
                }
            }
            return@withContext
        }

        // If an older task had #fallback_res= in its URL, resolve the real remote stream URL first
        if (sourceUrl.contains("#fallback_res=")) {
            val baseOrigin = SocialStreamExtractor.stripUrlFragment(sourceUrl)
            val currentTask = dao.getTaskById(taskId)
            val resolvedRealUrl = SocialStreamExtractor.resolveFreshStreamUrlOn403(
                httpClient = httpClient,
                failedUrl = baseOrigin,
                originPageUrl = taskOriginUrls[taskId] ?: baseOrigin,
                thumbnailUrl = currentTask?.thumbnailUrl,
                isAudio = currentTask?.mode == DownloaderMode.MUSIC.name || baseByteOffset > 0L,
                preferredHeight = currentTask?.height ?: 720
            ) ?: throw IOException("Could not resolve direct media stream URL for $baseOrigin.")
            if (currentTask != null && baseByteOffset == 0L) {
                dao.upsertTask(currentTask.copy(sourceUrl = resolvedRealUrl))
            }
            transferStreamToFile(
                taskId = taskId,
                sourceUrl = resolvedRealUrl,
                targetPartFile = targetPartFile,
                supportsResume = supportsResume,
                expectedTrackBytes = expectedTrackBytes,
                overallTotalBytes = overallTotalBytes,
                baseByteOffset = baseByteOffset,
                recoveryDepth = recoveryDepth + 1
            )
            return@withContext
        }

        var activeSourceUrl = sourceUrl
        var cleanUrl = normalizeStreamUrl(activeSourceUrl)

        // Proactive expiration check before opening a connection
        if (SocialStreamExtractor.isStreamUrlExpired(cleanUrl)) {
            val refreshed = resolveFreshUrlForTask(taskId, activeSourceUrl, cleanUrl, baseByteOffset, recoveryDepth)
            if (!refreshed.isNullOrBlank()) {
                activeSourceUrl = refreshed
                cleanUrl = normalizeStreamUrl(activeSourceUrl)
            } else {
                throw IOException("Access denied or expired media link (HTTP 410 / URL expired). Please re-analyze the source URL.")
            }
        }

        val urlClen = Regex("[?&]clen=(\\d+)").find(cleanUrl)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        var trackTotalBytes = when {
            urlClen > 0L -> urlClen
            expectedTrackBytes > 0L -> expectedTrackBytes
            else -> -1L
        }

        var currentOffset = if (supportsResume && targetPartFile.exists()) targetPartFile.length() else 0L
        if (trackTotalBytes > 0L && currentOffset > trackTotalBytes) {
            currentOffset = 0L
        }
        if (trackTotalBytes > 0L && currentOffset == trackTotalBytes) {
            return@withContext
        }

        val googleChunkWindow = 4L * 1024L * 1024L // 4 MB HTTP Range window for googlevideo.com
        var consecutiveFailures = 0
        var activeRecoveryDepth = recoveryDepth
        var refreshProgressAnchor = currentOffset

        while (true) {
            currentCoroutineContext().ensureActive()
            if (pausedOrCancelledTasks.containsKey(taskId)) {
                throw CancellationException("Task paused or cancelled")
            }
            if (currentOffset > refreshProgressAnchor) {
                activeRecoveryDepth = recoveryDepth
                refreshProgressAnchor = currentOffset
            }

            val host = runCatching { URI(cleanUrl).host?.lowercase() }.getOrNull().orEmpty()
            val isGoogleVideo = host.contains("googlevideo.com")
            val offsetBeforeAttempt = currentOffset

            val requestedWindowEnd = if (isGoogleVideo) {
                if (trackTotalBytes > 0L) {
                    minOf(currentOffset + googleChunkWindow - 1L, trackTotalBytes - 1L)
                } else {
                    currentOffset + googleChunkWindow - 1L
                }
            } else {
                -1L
            }
            val requestedWindowLen = if (requestedWindowEnd >= currentOffset) {
                requestedWindowEnd - currentOffset + 1L
            } else {
                -1L
            }

            try {
                val reqBuilder = Request.Builder().url(cleanUrl).get()
                for ((headerName, headerVal) in SocialStreamExtractor.buildStreamRequestHeaders(cleanUrl)) {
                    reqBuilder.header(headerName, headerVal)
                }
                if (isGoogleVideo && requestedWindowEnd >= currentOffset) {
                    reqBuilder.header("Range", "bytes=$currentOffset-$requestedWindowEnd")
                } else if (currentOffset > 0L && supportsResume) {
                    reqBuilder.header("Range", "bytes=$currentOffset-")
                }

                val initialResponse = httpClient.newCall(reqBuilder.build()).execute()
                val activeResponse = if (initialResponse.code in listOf(401, 403, 410)) {
                    val initialCode = initialResponse.code
                    initialResponse.close()
                    appendTaskLog(
                        taskId,
                        "HTTP $initialCode at offset=$currentOffset (host=$host, stage=${if (baseByteOffset == 0L) "primary" else "secondary-audio"})"
                    )

                    // Step A: For non-googlevideo 401/403, try alternative User-Agent / Referer profiles on the same URL.
                    // Never send mismatched TikTok/Facebook/iOS User-Agents to googlevideo.com as its URL signature binds `c=`.
                    val altResponse = if (initialCode != 410 && !isGoogleVideo) {
                        tryAlternativeHeaderProfiles(cleanUrl, currentOffset, requestedWindowEnd, supportsResume)
                    } else null

                    if (altResponse != null) {
                        appendTaskLog(taskId, "Alternative header profile succeeded with HTTP ${altResponse.code}")
                        altResponse
                    } else {
                        // Step B: Resolve a fresh non-expired stream URL from fallbacks or extractor
                        val freshUrl = resolveFreshUrlForTask(
                            taskId = taskId,
                            failedSourceUrl = activeSourceUrl,
                            cleanFailedUrl = cleanUrl,
                            baseByteOffset = baseByteOffset,
                            recoveryDepth = activeRecoveryDepth
                        )
                        if (!freshUrl.isNullOrBlank()) {
                            appendTaskLog(taskId, "Refreshed stream URL (recoveryDepth=${activeRecoveryDepth + 1}); resuming at offset=$currentOffset")
                            activeSourceUrl = freshUrl
                            cleanUrl = normalizeStreamUrl(activeSourceUrl)
                            activeRecoveryDepth++
                            if (!supportsResume && targetPartFile.exists()) {
                                targetPartFile.delete()
                                currentOffset = 0L
                            }
                            continue
                        }

                        if (initialCode == 410) {
                            throw IOException("Media link has expired and is no longer available (HTTP 410).")
                        }
                        throw IOException("Access denied or expired media link (HTTP $initialCode). Please re-analyze the source URL.")
                    }
                } else {
                    initialResponse
                }

                var streamCompleted = false
                activeResponse.use { response ->
                    // Handle HTTP 416 Range Not Satisfiable
                    if (response.code == 416 && currentOffset > 0L) {
                        val contentRangeTotal = parseContentRangeTotal(response.header("Content-Range"))
                        if (contentRangeTotal > 0L && currentOffset >= contentRangeTotal) {
                            trackTotalBytes = contentRangeTotal
                            streamCompleted = true
                            return@use
                        }
                        targetPartFile.delete()
                        currentOffset = 0L
                        return@use
                    }

                    if (!response.isSuccessful) {
                        val message = when (response.code) {
                            401, 403 -> "Access denied or expired media link (HTTP ${response.code}). Please re-analyze the source URL."
                            404 -> "Media file not found on remote server (HTTP 404)."
                            410 -> "Media link has expired and is no longer available (HTTP 410)."
                            429 -> "Rate limited by remote server (HTTP 429). Please wait and retry."
                            in 500..599 -> "Remote media server error (HTTP ${response.code} ${response.message})."
                            else -> "Server returned HTTP ${response.code} (${response.message})."
                        }
                        throw IOException(message)
                    }

                    val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    MediaProcessorEngine.detectNonMediaErrorPayload(ByteArray(0), 0, contentType)?.let { errMsg ->
                        targetPartFile.delete()
                        throw IllegalStateException(errMsg)
                    }

                    val isPartialContent = response.code == 206
                    val contentRangeTotal = parseContentRangeTotal(response.header("Content-Range"))
                    if (contentRangeTotal > 0L) {
                        trackTotalBytes = contentRangeTotal
                    }

                    val body = response.body ?: throw IOException("Server returned an empty HTTP response body.")
                    val contentLen = body.contentLength()
                    if (contentLen > 0L) {
                        checkAvailableStorageSpace(contentLen)
                    }

                    if (!isPartialContent) {
                        // HTTP 200 OK means server is sending the full stream starting from byte 0
                        currentOffset = 0L
                        if (contentLen > 0L) {
                            trackTotalBytes = contentLen
                        }
                    } else if (trackTotalBytes <= 0L && !isGoogleVideo && contentLen > 0L) {
                        trackTotalBytes = currentOffset + contentLen
                    }

                    val overallTotal = when {
                        overallTotalBytes > 0L && trackTotalBytes > 0L -> maxOf(overallTotalBytes, baseByteOffset + trackTotalBytes)
                        trackTotalBytes > 0L -> baseByteOffset + trackTotalBytes
                        overallTotalBytes > 0L -> overallTotalBytes
                        contentLen > 0L -> baseByteOffset + currentOffset + contentLen
                        else -> -1L
                    }

                    RandomAccessFile(targetPartFile, "rw").use { outputRaf ->
                        if (!isPartialContent) {
                            outputRaf.setLength(0L)
                        }
                        outputRaf.seek(currentOffset)

                        body.byteStream().use { inputStream ->
                            val buffer = ByteArray(64 * 1024)
                            var chunkBytesRead = 0L
                            var inspectedFirstChunk = currentOffset > 0L
                            var windowStart = System.currentTimeMillis()
                            var windowBytes = 0L
                            var lastDbUpdate = 0L

                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (pausedOrCancelledTasks.containsKey(taskId)) {
                                    throw CancellationException("Task paused or cancelled")
                                }

                                val read = inputStream.read(buffer)
                                if (read == -1) break

                                if (!inspectedFirstChunk && read > 0) {
                                    inspectedFirstChunk = true
                                    MediaProcessorEngine.detectNonMediaErrorPayload(buffer, read, contentType)?.let { errMsg ->
                                        outputRaf.close()
                                        targetPartFile.delete()
                                        throw IllegalStateException(errMsg)
                                    }
                                }

                                outputRaf.write(buffer, 0, read)
                                currentOffset += read
                                chunkBytesRead += read
                                windowBytes += read

                                val now = System.currentTimeMillis()
                                if (now - lastDbUpdate >= 100L && !pausedOrCancelledTasks.containsKey(taskId)) {
                                    val elapsedMs = (now - windowStart).coerceAtLeast(1L)
                                    val speedBps = (windowBytes * 1000L) / elapsedMs
                                    val currentOverall = baseByteOffset + currentOffset
                                    val rem = if (overallTotal > 0) (overallTotal - currentOverall).coerceAtLeast(0L) else 0L
                                    val etaSec = if (speedBps > 0 && overallTotal > 0) rem / speedBps else -1L

                                    dao.updateProgress(
                                        id = taskId,
                                        downloadedBytes = currentOverall,
                                        totalBytes = overallTotal.coerceAtLeast(currentOverall),
                                        speedBytesPerSec = speedBps,
                                        etaSeconds = etaSec,
                                        status = DownloadStatus.DOWNLOADING.name,
                                        statusDetail = if (isPartialContent && offsetBeforeAttempt > 0L) {
                                            "Resumed via HTTP Range (206) • ${formatBytesStatic(currentOverall)}"
                                        } else {
                                            "Downloading HTTP stream • ${formatBytesStatic(currentOverall)}"
                                        }
                                    )
                                    lastDbUpdate = now
                                    if (elapsedMs > 1000L) {
                                        windowStart = now
                                        windowBytes = 0L
                                    }
                                }
                            }

                            if (contentLen > 0L && chunkBytesRead < contentLen) {
                                throw EOFException(
                                    "Connection interrupted before completion: received ${formatBytesStatic(currentOffset)} of ${formatBytesStatic(offsetBeforeAttempt + contentLen)}."
                                )
                            }
                            if (chunkBytesRead <= 0L && currentOffset <= 0L) {
                                throw EOFException("Remote server closed connection after sending 0 bytes.")
                            }

                            consecutiveFailures = 0

                            when {
                                !isPartialContent -> {
                                    // Full HTTP 200 stream finished
                                    streamCompleted = true
                                }
                                trackTotalBytes > 0L && currentOffset >= trackTotalBytes -> {
                                    streamCompleted = true
                                }
                                !isGoogleVideo -> {
                                    // Open-ended Range: bytes=start- finished reading all advertised bytes
                                    streamCompleted = true
                                }
                                isGoogleVideo && requestedWindowLen > 0L && chunkBytesRead < requestedWindowLen -> {
                                    // Server reached EOF before the end of the 4 MB window
                                    trackTotalBytes = currentOffset
                                    streamCompleted = true
                                }
                            }
                        }
                    }
                }

                if (streamCompleted) {
                    appendTaskLog(
                        taskId,
                        "Stream transfer finished (${formatBytesStatic(currentOffset)}, stage=${if (baseByteOffset == 0L) "primary" else "secondary-audio"})"
                    )
                    break
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ise: IllegalStateException) {
                throw ise
            } catch (iae: IllegalArgumentException) {
                throw iae
            } catch (ioe: IOException) {
                currentCoroutineContext().ensureActive()
                if (pausedOrCancelledTasks.containsKey(taskId)) {
                    throw CancellationException("Task paused or cancelled")
                }

                val currentDiskLen = if (targetPartFile.exists()) targetPartFile.length() else 0L
                currentOffset = currentDiskLen
                val msg = ioe.message.orEmpty()
                val isPermanentHttpError = msg.contains("HTTP 401") ||
                    msg.contains("HTTP 403") ||
                    msg.contains("HTTP 404") ||
                    msg.contains("HTTP 410") ||
                    msg.contains("HTTP 429") ||
                    msg.contains("ENOSPC", ignoreCase = true) ||
                    msg.contains("Insufficient storage", ignoreCase = true) ||
                    msg.contains("Wi-Fi Only", ignoreCase = true)

                if (currentDiskLen > offsetBeforeAttempt + 65_536L) {
                    // Reset failure counter when meaningful forward progress was achieved before the drop
                    consecutiveFailures = 0
                }
                consecutiveFailures++

                if (supportsResume && !isPermanentHttpError && consecutiveFailures <= 5 && currentDiskLen > 0L) {
                    Log.i(TAG, "Stream interrupted at $currentDiskLen bytes (attempt $consecutiveFailures). Auto-resuming via HTTP Range...")
                    appendTaskLog(taskId, "Transient I/O drop at $currentDiskLen bytes (attempt $consecutiveFailures); auto-resuming via HTTP Range")
                    if (!pausedOrCancelledTasks.containsKey(taskId)) {
                        dao.updateProgress(
                            id = taskId,
                            downloadedBytes = baseByteOffset + currentDiskLen,
                            totalBytes = overallTotalBytes.coerceAtLeast(baseByteOffset + currentDiskLen),
                            speedBytesPerSec = 0L,
                            etaSeconds = -1L,
                            status = DownloadStatus.DOWNLOADING.name,
                            statusDetail = "Resuming stream (${formatBytesStatic(baseByteOffset + currentDiskLen)})..."
                        )
                    }
                    delay(minOf(150L * consecutiveFailures, 1000L))
                    continue
                }
                throw ioe
            }
        }

        val finalDiskBytes = if (targetPartFile.exists()) targetPartFile.length() else 0L
        if (finalDiskBytes <= 0L) {
            throw EOFException("Downloaded stream file is empty (0 bytes).")
        }
        if (trackTotalBytes > 0L && finalDiskBytes < trackTotalBytes) {
            throw EOFException(
                "Incomplete stream download: expected $trackTotalBytes bytes, but received $finalDiskBytes bytes."
            )
        }
    }

    private fun normalizeStreamUrl(rawUrl: String): String {
        val stripped = SocialStreamExtractor.stripUrlFragment(rawUrl)
        return if (stripped.contains("googlevideo.com/videoplayback")) {
            stripped.replace(Regex("([?&])range=[^&]+"), "$1")
                .replace("?&", "?")
                .replace("&&", "&")
                .trimEnd('?', '&')
        } else {
            stripped
        }
    }

    private fun parseContentRangeTotal(contentRangeHeader: String?): Long {
        if (contentRangeHeader.isNullOrBlank()) return -1L
        val totalPart = contentRangeHeader.substringAfterLast('/', "").trim()
        if (totalPart.isEmpty() || totalPart == "*") return -1L
        return totalPart.toLongOrNull() ?: -1L
    }

    private suspend fun resolveFreshUrlForTask(
        taskId: String,
        failedSourceUrl: String,
        cleanFailedUrl: String,
        baseByteOffset: Long,
        recoveryDepth: Int
    ): String? {
        if (recoveryDepth >= 3) return null
        val currentTask = dao.getTaskById(taskId)
        if (!pausedOrCancelledTasks.containsKey(taskId)) {
            dao.updateProgress(
                id = taskId,
                downloadedBytes = currentTask?.downloadedBytes ?: 0L,
                totalBytes = currentTask?.totalBytes ?: 0L,
                speedBytesPerSec = 0L,
                etaSeconds = -1L,
                status = DownloadStatus.DOWNLOADING.name,
                statusDetail = "Refreshing expired or restricted media stream..."
            )
        }

        // Only use queued fallback URLs for the primary stream (baseByteOffset == 0L).
        // When baseByteOffset > 0L, we are downloading Stage 2 secondaryAudioUrl and must resolve an audio URL!
        if (baseByteOffset == 0L) {
            val queuedFallbacks = taskFallbackUrls[taskId].orEmpty()
            val nextQueuedFallback = queuedFallbacks.firstOrNull {
                normalizeStreamUrl(it) != cleanFailedUrl &&
                    !SocialStreamExtractor.isStreamUrlExpired(it)
            }
            if (nextQueuedFallback != null) {
                taskFallbackUrls[taskId] = queuedFallbacks.filter { it != nextQueuedFallback }
                if (currentTask != null) {
                    dao.upsertTask(currentTask.copy(sourceUrl = nextQueuedFallback))
                }
                return nextQueuedFallback
            }
        }

        val freshUrl = SocialStreamExtractor.resolveFreshStreamUrlOn403(
            httpClient = httpClient,
            failedUrl = failedSourceUrl,
            originPageUrl = taskOriginUrls[taskId] ?: currentTask?.sourceUrl,
            thumbnailUrl = currentTask?.thumbnailUrl,
            isAudio = currentTask?.mode == DownloaderMode.MUSIC.name || baseByteOffset > 0L,
            preferredHeight = currentTask?.height ?: 720
        )
        if (!freshUrl.isNullOrBlank() && normalizeStreamUrl(freshUrl) != cleanFailedUrl) {
            if (currentTask != null) {
                if (baseByteOffset == 0L) {
                    dao.upsertTask(currentTask.copy(sourceUrl = freshUrl))
                } else {
                    dao.upsertTask(currentTask.copy(secondaryAudioUrl = freshUrl))
                }
            }
            return freshUrl
        }
        return null
    }

    private fun tryAlternativeHeaderProfiles(
        cleanUrl: String,
        existingBytes: Long,
        requestedWindowEnd: Long,
        supportsResume: Boolean
    ): okhttp3.Response? {
        val rangeVal = when {
            requestedWindowEnd >= existingBytes -> "bytes=$existingBytes-$requestedWindowEnd"
            existingBytes > 0L && supportsResume -> "bytes=$existingBytes-"
            else -> "bytes=0-"
        }
        val candidateProfiles = listOf(
            mapOf(
                "User-Agent" to SocialStreamExtractor.MOBILE_USER_AGENT,
                "Accept" to "*/*",
                "Referer" to "https://www.tikwm.com/"
            ),
            mapOf(
                "User-Agent" to SocialStreamExtractor.MOBILE_USER_AGENT,
                "Accept" to "*/*",
                "Referer" to "https://www.tiktok.com/"
            ),
            mapOf(
                "User-Agent" to SocialStreamExtractor.DESKTOP_USER_AGENT,
                "Accept" to "*/*",
                "Referer" to "https://www.facebook.com/"
            ),
            mapOf(
                "User-Agent" to SocialStreamExtractor.DESKTOP_USER_AGENT,
                "Accept" to "*/*",
                "Referer" to "https://www.instagram.com/"
            )
        )
        for (headers in candidateProfiles) {
            try {
                val rb = Request.Builder().url(cleanUrl).get()
                for ((k, v) in headers) {
                    rb.header(k, v)
                }
                if (existingBytes > 0L && supportsResume && !headers.containsKey("Range")) {
                    rb.header("Range", rangeVal)
                }
                val resp = httpClient.newCall(rb.build()).execute()
                if (resp.isSuccessful) {
                    val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    if (MediaProcessorEngine.detectNonMediaErrorPayload(ByteArray(0), 0, ct) == null) {
                        return resp
                    }
                }
                resp.close()
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Runs an end-to-end self-diagnostic that verifies:
     * 1. Extractor engine & Android/OkHttp runtime version,
     * 2. Android MediaMuxer + MediaExtractor availability,
     * 3. A REAL extraction and full download of `https://www.youtube.com/watch?v=jNQXAC9IVRw`
     *    ("Me at the zoo", 19s) into a temporary file, verifying MP4 container integrity and deleting
     *    the temporary file afterwards.
     */
    suspend fun runDiagnostics(): DiagnosticsReport = withContext(Dispatchers.IO) {
        val testUrl = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        val testVideoId = "jNQXAC9IVRw"
        val logs = mutableListOf<String>()
        fun logLine(msg: String) {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            logs.add("[$ts] $msg")
        }

        val extractorVer = SocialStreamExtractor.EXTRACTOR_ENGINE_VERSION
        logLine("Extractor: $extractorVer")
        logLine("Runtime: Android API ${Build.VERSION.SDK_INT} (${Build.MODEL}) • OkHttp 4.12")

        // Check MediaMuxer availability
        val muxerProbeFile = File(context.cacheDir, "diag_muxer_probe_${System.currentTimeMillis()}.mp4")
        val muxerAvailable = try {
            val muxer = android.media.MediaMuxer(
                muxerProbeFile.absolutePath,
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxer.release()
            true
        } catch (e: Exception) {
            logLine("MediaMuxer probe warning: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            runCatching { muxerProbeFile.delete() }
        }
        val muxerStatus = if (muxerAvailable) {
            "Available (Android MediaMuxer MPEG-4 + MediaExtractor)"
        } else {
            "Unavailable"
        }
        logLine("Muxer check: $muxerStatus")

        val tempVideoPart = File(context.cacheDir, "diag_${testVideoId}_${System.currentTimeMillis()}.part")
        val tempAudioPart = File(context.cacheDir, "diag_${testVideoId}_${System.currentTimeMillis()}.audio.part")
        val tempFinalFile = File(context.cacheDir, "diag_${testVideoId}_${System.currentTimeMillis()}.mp4")

        val startTime = System.currentTimeMillis()
        var exitCode = -1
        var statusSummary = "Failed"
        var downloadedBytes = 0L

        try {
            logLine("Extracting YouTube streams for $testUrl ...")
            val freshStreams = SocialStreamExtractor.extractFreshYouTubeTaskStreams(
                httpClient = httpClient,
                videoId = testVideoId,
                isAudioMode = false,
                preferredHeight = 360,
                preferProgressiveFallback = true
            )
            logs.addAll(SocialStreamExtractor.getDiagnosticLog(testVideoId).takeLast(6))

            if (freshStreams == null) {
                val (errTitle, errMsg) = SocialStreamExtractor.mapYouTubeFailureMessage(
                    SocialStreamExtractor.getLastPlayabilityReason(testVideoId),
                    testVideoId
                )
                logLine("Extraction failed: $errTitle — $errMsg")
                exitCode = 1
                statusSummary = "$errTitle: $errMsg"
            } else {
                val cleanPrimary = normalizeStreamUrl(freshStreams.primaryUrl)
                val itag = Regex("[?&]itag=(\\d+)(?:&|$)").find(cleanPrimary)?.groupValues?.getOrNull(1) ?: "unknown"
                val clientParam = Regex("[?&]c=([^&]+)").find(cleanPrimary)?.groupValues?.getOrNull(1) ?: "proxy"
                logLine("Resolved stream itag=$itag, client=$clientParam, merge=${freshStreams.requiresStreamMerge}")

                // Download primary stream into tempVideoPart using 4 MB Range window
                downloadDiagnosticStreamToFile(cleanPrimary, tempVideoPart) { msg -> logLine(msg) }

                if (freshStreams.requiresStreamMerge && !freshStreams.secondaryAudioUrl.isNullOrBlank()) {
                    val cleanAudio = normalizeStreamUrl(freshStreams.secondaryAudioUrl)
                    logLine("Downloading paired audio stream...")
                    downloadDiagnosticStreamToFile(cleanAudio, tempAudioPart) { msg -> logLine(msg) }
                    logLine("Merging video (${formatBytesStatic(tempVideoPart.length())}) + audio (${formatBytesStatic(tempAudioPart.length())})...")
                    val merged = MediaProcessorEngine.mergeVideoAndAudioStreams(tempVideoPart, tempAudioPart, tempFinalFile)
                    if (!merged) {
                        throw IOException("MediaMuxer failed to merge diagnostic video + audio tracks.")
                    }
                } else {
                    tempVideoPart.copyTo(tempFinalFile, overwrite = true)
                }

                downloadedBytes = tempFinalFile.length()
                val integrity = MediaProcessorEngine.validateMediaFileIntegrity(tempFinalFile)
                if (integrity.isSuccess && downloadedBytes > 0L) {
                    val meta = integrity.getOrThrow()
                    exitCode = 0
                    statusSummary = "HEALTHY • Downloaded ${formatBytesStatic(downloadedBytes)} (${meta.width}x${meta.height}, ${meta.durationMs / 1000}s) • Exit 0"
                    logLine("Integrity verified: ${formatBytesStatic(downloadedBytes)}, ${meta.width}x${meta.height}, duration=${meta.durationMs}ms")
                    logLine("Diagnostics test download succeeded (exitCode=0). Cleaning up temp file.")
                } else {
                    val reason = integrity.exceptionOrNull()?.message ?: "Empty or invalid container"
                    exitCode = 2
                    statusSummary = "Container verification failed: $reason"
                    logLine("Verification failed (exitCode=2): $reason")
                }
            }
        } catch (e: Exception) {
            exitCode = 3
            val mapped = if (e.message?.contains("403") == true) {
                SocialStreamExtractor.mapYouTubeFailureMessage(e.message, testVideoId).second
            } else {
                formatDownloadExceptionMessage(e)
            }
            statusSummary = mapped
            logLine("Diagnostic download exception (exitCode=3): ${e.javaClass.simpleName}: $mapped")
        } finally {
            runCatching { tempVideoPart.delete() }
            runCatching { tempAudioPart.delete() }
            runCatching { tempFinalFile.delete() }
        }

        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
        DiagnosticsReport(
            isHealthy = exitCode == 0,
            extractorVersion = extractorVer,
            muxerStatus = muxerStatus,
            testVideoUrl = testUrl,
            exitCode = exitCode,
            statusSummary = statusSummary,
            downloadedBytes = downloadedBytes,
            durationMs = elapsed,
            logLines = logs.takeLast(15)
        )
    }

    private fun downloadDiagnosticStreamToFile(
        cleanUrl: String,
        targetFile: File,
        onLog: (String) -> Unit
    ) {
        val host = runCatching { URI(cleanUrl).host?.lowercase() }.getOrNull().orEmpty()
        val isGoogleVideo = host.contains("googlevideo.com")
        val urlClen = Regex("[?&]clen=(\\d+)").find(cleanUrl)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
        var trackTotal = urlClen
        var offset = 0L
        val windowSize = 4L * 1024L * 1024L

        RandomAccessFile(targetFile, "rw").use { raf ->
            raf.setLength(0L)
            while (true) {
                val endByte = if (isGoogleVideo) {
                    if (trackTotal > 0L) minOf(offset + windowSize - 1L, trackTotal - 1L) else offset + windowSize - 1L
                } else -1L
                val reqBuilder = Request.Builder().url(cleanUrl).get()
                for ((k, v) in SocialStreamExtractor.buildStreamRequestHeaders(cleanUrl)) {
                    reqBuilder.header(k, v)
                }
                if (isGoogleVideo && endByte >= offset) {
                    reqBuilder.header("Range", "bytes=$offset-$endByte")
                }
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    onLog("HTTP GET ${host.ifEmpty { "stream" }} (offset=$offset) -> HTTP ${resp.code}")
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} from $host during diagnostic download")
                    }
                    val contentRangeTotal = parseContentRangeTotal(resp.header("Content-Range"))
                    if (contentRangeTotal > 0L) trackTotal = contentRangeTotal
                    val body = resp.body ?: throw IOException("Empty response body")
                    val contentLen = body.contentLength()
                    if (resp.code != 206 && contentLen > 0L) {
                        trackTotal = contentLen
                    }
                    var chunkRead = 0L
                    body.byteStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = ins.read(buf)
                            if (r == -1) break
                            raf.write(buf, 0, r)
                            offset += r
                            chunkRead += r
                        }
                    }
                    val done = resp.code != 206 ||
                        (trackTotal > 0L && offset >= trackTotal) ||
                        !isGoogleVideo ||
                        (endByte >= 0L && chunkRead < (endByte - (offset - chunkRead) + 1L))
                    if (done) break
                }
            }
        }
    }

    private fun ensureUniqueFileName(desiredName: String): String {
        val baseFile = File(downloadsDir, desiredName)
        if (!baseFile.exists()) return desiredName
        val nameWithoutExt = baseFile.nameWithoutExtension
        val ext = baseFile.extension
        var counter = 1
        while (true) {
            val candidate = if (ext.isNotEmpty()) "${nameWithoutExt}_$counter.$ext" else "${nameWithoutExt}_$counter"
            if (!File(downloadsDir, candidate).exists()) return candidate
            counter++
        }
    }

    private fun computeSha256Short(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().take(6).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "verified"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MediaSave Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for completed video and audio downloads"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun postCompletionNotification(task: DownloadTaskEntity) {
        if (!notificationsEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download Complete: ${task.title}")
                .setContentText("${task.containerFormat} • ${formatBytesStatic(task.totalBytes)}")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(task.id.hashCode(), notification)
        }
    }

    companion object {
        private const val TAG = "MediaSaveDownloadEngine"
        private const val CHANNEL_ID = "mediasave_downloads_channel"

        @Volatile
        private var INSTANCE: DownloadEngine? = null

        fun getInstance(context: Context, dao: DownloadTaskDao): DownloadEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadEngine(
                    context = context.applicationContext,
                    dao = dao,
                    externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                ).also { INSTANCE = it }
            }
        }

        fun formatBytesStatic(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val kb = 1024.0
            val mb = kb * 1024.0
            val gb = mb * 1024.0
            return when {
                bytes >= gb -> String.format("%.2f GB", bytes / gb)
                bytes >= mb -> String.format("%.2f MB", bytes / mb)
                bytes >= kb -> String.format("%.1f KB", bytes / kb)
                else -> "$bytes B"
            }
        }
    }
}
