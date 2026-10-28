package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.download.DownloadEngine
import com.example.domain.model.DownloadStatus
import com.example.domain.model.DownloadTaskEntity
import com.example.domain.model.DownloaderMode
import com.example.ui.components.MediaThumbnailArtwork
import com.example.ui.theme.CoralError
import com.example.ui.theme.MidnightBorder
import com.example.ui.theme.MidnightCard
import com.example.ui.theme.MidnightInputBg
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextSecondaryDark

private enum class DownloadsTabFilter(val label: String) {
    ALL("All"),
    VIDEOS("Videos"),
    MUSIC("Music")
}

/**
 * Implements both Screen 6 ("Downloading" Active Progress & Queue) and Screen 7 ("Downloads" Library).
 */
@Composable
fun DownloadsQueueScreen(
    allTasks: List<DownloadTaskEntity>,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onPlayTask: (DownloadTaskEntity) -> Unit,
    onShareTask: (DownloadTaskEntity) -> Unit,
    onClearHistory: () -> Unit,
    initialShowActiveScreen: Boolean = false,
    onRenameTask: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val hasActiveDownloads = allTasks.any { it.statusEnum.isActive || it.statusEnum == DownloadStatus.PAUSED }
    var showDownloadingProgressView by remember(initialShowActiveScreen, hasActiveDownloads) {
        mutableStateOf(initialShowActiveScreen || hasActiveDownloads)
    }

    if (showDownloadingProgressView) {
        Screen6DownloadingProgressView(
            allTasks = allTasks,
            onPauseTask = onPauseTask,
            onResumeTask = onResumeTask,
            onRetryTask = onRetryTask,
            onCancelTask = onCancelTask,
            onDeleteTask = onDeleteTask,
            onPlayTask = onPlayTask,
            onClearAll = onClearHistory,
            onMinimizeToLibrary = { showDownloadingProgressView = false },
            modifier = modifier
        )
    } else {
        Screen7DownloadsLibraryView(
            allTasks = allTasks,
            onPlayTask = onPlayTask,
            onShareTask = onShareTask,
            onDeleteTask = onDeleteTask,
            onRetryTask = onRetryTask,
            onCancelTask = onCancelTask,
            onOpenProgressScreen = { showDownloadingProgressView = true },
            modifier = modifier
        )
    }
}

/**
 * Screen 6: "Downloading" Progress & Queue Screen with real progress, speed, ETA, pause/resume/cancel/retry.
 */
@Composable
private fun Screen6DownloadingProgressView(
    allTasks: List<DownloadTaskEntity>,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onPlayTask: (DownloadTaskEntity) -> Unit,
    onClearAll: () -> Unit,
    onMinimizeToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedModeTab by remember { mutableStateOf(DownloaderMode.VIDEO) }

    val modeTasks = allTasks.filter { it.mode == selectedModeTab.name }
    val activeTask = modeTasks.firstOrNull { it.statusEnum.isActive || it.statusEnum == DownloadStatus.PAUSED }
        ?: allTasks.firstOrNull { it.statusEnum.isActive || it.statusEnum == DownloadStatus.PAUSED }
        ?: modeTasks.firstOrNull()
        ?: allTasks.firstOrNull()

    val queueList = allTasks.filter { it.id != activeTask?.id }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .testTag("downloading_progress_screen")
        ) {
            // Top Title: "Downloading"
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Downloading",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
                TextButton(
                    onClick = onMinimizeToLibrary,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Text("Library", color = Color(0xFF38BDF8))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Segmented Toggle: [ Video ] | [ Music ]
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MidnightInputBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                ) {
                    listOf(DownloaderMode.VIDEO to "Video", DownloaderMode.MUSIC to "Music").forEach { (mode, label) ->
                        val selected = selectedModeTab == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) NeonPurple else Color.Transparent)
                                .clickable { selectedModeTab = mode },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) Color.White else TextSecondaryDark,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Active Downloading Card matching Screen 6 with real task metrics
            if (activeTask != null) {
                val displayPercent = activeTask.progressPercent
                val displayFraction = activeTask.progressFraction

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MidnightCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MidnightBorder, RoundedCornerShape(20.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MediaThumbnailArtwork(
                                title = activeTask.title,
                                isVideo = activeTask.mode == DownloaderMode.VIDEO.name,
                                modifier = Modifier
                                    .width(76.dp)
                                    .height(54.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activeTask.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${activeTask.containerFormat} • ${activeTask.resolutionLabel ?: "${activeTask.bitrateKbps} kbps"} • ${activeTask.statusEnum.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = "$displayPercent%",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        LinearProgressIndicator(
                            progress = { displayFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (activeTask.statusEnum == DownloadStatus.FAILED) CoralError else NeonPurple,
                            trackColor = Color(0xFF283048)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val progressBytesText = if (activeTask.totalBytes > 0L) {
                                "${DownloadEngine.formatBytesStatic(activeTask.downloadedBytes)} / ${DownloadEngine.formatBytesStatic(activeTask.totalBytes)}"
                            } else {
                                DownloadEngine.formatBytesStatic(activeTask.downloadedBytes)
                            }
                            val speedText = when {
                                activeTask.speedBytesPerSec > 0L -> "${DownloadEngine.formatBytesStatic(activeTask.speedBytesPerSec)}/s"
                                activeTask.statusEnum == DownloadStatus.COMPLETED -> "Completed"
                                else -> activeTask.statusEnum.label
                            }
                            Text(
                                text = progressBytesText,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark
                            )
                            Text(
                                text = speedText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = if (activeTask.statusEnum == DownloadStatus.FAILED) CoralError else NeonPurple,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val etaLabel = when {
                                activeTask.statusEnum == DownloadStatus.FAILED -> activeTask.errorMessage ?: "Download failed"
                                activeTask.statusEnum == DownloadStatus.CANCELLED -> "Cancelled by user"
                                activeTask.etaSeconds > 0L -> "${activeTask.etaSeconds} seconds left"
                                activeTask.statusDetail.isNotBlank() -> activeTask.statusDetail
                                else -> activeTask.statusEnum.label
                            }
                            Text(
                                text = etaLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (activeTask.statusEnum == DownloadStatus.FAILED) CoralError else TextSecondaryDark
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // 3 Circular Control Buttons: Pause/Resume/Retry | Cancel | Background
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Pause / Resume / Retry / Play
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        when (activeTask.statusEnum) {
                                            DownloadStatus.PAUSED -> onResumeTask(activeTask.id)
                                            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> onRetryTask(activeTask.id)
                                            DownloadStatus.COMPLETED -> onPlayTask(activeTask)
                                            else -> onPauseTask(activeTask.id)
                                        }
                                    }
                                    .testTag("active_task_primary_action_button")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF252D44)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = when (activeTask.statusEnum) {
                                            DownloadStatus.PAUSED, DownloadStatus.COMPLETED -> Icons.Default.PlayArrow
                                            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> Icons.Default.Refresh
                                            else -> Icons.Default.Pause
                                        },
                                        contentDescription = "Action",
                                        tint = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = when (activeTask.statusEnum) {
                                        DownloadStatus.PAUSED -> "Resume"
                                        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> "Retry"
                                        DownloadStatus.COMPLETED -> "Play"
                                        else -> "Pause"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark
                                )
                            }

                            // Cancel (Red Circle)
                            val isTerminal = !activeTask.statusEnum.isActive && activeTask.statusEnum != DownloadStatus.PAUSED
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        if (isTerminal) {
                                            onDeleteTask(activeTask.id)
                                        } else {
                                            onCancelTask(activeTask.id)
                                        }
                                    }
                                    .testTag("active_task_cancel_button")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF252D44)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(CoralError),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (isTerminal) "Remove" else "Cancel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark
                                )
                            }

                            // Background
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable(onClick = onMinimizeToLibrary)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF252D44)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.OpenInFull,
                                        contentDescription = "Background",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Background",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark
                                )
                            }
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MidnightCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MidnightBorder, RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No Active Downloads",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Analyze a direct media URL or authorized test stream on the Home screen and tap Download to start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondaryDark,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Queue Header: "Queue (N)" and "Clear All"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Queue (${queueList.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.testTag("clear_history_button")
                ) {
                    Text(
                        text = "Clear All",
                        color = Color(0xFF38BDF8),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(queueList, key = { it.id }) { qTask ->
                    var itemMenuExpanded by remember { mutableStateOf(false) }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MidnightCard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MidnightBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MediaThumbnailArtwork(
                                title = qTask.title,
                                isVideo = qTask.mode == DownloaderMode.VIDEO.name,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = qTask.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${qTask.containerFormat} • ${qTask.resolutionLabel ?: "${qTask.bitrateKbps} kbps"} • ${DownloadEngine.formatBytesStatic(qTask.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark
                                )
                                val subStatus = when (qTask.statusEnum) {
                                    DownloadStatus.QUEUED -> "Waiting in queue..."
                                    DownloadStatus.FAILED -> qTask.errorMessage ?: "Failed"
                                    else -> "${qTask.statusEnum.label} (${qTask.progressPercent}%)"
                                }
                                Text(
                                    text = subStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (qTask.statusEnum == DownloadStatus.FAILED) CoralError else TextSecondaryDark
                                )
                            }
                            Box {
                                IconButton(onClick = { itemMenuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More",
                                        tint = TextSecondaryDark
                                    )
                                }
                                DropdownMenu(
                                    expanded = itemMenuExpanded,
                                    onDismissRequest = { itemMenuExpanded = false }
                                ) {
                                    if (qTask.statusEnum == DownloadStatus.COMPLETED) {
                                        DropdownMenuItem(
                                            text = { Text("Play") },
                                            onClick = {
                                                itemMenuExpanded = false
                                                onPlayTask(qTask)
                                            }
                                        )
                                    }
                                    if (qTask.statusEnum.isActive) {
                                        DropdownMenuItem(
                                            text = { Text("Pause") },
                                            onClick = {
                                                itemMenuExpanded = false
                                                onPauseTask(qTask.id)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Cancel") },
                                            onClick = {
                                                itemMenuExpanded = false
                                                onCancelTask(qTask.id)
                                            }
                                        )
                                    }
                                    if (qTask.statusEnum == DownloadStatus.PAUSED) {
                                        DropdownMenuItem(
                                            text = { Text("Resume") },
                                            onClick = {
                                                itemMenuExpanded = false
                                                onResumeTask(qTask.id)
                                            }
                                        )
                                    }
                                    if (qTask.statusEnum == DownloadStatus.FAILED || qTask.statusEnum == DownloadStatus.CANCELLED) {
                                        DropdownMenuItem(
                                            text = { Text("Retry") },
                                            onClick = {
                                                itemMenuExpanded = false
                                                onRetryTask(qTask.id)
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        onClick = {
                                            itemMenuExpanded = false
                                            onDeleteTask(qTask.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen 7: "Downloads" Library (All | Videos | Music) matching the reference image.
 */
@Composable
private fun Screen7DownloadsLibraryView(
    allTasks: List<DownloadTaskEntity>,
    onPlayTask: (DownloadTaskEntity) -> Unit,
    onShareTask: (DownloadTaskEntity) -> Unit,
    onDeleteTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onOpenProgressScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(DownloadsTabFilter.ALL) }

    val filteredTasks = remember(allTasks, selectedTab) {
        when (selectedTab) {
            DownloadsTabFilter.ALL -> allTasks
            DownloadsTabFilter.VIDEOS -> allTasks.filter { it.mode == DownloaderMode.VIDEO.name }
            DownloadsTabFilter.MUSIC -> allTasks.filter { it.mode == DownloaderMode.MUSIC.name }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .testTag("downloads_queue_screen")
        ) {
            // Top Header: Centered "Downloads" + Queue Monitor Icon
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
                IconButton(
                    onClick = onOpenProgressScreen,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .testTag("open_active_progress_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Downloading,
                        contentDescription = "Active Queue",
                        tint = NeonPurple
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Segmented Pill Tabs: All | Videos | Music
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DownloadsTabFilter.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(if (isSelected) NeonPurple else MidnightInputBg)
                            .clickable { selectedTab = tab }
                            .testTag("downloads_tab_${tab.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isSelected) Color.White else TextSecondaryDark,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (filteredTasks.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MidnightCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MidnightBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No downloads in ${selectedTab.label}",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Completed and active downloads will appear here with real file size, codec, and status details.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondaryDark,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredTasks, key = { it.id }) { task ->
                        DownloadsLibraryVideoCard(
                            task = task,
                            onPlay = { onPlayTask(task) },
                            onShare = { onShareTask(task) },
                            onRetry = { onRetryTask(task.id) },
                            onCancel = { onCancelTask(task.id) },
                            onDelete = { onDeleteTask(task.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsLibraryVideoCard(
    task: DownloadTaskEntity,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MidnightCard,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MidnightBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onPlay)
            .testTag("download_task_card_${task.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaThumbnailArtwork(
                title = task.title,
                isVideo = task.mode == DownloaderMode.VIDEO.name,
                modifier = Modifier
                    .width(106.dp)
                    .height(66.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val res = task.resolutionLabel ?: "${task.bitrateKbps} kbps"
                Text(
                    text = "$res • ${DownloadEngine.formatBytesStatic(task.totalBytes)} • ${task.containerFormat}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                val statusLine = when (task.statusEnum) {
                    DownloadStatus.FAILED -> task.errorMessage ?: "Failed"
                    DownloadStatus.COMPLETED -> task.statusDetail.ifBlank { "Completed" }
                    else -> "${task.statusEnum.label} (${task.progressPercent}%)"
                }
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (task.statusEnum == DownloadStatus.FAILED) CoralError else TextSecondaryDark.copy(alpha = 0.85f)
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = TextSecondaryDark
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (task.statusEnum == DownloadStatus.COMPLETED) {
                        DropdownMenuItem(
                            text = { Text("Play") },
                            onClick = {
                                menuExpanded = false
                                onPlay()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            }
                        )
                    }
                    if (task.statusEnum == DownloadStatus.FAILED || task.statusEnum == DownloadStatus.CANCELLED) {
                        DropdownMenuItem(
                            text = { Text("Retry") },
                            onClick = {
                                menuExpanded = false
                                onRetry()
                            }
                        )
                    }
                    if (task.statusEnum.isActive) {
                        DropdownMenuItem(
                            text = { Text("Cancel") },
                            onClick = {
                                menuExpanded = false
                                onCancel()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
