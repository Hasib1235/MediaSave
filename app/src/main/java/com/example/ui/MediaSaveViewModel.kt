package com.example.ui

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.download.DeviceStorageSummary
import com.example.data.download.DownloadEngine
import com.example.data.download.EnqueueResult
import com.example.data.local.AppDatabase
import com.example.data.media.AuthorizedSampleItem
import com.example.data.media.LocalSampleMediaGenerator
import com.example.data.platform.PlatformAdapterRegistry
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.preferences.UserSettings
import com.example.domain.model.AppThemeMode
import com.example.domain.model.AudioOutputChoice
import com.example.domain.model.AudioStreamOption
import com.example.domain.model.DownloadTaskEntity
import com.example.domain.model.DownloaderMode
import com.example.domain.model.LibrarySortOption
import com.example.domain.model.MediaAnalysisState
import com.example.domain.model.VideoStreamOption
import com.example.domain.validation.ClipboardParseResult
import com.example.domain.validation.SocialPlatformLaunchResult
import com.example.domain.validation.UrlValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AudioPlayerState(
    val activeTrack: DownloadTaskEntity? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Int = 0,
    val durationMs: Int = 0,
    val isShuffleEnabled: Boolean = false,
    val isRepeatEnabled: Boolean = false,
    val favoriteTrackIds: Set<String> = emptySet()
) {
    val isActiveTrackFavorite: Boolean
        get() = activeTrack?.id != null && favoriteTrackIds.contains(activeTrack.id)
}

data class DuplicateDownloadPrompt(
    val existingTask: DownloadTaskEntity,
    val onConfirmProceed: () -> Unit
)

class MediaSaveViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val dao = database.downloadTaskDao()
    private val preferencesRepository = UserPreferencesRepository(application)
    private val adapterRegistry = PlatformAdapterRegistry()
    val downloadEngine = DownloadEngine.getInstance(application, dao)

    val userSettings: StateFlow<UserSettings> = preferencesRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    private val _selectedMode = MutableStateFlow(DownloaderMode.VIDEO)
    val selectedMode: StateFlow<DownloaderMode> = _selectedMode.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _analysisState = MutableStateFlow<MediaAnalysisState>(MediaAnalysisState.Idle)
    val analysisState: StateFlow<MediaAnalysisState> = _analysisState.asStateFlow()

    private val _authorizedSamples = MutableStateFlow<List<AuthorizedSampleItem>>(emptyList())
    val authorizedSamples: StateFlow<List<AuthorizedSampleItem>> = _authorizedSamples.asStateFlow()

    private val _selectedVideoStream = MutableStateFlow<VideoStreamOption?>(null)
    val selectedVideoStream: StateFlow<VideoStreamOption?> = _selectedVideoStream.asStateFlow()

    private val _selectedAudioStream = MutableStateFlow<AudioStreamOption?>(null)
    val selectedAudioStream: StateFlow<AudioStreamOption?> = _selectedAudioStream.asStateFlow()

    private val _audioOutputChoices = MutableStateFlow<List<AudioOutputChoice>>(emptyList())
    val audioOutputChoices: StateFlow<List<AudioOutputChoice>> = _audioOutputChoices.asStateFlow()

    private val _selectedAudioOutput = MutableStateFlow<AudioOutputChoice?>(null)
    val selectedAudioOutput: StateFlow<AudioOutputChoice?> = _selectedAudioOutput.asStateFlow()

    // Full-screen options view state (Screen 4: Video Download Options / Screen 5: Music Download Options)
    private val _showStreamSelectionSheet = MutableStateFlow(false)
    val showStreamSelectionSheet: StateFlow<Boolean> = _showStreamSelectionSheet.asStateFlow()

    // Full-screen Active Downloading view state (Screen 6: Downloading Progress)
    private val _showActiveDownloadingScreen = MutableStateFlow(false)
    val showActiveDownloadingScreen: StateFlow<Boolean> = _showActiveDownloadingScreen.asStateFlow()

    // Direct Android Share Intent flow state (bypasses Splash & Home screen directly to Download Options)
    private val _isShareIntentFlow = MutableStateFlow(false)
    val isShareIntentFlow: StateFlow<Boolean> = _isShareIntentFlow.asStateFlow()

    private val _duplicatePrompt = MutableStateFlow<DuplicateDownloadPrompt?>(null)
    val duplicatePrompt: StateFlow<DuplicateDownloadPrompt?> = _duplicatePrompt.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Library sort & search
    private val _videoSortOption = MutableStateFlow(LibrarySortOption.DATE_DESC)
    val videoSortOption: StateFlow<LibrarySortOption> = _videoSortOption.asStateFlow()

    private val _musicSortOption = MutableStateFlow(LibrarySortOption.DATE_DESC)
    val musicSortOption: StateFlow<LibrarySortOption> = _musicSortOption.asStateFlow()

    private val _videoSearchQuery = MutableStateFlow("")
    val videoSearchQuery: StateFlow<String> = _videoSearchQuery.asStateFlow()

    private val _musicSearchQuery = MutableStateFlow("")
    val musicSearchQuery: StateFlow<String> = _musicSearchQuery.asStateFlow()

    val allTasks: StateFlow<List<DownloadTaskEntity>> = downloadEngine.allTasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val storageSummary: StateFlow<DeviceStorageSummary> = allTasks
        .map { withContext(Dispatchers.IO) { downloadEngine.computeStorageSummary() } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            DeviceStorageSummary()
        )

    val sortedVideos: StateFlow<List<DownloadTaskEntity>> = combine(
        downloadEngine.completedVideosFlow,
        _videoSortOption,
        _videoSearchQuery
    ) { list, sort, query ->
        val filtered = if (query.isBlank()) list else list.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.fileName.contains(query, ignoreCase = true) ||
                it.resolutionLabel.orEmpty().contains(query, ignoreCase = true)
        }
        when (sort) {
            LibrarySortOption.DATE_DESC -> filtered.sortedByDescending { it.completedAt ?: it.createdAt }
            LibrarySortOption.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            LibrarySortOption.SIZE_DESC -> filtered.sortedByDescending { it.totalBytes }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sortedMusic: StateFlow<List<DownloadTaskEntity>> = combine(
        downloadEngine.completedMusicFlow,
        _musicSortOption,
        _musicSearchQuery
    ) { list, sort, query ->
        val filtered = if (query.isBlank()) list else list.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.fileName.contains(query, ignoreCase = true) ||
                it.containerFormat.contains(query, ignoreCase = true)
        }
        when (sort) {
            LibrarySortOption.DATE_DESC -> filtered.sortedByDescending { it.completedAt ?: it.createdAt }
            LibrarySortOption.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            LibrarySortOption.SIZE_DESC -> filtered.sortedByDescending { it.totalBytes }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Built-in Full-Screen Video Player (Screen 10)
    private val _activeVideoPlayback = MutableStateFlow<DownloadTaskEntity?>(null)
    val activeVideoPlayback: StateFlow<DownloadTaskEntity?> = _activeVideoPlayback.asStateFlow()

    // Built-in Full-Screen Music Player (Screen 11) + Mini Bar
    private val _showFullMusicPlayer = MutableStateFlow(false)
    val showFullMusicPlayer: StateFlow<Boolean> = _showFullMusicPlayer.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressTrackerJob: Job? = null
    private var analysisJob: Job? = null
    private val _audioPlayerState = MutableStateFlow(AudioPlayerState())
    val audioPlayerState: StateFlow<AudioPlayerState> = _audioPlayerState.asStateFlow()

    init {
        viewModelScope.launch {
            val samples = withContext(Dispatchers.IO) {
                LocalSampleMediaGenerator.ensureAuthorizedSamplesReady(getApplication())
            }
            _authorizedSamples.value = samples
        }
        viewModelScope.launch {
            preferencesRepository.settingsFlow.collect { s ->
                downloadEngine.updateMaxConcurrentDownloads(s.maxConcurrentDownloads)
                downloadEngine.updateStorageAndNetworkSettings(
                    customTreeUri = s.customDownloadTreeUri,
                    notificationsEnabled = s.notificationsEnabled,
                    wifiOnly = s.wifiOnlyDownloads
                )
            }
        }
    }

    fun selectMode(mode: DownloaderMode) {
        _selectedMode.value = mode
    }

    fun updateUrlInput(newUrl: String) {
        _urlInput.value = newUrl
        if (_analysisState.value is MediaAnalysisState.Error || _analysisState.value is MediaAnalysisState.PlatformRestricted) {
            _analysisState.value = MediaAnalysisState.Idle
        }
    }

    fun clearUrlInput() {
        analysisJob?.cancel()
        _urlInput.value = ""
        _analysisState.value = MediaAnalysisState.Idle
        _showStreamSelectionSheet.value = false
    }

    fun showMessage(message: String) {
        _snackbarMessage.value = message
    }

    /**
     * Reads text from Android's system ClipboardManager (or Compose clipboard fallback),
     * inserts it into the URL field, and validates it immediately.
     * Shows a clear message if the clipboard is empty without substituting a sample link.
     */
    fun pasteFromClipboard(composeClipboardText: String? = null) {
        val systemText = UrlValidator.readClipboardTextFromSystem(getApplication())
        val rawClipboard = systemText?.takeIf { it.isNotBlank() } ?: composeClipboardText

        when (val parsed = UrlValidator.parseClipboardContent(rawClipboard)) {
            is ClipboardParseResult.Empty -> {
                _analysisState.value = MediaAnalysisState.Error(
                    url = _urlInput.value,
                    errorTitle = "Clipboard is Empty",
                    userMessage = "Clipboard is empty. Copy a video or music link first, then tap Paste Link."
                )
                _snackbarMessage.value = "Clipboard is empty. Copy a video or music link first."
            }

            is ClipboardParseResult.InvalidText -> {
                _urlInput.value = parsed.pastedText
                _analysisState.value = MediaAnalysisState.Error(
                    url = parsed.pastedText,
                    errorTitle = parsed.validation.errorTitle,
                    userMessage = parsed.validation.errorMessage
                )
                _snackbarMessage.value = "${parsed.validation.errorTitle}: ${parsed.validation.errorMessage}"
            }

            is ClipboardParseResult.ValidUrl -> {
                _urlInput.value = parsed.pastedText
                _analysisState.value = MediaAnalysisState.Idle
                _snackbarMessage.value = "Pasted ${parsed.validation.platform.displayName} link from clipboard"
            }
        }
    }

    fun loadSampleAndAnalyze(sample: AuthorizedSampleItem) {
        _selectedMode.value = if (sample.isVideo) DownloaderMode.VIDEO else DownloaderMode.MUSIC
        _urlInput.value = sample.uri
        analyzeCurrentUrl()
    }

    /**
     * Opens the official installed social app or external web browser for YouTube, Facebook,
     * Instagram, or TikTok (https://www.youtube.com/, https://www.facebook.com/,
     * https://www.instagram.com/, https://www.tiktok.com/).
     * Never modifies urlInput, never routes to the test-media screen, and never triggers a download.
     */
    fun openSocialPlatform(platformName: String): SocialPlatformLaunchResult {
        val result = UrlValidator.launchSocialPlatform(getApplication(), platformName)
        when (result) {
            is SocialPlatformLaunchResult.LaunchedOfficialApp -> {
                _snackbarMessage.value = "Opening ${result.shortcut.displayName} app..."
            }
            is SocialPlatformLaunchResult.LaunchedBrowser -> {
                _snackbarMessage.value = "Opening ${result.shortcut.displayName} (${result.targetUrl})..."
            }
            is SocialPlatformLaunchResult.Failed -> {
                _snackbarMessage.value = result.userMessage
            }
        }
        return result
    }

    /**
     * Handles an incoming Android Share Intent (ACTION_SEND or ACTION_VIEW) from YouTube, TikTok,
     * Instagram, Facebook, or browsers. Automatically extracts the shared URL, bypasses the Splash
     * and Home screens, and immediately analyzes the link to show Download Options directly.
     */
    fun handleIncomingShareIntent(intent: Intent?): Boolean {
        val extractedUrl = UrlValidator.extractSharedUrlFromIntent(intent) ?: return false
        if (extractedUrl.isBlank()) return false
        handleSharedUrlDirect(extractedUrl)
        return true
    }

    /**
     * Directly processes a shared URL or share caption without going through the Home screen.
     */
    fun handleSharedUrlDirect(rawSharedInput: String) {
        val cleanUrl = UrlValidator.extractFirstUrlFromText(rawSharedInput)?.trim().orEmpty()
        if (cleanUrl.isEmpty()) return
        _isShareIntentFlow.value = true
        _showActiveDownloadingScreen.value = false
        _urlInput.value = cleanUrl
        analyzeCurrentUrl(isFromShareIntent = true)
    }

    fun dismissShareIntentFlow() {
        _isShareIntentFlow.value = false
        _showStreamSelectionSheet.value = false
        if (_analysisState.value is MediaAnalysisState.Analyzing) {
            analysisJob?.cancel()
            _analysisState.value = MediaAnalysisState.Idle
        }
    }

    fun analyzeCurrentUrl(isFromShareIntent: Boolean = false) {
        val rawUrl = _urlInput.value.trim()
        analysisJob?.cancel()

        // Immediately validate without showing the Analyzing screen if the input is empty or malformed
        if (rawUrl.isEmpty()) {
            _analysisState.value = MediaAnalysisState.Error(
                url = rawUrl,
                errorTitle = "Empty URL",
                userMessage = "Please paste or enter a direct media link (MP4, WebM, M4A, MP3, WAV) or select an authorized test stream."
            )
            return
        }

        if (isFromShareIntent) {
            _isShareIntentFlow.value = true
        }

        val platformLabel = when (val v = UrlValidator.validateAndClassify(rawUrl)) {
            is com.example.domain.validation.UrlValidationResult.Valid -> v.platform.displayName
            else -> "Shared Media"
        }

        // Set Analyzing state synchronously before launching coroutine so the first frame skips Home screen
        _analysisState.value = MediaAnalysisState.Analyzing(
            url = rawUrl,
            stageMessage = if (isFromShareIntent) {
                "Shared from $platformLabel • Extracting direct download options..."
            } else {
                "Inspecting media container, tracks, and codecs..."
            }
        )

        analysisJob = viewModelScope.launch {
            if (!isFromShareIntent) {
                delay(400)
            }
            val result = withContext(Dispatchers.IO) {
                adapterRegistry.validateAndAnalyze(rawUrl)
            }
            _analysisState.value = result

            if (result is MediaAnalysisState.Success) {
                val meta = result.metadata
                // Select highest genuinely detected resolution by default (e.g. 1080p Full HD if available)
                val defaultVideo = meta.videoStreams.maxByOrNull { it.resolutionHeight }
                _selectedVideoStream.value = defaultVideo

                val defaultAudio = meta.audioStreams.firstOrNull()
                selectAudioSourceStream(defaultAudio)

                if (meta.videoStreams.isEmpty() && meta.audioStreams.isNotEmpty()) {
                    _selectedMode.value = DownloaderMode.MUSIC
                } else if (isFromShareIntent && meta.videoStreams.isNotEmpty()) {
                    _selectedMode.value = DownloaderMode.VIDEO
                }
                _showStreamSelectionSheet.value = true
            }
        }
    }

    fun cancelAnalyzing() {
        analysisJob?.cancel()
        _isShareIntentFlow.value = false
        _analysisState.value = MediaAnalysisState.Idle
        _showStreamSelectionSheet.value = false
    }

    fun selectVideoStream(stream: VideoStreamOption) {
        _selectedVideoStream.value = stream
    }

    fun selectAudioSourceStream(stream: AudioStreamOption?) {
        _selectedAudioStream.value = stream
        if (stream != null) {
            val choices = UrlValidator.buildSupportedAudioOutputChoices(stream)
            _audioOutputChoices.value = choices
            _selectedAudioOutput.value = choices.firstOrNull()
        } else {
            _audioOutputChoices.value = emptyList()
            _selectedAudioOutput.value = null
        }
    }

    fun selectAudioOutputChoice(choice: AudioOutputChoice) {
        _selectedAudioOutput.value = choice
    }

    fun dismissStreamSelectionSheet() {
        _showStreamSelectionSheet.value = false
        _isShareIntentFlow.value = false
    }

    fun openStreamSelectionSheet() {
        if (_analysisState.value is MediaAnalysisState.Success) {
            _showStreamSelectionSheet.value = true
        }
    }

    fun openActiveDownloadingScreen() {
        _showActiveDownloadingScreen.value = true
    }

    fun closeActiveDownloadingScreen() {
        _showActiveDownloadingScreen.value = false
    }

    fun startSelectedDownload(
        forceAllowDuplicate: Boolean = false,
        onNavigateToQueue: () -> Unit = {}
    ) {
        val currentAnalysis = _analysisState.value as? MediaAnalysisState.Success ?: return
        val metadata = currentAnalysis.metadata
        val mode = _selectedMode.value
        val allowDup = forceAllowDuplicate || !userSettings.value.warnOnDuplicateDownload

        viewModelScope.launch {
            if (mode == DownloaderMode.VIDEO && metadata.videoStreams.isNotEmpty()) {
                val stream = _selectedVideoStream.value
                    ?: metadata.videoStreams.maxByOrNull { it.resolutionHeight }
                    ?: metadata.videoStreams.first()
                val res = downloadEngine.enqueueVideoDownload(
                    metadata = metadata,
                    stream = stream,
                    allowDuplicate = allowDup
                )
                handleEnqueueResult(res, onNavigateToQueue = onNavigateToQueue) {
                    startSelectedDownload(forceAllowDuplicate = true, onNavigateToQueue = onNavigateToQueue)
                }
            } else if (metadata.audioStreams.isNotEmpty()) {
                val audioStream = _selectedAudioStream.value ?: metadata.audioStreams.first()
                val outputChoice = _selectedAudioOutput.value
                    ?: _audioOutputChoices.value.firstOrNull()
                    ?: UrlValidator.buildSupportedAudioOutputChoices(audioStream).firstOrNull()
                    ?: return@launch
                val res = downloadEngine.enqueueAudioDownload(
                    metadata = metadata,
                    sourceStream = audioStream,
                    outputChoice = outputChoice,
                    allowDuplicate = allowDup
                )
                handleEnqueueResult(res, onNavigateToQueue = onNavigateToQueue) {
                    startSelectedDownload(forceAllowDuplicate = true, onNavigateToQueue = onNavigateToQueue)
                }
            } else {
                _snackbarMessage.value = "No downloadable media stream is available for this selection."
            }
        }
    }

    private fun handleEnqueueResult(
        result: EnqueueResult,
        onNavigateToQueue: () -> Unit,
        onConfirmProceed: () -> Unit
    ) {
        when (result) {
            is EnqueueResult.Enqueued -> {
                _isShareIntentFlow.value = false
                _showStreamSelectionSheet.value = false
                _duplicatePrompt.value = null
                _showActiveDownloadingScreen.value = true
                onNavigateToQueue()
                _snackbarMessage.value = "Downloading '${result.task.title}'..."
            }
            is EnqueueResult.DuplicateDetected -> {
                _duplicatePrompt.value = DuplicateDownloadPrompt(
                    existingTask = result.existingTask,
                    onConfirmProceed = {
                        _duplicatePrompt.value = null
                        onConfirmProceed()
                    }
                )
            }
            is EnqueueResult.Rejected -> {
                _showStreamSelectionSheet.value = false
                _analysisState.value = MediaAnalysisState.Error(
                    url = _urlInput.value,
                    errorTitle = result.reasonTitle,
                    userMessage = result.reasonMessage
                )
                _snackbarMessage.value = "${result.reasonTitle}: ${result.reasonMessage}"
            }
        }
    }

    fun dismissDuplicatePrompt() {
        _duplicatePrompt.value = null
    }

    fun clearSnackbarMessage() {
        _snackbarMessage.value = null
    }

    // Queue actions
    fun pauseTask(taskId: String) = downloadEngine.pauseDownload(taskId)
    fun resumeTask(taskId: String) = downloadEngine.resumeDownload(taskId)
    fun retryTask(taskId: String) = downloadEngine.retryDownload(taskId)
    fun cancelTask(taskId: String) = downloadEngine.cancelDownload(taskId)

    fun deleteTask(taskId: String) {
        if (_audioPlayerState.value.activeTrack?.id == taskId) {
            stopAudioPlayback()
        }
        if (_activeVideoPlayback.value?.id == taskId) {
            _activeVideoPlayback.value = null
        }
        viewModelScope.launch {
            downloadEngine.deleteTaskAndFile(taskId)
            _snackbarMessage.value = "Deleted media file"
        }
    }

    fun renameTask(taskId: String, newTitle: String) {
        viewModelScope.launch {
            val ok = downloadEngine.renameMediaFile(taskId, newTitle)
            if (ok && _audioPlayerState.value.activeTrack?.id == taskId) {
                val updated = withContext(Dispatchers.IO) { dao.getTaskById(taskId) }
                if (updated != null) {
                    _audioPlayerState.value = _audioPlayerState.value.copy(activeTrack = updated)
                }
            }
            _snackbarMessage.value = if (ok) "Renamed to '$newTitle'" else "Could not rename file"
        }
    }

    fun clearFinishedHistory() {
        viewModelScope.launch {
            downloadEngine.clearFinishedHistory()
            _snackbarMessage.value = "Cleared download history"
        }
    }

    // Library Sort & Search
    fun setVideoSortOption(option: LibrarySortOption) {
        _videoSortOption.value = option
    }

    fun setMusicSortOption(option: LibrarySortOption) {
        _musicSortOption.value = option
    }

    fun setVideoSearchQuery(query: String) {
        _videoSearchQuery.value = query
    }

    fun setMusicSearchQuery(query: String) {
        _musicSearchQuery.value = query
    }

    // Video Player (Screen 10)
    fun openVideoPlayer(task: DownloadTaskEntity) {
        val file = File(task.outputFilePath)
        if (!file.exists()) {
            _snackbarMessage.value = "Video file is not on disk (${task.statusEnum.label})"
            return
        }
        pauseAudioPlayback()
        _activeVideoPlayback.value = task
    }

    fun closeVideoPlayer() {
        _activeVideoPlayback.value = null
    }

    // Music Player (Screen 11)
    fun openFullMusicPlayer(task: DownloadTaskEntity) {
        playAudioTrack(task)
    }

    fun closeFullMusicPlayer() {
        _showFullMusicPlayer.value = false
    }

    fun playAudioTrack(task: DownloadTaskEntity) {
        val file = File(task.outputFilePath)
        if (!file.exists()) {
            _snackbarMessage.value = "Audio file not found on disk (${task.statusEnum.label})"
            return
        }
        if (_audioPlayerState.value.activeTrack?.id == task.id && mediaPlayer != null) {
            _showFullMusicPlayer.value = true
            return
        }

        val prevShuffle = _audioPlayerState.value.isShuffleEnabled
        val prevRepeat = _audioPlayerState.value.isRepeatEnabled
        val prevFavorites = _audioPlayerState.value.favoriteTrackIds
        stopAudioPlayback()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                isLooping = prevRepeat
                prepare()
                start()
                setOnCompletionListener {
                    if (_audioPlayerState.value.isRepeatEnabled) {
                        seekTo(0)
                        start()
                    } else if (_audioPlayerState.value.isShuffleEnabled && sortedMusic.value.size > 1) {
                        skipAudioTrack(1)
                    } else {
                        _audioPlayerState.value = _audioPlayerState.value.copy(
                            isPlaying = false,
                            currentPositionMs = duration
                        )
                    }
                }
            }
            mediaPlayer = mp
            _audioPlayerState.value = AudioPlayerState(
                activeTrack = task,
                isPlaying = true,
                currentPositionMs = 0,
                durationMs = mp.duration.coerceAtLeast(task.durationMs.toInt()).coerceAtLeast(1000),
                isShuffleEnabled = prevShuffle,
                isRepeatEnabled = prevRepeat,
                favoriteTrackIds = prevFavorites
            )
            startAudioProgressTracker()
            _showFullMusicPlayer.value = true
        } catch (e: Exception) {
            _snackbarMessage.value = "Playback error (${task.containerFormat}): ${e.localizedMessage ?: "Unsupported codec"}"
        }
    }

    fun toggleAudioPlayPause() {
        val mp = mediaPlayer ?: return
        try {
            if (mp.isPlaying) {
                mp.pause()
                _audioPlayerState.value = _audioPlayerState.value.copy(isPlaying = false)
            } else {
                mp.start()
                _audioPlayerState.value = _audioPlayerState.value.copy(isPlaying = true)
                startAudioProgressTracker()
            }
        } catch (_: Exception) {
        }
    }

    fun toggleAudioShuffle() {
        val next = !_audioPlayerState.value.isShuffleEnabled
        _audioPlayerState.value = _audioPlayerState.value.copy(isShuffleEnabled = next)
        _snackbarMessage.value = if (next) "Shuffle playback enabled" else "Shuffle playback disabled"
    }

    fun toggleAudioRepeat() {
        val next = !_audioPlayerState.value.isRepeatEnabled
        runCatching { mediaPlayer?.isLooping = next }
        _audioPlayerState.value = _audioPlayerState.value.copy(isRepeatEnabled = next)
        _snackbarMessage.value = if (next) "Repeat track enabled" else "Repeat track disabled"
    }

    fun toggleFavoriteTrack(taskId: String) {
        val currentFavs = _audioPlayerState.value.favoriteTrackIds.toMutableSet()
        val added = if (currentFavs.contains(taskId)) {
            currentFavs.remove(taskId)
            false
        } else {
            currentFavs.add(taskId)
            true
        }
        _audioPlayerState.value = _audioPlayerState.value.copy(favoriteTrackIds = currentFavs)
        _snackbarMessage.value = if (added) "Added to Favorites playlist" else "Removed from Favorites playlist"
    }

    fun seekAudioTo(positionMs: Int) {
        val cur = _audioPlayerState.value
        _audioPlayerState.value = cur.copy(currentPositionMs = positionMs.coerceIn(0, cur.durationMs.coerceAtLeast(1)))
        val mp = mediaPlayer ?: return
        runCatching {
            mp.seekTo(positionMs.coerceIn(0, mp.duration))
        }
    }

    fun skipAudioTrack(direction: Int) {
        val list = sortedMusic.value
        val current = _audioPlayerState.value.activeTrack ?: return
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == current.id }
        if (idx == -1) return
        val nextIdx = if (_audioPlayerState.value.isShuffleEnabled && list.size > 1) {
            list.indices.filter { it != idx }.random()
        } else {
            (idx + direction + list.size) % list.size
        }
        playAudioTrack(list[nextIdx])
    }

    fun stopAudioPlayback() {
        progressTrackerJob?.cancel()
        val prevShuffle = _audioPlayerState.value.isShuffleEnabled
        val prevRepeat = _audioPlayerState.value.isRepeatEnabled
        val prevFavorites = _audioPlayerState.value.favoriteTrackIds
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        _showFullMusicPlayer.value = false
        _audioPlayerState.value = AudioPlayerState(
            isShuffleEnabled = prevShuffle,
            isRepeatEnabled = prevRepeat,
            favoriteTrackIds = prevFavorites
        )
    }

    private fun pauseAudioPlayback() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            _audioPlayerState.value = _audioPlayerState.value.copy(isPlaying = false)
        }
    }

    private fun startAudioProgressTracker() {
        progressTrackerJob?.cancel()
        progressTrackerJob = viewModelScope.launch {
            while (true) {
                val mp = mediaPlayer ?: break
                val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                val curDur = _audioPlayerState.value.durationMs.coerceAtLeast(1)
                _audioPlayerState.value = _audioPlayerState.value.copy(
                    isPlaying = playing,
                    currentPositionMs = pos,
                    durationMs = curDur
                )
                if (!playing) break
                delay(200)
            }
        }
    }

    // External Share & Open File via FileProvider
    fun shareDownloadedFile(task: DownloadTaskEntity) {
        val app = getApplication<Application>()
        val file = File(task.outputFilePath)
        if (!file.exists()) {
            _snackbarMessage.value = "File not found on disk"
            return
        }
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = task.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, task.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share ${task.title}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(app, "Unable to share file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareAppInfo() {
        val app = getApplication<Application>()
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "MediaSave — Video & Music Downloader")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "MediaSave — All-in-One Video & Music Downloader for Android with real stream inspection, multi-resolution MP4/WebM support, and on-device audio extraction."
                )
            }
            val chooser = Intent.createChooser(shareIntent, "Share MediaSave").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(chooser)
        } catch (_: Exception) {
            _snackbarMessage.value = "MediaSave v1.0 — Ready to share"
        }
    }

    fun openDownloadedFileExternally(task: DownloadTaskEntity) {
        val app = getApplication<Application>()
        val file = File(task.outputFilePath)
        if (!file.exists()) {
            _snackbarMessage.value = "File not found on disk"
            return
        }
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, task.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(viewIntent)
        } catch (_: Exception) {
            _snackbarMessage.value = "Opening in built-in player..."
            if (task.mode == DownloaderMode.VIDEO.name) {
                openVideoPlayer(task)
            } else {
                openFullMusicPlayer(task)
            }
        }
    }

    // Settings updates
    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    }

    fun setMaxConcurrentDownloads(count: Int) {
        downloadEngine.updateMaxConcurrentDownloads(count)
        viewModelScope.launch { preferencesRepository.setMaxConcurrentDownloads(count) }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        downloadEngine.updateStorageAndNetworkSettings(wifiOnly = enabled)
        viewModelScope.launch { preferencesRepository.setWifiOnlyDownloads(enabled) }
    }

    fun setWarnOnDuplicateDownload(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setWarnOnDuplicateDownload(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        downloadEngine.updateStorageAndNetworkSettings(notificationsEnabled = enabled)
        viewModelScope.launch { preferencesRepository.setNotificationsEnabled(enabled) }
    }

    fun setCustomDownloadLocation(uri: android.net.Uri?) {
        if (uri == null) return
        val app = getApplication<Application>()
        runCatching {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            app.contentResolver.takePersistableUriPermission(uri, takeFlags)
        }
        val rawSegment = uri.lastPathSegment ?: uri.toString()
        val readablePath = rawSegment
            .replace("primary:", "/storage/emulated/0/")
            .let { if (it.startsWith("/")) it else "SAF: $it" }
        downloadEngine.updateStorageAndNetworkSettings(customTreeUri = uri.toString())
        _snackbarMessage.value = "Custom download location set to $readablePath"
        viewModelScope.launch {
            preferencesRepository.setCustomDownloadLocation(
                treeUri = uri.toString(),
                displayLabel = "$readablePath (Custom SAF Folder)"
            )
        }
    }

    fun resetCustomDownloadLocation() {
        downloadEngine.updateStorageAndNetworkSettings(customTreeUri = "")
        _snackbarMessage.value = "Download location reset to MediaStore (Movies/MediaSave & Music/MediaSave)"
        viewModelScope.launch {
            preferencesRepository.setCustomDownloadLocation(treeUri = "", displayLabel = "")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioPlayback()
    }
}
