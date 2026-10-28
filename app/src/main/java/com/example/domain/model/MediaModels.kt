package com.example.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloaderMode(val label: String) {
    VIDEO("Video Downloader"),
    MUSIC("Music / Audio")
}

enum class MediaPlatform(
    val displayName: String,
    val badgeLabel: String,
    val supportsDirectDownload: Boolean,
    val complianceSummary: String
) {
    DIRECT_URL(
        displayName = "Direct Media URL",
        badgeLabel = "DIRECT HTTPS",
        supportsDirectDownload = true,
        complianceSummary = "Standard HTTP/HTTPS media streams and JSON manifests with Content-Type and Range support."
    ),
    AUTHORIZED_LOCAL(
        displayName = "Authorized Studio Stream",
        badgeLabel = "AUTHORIZED",
        supportsDirectDownload = true,
        complianceSummary = "User-owned or on-device synthesized multi-resolution media streams for lawful extraction, merging, and format conversion."
    ),
    YOUTUBE(
        displayName = "YouTube",
        badgeLabel = "YOUTUBE",
        supportsDirectDownload = true,
        complianceSummary = "Extracts multi-resolution MP4/WebM video and M4A/Opus audio streams from YouTube videos and Shorts."
    ),
    FACEBOOK(
        displayName = "Facebook",
        badgeLabel = "FACEBOOK",
        supportsDirectDownload = true,
        complianceSummary = "Extracts HD and SD MP4 video and audio streams from public Facebook Watch, Reels, and video posts."
    ),
    INSTAGRAM(
        displayName = "Instagram",
        badgeLabel = "INSTAGRAM",
        supportsDirectDownload = true,
        complianceSummary = "Extracts HD MP4 video and audio streams from public Instagram Reels and video posts."
    ),
    TIKTOK(
        displayName = "TikTok",
        badgeLabel = "TIKTOK",
        supportsDirectDownload = true,
        complianceSummary = "Extracts No-Watermark HD MP4 video and original audio streams from TikTok links."
    ),
    UNSUPPORTED(
        displayName = "Unsupported Source",
        badgeLabel = "UNSUPPORTED",
        supportsDirectDownload = false,
        complianceSummary = "URL scheme or host is not a recognized media stream or authorized platform adapter."
    )
}

enum class StreamType(val displayName: String, val badge: String) {
    PROGRESSIVE("Progressive (Video + Audio)", "A+V"),
    VIDEO_ONLY("Adaptive Video-Only (Auto-Merges Audio)", "VIDEO ONLY"),
    AUDIO_ONLY("Audio Stream", "AUDIO")
}

enum class VideoContainerFormat(val extension: String, val mimeType: String, val label: String) {
    MP4("mp4", "video/mp4", "MP4"),
    WEBM("webm", "video/webm", "WebM"),
    MKV("mkv", "video/x-matroska", "MKV"),
    MOV("mov", "video/quicktime", "MOV");

    companion object {
        fun fromMimeOrExtension(mime: String?, urlOrExt: String): VideoContainerFormat {
            val lowerMime = mime?.lowercase().orEmpty()
            val lowerExt = urlOrExt.lowercase()
            return when {
                lowerMime.contains("webm") || lowerExt.endsWith(".webm") -> WEBM
                lowerMime.contains("matroska") || lowerMime.contains("mkv") || lowerExt.endsWith(".mkv") -> MKV
                lowerMime.contains("quicktime") || lowerExt.endsWith(".mov") -> MOV
                else -> MP4
            }
        }
    }
}

enum class AudioFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
    val codecDescription: String,
    val isLossless: Boolean,
    val supportsOnDeviceEncoding: Boolean,
    val technicalNote: String
) {
    M4A(
        extension = "m4a",
        mimeType = "audio/mp4",
        label = "M4A (AAC)",
        codecDescription = "AAC-LC in MP4 Audio Container",
        isLossless = false,
        supportsOnDeviceEncoding = true,
        technicalNote = "Native hardware-accelerated demuxing and AAC encoding via Android MediaCodec/MediaMuxer."
    ),
    AAC(
        extension = "aac",
        mimeType = "audio/aac",
        label = "AAC (ADTS)",
        codecDescription = "Advanced Audio Coding (Raw ADTS Stream)",
        isLossless = false,
        supportsOnDeviceEncoding = true,
        technicalNote = "Hardware-accelerated AAC stream with ADTS frame headers."
    ),
    WAV(
        extension = "wav",
        mimeType = "audio/wav",
        label = "WAV (PCM)",
        codecDescription = "Uncompressed 16-bit Linear PCM (RIFF WAVE)",
        isLossless = true,
        supportsOnDeviceEncoding = true,
        technicalNote = "Lossless uncompressed PCM container. Converting from a lossy source preserves decoded samples without restoring lost frequencies."
    ),
    AIFF(
        extension = "aiff",
        mimeType = "audio/x-aiff",
        label = "AIFF (PCM)",
        codecDescription = "Uncompressed 16-bit Big-Endian PCM (Apple IFF)",
        isLossless = true,
        supportsOnDeviceEncoding = true,
        technicalNote = "Studio uncompressed PCM format. Converting from a lossy source does not restore lost frequency data."
    ),
    MP3(
        extension = "mp3",
        mimeType = "audio/mpeg",
        label = "MP3",
        codecDescription = "MPEG-1/2 Audio Layer III",
        isLossless = false,
        supportsOnDeviceEncoding = false,
        technicalNote = "Available for direct stream download when provided by the source server."
    ),
    FLAC(
        extension = "flac",
        mimeType = "audio/flac",
        label = "FLAC",
        codecDescription = "Free Lossless Audio Codec",
        isLossless = true,
        supportsOnDeviceEncoding = false,
        technicalNote = "Available when source provides native FLAC or device hardware exposes a FLAC encoder."
    ),
    OGG(
        extension = "ogg",
        mimeType = "audio/ogg",
        label = "OGG (Vorbis)",
        codecDescription = "Ogg Vorbis Audio Container",
        isLossless = false,
        supportsOnDeviceEncoding = false,
        technicalNote = "Available for direct stream download when provided by the source."
    ),
    OPUS(
        extension = "opus",
        mimeType = "audio/opus",
        label = "Opus",
        codecDescription = "Opus Interactive Audio Codec",
        isLossless = false,
        supportsOnDeviceEncoding = false,
        technicalNote = "Available for direct stream download or WebM/Ogg audio demuxing."
    ),
    ALAC(
        extension = "m4a",
        mimeType = "audio/alac",
        label = "ALAC",
        codecDescription = "Apple Lossless Audio Codec",
        isLossless = true,
        supportsOnDeviceEncoding = false,
        technicalNote = "Available only when the source provides a genuine ALAC stream."
    );

    companion object {
        fun fromMimeOrExtension(mime: String?, urlOrExt: String): AudioFormat {
            val lowerMime = mime?.lowercase().orEmpty()
            val lowerExt = urlOrExt.lowercase()
            return when {
                lowerMime.contains("mpeg") || lowerMime.contains("mp3") || lowerExt.endsWith(".mp3") -> MP3
                lowerMime.contains("wav") || lowerMime.contains("wave") || lowerExt.endsWith(".wav") -> WAV
                lowerMime.contains("flac") || lowerExt.endsWith(".flac") -> FLAC
                lowerMime.contains("opus") || lowerExt.endsWith(".opus") -> OPUS
                lowerMime.contains("ogg") || lowerExt.endsWith(".ogg") -> OGG
                lowerMime.contains("aiff") || lowerExt.endsWith(".aiff") || lowerExt.endsWith(".aif") -> AIFF
                lowerMime.contains("alac") -> ALAC
                lowerExt.endsWith(".aac") || lowerMime == "audio/aac" -> AAC
                else -> M4A
            }
        }
    }
}

data class VideoStreamOption(
    val id: String,
    val resolutionHeight: Int,
    val resolutionWidth: Int,
    val resolutionLabel: String, // e.g., "144p", "360p", "720p HD", "1080p FHD", "1440p QHD", "2160p 4K"
    val videoCodec: String,      // e.g., "H.264 (AVC1)", "VP9", "AV1", "HEVC (H.265)"
    val audioCodec: String?,     // null if VIDEO_ONLY
    val container: VideoContainerFormat,
    val streamType: StreamType,
    val frameRate: Int,
    val isHdr: Boolean,
    val hdrFormat: String = if (isHdr) "HDR10" else "SDR",
    val bitrateKbps: Int,
    val estimatedSizeBytes: Long,
    val downloadUrl: String,
    val pairedAudioStreamUrl: String? = null,
    val pairedAudioSizeBytes: Long = 0L
) {
    val totalEstimatedBytes: Long
        get() = estimatedSizeBytes + pairedAudioSizeBytes
}

data class AudioStreamOption(
    val id: String,
    val sourceFormat: AudioFormat,
    val sourceCodec: String,       // e.g., "AAC-LC", "PCM 16-bit", "MP3", "Opus"
    val bitrateKbps: Int,
    val sampleRateHz: Int,         // e.g., 44100, 48000
    val channelCount: Int,         // 1 = Mono, 2 = Stereo, 6 = 5.1
    val isLosslessSource: Boolean,
    val estimatedSizeBytes: Long,
    val downloadUrl: String,
    val isExtractedFromVideo: Boolean = false
) {
    val channelConfigurationLabel: String
        get() = when (channelCount) {
            1 -> "Mono (1.0)"
            2 -> "Stereo (2.0)"
            6 -> "Surround (5.1)"
            else -> "$channelCount ch"
        }
}

data class AudioOutputChoice(
    val targetFormat: AudioFormat,
    val isDirectStreamCopy: Boolean,
    val isLossyToLosslessWrap: Boolean,
    val estimatedOutputBytes: Long,
    val description: String,
    val warningMessage: String? = null
)

data class AnalyzedMediaMetadata(
    val sourceUrl: String,
    val title: String,
    val authorOrDomain: String,
    val platform: MediaPlatform,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val supportsRangeRequests: Boolean,
    val videoStreams: List<VideoStreamOption>,
    val audioStreams: List<AudioStreamOption>,
    val inspectionMethod: String // e.g., "MediaExtractor + MediaMetadataRetriever", "HTTP HEAD + Manifest Inspection"
)

sealed class MediaAnalysisState {
    data object Idle : MediaAnalysisState()
    data class Analyzing(val url: String, val stageMessage: String) : MediaAnalysisState()
    data class Success(val metadata: AnalyzedMediaMetadata) : MediaAnalysisState()
    data class PlatformRestricted(
        val platform: MediaPlatform,
        val url: String,
        val publicTitle: String?,
        val publicAuthor: String?,
        val thumbnailUrl: String?,
        val restrictionReason: String,
        val permittedAlternative: String
    ) : MediaAnalysisState()
    data class Error(
        val url: String,
        val errorTitle: String,
        val userMessage: String,
        val technicalDetail: String? = null
    ) : MediaAnalysisState()
}

enum class DownloadStatus(val label: String, val isActive: Boolean) {
    QUEUED("Queued", true),
    DOWNLOADING("Downloading", true),
    PAUSED("Paused", false),
    MERGING_STREAMS("Merging Video + Audio (MediaMuxer)", true),
    EXTRACTING_AUDIO("Extracting Audio Stream", true),
    CONVERTING_AUDIO("Converting Audio Format", true),
    VALIDATING("Validating File Integrity", true),
    COMPLETED("Completed", false),
    FAILED("Failed", false),
    CANCELLED("Cancelled", false)
}

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val secondaryAudioUrl: String? = null,
    val title: String,
    val author: String,
    val platformName: String,
    val mode: String, // "VIDEO" or "MUSIC"
    val thumbnailUrl: String? = null,
    val outputFilePath: String,
    val fileName: String,
    val mimeType: String,
    val containerFormat: String,
    val codecInfo: String,
    val resolutionLabel: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Int = 0,
    val isHdr: Boolean = false,
    val streamTypeLabel: String = "Progressive",
    val sampleRateHz: Int = 0,
    val channelConfig: String = "",
    val bitrateKbps: Int = 0,
    val durationMs: Long = 0L,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val status: String, // DownloadStatus.name
    val statusDetail: String = "",
    val supportsRangeResume: Boolean = true,
    val requiresStreamMerge: Boolean = false,
    val requiresAudioExtraction: Boolean = false,
    val targetAudioFormat: String? = null,
    val isConverted: Boolean = false,
    val conversionNote: String? = null,
    val errorMessage: String? = null,
    val checksumSha256: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    val statusEnum: DownloadStatus
        get() = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED)

    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else if (statusEnum == DownloadStatus.COMPLETED) {
            1f
        } else {
            0f
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

enum class LibrarySortOption(val label: String) {
    DATE_DESC("Newest First"),
    NAME_ASC("Name (A–Z)"),
    SIZE_DESC("Largest Size")
}

enum class AppThemeMode(val label: String) {
    DARK("Studio Dark (Default)"),
    LIGHT("Daylight Clean"),
    SYSTEM("Follow System")
}
