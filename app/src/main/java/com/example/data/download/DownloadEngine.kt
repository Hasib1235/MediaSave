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
import com.example.data.media.MediaProcessorEngine
import com.example.domain.model.AnalyzedMediaMetadata
import com.example.domain.model.AudioFormat
import com.example.domain.model.AudioOutputChoice
import com.example.domain.model.AudioStreamOption
import com.example.domain.model.DownloadStatus
import com.example.domain.model.DownloadTaskEntity
import com.example.domain.model.DownloaderMode
import com.example.domain.model.StreamType
import com.example.domain.model.VideoStreamOption
import com.example.domain.validation.UrlValidationResult
import com.example.domain.validation.UrlValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
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

    init {
        createNotificationChannel()
        externalScope.launch {
            runCatching {
                val recovered = dao.recoverInterruptedTasksOnStartup()
                if (recovered > 0) {
                    Log.i(TAG, "Recovered $recovered interrupted download task(s) to PAUSED state.")
                }
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

        dao.upsertTask(task)
        startTaskExecution(task.id)
        EnqueueResult.Enqueued(task)
    }

    fun pauseDownload(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        externalScope.launch {
            val current = dao.getTaskById(taskId) ?: return@launch
            if (current.statusEnum.isActive) {
                dao.upsertTask(
                    current.copy(
                        status = DownloadStatus.PAUSED.name,
                        statusDetail = "Paused at ${current.progressPercent}% • Ready to resume",
                        speedBytesPerSec = 0L,
                        etaSeconds = -1L
                    )
                )
            }
        }
    }

    fun resumeDownload(taskId: String) {
        externalScope.launch {
            val current = dao.getTaskById(taskId) ?: return@launch
            if (current.statusEnum == DownloadStatus.PAUSED || current.statusEnum == DownloadStatus.FAILED) {
                dao.upsertTask(
                    current.copy(
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
        externalScope.launch {
            val current = dao.getTaskById(taskId) ?: return@launch
            val partFile = File("${current.outputFilePath}.part")
            if (partFile.exists()) partFile.delete()
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
        activeJobs.remove(taskId)?.cancel()
        externalScope.launch {
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
        activeJobs.remove(taskId)?.cancel()
        val task = dao.getTaskById(taskId)
        if (task != null) {
            runCatching { File(task.outputFilePath).delete() }
            runCatching { File("${task.outputFilePath}.part").delete() }
            runCatching { File("${task.outputFilePath}.audio.part").delete() }
            dao.deleteById(taskId)
        }
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
        val job = externalScope.launch {
            concurrencySemaphore.withPermit {
                executeDownloadPipeline(taskId)
            }
        }
        activeJobs[taskId] = job
        job.invokeOnCompletion { activeJobs.remove(taskId) }
    }

    private suspend fun executeDownloadPipeline(taskId: String) {
        val initialTask = dao.getTaskById(taskId) ?: return
        val finalFile = File(initialTask.outputFilePath)
        val primaryPartFile = File("${initialTask.outputFilePath}.part")
        val secondaryAudioPartFile = File("${initialTask.outputFilePath}.audio.part")
        val sanitizedUrl = UrlValidator.sanitizeUrlForDiagnosticLog(initialTask.sourceUrl)

        try {
            // Pre-flight check 1: Validate URL is not mock or invalid
            when (val validation = UrlValidator.validateAndClassify(initialTask.sourceUrl)) {
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
            checkNetworkConnectivity(initialTask.sourceUrl)
            checkAvailableStorageSpace(initialTask.totalBytes)

            // Stage 1: Transfer primary stream to temporary .part file (with Range resume support)
            dao.updateProgress(
                id = taskId,
                downloadedBytes = initialTask.downloadedBytes,
                totalBytes = initialTask.totalBytes,
                speedBytesPerSec = 0L,
                etaSeconds = -1L,
                status = DownloadStatus.DOWNLOADING.name,
                statusDetail = "Connecting to media stream..."
            )

            transferStreamToFile(
                taskId = taskId,
                sourceUrl = initialTask.sourceUrl,
                targetPartFile = primaryPartFile,
                supportsResume = initialTask.supportsRangeResume,
                knownTotalBytes = initialTask.totalBytes,
                baseByteOffset = 0L
            )

            if (!primaryPartFile.exists() || primaryPartFile.length() == 0L) {
                throw IOException("Empty response: Downloaded stream wrote 0 bytes to temporary file.")
            }

            // Stage 2: If video-only adaptive stream requires merging with paired audio stream
            if (initialTask.requiresStreamMerge && !initialTask.secondaryAudioUrl.isNullOrBlank()) {
                dao.updateProgress(
                    id = taskId,
                    downloadedBytes = primaryPartFile.length(),
                    totalBytes = initialTask.totalBytes,
                    speedBytesPerSec = 0L,
                    etaSeconds = 1L,
                    status = DownloadStatus.DOWNLOADING.name,
                    statusDetail = "Downloading paired AAC audio stream..."
                )

                transferStreamToFile(
                    taskId = taskId,
                    sourceUrl = initialTask.secondaryAudioUrl,
                    targetPartFile = secondaryAudioPartFile,
                    supportsResume = true,
                    knownTotalBytes = initialTask.totalBytes,
                    baseByteOffset = primaryPartFile.length()
                )

                dao.updateProgress(
                    id = taskId,
                    downloadedBytes = primaryPartFile.length() + secondaryAudioPartFile.length(),
                    totalBytes = initialTask.totalBytes,
                    speedBytesPerSec = 0L,
                    etaSeconds = 1L,
                    status = DownloadStatus.MERGING_STREAMS.name,
                    statusDetail = "Muxing H.264 video + AAC audio via Android MediaMuxer..."
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
            } else if (initialTask.mode == DownloaderMode.MUSIC.name &&
                (initialTask.requiresAudioExtraction || initialTask.isConverted)
            ) {
                // Stage 3: Lawful Audio Extraction or Format Conversion
                val statusStage = if (initialTask.requiresAudioExtraction && !initialTask.isConverted) {
                    DownloadStatus.EXTRACTING_AUDIO
                } else {
                    DownloadStatus.CONVERTING_AUDIO
                }
                val targetFormat = runCatching {
                    AudioFormat.valueOf(initialTask.targetAudioFormat ?: "M4A")
                }.getOrDefault(AudioFormat.M4A)

                dao.updateProgress(
                    id = taskId,
                    downloadedBytes = primaryPartFile.length(),
                    totalBytes = initialTask.totalBytes.coerceAtLeast(primaryPartFile.length()),
                    speedBytesPerSec = 0L,
                    etaSeconds = 1L,
                    status = statusStage.name,
                    statusDetail = "${statusStage.label} -> ${targetFormat.label}..."
                )

                val convertedOk = MediaProcessorEngine.extractOrConvertAudio(
                    sourceFile = primaryPartFile,
                    outputFile = finalFile,
                    targetFormat = targetFormat,
                    sampleRateHz = initialTask.sampleRateHz.takeIf { it > 0 } ?: 44100,
                    channelCount = if (initialTask.channelConfig.contains("Mono")) 1 else 2
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

            if (!finalFile.exists() || finalFile.length() == 0L) {
                if (finalFile.exists()) finalFile.delete()
                throw IllegalStateException("Storage write failure: Final media file is empty (0 bytes).")
            }

            val sha256 = computeSha256Short(finalFile)
            val inspected = MediaProcessorEngine.inspectMediaFile(finalFile)
            val refreshedTask = dao.getTaskById(taskId) ?: initialTask

            // Save completed file to custom SAF directory (if configured) and Android MediaStore
            val savedLocationNote = exportCompletedFileToMediaStore(finalFile, refreshedTask)

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
                errorMessage = null
            )

            dao.upsertTask(finalCompleted)
            postCompletionNotification(finalCompleted)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Remove incomplete output or empty/non-resumable temporary files on failure
            runCatching {
                if (finalFile.exists()) finalFile.delete()
                if (secondaryAudioPartFile.exists()) secondaryAudioPartFile.delete()
                if (primaryPartFile.exists() && (!initialTask.supportsRangeResume || primaryPartFile.length() == 0L)) {
                    primaryPartFile.delete()
                }
            }
            val latest = dao.getTaskById(taskId) ?: initialTask
            val readableError = formatDownloadExceptionMessage(e)
            Log.w(TAG, "Download failed for source=$sanitizedUrl (${e::class.java.simpleName}): $readableError")
            dao.upsertTask(
                latest.copy(
                    status = DownloadStatus.FAILED.name,
                    statusDetail = "Download failed",
                    errorMessage = readableError,
                    speedBytesPerSec = 0L,
                    etaSeconds = -1L
                )
            )
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
        knownTotalBytes: Long,
        baseByteOffset: Long
    ) {
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

            val overallTotal = if (knownTotalBytes > 0) knownTotalBytes else (baseByteOffset + totalFileLen)
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
                        val read = inputRaf.read(buffer)
                        if (read == -1) break
                        outputRaf.write(buffer, 0, read)
                        copied += read
                        bytesInWindow += read

                        // Slight yield so pause/cancel & progress bar updates are genuinely measurable
                        delay(18)

                        val now = System.currentTimeMillis()
                        val elapsedMs = (now - startWindowTime).coerceAtLeast(1L)
                        val speedBps = (bytesInWindow * 1000L) / elapsedMs
                        val remainingBytes = (overallTotal - (baseByteOffset + copied)).coerceAtLeast(0L)
                        val etaSec = if (speedBps > 0) remainingBytes / speedBps else 0L

                        dao.updateProgress(
                            id = taskId,
                            downloadedBytes = baseByteOffset + copied,
                            totalBytes = overallTotal,
                            speedBytesPerSec = speedBps,
                            etaSeconds = etaSec,
                            status = DownloadStatus.DOWNLOADING.name,
                            statusDetail = "Streaming bytes (${formatBytesStatic(baseByteOffset + copied)} / ${formatBytesStatic(overallTotal)})"
                        )

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
            return
        }

        // HTTP / HTTPS real network stream with Range header support
        val reqBuilder = Request.Builder()
            .url(sourceUrl)
            .header("User-Agent", "MediaSave-Android/1.0")
        if (existingBytes > 0L && supportsResume) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            // If Range is not satisfiable (HTTP 416), reset partial file and retry from byte 0
            if (response.code == 416 && existingBytes > 0L) {
                response.close()
                targetPartFile.delete()
                transferStreamToFile(
                    taskId = taskId,
                    sourceUrl = sourceUrl,
                    targetPartFile = targetPartFile,
                    supportsResume = false,
                    knownTotalBytes = knownTotalBytes,
                    baseByteOffset = baseByteOffset
                )
                return
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
            if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
                targetPartFile.delete()
                throw IllegalStateException(
                    "Server returned an HTML webpage ($contentType) instead of a downloadable media file."
                )
            }

            val isPartialContent = response.code == 206
            val startOffset = if (isPartialContent) existingBytes else 0L
            val body = response.body ?: throw IOException("Server returned an empty HTTP response body.")
            val contentLen = body.contentLength()
            if (contentLen > 0L) {
                checkAvailableStorageSpace(contentLen)
            }

            val overallTotal = when {
                contentLen > 0 -> baseByteOffset + startOffset + contentLen
                knownTotalBytes > 0 -> knownTotalBytes
                else -> -1L
            }

            RandomAccessFile(targetPartFile, "rw").use { outputRaf ->
                if (!isPartialContent) {
                    outputRaf.setLength(0L)
                }
                outputRaf.seek(startOffset)

                body.byteStream().use { inputStream ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = startOffset
                    var sessionBytesRead = 0L
                    var inspectedFirstChunk = startOffset > 0L
                    var windowStart = System.currentTimeMillis()
                    var windowBytes = 0L
                    var lastDbUpdate = 0L

                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break

                        if (!inspectedFirstChunk && read > 0) {
                            inspectedFirstChunk = true
                            val headSample = String(buffer, 0, minOf(read, 256), Charsets.UTF_8).trimStart().lowercase()
                            if (headSample.startsWith("<!doctype html") || headSample.startsWith("<html")) {
                                outputRaf.close()
                                targetPartFile.delete()
                                throw IllegalStateException(
                                    "Downloaded response is an HTML webpage rather than a valid media container."
                                )
                            }
                        }

                        outputRaf.write(buffer, 0, read)
                        downloaded += read
                        sessionBytesRead += read
                        windowBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastDbUpdate >= 100L) {
                            val elapsedMs = (now - windowStart).coerceAtLeast(1L)
                            val speedBps = (windowBytes * 1000L) / elapsedMs
                            val currentOverall = baseByteOffset + downloaded
                            val rem = if (overallTotal > 0) (overallTotal - currentOverall).coerceAtLeast(0L) else 0L
                            val etaSec = if (speedBps > 0 && overallTotal > 0) rem / speedBps else -1L

                            dao.updateProgress(
                                id = taskId,
                                downloadedBytes = currentOverall,
                                totalBytes = overallTotal.coerceAtLeast(currentOverall),
                                speedBytesPerSec = speedBps,
                                etaSeconds = etaSec,
                                status = DownloadStatus.DOWNLOADING.name,
                                statusDetail = if (isPartialContent) {
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

                    // Verify complete payload was received when Content-Length was advertised
                    if (contentLen > 0L && sessionBytesRead < contentLen) {
                        throw EOFException(
                            "Connection interrupted before completion: received ${formatBytesStatic(downloaded)} of ${formatBytesStatic(startOffset + contentLen)}."
                        )
                    }
                    if (downloaded <= 0L) {
                        throw EOFException("Remote server closed connection after sending 0 bytes.")
                    }
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
