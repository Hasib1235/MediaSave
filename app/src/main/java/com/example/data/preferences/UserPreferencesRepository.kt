package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mediasave_settings")

data class UserSettings(
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val maxConcurrentDownloads: Int = 2,
    val wifiOnlyDownloads: Boolean = false,
    val warnOnDuplicateDownload: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val customDownloadTreeUri: String = "",
    val customDownloadPathLabel: String = ""
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent_downloads")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        val WARN_DUPLICATES = booleanPreferencesKey("warn_duplicate_downloads")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val CUSTOM_DOWNLOAD_TREE_URI = stringPreferencesKey("custom_download_tree_uri")
        val CUSTOM_DOWNLOAD_PATH_LABEL = stringPreferencesKey("custom_download_path_label")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: AppThemeMode.DARK.name
        val theme = runCatching { AppThemeMode.valueOf(themeStr) }.getOrDefault(AppThemeMode.DARK)
        UserSettings(
            themeMode = theme,
            maxConcurrentDownloads = (prefs[Keys.MAX_CONCURRENT] ?: 2).coerceIn(1, 4),
            wifiOnlyDownloads = prefs[Keys.WIFI_ONLY] ?: false,
            warnOnDuplicateDownload = prefs[Keys.WARN_DUPLICATES] ?: true,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            customDownloadTreeUri = prefs[Keys.CUSTOM_DOWNLOAD_TREE_URI] ?: "",
            customDownloadPathLabel = prefs[Keys.CUSTOM_DOWNLOAD_PATH_LABEL] ?: ""
        )
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setMaxConcurrentDownloads(count: Int) {
        context.dataStore.edit { it[Keys.MAX_CONCURRENT] = count.coerceIn(1, 4) }
    }

    suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setWarnOnDuplicateDownload(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WARN_DUPLICATES] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    suspend fun setCustomDownloadLocation(treeUri: String, displayLabel: String) {
        context.dataStore.edit {
            it[Keys.CUSTOM_DOWNLOAD_TREE_URI] = treeUri
            it[Keys.CUSTOM_DOWNLOAD_PATH_LABEL] = displayLabel
        }
    }
}
