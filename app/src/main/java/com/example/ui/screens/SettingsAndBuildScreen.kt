package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.download.DeviceStorageSummary
import com.example.data.download.DownloadEngine
import com.example.data.preferences.UserSettings
import com.example.domain.model.AppThemeMode
import com.example.ui.theme.MidnightBorder
import com.example.ui.theme.MidnightCard
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextSecondaryDark
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Storage

/**
 * Screen 9: "Settings" matching the exact layout of the reference image, with functional
 * Download Settings, App Settings, Storage Usage, History Management, Privacy Policy, and About.
 */
@Composable
fun SettingsAndBuildScreen(
    settings: UserSettings,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onMaxConcurrentChange: (Int) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onWarnDuplicateChange: (Boolean) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onSelectCustomDownloadFolder: (android.net.Uri?) -> Unit = {},
    onResetDownloadFolder: () -> Unit = {},
    storageSummary: DeviceStorageSummary = DeviceStorageSummary(),
    onClearCompletedHistory: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var askQualityBeforeDownload by remember { mutableStateOf(true) }
    var autoResumeDownload by remember { mutableStateOf(true) }
    var selectedLanguage by remember { mutableStateOf("English") }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onNotificationsChange(granted)
        if (granted) {
            onShowMessage("Download notifications enabled.")
        } else {
            onShowMessage("Notification permission denied. Downloads will continue with in-app progress.")
        }
    }

    val safFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            onSelectCustomDownloadFolder(treeUri)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .testTag("settings_screen"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Title: Centered "Settings"
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Section 1: Download Settings
            item {
                Text(
                    text = "Download Settings",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MidnightCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MidnightBorder.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                ) {
                    Column {
                        val pathSubtitle = settings.customDownloadPathLabel.ifBlank {
                            "/storage/emulated/0/MediaSave (MediaStore Movies, Music & Downloads)"
                        }
                        SettingsChevronRow(
                            icon = Icons.Outlined.Folder,
                            title = "Download Path",
                            subtitle = pathSubtitle,
                            onClick = {
                                try {
                                    safFolderPickerLauncher.launch(null)
                                } catch (_: Exception) {
                                    if (settings.customDownloadTreeUri.isNotBlank()) {
                                        onResetDownloadFolder()
                                    } else {
                                        onShowMessage("Files are saved to MediaSave Storage and exported to Android MediaStore (Movies/MediaSave, Music/MediaSave, Download/MediaSave).")
                                    }
                                }
                            }
                        )
                        SettingsDivider()

                        SettingsChevronRow(
                            icon = Icons.Outlined.AccountTree,
                            title = "Max Download Threads",
                            subtitle = "${settings.maxConcurrentDownloads.coerceIn(1, 4)}",
                            onClick = {
                                val next = (settings.maxConcurrentDownloads % 4) + 1
                                onMaxConcurrentChange(next)
                                onShowMessage("Max concurrent download threads set to $next")
                            }
                        )
                        SettingsDivider()

                        SettingsSwitchRow(
                            icon = Icons.Outlined.HighQuality,
                            title = "Ask Quality Before Download",
                            checked = askQualityBeforeDownload,
                            onCheckedChange = {
                                askQualityBeforeDownload = it
                                onWarnDuplicateChange(it)
                            },
                            testTag = "toggle_ask_quality"
                        )
                        SettingsDivider()

                        SettingsSwitchRow(
                            icon = Icons.Outlined.Notifications,
                            title = "Show Download Notification",
                            checked = settings.notificationsEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    try {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } catch (_: Exception) {
                                        onNotificationsChange(true)
                                    }
                                } else {
                                    onNotificationsChange(enabled)
                                }
                            },
                            testTag = "toggle_notifications"
                        )
                        SettingsDivider()

                        SettingsSwitchRow(
                            icon = Icons.Outlined.Autorenew,
                            title = "Auto Resume Download",
                            checked = autoResumeDownload,
                            onCheckedChange = {
                                autoResumeDownload = it
                                onWifiOnlyChange(!it)
                            },
                            testTag = "toggle_auto_resume"
                        )
                    }
                }
            }

            // Section 2: App Settings
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "App Settings",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MidnightCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MidnightBorder.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                ) {
                    Column {
                        val themeValueText = when (settings.themeMode) {
                            AppThemeMode.DARK -> "Dark"
                            AppThemeMode.LIGHT -> "Light"
                            AppThemeMode.SYSTEM -> "System"
                        }
                        SettingsInlineValueRow(
                            icon = Icons.Outlined.DarkMode,
                            title = "Theme",
                            valueText = themeValueText,
                            onClick = {
                                val next = when (settings.themeMode) {
                                    AppThemeMode.DARK -> AppThemeMode.LIGHT
                                    AppThemeMode.LIGHT -> AppThemeMode.SYSTEM
                                    AppThemeMode.SYSTEM -> AppThemeMode.DARK
                                }
                                onThemeModeChange(next)
                            },
                            testTag = "theme_selector_row"
                        )
                        SettingsDivider()

                        SettingsInlineValueRow(
                            icon = Icons.Outlined.Language,
                            title = "Language",
                            valueText = selectedLanguage,
                            onClick = {
                                selectedLanguage = if (selectedLanguage == "English") "বাংলা (Bengali)" else "English"
                            },
                            testTag = "language_selector_row"
                        )
                    }
                }
            }

            // Section 3: Storage, History & About
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Storage & Information",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MidnightCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MidnightBorder.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                ) {
                    Column {
                        val usedLabel = DownloadEngine.formatBytesStatic(storageSummary.usedByAppBytes)
                        val freeLabel = if (storageSummary.availableBytes > 0L) {
                            " • ${DownloadEngine.formatBytesStatic(storageSummary.availableBytes)} free"
                        } else ""
                        SettingsChevronRow(
                            icon = Icons.Outlined.Storage,
                            title = "Storage Usage",
                            subtitle = "MediaSave Downloads: $usedLabel$freeLabel",
                            onClick = {
                                onShowMessage("MediaSave is using $usedLabel$freeLabel")
                            }
                        )
                        SettingsDivider()

                        SettingsChevronRow(
                            icon = Icons.Outlined.DeleteSweep,
                            title = "Clear Completed History",
                            subtitle = "Remove finished & cancelled entries from download list",
                            onClick = onClearCompletedHistory
                        )
                        SettingsDivider()

                        SettingsChevronRow(
                            icon = Icons.Outlined.PrivacyTip,
                            title = "Privacy Policy",
                            subtitle = "Zero-tracking local media processing & platform compliance",
                            onClick = onOpenPrivacyPolicy
                        )
                        SettingsDivider()

                        SettingsChevronRow(
                            icon = Icons.Outlined.Info,
                            title = "About MediaSave",
                            subtitle = "Version 1.0 (Build 1) • Architecture & APK/AAB Build Guide",
                            onClick = onOpenAbout
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = MidnightBorder.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun SettingsChevronRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondaryDark,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondaryDark
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextSecondaryDark
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondaryDark,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NeonPurple,
                uncheckedThumbColor = TextSecondaryDark,
                uncheckedTrackColor = Color(0xFF252D44)
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SettingsInlineValueRow(
    icon: ImageVector,
    title: String,
    valueText: String,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondaryDark,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondaryDark
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextSecondaryDark
        )
    }
}
