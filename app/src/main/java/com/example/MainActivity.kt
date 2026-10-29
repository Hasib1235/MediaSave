package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.DownloaderMode
import com.example.domain.model.MediaAnalysisState
import com.example.ui.MediaSaveViewModel
import com.example.ui.components.AboutMediaSaveDialog
import com.example.ui.components.AnalyzingLinkScreen
import com.example.ui.components.DuplicateWarningDialog
import com.example.ui.components.FloatingMusicPlayerBar
import com.example.ui.components.FullScreenMusicPlayer
import com.example.ui.components.MediaSaveNavigationDrawerContent
import com.example.ui.components.MediaSaveSplashScreen
import com.example.ui.components.PrivacyPolicyDialog
import com.example.ui.components.ShareIntentErrorOverlay
import com.example.ui.components.StreamSelectionBottomSheet
import com.example.ui.components.VideoPlayerModal
import com.example.ui.screens.DownloadsQueueScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MusicLibraryScreen
import com.example.ui.screens.SettingsAndBuildScreen
import com.example.ui.screens.VideoLibraryScreen
import com.example.ui.theme.MediaSaveTheme
import com.example.ui.theme.MidnightSurface
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextSecondaryDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class MediaSaveDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME("home", "Home", Icons.Filled.Home, Icons.Outlined.Home, "nav_tab_home"),
    QUEUE("queue", "Downloads", Icons.Filled.Download, Icons.Outlined.Download, "nav_tab_queue"),
    MUSIC("music", "Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic, "nav_tab_music"),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_tab_settings")
}

class MainActivity : ComponentActivity() {

    private val viewModel: MediaSaveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingShareIntent(intent)

        setContent {
            val settings by viewModel.userSettings.collectAsStateWithLifecycle()

            MediaSaveTheme(themeMode = settings.themeMode) {
                MediaSaveAppRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    internal fun handleIncomingShareIntent(intent: Intent?) {
        if (viewModel.handleIncomingShareIntent(intent)) {
            // Clear action after consuming so configuration changes do not re-trigger the same intent
            intent?.action = null
        }
    }
}

@Composable
fun MediaSaveAppRoot(viewModel: MediaSaveViewModel) {
    var showSplash by rememberSaveable { mutableStateOf(true) }
    var currentDestination by rememberSaveable { mutableStateOf(MediaSaveDestination.HOME) }
    var showVideoLibraryOverlay by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showPrivacyPolicyDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(1400)
        showSplash = false
    }

    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val selectedMode by viewModel.selectedMode.collectAsStateWithLifecycle()
    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val analysisState by viewModel.analysisState.collectAsStateWithLifecycle()
    val authorizedSamples by viewModel.authorizedSamples.collectAsStateWithLifecycle()
    val allTasks by viewModel.allTasks.collectAsStateWithLifecycle()
    val storageSummary by viewModel.storageSummary.collectAsStateWithLifecycle()
    val sortedVideos by viewModel.sortedVideos.collectAsStateWithLifecycle()
    val videoSortOption by viewModel.videoSortOption.collectAsStateWithLifecycle()
    val videoSearchQuery by viewModel.videoSearchQuery.collectAsStateWithLifecycle()
    val sortedMusic by viewModel.sortedMusic.collectAsStateWithLifecycle()
    val musicSortOption by viewModel.musicSortOption.collectAsStateWithLifecycle()
    val musicSearchQuery by viewModel.musicSearchQuery.collectAsStateWithLifecycle()

    val selectedVideoStream by viewModel.selectedVideoStream.collectAsStateWithLifecycle()
    val selectedAudioStream by viewModel.selectedAudioStream.collectAsStateWithLifecycle()
    val audioOutputChoices by viewModel.audioOutputChoices.collectAsStateWithLifecycle()
    val selectedAudioOutput by viewModel.selectedAudioOutput.collectAsStateWithLifecycle()
    val showStreamSelectionSheet by viewModel.showStreamSelectionSheet.collectAsStateWithLifecycle()
    val showActiveDownloadingScreen by viewModel.showActiveDownloadingScreen.collectAsStateWithLifecycle()
    val isShareIntentFlow by viewModel.isShareIntentFlow.collectAsStateWithLifecycle()
    val duplicatePrompt by viewModel.duplicatePrompt.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val activeVideoPlayback by viewModel.activeVideoPlayback.collectAsStateWithLifecycle()
    val showFullMusicPlayer by viewModel.showFullMusicPlayer.collectAsStateWithLifecycle()
    val audioPlayerState by viewModel.audioPlayerState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbarMessage()
        }
    }

    // Never show Splash Screen when launched via Android Share Intent (Share -> MediaSave)
    if (showSplash && !isShareIntentFlow && !showStreamSelectionSheet && analysisState !is MediaAnalysisState.Analyzing) {
        MediaSaveSplashScreen(onSkipOrFinish = { showSplash = false })
        return
    }

    // BackHandler priority stack for full-screen overlays and secondary tabs
    BackHandler(
        enabled = activeVideoPlayback != null ||
            showFullMusicPlayer ||
            showStreamSelectionSheet ||
            isShareIntentFlow ||
            showActiveDownloadingScreen ||
            showVideoLibraryOverlay ||
            analysisState is MediaAnalysisState.Analyzing ||
            currentDestination != MediaSaveDestination.HOME
    ) {
        when {
            activeVideoPlayback != null -> viewModel.closeVideoPlayer()
            showFullMusicPlayer -> viewModel.closeFullMusicPlayer()
            showStreamSelectionSheet -> viewModel.dismissStreamSelectionSheet()
            isShareIntentFlow -> viewModel.dismissShareIntentFlow()
            showActiveDownloadingScreen -> viewModel.closeActiveDownloadingScreen()
            showVideoLibraryOverlay -> showVideoLibraryOverlay = false
            analysisState is MediaAnalysisState.Analyzing -> viewModel.cancelAnalyzing()
            else -> currentDestination = MediaSaveDestination.HOME
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MediaSaveNavigationDrawerContent(
                currentRoute = when {
                    showVideoLibraryOverlay -> "video_library"
                    showActiveDownloadingScreen -> "active_downloading"
                    else -> currentDestination.route
                },
                onSelectHome = {
                    coroutineScope.launch { drawerState.close() }
                    showVideoLibraryOverlay = false
                    viewModel.closeActiveDownloadingScreen()
                    currentDestination = MediaSaveDestination.HOME
                },
                onSelectDownloads = {
                    coroutineScope.launch { drawerState.close() }
                    showVideoLibraryOverlay = false
                    viewModel.openActiveDownloadingScreen()
                    currentDestination = MediaSaveDestination.QUEUE
                },
                onSelectVideoLibrary = {
                    coroutineScope.launch { drawerState.close() }
                    viewModel.closeActiveDownloadingScreen()
                    showVideoLibraryOverlay = true
                },
                onSelectMusicLibrary = {
                    coroutineScope.launch { drawerState.close() }
                    showVideoLibraryOverlay = false
                    viewModel.closeActiveDownloadingScreen()
                    currentDestination = MediaSaveDestination.MUSIC
                },
                onSelectSettings = {
                    coroutineScope.launch { drawerState.close() }
                    showVideoLibraryOverlay = false
                    viewModel.closeActiveDownloadingScreen()
                    currentDestination = MediaSaveDestination.SETTINGS
                },
                onCloseDrawer = {
                    coroutineScope.launch { drawerState.close() }
                },
                onShareApp = {
                    coroutineScope.launch { drawerState.close() }
                    viewModel.shareAppInfo()
                },
                onRateUs = {
                    coroutineScope.launch { drawerState.close() }
                    viewModel.showMessage("Thank you for rating MediaSave 5 Stars!")
                },
                onOpenPrivacyPolicy = {
                    coroutineScope.launch { drawerState.close() }
                    showPrivacyPolicyDialog = true
                },
                onOpenAbout = {
                    coroutineScope.launch { drawerState.close() }
                    showAboutDialog = true
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column {
                    if (audioPlayerState.activeTrack != null && !showFullMusicPlayer) {
                        FloatingMusicPlayerBar(
                            playerState = audioPlayerState,
                            onTogglePlayPause = viewModel::toggleAudioPlayPause,
                            onSeekTo = viewModel::seekAudioTo,
                            onSkipPrev = { viewModel.skipAudioTrack(-1) },
                            onSkipNext = { viewModel.skipAudioTrack(1) },
                            onClose = viewModel::stopAudioPlayback,
                            onExpand = { viewModel.openFullMusicPlayer(audioPlayerState.activeTrack!!) }
                        )
                    }

                    NavigationBar(
                        containerColor = MidnightSurface,
                        tonalElevation = 8.dp
                    ) {
                        MediaSaveDestination.entries.forEach { dest ->
                            val selected = currentDestination == dest && !showVideoLibraryOverlay
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    showVideoLibraryOverlay = false
                                    viewModel.closeActiveDownloadingScreen()
                                    currentDestination = dest
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NeonPurple,
                                    selectedTextColor = NeonPurple,
                                    indicatorColor = Color.Transparent,
                                    unselectedIconColor = TextSecondaryDark,
                                    unselectedTextColor = TextSecondaryDark
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showVideoLibraryOverlay) {
                        VideoLibraryScreen(
                            videos = sortedVideos,
                            sortOption = videoSortOption,
                            searchQuery = videoSearchQuery,
                            onSortChange = viewModel::setVideoSortOption,
                            onSearchChange = viewModel::setVideoSearchQuery,
                            onPlayVideo = viewModel::openVideoPlayer,
                            onRenameVideo = viewModel::renameTask,
                            onShareVideo = viewModel::shareDownloadedFile,
                            onOpenExternally = viewModel::openDownloadedFileExternally,
                            onDeleteVideo = viewModel::deleteTask
                        )
                    } else {
                        when (currentDestination) {
                            MediaSaveDestination.HOME -> HomeScreen(
                                selectedMode = selectedMode,
                                urlInput = urlInput,
                                analysisState = analysisState,
                                authorizedSamples = authorizedSamples,
                                allTasks = allTasks,
                                onSelectMode = viewModel::selectMode,
                                onUrlChange = viewModel::updateUrlInput,
                                onClearUrl = viewModel::clearUrlInput,
                                onPasteClick = viewModel::pasteFromClipboard,
                                onAnalyzeClick = viewModel::analyzeCurrentUrl,
                                onOpenSelectionSheet = viewModel::openStreamSelectionSheet,
                                onSelectSample = viewModel::loadSampleAndAnalyze,
                                onPlatformIconClick = { platformName ->
                                    viewModel.openSocialPlatform(platformName)
                                },
                                onPauseTask = viewModel::pauseTask,
                                onResumeTask = viewModel::resumeTask,
                                onOpenTaskInPlayer = { task ->
                                    if (task.mode == DownloaderMode.VIDEO.name) {
                                        viewModel.openVideoPlayer(task)
                                    } else {
                                        viewModel.openFullMusicPlayer(task)
                                    }
                                },
                                onNavigateToQueue = {
                                    viewModel.closeActiveDownloadingScreen()
                                    currentDestination = MediaSaveDestination.QUEUE
                                },
                                onNavigateToSettings = {
                                    viewModel.closeActiveDownloadingScreen()
                                    currentDestination = MediaSaveDestination.SETTINGS
                                },
                                onOpenDrawer = {
                                    coroutineScope.launch { drawerState.open() }
                                },
                                onDeleteTask = viewModel::deleteTask,
                                onShareTask = viewModel::shareDownloadedFile
                            )

                            MediaSaveDestination.QUEUE -> DownloadsQueueScreen(
                                allTasks = allTasks,
                                onPauseTask = viewModel::pauseTask,
                                onResumeTask = viewModel::resumeTask,
                                onRetryTask = viewModel::retryTask,
                                onCancelTask = viewModel::cancelTask,
                                onDeleteTask = viewModel::deleteTask,
                                onPlayTask = { task ->
                                    if (task.mode == DownloaderMode.VIDEO.name) {
                                        viewModel.openVideoPlayer(task)
                                    } else {
                                        viewModel.openFullMusicPlayer(task)
                                    }
                                },
                                onShareTask = viewModel::shareDownloadedFile,
                                onClearHistory = viewModel::clearFinishedHistory,
                                initialShowActiveScreen = showActiveDownloadingScreen,
                                onRenameTask = viewModel::renameTask
                            )

                            MediaSaveDestination.MUSIC -> MusicLibraryScreen(
                                tracks = sortedMusic,
                                audioPlayerState = audioPlayerState,
                                sortOption = musicSortOption,
                                searchQuery = musicSearchQuery,
                                onSortChange = viewModel::setMusicSortOption,
                                onSearchChange = viewModel::setMusicSearchQuery,
                                onPlayTrack = viewModel::openFullMusicPlayer,
                                onRenameTrack = viewModel::renameTask,
                                onShareTrack = viewModel::shareDownloadedFile,
                                onOpenExternally = viewModel::openDownloadedFileExternally,
                                onDeleteTrack = viewModel::deleteTask,
                                onBack = { currentDestination = MediaSaveDestination.HOME }
                            )

                            MediaSaveDestination.SETTINGS -> SettingsAndBuildScreen(
                                settings = settings,
                                onThemeModeChange = viewModel::setThemeMode,
                                onMaxConcurrentChange = viewModel::setMaxConcurrentDownloads,
                                onWifiOnlyChange = viewModel::setWifiOnlyDownloads,
                                onWarnDuplicateChange = viewModel::setWarnOnDuplicateDownload,
                                onNotificationsChange = viewModel::setNotificationsEnabled,
                                onSelectCustomDownloadFolder = viewModel::setCustomDownloadLocation,
                                onResetDownloadFolder = viewModel::resetCustomDownloadLocation,
                                storageSummary = storageSummary,
                                onClearCompletedHistory = viewModel::clearFinishedHistory,
                                onOpenPrivacyPolicy = { showPrivacyPolicyDialog = true },
                                onOpenAbout = { showAboutDialog = true },
                                onShowMessage = viewModel::showMessage
                            )
                        }
                    }
                }
            }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPromptedNotificationPermission by rememberSaveable { mutableStateOf(false) }
    val runtimeNotificationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Handle denial gracefully: download proceeds normally with in-app progress
            viewModel.setNotificationsEnabled(false)
        }
    }

    // Screen 3: Full-Screen "Analyzing Link" Overlay
    val analyzingState = analysisState as? MediaAnalysisState.Analyzing
    if (analyzingState != null) {
        AnalyzingLinkScreen(
            stageMessage = analyzingState.stageMessage,
            onBack = viewModel::cancelAnalyzing,
            sharedUrl = analyzingState.url,
            isFromShareIntent = isShareIntentFlow
        )
    }

    // Screen 4 (Video Download Options) & Screen 5 (Music Download Options) Full-Screen Overlay
    val currentSuccess = analysisState as? MediaAnalysisState.Success
    if ((showStreamSelectionSheet || isShareIntentFlow) && currentSuccess != null) {
        StreamSelectionBottomSheet(
            metadata = currentSuccess.metadata,
            selectedMode = selectedMode,
            selectedVideoStream = selectedVideoStream,
            selectedAudioStream = selectedAudioStream,
            audioOutputChoices = audioOutputChoices,
            selectedAudioOutput = selectedAudioOutput,
            onSelectMode = viewModel::selectMode,
            onSelectVideoStream = viewModel::selectVideoStream,
            onSelectAudioStream = viewModel::selectAudioSourceStream,
            onSelectAudioOutput = viewModel::selectAudioOutputChoice,
            onStartDownload = {
                if (
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                    settings.notificationsEnabled &&
                    !hasPromptedNotificationPermission &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    hasPromptedNotificationPermission = true
                    runCatching {
                        runtimeNotificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                viewModel.startSelectedDownload(
                    onNavigateToQueue = {
                        currentDestination = MediaSaveDestination.QUEUE
                    }
                )
            },
            onDismiss = viewModel::dismissStreamSelectionSheet,
            isFromShareIntent = isShareIntentFlow
        )
    }

    // Direct Share Intent Error / Retry Overlay so errors during Share -> MediaSave do not drop onto Home Screen
    val shareErrorState = analysisState as? MediaAnalysisState.Error
    if (isShareIntentFlow && shareErrorState != null) {
        ShareIntentErrorOverlay(
            sharedUrl = shareErrorState.url,
            errorTitle = shareErrorState.errorTitle,
            errorMessage = shareErrorState.userMessage,
            onRetry = { viewModel.analyzeCurrentUrl(isFromShareIntent = true) },
            onDismissToHome = viewModel::dismissShareIntentFlow
        )
    }

    // Screen 10: Full-Screen Video Player
    activeVideoPlayback?.let { videoTask ->
        VideoPlayerModal(
            task = videoTask,
            onDismiss = viewModel::closeVideoPlayer,
            onShare = viewModel::shareDownloadedFile,
            onOpenExternally = viewModel::openDownloadedFileExternally
        )
    }

    // Screen 11: Full-Screen Music Player
    if (showFullMusicPlayer && audioPlayerState.activeTrack != null) {
        FullScreenMusicPlayer(
            playerState = audioPlayerState,
            onBack = viewModel::closeFullMusicPlayer,
            onTogglePlayPause = viewModel::toggleAudioPlayPause,
            onSeekTo = viewModel::seekAudioTo,
            onSkipPrev = { viewModel.skipAudioTrack(-1) },
            onSkipNext = { viewModel.skipAudioTrack(1) },
            onShare = viewModel::shareDownloadedFile,
            onToggleShuffle = viewModel::toggleAudioShuffle,
            onToggleRepeat = viewModel::toggleAudioRepeat,
            onToggleFavorite = viewModel::toggleFavoriteTrack,
            onOpenExternally = viewModel::openDownloadedFileExternally,
            onRenameTrack = viewModel::renameTask,
            onShowSavedInfo = { track ->
                viewModel.showMessage("Saved: ${track.outputFilePath}")
            }
        )
    }

    // Duplicate Download Confirmation Dialog
    duplicatePrompt?.let { prompt ->
        DuplicateWarningDialog(
            prompt = prompt,
            onDismiss = viewModel::dismissDuplicatePrompt
        )
    }

    if (showAboutDialog) {
        AboutMediaSaveDialog(onDismiss = { showAboutDialog = false })
    }

    if (showPrivacyPolicyDialog) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicyDialog = false })
    }
}
