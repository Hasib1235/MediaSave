package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.download.DownloadEngine
import com.example.data.media.AuthorizedSampleItem
import com.example.domain.model.DownloadTaskEntity
import com.example.domain.model.DownloaderMode
import com.example.domain.model.MediaAnalysisState
import com.example.ui.components.MediaSaveGradientTitle
import com.example.ui.components.MediaThumbnailArtwork
import com.example.ui.components.PlatformBrandIconItem
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.MidnightBorder
import com.example.ui.theme.MidnightCard
import com.example.ui.theme.MidnightInputBg
import com.example.ui.theme.NeonIndigo
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextSecondaryDark

@Composable
fun HomeScreen(
    selectedMode: DownloaderMode,
    urlInput: String,
    analysisState: MediaAnalysisState,
    authorizedSamples: List<AuthorizedSampleItem>,
    allTasks: List<DownloadTaskEntity>,
    onSelectMode: (DownloaderMode) -> Unit,
    onUrlChange: (String) -> Unit,
    onClearUrl: () -> Unit,
    onPasteClick: (String?) -> Unit = {},
    onAnalyzeClick: () -> Unit,
    onOpenSelectionSheet: () -> Unit,
    onSelectSample: (AuthorizedSampleItem) -> Unit,
    onPlatformIconClick: (String) -> Unit = {},
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onOpenTaskInPlayer: (DownloadTaskEntity) -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    onDeleteTask: (String) -> Unit = {},
    onShareTask: (DownloadTaskEntity) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val recentTasks = allTasks.take(4)
    val customTextSelectionColors = remember {
        TextSelectionColors(
            handleColor = NeonPurple,
            backgroundColor = NeonPurple.copy(alpha = 0.42f)
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .testTag("home_screen_list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Top Header: Menu Button | Centered Gradient "MediaSave" + Subtitle | Settings Gear
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .testTag("home_drawer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Menu",
                            tint = Color.White
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        MediaSaveGradientTitle(
                            fontSize = 30.sp,
                            modifier = Modifier.testTag("home_app_title")
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Video & Music Downloader",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondaryDark
                        )
                    }

                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .testTag("home_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }
                }
            }

            // 2. Rounded Pill URL Input Field ("Paste video or music link here...")
            // Supports native Android long-press Paste / Select All / Copy context menu, selection handles, and URI keyboard.
            item {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MidnightInputBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .border(1.dp, MidnightBorder, RoundedCornerShape(26.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = TextSecondaryDark,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))

                        CompositionLocalProvider(LocalTextSelectionColors provides customTextSelectionColors) {
                            BasicTextField(
                                value = urlInput,
                                onValueChange = onUrlChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                cursorBrush = SolidColor(NeonPurple),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go,
                                    autoCorrectEnabled = false
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        focusManager.clearFocus()
                                        onAnalyzeClick()
                                    }
                                ),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (urlInput.isEmpty()) {
                                            Text(
                                                text = "Paste video or music link here...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextSecondaryDark
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .testTag("url_input_field")
                            )
                        }

                        IconButton(
                            onClick = onClearUrl,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("clear_url_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Clear URL",
                                tint = TextSecondaryDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // 3. Side-by-Side "Paste Link" and "Analyze" Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1E2436),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, MidnightBorder, RoundedCornerShape(14.dp))
                            .clickable {
                                val composeClip = runCatching { clipboardManager.getText()?.text }.getOrNull()
                                onPasteClick(composeClip)
                            }
                            .testTag("paste_link_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Paste Link",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Button(
                        onClick = onAnalyzeClick,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.horizontalGradient(listOf(NeonPurple, NeonIndigo))
                            )
                            .testTag("analyze_link_button")
                    ) {
                        Text(
                            text = "Analyze",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 4. Inline Error or Platform Compliance Alert (if user entered an invalid/restricted URL)
            item {
                AnimatedVisibility(
                    visible = analysisState is MediaAnalysisState.Error ||
                        analysisState is MediaAnalysisState.PlatformRestricted
                ) {
                    when (analysisState) {
                        is MediaAnalysisState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = CoralError.copy(alpha = 0.14f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, CoralError.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                    .testTag("unsupported_url_error_card")
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CoralError)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = analysisState.errorTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = CoralError,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = analysisState.userMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        is MediaAnalysisState.PlatformRestricted -> {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = AmberWarning.copy(alpha = 0.14f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, AmberWarning.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                    .testTag("platform_restricted_card")
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Security, contentDescription = null, tint = AmberWarning)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${analysisState.platform.displayName} Restriction Notice",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = analysisState.restrictionReason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = analysisState.permittedAlternative,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondaryDark
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedButton(
                                        onClick = {
                                            val sample = if (selectedMode == DownloaderMode.VIDEO) {
                                                authorizedSamples.firstOrNull { it.isVideo } ?: authorizedSamples.firstOrNull()
                                            } else {
                                                authorizedSamples.firstOrNull { !it.isVideo } ?: authorizedSamples.firstOrNull()
                                            }
                                            sample?.let(onSelectSample)
                                        },
                                        modifier = Modifier.testTag("try_authorized_sample_button")
                                    ) {
                                        Text("Test Authorized Multi-Stream Media Instead")
                                    }
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }

            // 5. Two Large Square Mode Cards: "Video Downloader" and "Music Downloader"
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Left: Video Downloader Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(142.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF3B2175),
                                        Color(0xFF1F183D),
                                        Color(0xFF15192B)
                                    )
                                )
                            )
                            .border(
                                width = if (selectedMode == DownloaderMode.VIDEO) 2.dp else 1.dp,
                                color = if (selectedMode == DownloaderMode.VIDEO) NeonPurple else MidnightBorder,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                onSelectMode(DownloaderMode.VIDEO)
                                if (urlInput.isNotBlank()) {
                                    onAnalyzeClick()
                                } else {
                                    val sample = authorizedSamples.firstOrNull { it.isVideo }
                                    if (sample != null) onSelectSample(sample)
                                }
                            }
                            .testTag("mode_video_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(listOf(NeonPurple, NeonIndigo))
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Video Downloader",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Video\nDownloader",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )
                        }
                    }

                    // Right: Music Downloader Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(142.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF281B48),
                                        Color(0xFF0E3A42),
                                        Color(0xFF0B2933)
                                    )
                                )
                            )
                            .border(
                                width = if (selectedMode == DownloaderMode.MUSIC) 2.dp else 1.dp,
                                color = if (selectedMode == DownloaderMode.MUSIC) NeonPurple else MidnightBorder,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                onSelectMode(DownloaderMode.MUSIC)
                                if (urlInput.isNotBlank()) {
                                    onAnalyzeClick()
                                } else {
                                    val sample = authorizedSamples.firstOrNull { !it.isVideo }
                                    if (sample != null) onSelectSample(sample)
                                }
                            }
                            .testTag("mode_music_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(listOf(NeonPurple, NeonIndigo))
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = "Music Downloader",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Music\nDownloader",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            // 6. Row of 4 Platform Icons: YouTube | Facebook | Instagram | TikTok
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("YouTube", "Facebook", "Instagram", "TikTok").forEach { platformName ->
                        PlatformBrandIconItem(
                            name = platformName,
                            onClick = { onPlatformIconClick(platformName) },
                            modifier = Modifier.testTag("platform_icon_${platformName.lowercase()}")
                        )
                    }
                }
            }

            // 6b. Authorized Test Media Quick-Load Chips (for 1-tap verification of real downloads)
            if (authorizedSamples.isNotEmpty()) {
                item {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Authorized Test Media (Real Inspection & Download)",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondaryDark
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            authorizedSamples.forEach { sample ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MidnightCard,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, MidnightBorder, RoundedCornerShape(12.dp))
                                        .clickable { onSelectSample(sample) }
                                        .testTag("sample_chip_${sample.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (sample.isVideo) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = NeonPurple,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${sample.title} (${sample.badge})",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 7. Recent Downloads Section with "See All"
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Downloads",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = onNavigateToQueue,
                        modifier = Modifier.testTag("home_history_button")
                    ) {
                        Text(
                            text = "See All",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (recentTasks.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MidnightCard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MidnightBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .testTag("empty_recent_downloads_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No downloads yet",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Paste a direct media URL or tap an Authorized Test Media chip above to inspect streams and download.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(recentTasks, key = { it.id }) { task ->
                    HomeRecentDownloadCard(
                        task = task,
                        onClick = { onOpenTaskInPlayer(task) },
                        onShare = { onShareTask(task) },
                        onDelete = { onDeleteTask(task.id) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HomeRecentDownloadCard(
    task: DownloadTaskEntity,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MidnightCard,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MidnightBorder.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
                    .width(96.dp)
                    .height(60.dp)
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
                val resOrBitrate = task.resolutionLabel ?: "${task.bitrateKbps} kbps"
                Text(
                    text = "${task.containerFormat} • $resOrBitrate • ${DownloadEngine.formatBytesStatic(task.totalBytes)} • ${task.statusEnum.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = TextSecondaryDark
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play") },
                        onClick = {
                            menuExpanded = false
                            onClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        }
                    )
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
