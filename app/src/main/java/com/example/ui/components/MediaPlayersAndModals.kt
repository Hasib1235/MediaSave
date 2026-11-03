package com.example.ui.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.download.DownloadEngine
import com.example.domain.model.DiagnosticsReport
import com.example.domain.model.DownloadTaskEntity
import com.example.ui.AudioPlayerState
import com.example.ui.DuplicateDownloadPrompt
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.MidnightBorder
import com.example.ui.theme.MidnightCard
import com.example.ui.theme.MidnightSurface
import com.example.ui.theme.NeonIndigo
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.StudioViolet
import com.example.ui.theme.TextSecondaryDark
import java.io.File

/**
 * Screen 1: Splash Screen with glowing 3D Play+Music-Note emblem, MediaSave title,
 * "YouTube | Facebook | Instagram | TikTok" subtitle, and purple loading bar.
 */
@Composable
fun MediaSaveSplashScreen(
    onSkipOrFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MidnightBg,
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onSkipOrFinish)
            .testTag("splash_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            MediaSaveBrandLogo(size = 150.dp)

            Spacer(modifier = Modifier.height(28.dp))

            MediaSaveGradientTitle(fontSize = 36.sp)

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Video & Music Downloader",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFA5B4FC)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "YouTube  |  Facebook  |  Instagram  |  TikTok",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondaryDark
            )

            Spacer(modifier = Modifier.weight(1f))

            LinearProgressIndicator(
                modifier = Modifier
                    .width(210.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = NeonPurple,
                trackColor = Color(0xFF1E293B)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Loading...",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondaryDark
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Screen 10: Full-Screen Video Player matching the reference design.
 */
@Composable
fun VideoPlayerModal(
    task: DownloadTaskEntity,
    onDismiss: () -> Unit,
    onShare: (DownloadTaskEntity) -> Unit,
    onOpenExternally: (DownloadTaskEntity) -> Unit
) {
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosMs by remember { mutableStateOf(0L) }
    var totalDurMs by remember { mutableStateOf(task.durationMs.coerceAtLeast(1000L)) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playbackSpeed by remember { mutableStateOf("1.0x") }
    var isLocked by remember { mutableStateOf(false) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching {
                videoViewRef?.pause()
                videoViewRef?.stopPlayback()
            }
            mediaPlayerRef = null
            videoViewRef = null
        }
    }

    androidx.compose.runtime.LaunchedEffect(videoViewRef, isPlaying) {
        while (true) {
            val vv = videoViewRef
            if (vv != null) {
                val pos = runCatching { vv.currentPosition.toLong() }.getOrDefault(currentPosMs)
                val dur = runCatching { vv.duration.toLong() }.getOrDefault(totalDurMs)
                if (dur > 0) totalDurMs = dur
                currentPosMs = pos.coerceIn(0L, totalDurMs)
            }
            kotlinx.coroutines.delay(200)
        }
    }

    val progressFraction = (currentPosMs.toFloat() / totalDurMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

    Surface(
        color = MidnightBg,
        modifier = Modifier
            .fillMaxSize()
            .testTag("video_player_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar: < Back and Video Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 36.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // 16:9 Video Canvas + Android VideoView
            val videoFile = remember(task.outputFilePath) { File(task.outputFilePath) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .clickable {
                        if (!isLocked) {
                            isPlaying = !isPlaying
                            videoViewRef?.let { vv ->
                                if (isPlaying) vv.start() else vv.pause()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (videoFile.exists()) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                videoViewRef = this
                                val mediaController = MediaController(ctx)
                                mediaController.setAnchorView(this)
                                setVideoURI(Uri.fromFile(videoFile))
                                setOnPreparedListener { mp ->
                                    mediaPlayerRef = mp
                                    mp.isLooping = true
                                    if (mp.duration > 0) totalDurMs = mp.duration.toLong()
                                    val speedFloat = playbackSpeed.removeSuffix("x").toFloatOrNull() ?: 1.0f
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && speedFloat != 1.0f) {
                                        runCatching {
                                            mp.playbackParams = mp.playbackParams.setSpeed(speedFloat)
                                        }
                                    }
                                    start()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (!isPlaying) {
                    MediaThumbnailArtwork(
                        title = task.title,
                        isVideo = true,
                        showPlayOverlay = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Seek Slider with real playback time and total duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDurationReadable(currentPosMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
                Slider(
                    value = progressFraction,
                    enabled = !isLocked,
                    onValueChange = { fraction ->
                        if (!isLocked) {
                            val targetMs = (fraction * totalDurMs).toLong()
                            currentPosMs = targetMs
                            runCatching { videoViewRef?.seekTo(targetMs.toInt()) }
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = NeonPurple,
                        inactiveTrackColor = Color(0xFF2A324B)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                )
                Text(
                    text = formatDurationReadable(totalDurMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Primary Transport Row: Replay10   ( ▶ )   Forward10
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                IconButton(
                    enabled = !isLocked,
                    onClick = {
                        val targetMs = (currentPosMs - 10_000L).coerceAtLeast(0L)
                        currentPosMs = targetMs
                        runCatching { videoViewRef?.seekTo(targetMs.toInt()) }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "Replay 10 seconds",
                        tint = if (isLocked) TextSecondaryDark else Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MidnightCard)
                        .border(1.dp, MidnightBorder, CircleShape)
                        .clickable(enabled = !isLocked) {
                            isPlaying = !isPlaying
                            videoViewRef?.let { vv ->
                                if (isPlaying) vv.start() else vv.pause()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = if (isLocked) TextSecondaryDark else Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(
                    enabled = !isLocked,
                    onClick = {
                        val targetMs = (currentPosMs + 10_000L).coerceAtMost(totalDurMs)
                        currentPosMs = targetMs
                        runCatching { videoViewRef?.seekTo(targetMs.toInt()) }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Forward 10 seconds",
                        tint = if (isLocked) TextSecondaryDark else Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.7f))

            // Bottom Action Bar: Lock | Speed | Open App
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerBottomActionItem(
                    icon = Icons.Default.Lock,
                    label = if (isLocked) "Locked" else "Lock",
                    onClick = { isLocked = !isLocked }
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !isLocked) {
                            val nextSpeed = when (playbackSpeed) {
                                "1.0x" -> "1.25x"
                                "1.25x" -> "1.5x"
                                "1.5x" -> "2.0x"
                                else -> "1.0x"
                            }
                            playbackSpeed = nextSpeed
                            val speedFloat = nextSpeed.removeSuffix("x").toFloatOrNull() ?: 1.0f
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                runCatching {
                                    mediaPlayerRef?.let { mp ->
                                        mp.playbackParams = mp.playbackParams.setSpeed(speedFloat)
                                    }
                                        ?: videoViewRef?.let { vv ->
                                            if (!isPlaying) vv.start()
                                        }
                                }
                            }
                        }
                        .padding(8.dp)
                ) {
                    Text(
                        text = playbackSpeed,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isLocked) TextSecondaryDark else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Speed",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondaryDark
                    )
                }
                PlayerBottomActionItem(
                    icon = Icons.Default.Fullscreen,
                    label = "Open App",
                    enabled = !isLocked,
                    onClick = { onOpenExternally(task) }
                )
            }
        }
    }
}

/**
 * Screen 11: Full-Screen Music Player matching the reference design with all controls functional.
 */
@Composable
fun FullScreenMusicPlayer(
    playerState: AudioPlayerState,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,
    onShare: (DownloadTaskEntity) -> Unit,
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    onOpenExternally: (DownloadTaskEntity) -> Unit = {},
    onRenameTrack: (String, String) -> Unit = { _, _ -> },
    onShowSavedInfo: (DownloadTaskEntity) -> Unit = {}
) {
    val track = playerState.activeTrack ?: return
    var showMoreMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Surface(
        color = MidnightBg,
        modifier = Modifier
            .fillMaxSize()
            .testTag("full_music_player_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar: <- Back, Title & Artist
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${track.author} • ${track.containerFormat} (${track.bitrateKbps} kbps)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryDark
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // Large Circular Vinyl / Album Artwork with Glowing Cyan/Purple Border
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .clip(CircleShape)
                    .border(
                        width = 3.dp,
                        brush = Brush.sweepGradient(
                            listOf(ElectricCyan, NeonPurple, ElectricBlue, ElectricCyan)
                        ),
                        shape = CircleShape
                    )
                    .padding(6.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                MediaThumbnailArtwork(
                    title = track.title,
                    isVideo = false,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // Seek Slider Row with real audio playback position and duration
            val totalMs = playerState.durationMs.coerceAtLeast(track.durationMs.toInt()).coerceAtLeast(1000)
            val currentMs = playerState.currentPositionMs.coerceIn(0, totalMs)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDurationReadable(currentMs.toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
                Slider(
                    value = currentMs.toFloat(),
                    onValueChange = { onSeekTo(it.toInt()) },
                    valueRange = 0f..totalMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = NeonPurple,
                        activeTrackColor = NeonPurple,
                        inactiveTrackColor = Color(0xFF2A324B)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                )
                Text(
                    text = formatDurationReadable(totalMs.toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Transport Controls: Shuffle | Prev | Purple Pause/Play Circle | Next | Repeat
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.testTag("music_player_shuffle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playerState.isShuffleEnabled) NeonPurple else TextSecondaryDark
                    )
                }

                IconButton(onClick = onSkipPrev) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(NeonPurple, NeonIndigo))
                        )
                        .clickable(onClick = onTogglePlayPause)
                        .testTag("music_player_play_pause_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(onClick = onSkipNext) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(
                    onClick = onToggleRepeat,
                    modifier = Modifier.testTag("music_player_repeat_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (playerState.isRepeatEnabled) NeonPurple else TextSecondaryDark
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // Bottom 3-Icon Bar: Add to Playlist | Download | More
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerBottomActionItem(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = if (playerState.isActiveTrackFavorite) "In Favorites" else "Add to Playlist",
                    onClick = { onToggleFavorite(track.id) }
                )
                PlayerBottomActionItem(
                    icon = Icons.Default.Download,
                    label = "Saved File",
                    onClick = { onShowSavedInfo(track) }
                )
                Box {
                    PlayerBottomActionItem(
                        icon = Icons.Default.MoreVert,
                        label = "More",
                        onClick = { showMoreMenu = true }
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Rename Track") },
                            onClick = {
                                showMoreMenu = false
                                showRenameDialog = true
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Share Audio File") },
                            onClick = {
                                showMoreMenu = false
                                onShare(track)
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Open in External Player") },
                            onClick = {
                                showMoreMenu = false
                                onOpenExternally(track)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameMediaDialog(
            task = track,
            onConfirmRename = { newTitle -> onRenameTrack(track.id, newTitle) },
            onDismiss = { showRenameDialog = false }
        )
    }
}

@Composable
private fun PlayerBottomActionItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) Color.White else TextSecondaryDark,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondaryDark
        )
    }
}

/**
 * Screen 12: Slide-Out Navigation Menu Drawer matching the reference image.
 */
@Composable
fun MediaSaveNavigationDrawerContent(
    currentRoute: String,
    onSelectHome: () -> Unit,
    onSelectDownloads: () -> Unit,
    onSelectVideoLibrary: () -> Unit,
    onSelectMusicLibrary: () -> Unit,
    onSelectSettings: () -> Unit,
    onCloseDrawer: () -> Unit,
    onShareApp: () -> Unit,
    onRateUs: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenAbout: () -> Unit = {}
) {
    Surface(
        color = MidnightSurface,
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .testTag("navigation_drawer_content")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            // Drawer Header with Logo, MediaSave Title, and Close X
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MediaSaveBrandLogo(size = 44.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        MediaSaveGradientTitle(fontSize = 20.sp)
                        Text(
                            text = "Video & Music Downloader",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondaryDark
                        )
                    }
                }
                IconButton(onClick = onCloseDrawer) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Menu",
                        tint = TextSecondaryDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            DrawerNavRow(
                icon = Icons.Default.Home,
                label = "Home",
                selected = currentRoute == "home",
                onClick = onSelectHome
            )
            DrawerNavRow(
                icon = Icons.Default.Download,
                label = "Downloads",
                selected = currentRoute == "active_downloading" || currentRoute == "queue",
                onClick = onSelectDownloads
            )
            DrawerNavRow(
                icon = Icons.Default.VideoLibrary,
                label = "Video Library",
                selected = currentRoute == "video_library",
                onClick = onSelectVideoLibrary
            )
            DrawerNavRow(
                icon = Icons.Default.LibraryMusic,
                label = "Music Library",
                selected = currentRoute == "music",
                onClick = onSelectMusicLibrary
            )
            DrawerNavRow(
                icon = Icons.Default.Settings,
                label = "Settings",
                selected = currentRoute == "settings",
                onClick = onSelectSettings
            )

            HorizontalDivider(
                color = MidnightBorder.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 14.dp)
            )

            DrawerNavRow(
                icon = Icons.Default.Share,
                label = "Share App",
                selected = false,
                onClick = onShareApp
            )
            DrawerNavRow(
                icon = Icons.Default.StarOutline,
                label = "Rate Us",
                selected = false,
                onClick = onRateUs
            )
            DrawerNavRow(
                icon = Icons.Default.PrivacyTip,
                label = "Privacy Policy",
                selected = false,
                onClick = onOpenPrivacyPolicy
            )
            DrawerNavRow(
                icon = Icons.Default.Info,
                label = "About",
                selected = false,
                onClick = onOpenAbout
            )
        }
    }
}

@Composable
private fun DrawerNavRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MidnightCard else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) NeonPurple else TextSecondaryDark,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
fun FloatingMusicPlayerBar(
    playerState: AudioPlayerState,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val track = playerState.activeTrack ?: return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onExpand)
            .testTag("floating_music_player"),
        shape = RoundedCornerShape(16.dp),
        color = MidnightCard,
        tonalElevation = 8.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            MediaThumbnailArtwork(
                title = track.title,
                isVideo = false,
                modifier = Modifier.size(42.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onSkipPrev) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White)
            }

            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(NeonPurple)
            ) {
                Icon(
                    imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White
                )
            }

            IconButton(onClick = onSkipNext) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
            }

            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark)
            }
        }
    }
}

@Composable
fun RenameMediaDialog(
    task: DownloadTaskEntity,
    onConfirmRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var inputTitle by remember(task.id) { mutableStateOf(task.title) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Media File") },
        text = {
            Column {
                Text(
                    text = "Current file: ${task.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = inputTitle,
                    onValueChange = { inputTitle = it },
                    label = { Text("New Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_input_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (inputTitle.isNotBlank()) {
                        onConfirmRename(inputTitle.trim())
                        onDismiss()
                    }
                },
                modifier = Modifier.testTag("confirm_rename_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DuplicateWarningDialog(
    prompt: DuplicateDownloadPrompt,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = AmberWarning
            )
        },
        title = { Text("Duplicate Download Detected") },
        text = {
            Text(
                text = "'${prompt.existingTask.title}' is already in your Downloads. Download another copy?"
            )
        },
        confirmButton = {
            Button(onClick = prompt.onConfirmProceed) {
                Text("Download Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AboutMediaSaveDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About MediaSave v1.0") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "MediaSave — All-in-One Video & Music Downloader (Version 1.0, Build 1)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• Real Stream Inspection: Uses HTTP HEAD/Range requests, Android MediaExtractor, and MediaMetadataRetriever to detect actual resolutions (144p–4K+), codecs (H.264, HEVC, VP9, AV1, AAC, Opus, PCM), frame rates, and bitrates.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• On-Device Media Processing: Uses Android MediaMuxer and MediaCodec for adaptive video+audio stream merging, audio demuxing, and WAV/AIFF/M4A/AAC conversion without external servers.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• APK & AAB Build Commands:\n  Debug APK: gradle assembleDebug\n  Release APK: gradle assembleRelease\n  Play Store AAB: gradle bundleRelease",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_about_dialog_button")
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
fun PrivacyPolicyDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy Policy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "1. Third-Party Link Lookups & Helper Services",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "When you paste or analyze a link, MediaSave sends the URL and stream lookup requests to the source site and to third-party helper services (including Piped, Invidious, TikWM, TiklyDown, and ddinstagram) to resolve downloadable media streams. Those third-party services receive your submitted link and network request metadata under their own privacy policies.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "2. Local Storage & Media Processing",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Downloaded video and audio files, stream remuxing, and audio format conversions are stored on your Android device in app-scoped storage and exported to Android MediaStore (Movies/MediaSave, Music/MediaSave, and Download/MediaSave).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_privacy_dialog_button")
            ) {
                Text("Understood")
            }
        }
    )
}

/**
 * Expandable "Technical details" section with Copy button that displays the last 30 diagnostic lines.
 */
@Composable
fun ExpandableTechnicalDetailsSection(
    technicalDetails: String,
    modifier: Modifier = Modifier
) {
    if (technicalDetails.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
                .testTag("toggle_technical_details_button"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = "Toggle Technical details",
                tint = NeonPurple,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Technical details",
                style = MaterialTheme.typography.labelMedium,
                color = NeonPurple,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MidnightBg.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MidnightBorder, RoundedCornerShape(10.dp))
                    .testTag("technical_details_box")
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Diagnostic Log (Last 30 lines)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondaryDark,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = {
                                runCatching {
                                    clipboardManager.setText(AnnotatedString(technicalDetails))
                                    copied = true
                                }
                            },
                            modifier = Modifier.testTag("copy_technical_details_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Technical Details",
                                tint = NeonPurple,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (copied) "Copied" else "Copy",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonPurple,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = technicalDetails,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        color = Color(0xFFE2E8F0)
                    )
                }
            }
        }
    }
}

/**
 * Self-Test Diagnostics Dialog ("Run diagnostics") that executes a real test extraction + download
 * of https://www.youtube.com/watch?v=jNQXAC9IVRw and shows extractor version, muxer status, exit code,
 * and the last 15 log lines with a Copy button.
 */
@Composable
fun DiagnosticsReportDialog(
    report: DiagnosticsReport?,
    isRunning: Boolean,
    onRunAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(report) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        icon = {
            Icon(
                imageVector = if (report?.isHealthy == true) Icons.Default.CheckCircle else Icons.Default.BugReport,
                contentDescription = null,
                tint = when {
                    isRunning -> NeonPurple
                    report?.isHealthy == true -> EmeraldSuccess
                    else -> CoralError
                }
            )
        },
        title = {
            Text(
                text = if (isRunning) "Running Diagnostics..." else "YouTube Downloader Diagnostics",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (isRunning || report == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = NeonPurple)
                    Text(
                        text = "Testing real extraction, stream download, and container integrity for:\nhttps://www.youtube.com/watch?v=jNQXAC9IVRw",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Status: ${report.statusSummary}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (report.isHealthy) EmeraldSuccess else CoralError,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("diagnostics_status_summary")
                    )
                    Text(
                        text = "• Extractor: ${report.extractorVersion}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• MediaMuxer: ${report.muxerStatus}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Test Video: ${report.testVideoUrl}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Exit Code: ${report.exitCode} • Downloaded: ${DownloadEngine.formatBytesStatic(report.downloadedBytes)} in ${report.durationMs} ms",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MidnightBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MidnightBorder, RoundedCornerShape(10.dp))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Last 15 Diagnostic Lines",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondaryDark,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            val fullCopy = buildString {
                                                appendLine("Extractor: ${report.extractorVersion}")
                                                appendLine("MediaMuxer: ${report.muxerStatus}")
                                                appendLine("Test URL: ${report.testVideoUrl}")
                                                appendLine("Exit Code: ${report.exitCode} (${report.statusSummary})")
                                                appendLine(report.formattedLog)
                                            }
                                            clipboardManager.setText(AnnotatedString(fullCopy))
                                            copied = true
                                        }
                                    },
                                    modifier = Modifier.testTag("copy_diagnostics_report_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Diagnostics",
                                        tint = NeonPurple,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (copied) "Copied" else "Copy",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonPurple
                                    )
                                }
                            }
                            Text(
                                text = report.formattedLog,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                ),
                                color = Color(0xFFE2E8F0),
                                modifier = Modifier.testTag("diagnostics_log_output")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                enabled = !isRunning,
                modifier = Modifier.testTag("close_diagnostics_dialog_button")
            ) {
                Text("Close")
            }
        },
        dismissButton = {
            if (!isRunning) {
                OutlinedButton(
                    onClick = onRunAgain,
                    modifier = Modifier.testTag("re_run_diagnostics_button")
                ) {
                    Text("Run Again")
                }
            }
        }
    )
}

