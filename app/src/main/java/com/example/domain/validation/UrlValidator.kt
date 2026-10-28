package com.example.domain.validation

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.domain.model.AudioFormat
import com.example.domain.model.AudioOutputChoice
import com.example.domain.model.AudioStreamOption
import com.example.domain.model.MediaPlatform
import java.net.URI

enum class SocialPlatformShortcut(
    val id: String,
    val displayName: String,
    val officialWebUrl: String,
    val appPackages: List<String>
) {
    YOUTUBE(
        id = "youtube",
        displayName = "YouTube",
        officialWebUrl = "https://www.youtube.com/",
        appPackages = listOf("com.google.android.youtube")
    ),
    FACEBOOK(
        id = "facebook",
        displayName = "Facebook",
        officialWebUrl = "https://www.facebook.com/",
        appPackages = listOf("com.facebook.katana", "com.facebook.lite")
    ),
    INSTAGRAM(
        id = "instagram",
        displayName = "Instagram",
        officialWebUrl = "https://www.instagram.com/",
        appPackages = listOf("com.instagram.android")
    ),
    TIKTOK(
        id = "tiktok",
        displayName = "TikTok",
        officialWebUrl = "https://www.tiktok.com/",
        appPackages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    );

    companion object {
        fun fromName(name: String): SocialPlatformShortcut? {
            val clean = name.trim().lowercase()
            return entries.firstOrNull { it.id == clean || it.displayName.lowercase() == clean }
        }
    }
}

sealed class SocialPlatformLaunchResult {
    data class LaunchedOfficialApp(
        val shortcut: SocialPlatformShortcut,
        val packageName: String,
        val targetUrl: String
    ) : SocialPlatformLaunchResult()

    data class LaunchedBrowser(
        val shortcut: SocialPlatformShortcut,
        val targetUrl: String
    ) : SocialPlatformLaunchResult()

    data class Failed(
        val shortcut: SocialPlatformShortcut?,
        val userMessage: String
    ) : SocialPlatformLaunchResult()
}

sealed class UrlValidationResult {
    data class Valid(
        val normalizedUrl: String,
        val scheme: String,
        val host: String,
        val path: String,
        val platform: MediaPlatform
    ) : UrlValidationResult()

    data class Invalid(
        val rawInput: String,
        val errorTitle: String,
        val errorMessage: String
    ) : UrlValidationResult()
}

sealed class ClipboardParseResult {
    data object Empty : ClipboardParseResult()
    data class ValidUrl(
        val pastedText: String,
        val validation: UrlValidationResult.Valid
    ) : ClipboardParseResult()
    data class InvalidText(
        val pastedText: String,
        val validation: UrlValidationResult.Invalid
    ) : ClipboardParseResult()
}

object UrlValidator {

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com", "youtube-nocookie.com"
    )
    private val FACEBOOK_HOSTS = setOf(
        "facebook.com", "www.facebook.com", "m.facebook.com", "mbasic.facebook.com", "web.facebook.com", "fb.watch", "fb.com", "www.fb.com", "fb.gg"
    )
    private val INSTAGRAM_HOSTS = setOf(
        "instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am", "ddinstagram.com"
    )
    private val TIKTOK_HOSTS = setOf(
        "tiktok.com", "www.tiktok.com", "vm.tiktok.com", "vt.tiktok.com", "m.tiktok.com", "v.tiktok.com"
    )

    private val SUPPORTED_SCHEMES = setOf("https", "http", "file")

    private val MOCK_OR_PLACEHOLDER_PREFIXES = listOf(
        "mock://",
        "sample://",
        "placeholder://",
        "fake://",
        "about:blank"
    )

    private val MOCK_HOSTS = setOf(
        "mock.local",
        "test.invalid",
        "invalid.local",
        "placeholder.invalid"
    )

    /**
     * Resolves a social platform shortcut and builds an ACTION_VIEW Intent for its official web URL.
     * Never routes to test-media screens and never triggers a download.
     */
    fun resolveSocialPlatformShortcut(platformName: String): SocialPlatformShortcut? {
        return SocialPlatformShortcut.fromName(platformName)
    }

    fun buildSocialBrowserIntent(shortcut: SocialPlatformShortcut): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(shortcut.officialWebUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Opens the official installed social app if available, or falls back to opening the platform's
     * official website (https://www.youtube.com/, https://www.facebook.com/, https://www.instagram.com/,
     * https://www.tiktok.com/) in the device browser. Handles missing apps/browsers gracefully.
     */
    fun launchSocialPlatform(context: Context, platformName: String): SocialPlatformLaunchResult {
        val shortcut = resolveSocialPlatformShortcut(platformName)
            ?: return SocialPlatformLaunchResult.Failed(
                shortcut = null,
                userMessage = "Unknown social platform '$platformName'."
            )

        val webUri = Uri.parse(shortcut.officialWebUrl)
        val pm = context.packageManager

        // 1. Try opening the official installed native app with ACTION_VIEW for the platform's homepage
        for (pkg in shortcut.appPackages) {
            try {
                val appViewIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (appViewIntent.resolveActivity(pm) != null) {
                    context.startActivity(appViewIntent)
                    return SocialPlatformLaunchResult.LaunchedOfficialApp(
                        shortcut = shortcut,
                        packageName = pkg,
                        targetUrl = shortcut.officialWebUrl
                    )
                }
                val launchIntent = pm.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    return SocialPlatformLaunchResult.LaunchedOfficialApp(
                        shortcut = shortcut,
                        packageName = pkg,
                        targetUrl = shortcut.officialWebUrl
                    )
                }
            } catch (_: Exception) {
                // Fall through to browser intent
            }
        }

        // 2. Fallback: Open the official platform URL in the user's browser / Custom Tab
        return try {
            val browserIntent = buildSocialBrowserIntent(shortcut)
            context.startActivity(browserIntent)
            SocialPlatformLaunchResult.LaunchedBrowser(
                shortcut = shortcut,
                targetUrl = shortcut.officialWebUrl
            )
        } catch (_: ActivityNotFoundException) {
            SocialPlatformLaunchResult.Failed(
                shortcut = shortcut,
                userMessage = "Could not open ${shortcut.displayName} (${shortcut.officialWebUrl}): No web browser or ${shortcut.displayName} app is installed on this device."
            )
        } catch (e: Exception) {
            SocialPlatformLaunchResult.Failed(
                shortcut = shortcut,
                userMessage = "Unable to open ${shortcut.displayName}: ${e.localizedMessage ?: "No compatible app found"}."
            )
        }
    }

    /**
     * Sanitizes a URL for diagnostic logging so private query tokens, credentials, or full paths are never logged.
     */
    fun sanitizeUrlForDiagnosticLog(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return "<empty>"
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: "unknown"
            if (scheme == "file") {
                val fileName = uri.path?.substringAfterLast('/')?.ifEmpty { "local_file" } ?: "local_file"
                "file://.../$fileName"
            } else {
                val host = uri.host?.lowercase() ?: "unknown-host"
                val ext = uri.path?.substringAfterLast('.', "")?.takeIf { it.length in 1..6 }
                if (ext != null) "$scheme://$host/.../*.$ext" else "$scheme://$host/..."
            }
        } catch (_: Exception) {
            "<malformed-url>"
        }
    }

    /**
     * Reads text or URI from Android's system ClipboardManager safely.
     */
    fun readClipboardTextFromSystem(context: Context): String? {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return null
            if (!clipboard.hasPrimaryClip()) return null
            val clip = clipboard.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            val item = clip.getItemAt(0) ?: return null
            val directText = item.text?.toString()?.trim()
            if (!directText.isNullOrEmpty()) return directText
            val uriText = item.uri?.toString()?.trim()
            if (!uriText.isNullOrEmpty()) return uriText
            item.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses raw text from the clipboard, extracting an embedded URL if the user copied share text
     * (e.g., "Watch this video: https://...") and validating the result.
     */
    fun parseClipboardContent(rawClipboardText: String?): ClipboardParseResult {
        val trimmed = rawClipboardText?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return ClipboardParseResult.Empty
        }

        // If the clipboard contains surrounding share text with a single http/https/file URL, extract the URL
        val candidate = if (trimmed.contains(" ") || trimmed.contains("\n")) {
            val urlRegex = Regex("(https?://[^\\s\"<>]+|file://[^\\s\"<>]+)", RegexOption.IGNORE_CASE)
            urlRegex.find(trimmed)?.value?.trim() ?: trimmed
        } else {
            trimmed
        }

        return when (val result = validateAndClassify(candidate)) {
            is UrlValidationResult.Valid -> ClipboardParseResult.ValidUrl(
                pastedText = result.normalizedUrl,
                validation = result
            )
            is UrlValidationResult.Invalid -> ClipboardParseResult.InvalidText(
                pastedText = trimmed,
                validation = result
            )
        }
    }

    /**
     * Identifies whether a URL is mock/placeholder metadata rather than a real downloadable media URL.
     */
    fun isMockOrPlaceholderUrl(rawUrl: String): Boolean {
        val lower = rawUrl.trim().lowercase()
        if (lower.isEmpty()) return true
        if (MOCK_OR_PLACEHOLDER_PREFIXES.any { lower.startsWith(it) }) return true
        if (lower.contains("://example.com/mock") || lower.contains("://www.example.com/mock")) return true
        val host = runCatching { URI(lower).host?.lowercase() }.getOrNull()
        if (host != null && (host in MOCK_HOSTS || host.endsWith(".invalid"))) return true
        return false
    }

    fun validateAndClassify(rawInput: String): UrlValidationResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return UrlValidationResult.Invalid(
                rawInput = rawInput,
                errorTitle = "Empty Link",
                errorMessage = "Please enter or paste a valid media URL or select an authorized test stream."
            )
        }

        if (isMockOrPlaceholderUrl(trimmed)) {
            return UrlValidationResult.Invalid(
                rawInput = rawInput,
                errorTitle = "Mock or Non-Downloadable Test Metadata",
                errorMessage = "This media option is mock/test metadata and does not map to a real downloadable media URL."
            )
        }

        if (trimmed.contains(" ") || trimmed.contains("\n")) {
            return UrlValidationResult.Invalid(
                rawInput = rawInput,
                errorTitle = "Malformed URL",
                errorMessage = "The link contains spaces or line breaks. Please provide a single valid URL."
            )
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return UrlValidationResult.Invalid(
                rawInput = rawInput,
                errorTitle = "Invalid URL Syntax",
                errorMessage = "Could not parse the URL syntax: ${e.localizedMessage ?: "Illegal character in URL"}."
            )
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in SUPPORTED_SCHEMES) {
            return UrlValidationResult.Invalid(
                rawInput = rawInput,
                errorTitle = "Unsupported Protocol",
                errorMessage = "Only HTTPS, HTTP, and authorized local file:// URIs are supported. Found: '${scheme ?: "none"}'."
            )
        }

        if (scheme == "file") {
            val path = uri.path.orEmpty()
            if (path.isBlank() || path.contains("..")) {
                return UrlValidationResult.Invalid(
                    rawInput = rawInput,
                    errorTitle = "Invalid Local File Path",
                    errorMessage = "Local file URI must point to an authorized media path without directory traversal."
                )
            }
            return UrlValidationResult.Valid(
                normalizedUrl = trimmed,
                scheme = scheme,
                host = "localhost",
                path = path,
                platform = MediaPlatform.AUTHORIZED_LOCAL
            )
        }

        val host = uri.host?.lowercase()?.removePrefix("www.")?.let {
            uri.host.lowercase()
        }
        if (host.isNullOrBlank() || (!host.contains(".") && host != "localhost" && !host.startsWith("127."))) {
            return UrlValidationResult.Invalid(
                rawInput = rawInput,
                errorTitle = "Invalid Hostname",
                errorMessage = "The URL does not contain a valid domain name or host."
            )
        }

        val cleanHost = host.lowercase()
        val platform = when {
            cleanHost in YOUTUBE_HOSTS || cleanHost.endsWith(".youtube.com") -> MediaPlatform.YOUTUBE
            cleanHost in FACEBOOK_HOSTS || cleanHost.endsWith(".facebook.com") -> MediaPlatform.FACEBOOK
            cleanHost in INSTAGRAM_HOSTS || cleanHost.endsWith(".instagram.com") -> MediaPlatform.INSTAGRAM
            cleanHost in TIKTOK_HOSTS || cleanHost.endsWith(".tiktok.com") -> MediaPlatform.TIKTOK
            else -> MediaPlatform.DIRECT_URL
        }

        return UrlValidationResult.Valid(
            normalizedUrl = trimmed,
            scheme = scheme,
            host = cleanHost,
            path = uri.path.orEmpty(),
            platform = platform
        )
    }

    /**
     * Generates a safe, filesystem-compliant filename without path traversal or illegal characters.
     */
    fun sanitizeFileName(rawTitle: String, extension: String): String {
        val cleanExt = extension.trim().lowercase().removePrefix(".")
        .replace(Regex("[^a-z0-9]"), "")
            .ifEmpty { "bin" }

        val base = rawTitle
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace("..", "_")
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.')
            .take(80)
            .ifEmpty { "mediasave_file" }

        return "$base.$cleanExt"
    }

    /**
     * Builds truthful audio output choices for a detected source audio stream.
     * Clearly distinguishes between direct stream copy (no re-encoding) and converted output formats,
     * and never claims lossy-to-lossless conversion restores lost audio information.
     */
    fun buildSupportedAudioOutputChoices(source: AudioStreamOption): List<AudioOutputChoice> {
        val choices = mutableListOf<AudioOutputChoice>()

        // 1. Original source format (Direct Download / Demux without re-encoding)
        choices.add(
            AudioOutputChoice(
                targetFormat = source.sourceFormat,
                isDirectStreamCopy = true,
                isLossyToLosslessWrap = false,
                estimatedOutputBytes = source.estimatedSizeBytes,
                description = if (source.isExtractedFromVideo) {
                    "Direct lossless demux of original ${source.sourceCodec} stream (${source.bitrateKbps} kbps) without re-encoding."
                } else {
                    "Original ${source.sourceFormat.label} stream (${source.sourceCodec}, ${source.bitrateKbps} kbps) — zero re-encoding."
                },
                warningMessage = null
            )
        )

        // 2. Lawful on-device conversions for formats supported by Android MediaCodec / PCM writers
        val durationSec = if (source.bitrateKbps > 0) {
            (source.estimatedSizeBytes * 8.0) / (source.bitrateKbps * 1000.0)
        } else {
            10.0
        }.coerceAtLeast(1.0)

        val pcmBytesEstimate = (durationSec * source.sampleRateHz * source.channelCount * 2).toLong() + 64L

        for (format in AudioFormat.entries) {
            if (format == source.sourceFormat) continue
            if (!format.supportsOnDeviceEncoding) continue

            val isLossyToLossless = !source.isLosslessSource && format.isLossless
            val estimatedBytes = when (format) {
                AudioFormat.WAV, AudioFormat.AIFF -> pcmBytesEstimate
                AudioFormat.M4A, AudioFormat.AAC -> ((durationSec * 192_000) / 8.0).toLong().coerceAtLeast(16_384L)
                else -> source.estimatedSizeBytes
            }

            val warning = if (isLossyToLossless) {
                "Note: Source audio is lossy (${source.sourceCodec} ${source.bitrateKbps} kbps). Converting to ${format.label} wraps decoded PCM audio for compatibility and does NOT restore lost frequencies."
            } else null

            choices.add(
                AudioOutputChoice(
                    targetFormat = format,
                    isDirectStreamCopy = false,
                    isLossyToLosslessWrap = isLossyToLossless,
                    estimatedOutputBytes = estimatedBytes,
                    description = "Converted to ${format.label} (${format.codecDescription}) at ${source.sampleRateHz} Hz, ${source.channelConfigurationLabel}.",
                    warningMessage = warning
                )
            )
        }

        return choices
    }
}
