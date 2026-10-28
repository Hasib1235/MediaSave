package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.download.DownloadEngine
import com.example.domain.model.AnalyzedMediaMetadata
import com.example.domain.model.AudioOutputChoice
import com.example.domain.model.AudioStreamOption
import com.example.domain.model.DownloaderMode
import com.example.domain.model.VideoStreamOption
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BrandYouTubeRed
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.MidnightBorder
import com.example.ui.theme.MidnightCard
import com.example.ui.theme.MidnightCardSelected
import com.example.ui.theme.NeonIndigo
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextSecondaryDark

/**
 * Screen 3: Full-Screen "Analyzing Link" screen with glowing concentric neon circles.
 */
@Composable
fun AnalyzingLinkScreen(
    stageMessage: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MidnightBg,
        modifier = modifier
            .fillMaxSize()
            .testTag("analyzing_link_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Analyzing Link",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.weight(0.8f))

            AnalyzingLinkNeonRing(size = 220.dp)

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Analyzing link...",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stageMessage.ifBlank { "This may take a few seconds" },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondaryDark
            )

            Spacer(modifier = Modifier.height(24.dp))

            LinearProgressIndicator(
                modifier = Modifier
                    .width(240.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = NeonPurple,
                trackColor = Color(0xFF1E293B)
            )

            Spacer(modifier = Modifier.weight(1.2f))
        }
    }
}

/**
 * Screen 4 (Video Download Options) & Screen 5 (Music Download Options).
 */
@Composable
fun StreamSelectionBottomSheet(
    metadata: AnalyzedMediaMetadata,
    selectedMode: DownloaderMode,
    selectedVideoStream: VideoStreamOption?,
    selectedAudioStream: AudioStreamOption?,
    audioOutputChoices: List<AudioOutputChoice>,
    selectedAudioOutput: AudioOutputChoice?,
    onSelectMode: (DownloaderMode) -> Unit,
    onSelectVideoStream: (VideoStreamOption) -> Unit,
    onSelectAudioStream: (AudioStreamOption) -> Unit,
    onSelectAudioOutput: (AudioOutputChoice) -> Unit,
    onStartDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MidnightBg,
        modifier = Modifier
            .fillMaxSize()
            .testTag("stream_selection_sheet")
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                // Top Header Bar matching Screen 4 & Screen 5
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (selectedMode == DownloaderMode.VIDEO) "Video Download" else "Music Download",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(width = 18.dp, height = 13.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(BrandYouTubeRed),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = metadata.platform.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            onSelectMode(
                                if (selectedMode == DownloaderMode.VIDEO) DownloaderMode.MUSIC else DownloaderMode.VIDEO
                            )
                        },
                        modifier = Modifier.testTag(
                            if (selectedMode == DownloaderMode.VIDEO) "sheet_mode_music" else "sheet_mode_video"
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Switch Mode",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedMode == DownloaderMode.VIDEO) {
                    // Screen 4: Video Download Options
                    val displayStreams = metadata.videoStreams.sortedByDescending { it.resolutionHeight }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            MediaThumbnailArtwork(
                                title = metadata.title,
                                isVideo = true,
                                showPlayOverlay = true,
                                durationBadge = formatDurationReadable(metadata.durationMs),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = metadata.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Park,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = metadata.authorOrDomain,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondaryDark
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Available Qualities (${displayStreams.size} detected)",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        if (displayStreams.isEmpty()) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MidnightCard,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = "Audio-Only Source Detected",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "No video tracks exist in this container. Tap the top-right menu button to switch to Music Download mode.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondaryDark
                                        )
                                    }
                                }
                            }
                        } else {
                            items(displayStreams, key = { it.id }) { stream ->
                                val isSelected = selectedVideoStream?.id == stream.id
                                VideoQualityOptionRow(
                                    stream = stream,
                                    isSelected = isSelected,
                                    onClick = { onSelectVideoStream(stream) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onStartDownload,
                        enabled = displayStreams.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.horizontalGradient(listOf(NeonPurple, NeonIndigo))
                            )
                            .testTag("confirm_start_download_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Download Video",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // Screen 5: Music Download Options
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MediaThumbnailArtwork(
                                    title = metadata.title,
                                    isVideo = false,
                                    modifier = Modifier.size(84.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = metadata.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = metadata.authorOrDomain,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondaryDark
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val srcInfo = selectedAudioStream?.let {
                                        "Source: ${it.sourceFormat.name} (${it.sourceCodec}, ${it.sampleRateHz}Hz ${it.channelConfigurationLabel})"
                                    } ?: "Duration: ${formatDurationReadable(metadata.durationMs)}"
                                    Text(
                                        text = srcInfo,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondaryDark
                                    )
                                }
                            }

                            if (metadata.audioStreams.size > 1) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Detected Audio Tracks",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                metadata.audioStreams.forEach { srcStream ->
                                    val srcSelected = selectedAudioStream?.id == srcStream.id
                                    AudioSourceTrackRow(
                                        stream = srcStream,
                                        isSelected = srcSelected,
                                        onClick = { onSelectAudioStream(srcStream) }
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Audio Format",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        items(audioOutputChoices, key = { it.targetFormat.name }) { choice ->
                            val isSelected = selectedAudioOutput?.targetFormat == choice.targetFormat
                            AudioOutputChoiceRow(
                                choice = choice,
                                isSelected = isSelected,
                                onClick = { onSelectAudioOutput(choice) }
                            )
                        }

                        selectedAudioOutput?.warningMessage?.let { warning ->
                            item {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = AmberWarning.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, AmberWarning.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = AmberWarning,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = warning,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onStartDownload,
                        enabled = audioOutputChoices.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.horizontalGradient(listOf(NeonPurple, NeonIndigo))
                            )
                            .testTag("confirm_start_download_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Download Music",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoQualityOptionRow(
    stream: VideoStreamOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MidnightCardSelected else MidnightCard.copy(alpha = 0.65f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) NeonPurple else MidnightBorder.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .testTag("video_stream_option_${stream.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onClick,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = NeonPurple,
                        unselectedColor = TextSecondaryDark
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = stream.resolutionLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                    val streamKind = stream.streamType.badge
                    val hdrBadge = if (stream.isHdr) " • ${stream.hdrFormat}" else ""
                    Text(
                        text = "${stream.videoCodec} • ${stream.frameRate}fps • $streamKind$hdrBadge",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondaryDark
                    )
                }
            }

            Text(
                text = "${stream.container.label} • ${DownloadEngine.formatBytesStatic(stream.totalEstimatedBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) Color.White else TextSecondaryDark,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun AudioSourceTrackRow(
    stream: AudioStreamOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MidnightCardSelected else MidnightCard.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = if (isSelected) NeonPurple else MidnightBorder.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = NeonPurple,
                    unselectedColor = TextSecondaryDark
                )
            )
            Column {
                Text(
                    text = "${stream.sourceFormat.name} (${stream.sourceCodec})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${stream.bitrateKbps} kbps • ${stream.sampleRateHz} Hz • ${stream.channelConfigurationLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondaryDark
                )
            }
        }
    }
}

@Composable
private fun AudioOutputChoiceRow(
    choice: AudioOutputChoice,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MidnightCardSelected else MidnightCard.copy(alpha = 0.65f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) NeonPurple else MidnightBorder.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .testTag("audio_output_option_${choice.targetFormat.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = NeonPurple,
                    unselectedColor = TextSecondaryDark
                )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (choice.isDirectStreamCopy) {
                            "${choice.targetFormat.name} (Original Stream)"
                        } else {
                            "${choice.targetFormat.name} (Converted)"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = DownloadEngine.formatBytesStatic(choice.estimatedOutputBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) Color.White else TextSecondaryDark
                    )
                }
                Text(
                    text = choice.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark
                )
            }
        }
    }
}
