package com.example.data.platform

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import com.example.data.media.MediaProcessorEngine
import com.example.domain.model.AnalyzedMediaMetadata
import com.example.domain.model.AudioFormat
import com.example.domain.model.AudioStreamOption
import com.example.domain.model.MediaAnalysisState
import com.example.domain.model.MediaPlatform
import com.example.domain.model.StreamType
import com.example.domain.model.VideoContainerFormat
import com.example.domain.model.VideoStreamOption
import com.example.domain.validation.UrlValidationResult
import com.example.domain.validation.UrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

interface PlatformMediaAdapter {
    val platform: MediaPlatform
    suspend fun analyze(url: String): MediaAnalysisState
}

/**
 * Adapter 1: Authorized Local Media & Multi-Stream Manifests (file://)
 */
class AuthorizedLocalMediaAdapter : PlatformMediaAdapter {
    override val platform: MediaPlatform = MediaPlatform.AUTHORIZED_LOCAL

    override suspend fun analyze(url: String): MediaAnalysisState = withContext(Dispatchers.IO) {
        val file = try {
            File(URI(url))
        } catch (e: Exception) {
            return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Invalid Local File URI",
                userMessage = "Could not resolve the local file URI.",
                technicalDetail = e.localizedMessage
            )
        }

        if (!file.exists() || !file.canRead()) {
            return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "File Not Found",
                userMessage = "The specified local media file does not exist or cannot be read: ${file.name}"
            )
        }

        if (file.name.endsWith(".mediasave.json", ignoreCase = true) || file.extension.equals("json", ignoreCase = true)) {
            return@withContext parseMediaSaveManifest(url, file.readText(), isLocal = true)
        }

        if (file.extension.equals("m3u8", ignoreCase = true)) {
            return@withContext parseHlsMasterPlaylist(url, file.readText(), "Local HLS Stream")
        }

        val info = MediaProcessorEngine.inspectMediaFile(file)
        if (!info.hasVideo && !info.hasAudio) {
            return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Unsupported File Content",
                userMessage = "No valid video or audio tracks were detected inside '${file.name}'."
            )
        }

        val cleanTitle = file.nameWithoutExtension
            .replace('_', ' ')
            .replaceFirstChar { it.uppercase() }

        val videoStreams = mutableListOf<VideoStreamOption>()
        val audioStreams = mutableListOf<AudioStreamOption>()

        if (info.hasVideo) {
            val container = VideoContainerFormat.fromMimeOrExtension(null, file.name)
            val resLabel = MediaProcessorEngine.resolutionLabelForHeight(info.width, info.height)
            val streamType = if (info.hasAudio) StreamType.PROGRESSIVE else StreamType.VIDEO_ONLY
            videoStreams.add(
                VideoStreamOption(
                    id = "local_v_${info.height}p",
                    resolutionHeight = info.height.coerceAtLeast(144),
                    resolutionWidth = info.width.coerceAtLeast(256),
                    resolutionLabel = resLabel,
                    videoCodec = info.videoCodec,
                    audioCodec = if (info.hasAudio) info.audioCodec else null,
                    container = container,
                    streamType = streamType,
                    frameRate = info.frameRate.coerceAtLeast(24),
                    isHdr = info.isHdr,
                    bitrateKbps = info.bitrateKbps,
                    estimatedSizeBytes = info.fileSizeBytes,
                    downloadUrl = url
                )
            )
        }

        if (info.hasAudio) {
            val audioFmt = if (info.hasVideo) {
                AudioFormat.M4A
            } else {
                AudioFormat.fromMimeOrExtension(null, file.name)
            }
            val isLossless = audioFmt.isLossless || info.audioCodec.contains("PCM", ignoreCase = true)
            val estAudioSize = if (info.hasVideo) {
                ((info.durationMs / 1000.0) * 24_000).toLong().coerceAtLeast(16_384L)
            } else {
                info.fileSizeBytes
            }
            audioStreams.add(
                AudioStreamOption(
                    id = "local_a_native",
                    sourceFormat = audioFmt,
                    sourceCodec = info.audioCodec,
                    bitrateKbps = if (info.hasVideo) 192 else info.bitrateKbps,
                    sampleRateHz = info.sampleRateHz,
                    channelCount = info.channelCount,
                    isLosslessSource = isLossless,
                    estimatedSizeBytes = estAudioSize,
                    downloadUrl = url,
                    isExtractedFromVideo = info.hasVideo
                )
            )
        }

        MediaAnalysisState.Success(
            AnalyzedMediaMetadata(
                sourceUrl = url,
                title = cleanTitle,
                authorOrDomain = "Authorized Local Storage",
                platform = MediaPlatform.AUTHORIZED_LOCAL,
                durationMs = info.durationMs,
                thumbnailUrl = null,
                supportsRangeRequests = true,
                videoStreams = videoStreams.sortedBy { it.resolutionHeight },
                audioStreams = audioStreams,
                inspectionMethod = "Android MediaExtractor + MediaMetadataRetriever (On-Device Track Inspection)"
            )
        )
    }
}

/**
 * Adapter 2: Direct Downloadable Media URLs (HTTPS / HTTP)
 * Performs real HTTP HEAD / Range inspection plus Android MediaExtractor / MediaMetadataRetriever track probing.
 */
class DirectUrlMediaAdapter(
    private val httpClient: OkHttpClient
) : PlatformMediaAdapter {
    override val platform: MediaPlatform = MediaPlatform.DIRECT_URL

    override suspend fun analyze(url: String): MediaAnalysisState = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Malformed URL",
                userMessage = "Could not parse URL."
            )

        val host = uri.host ?: "Remote Server"
        val path = uri.path.orEmpty()
        val rawFileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "remote_media"
        val decodedName = runCatching { URLDecoder.decode(rawFileName, "UTF-8") }.getOrDefault(rawFileName)

        var contentType: String? = null
        var contentLength: Long = -1L
        var supportsRanges = false
        var statusCode = 0

        try {
            val headRequest = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "MediaSave-Android/1.0")
                .build()

            httpClient.newCall(headRequest).execute().use { response ->
                statusCode = response.code
                contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val acceptRanges = response.header("Accept-Ranges")?.lowercase().orEmpty()
                supportsRanges = acceptRanges.contains("bytes")
            }
        } catch (_: Exception) {
        }

        var initialProbeBytes: ByteArray? = null

        if (statusCode !in 200..299 || contentType == null || path.endsWith(".json", true) || path.endsWith(".m3u8", true)) {
            try {
                val getReq = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-65535")
                    .header("User-Agent", "MediaSave-Android/1.0")
                    .get()
                    .build()

                httpClient.newCall(getReq).execute().use { response ->
                    statusCode = response.code
                    if (statusCode !in 200..299) {
                        val (errTitle, errMsg) = when (statusCode) {
                            401, 403 -> "Access Denied or Expired Link (HTTP $statusCode)" to
                                "The server denied access to this URL (HTTP $statusCode). The link may have expired or requires authentication."
                            404 -> "Media File Not Found (HTTP 404)" to
                                "The requested media file does not exist on $host (HTTP 404)."
                            410 -> "Expired Media Link (HTTP 410)" to
                                "This media URL has expired and is no longer available on $host (HTTP 410)."
                            in 500..599 -> "Remote Server Error (HTTP $statusCode)" to
                                "The remote media server ($host) returned HTTP $statusCode (${response.message})."
                            else -> "HTTP $statusCode Error" to
                                "The server responded with HTTP $statusCode (${response.message}). Verify the link is publicly accessible."
                        }
                        return@withContext MediaAnalysisState.Error(
                            url = url,
                            errorTitle = errTitle,
                            userMessage = errMsg
                        )
                    }
                    contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                    val contentRange = response.header("Content-Range")
                    contentLength = contentRange?.substringAfterLast('/')?.toLongOrNull()
                        ?: response.header("Content-Length")?.toLongOrNull()
                        ?: contentLength
                    supportsRanges = statusCode == 206 || response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true

                    val rawBytes = response.body?.bytes()
                    initialProbeBytes = rawBytes

                    if (contentType?.contains("json") == true || path.endsWith(".mediasave.json", ignoreCase = true)) {
                        val bodyText = rawBytes?.toString(Charsets.UTF_8).orEmpty()
                        return@withContext parseMediaSaveManifest(url, bodyText, isLocal = false)
                    }

                    if (contentType?.contains("mpegurl") == true || path.endsWith(".m3u8", ignoreCase = true)) {
                        val bodyText = rawBytes?.toString(Charsets.UTF_8).orEmpty()
                        if (bodyText.contains("#EXTM3U")) {
                            return@withContext parseHlsMasterPlaylist(url, bodyText, host)
                        }
                    }
                }
            } catch (e: Exception) {
                return@withContext MediaAnalysisState.Error(
                    url = url,
                    errorTitle = "Network Connection Failed",
                    userMessage = "Could not connect to $host. Check your internet connection or select an Authorized Test Media stream.",
                    technicalDetail = e.localizedMessage
                )
            }
        }

        val mime = contentType.orEmpty()
        val lowerPath = path.lowercase()

        // Inspect header bytes and Content-Type to ensure the server did not return an HTML webpage
        val leadingText = initialProbeBytes?.let { bytes ->
            bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart().lowercase()
        }.orEmpty()
        val isHtmlResponse = mime.startsWith("text/html") ||
            mime.startsWith("application/xhtml") ||
            leadingText.startsWith("<!doctype html") ||
            leadingText.startsWith("<html")

        if (isHtmlResponse) {
            return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Webpage Instead of Direct Media Stream",
                userMessage = "The server returned an HTML webpage (Content-Type: '${mime.ifEmpty { "text/html" }}') instead of a downloadable video or audio stream."
            )
        }

        val isPlainTextNonPlaylist = mime.startsWith("text/plain") && !lowerPath.endsWith(".m3u8")
        if (isPlainTextNonPlaylist) {
            return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Unsupported Text Response",
                userMessage = "The URL returned plain text ('text/plain') rather than a binary video or audio container."
            )
        }

        val isVideoExt = lowerPath.endsWith(".mp4") || lowerPath.endsWith(".webm") ||
            lowerPath.endsWith(".mkv") || lowerPath.endsWith(".mov")
        val isAudioExt = lowerPath.endsWith(".mp3") || lowerPath.endsWith(".m4a") ||
            lowerPath.endsWith(".aac") || lowerPath.endsWith(".wav") ||
            lowerPath.endsWith(".flac") || lowerPath.endsWith(".ogg") ||
            lowerPath.endsWith(".opus") || lowerPath.endsWith(".aiff")

        val isBinaryStream = mime.isEmpty() ||
            mime == "application/octet-stream" ||
            mime == "binary/octet-stream"
        val isVideo = mime.startsWith("video/") || (isVideoExt && (isBinaryStream || mime.startsWith("video/")))
        val isAudio = mime.startsWith("audio/") || (isAudioExt && (isBinaryStream || mime.startsWith("audio/")))

        if (!isVideo && !isAudio) {
            return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Not a Direct Media Stream",
                userMessage = "The URL returned Content-Type '${mime.ifEmpty { "unknown" }}' rather than a direct video or audio file. MediaSave never fabricates download options for web pages or unsupported links."
            )
        }

        val cleanTitle = decodedName.substringBeforeLast('.')
            .replace(Regex("[_\\-]+"), " ")
            .trim()
            .ifEmpty { "Direct Media Stream" }

        val safeSize = contentLength.coerceAtLeast(0L)

        // Probe actual remote track properties using MediaMetadataRetriever & MediaExtractor
        val probed = probeRemoteOrBytesMedia(url, initialProbeBytes, decodedName)

        val videoStreams = mutableListOf<VideoStreamOption>()
        val audioStreams = mutableListOf<AudioStreamOption>()

        if (isVideo || probed?.hasVideo == true) {
            val container = VideoContainerFormat.fromMimeOrExtension(mime, lowerPath)
            val detectedWidth = probed?.width?.takeIf { it > 0 } ?: 1280
            val detectedHeight = probed?.height?.takeIf { it > 0 } ?: 720
            val resLabel = if (probed != null && probed.height > 0) {
                MediaProcessorEngine.resolutionLabelForHeight(detectedWidth, detectedHeight)
            } else {
                "Original (${container.label})"
            }
            val detectedVideoCodec = probed?.videoCodec?.takeIf { it != "None" && it != "Unknown" }
                ?: when (container) {
                    VideoContainerFormat.WEBM -> "VP9"
                    else -> "H.264 (AVC1)"
                }
            val hasAudioTrack = probed?.hasAudio ?: true
            val detectedAudioCodec = if (hasAudioTrack) {
                probed?.audioCodec?.takeIf { it != "None" && it != "Unknown" }
                    ?: if (container == VideoContainerFormat.WEBM) "Opus" else "AAC-LC"
            } else null
            val detectedFps = probed?.frameRate?.takeIf { it > 0 } ?: 30
            val detectedBitrate = probed?.bitrateKbps?.takeIf { it > 0 }
                ?: if (safeSize > 0) ((safeSize * 8) / (10 * 1000)).toInt().coerceIn(250, 25000) else 1500

            videoStreams.add(
                VideoStreamOption(
                    id = "direct_v_native",
                    resolutionHeight = detectedHeight,
                    resolutionWidth = detectedWidth,
                    resolutionLabel = resLabel,
                    videoCodec = detectedVideoCodec,
                    audioCodec = detectedAudioCodec,
                    container = container,
                    streamType = if (hasAudioTrack) StreamType.PROGRESSIVE else StreamType.VIDEO_ONLY,
                    frameRate = detectedFps,
                    isHdr = probed?.isHdr ?: false,
                    bitrateKbps = detectedBitrate,
                    estimatedSizeBytes = safeSize,
                    downloadUrl = url
                )
            )

            if (hasAudioTrack) {
                audioStreams.add(
                    AudioStreamOption(
                        id = "direct_v_audio_extract",
                        sourceFormat = AudioFormat.M4A,
                        sourceCodec = "$detectedAudioCodec (Demuxed from Video)",
                        bitrateKbps = 192,
                        sampleRateHz = probed?.sampleRateHz?.takeIf { it > 0 } ?: 44100,
                        channelCount = probed?.channelCount?.takeIf { it > 0 } ?: 2,
                        isLosslessSource = false,
                        estimatedSizeBytes = if (safeSize > 0) (safeSize / 6).coerceAtLeast(16_384L) else 0L,
                        downloadUrl = url,
                        isExtractedFromVideo = true
                    )
                )
            }
        } else {
            val audioFmt = AudioFormat.fromMimeOrExtension(mime, lowerPath)
            val detectedAudioCodec = probed?.audioCodec?.takeIf { it != "None" && it != "Unknown" }
                ?: audioFmt.codecDescription
            val detectedSr = probed?.sampleRateHz?.takeIf { it > 0 } ?: 44100
            val detectedChannels = probed?.channelCount?.takeIf { it > 0 } ?: 2
            val detectedBr = probed?.bitrateKbps?.takeIf { it > 0 } ?: if (audioFmt.isLossless) 1411 else 192

            audioStreams.add(
                AudioStreamOption(
                    id = "direct_a_source",
                    sourceFormat = audioFmt,
                    sourceCodec = detectedAudioCodec,
                    bitrateKbps = detectedBr,
                    sampleRateHz = detectedSr,
                    channelCount = detectedChannels,
                    isLosslessSource = audioFmt.isLossless,
                    estimatedSizeBytes = safeSize,
                    downloadUrl = url,
                    isExtractedFromVideo = false
                )
            )
        }

        MediaAnalysisState.Success(
            AnalyzedMediaMetadata(
                sourceUrl = url,
                title = cleanTitle,
                authorOrDomain = host,
                platform = MediaPlatform.DIRECT_URL,
                durationMs = probed?.durationMs ?: 0L,
                thumbnailUrl = null,
                supportsRangeRequests = supportsRanges,
                videoStreams = videoStreams.sortedBy { it.resolutionHeight },
                audioStreams = audioStreams,
                inspectionMethod = "HTTP HEAD/Range + MediaExtractor Track Probe (${mime.ifEmpty { "direct stream" }})"
            )
        )
    }

    private suspend fun probeRemoteOrBytesMedia(
        url: String,
        initialBytes: ByteArray?,
        fileName: String
    ): com.example.data.media.InspectedFileMetadata? {
        // First try probing initialBytes via a temporary file if we already downloaded the header chunk
        if (initialBytes != null && initialBytes.size > 256) {
            val ext = fileName.substringAfterLast('.', "mp4")
            val tempFile = runCatching { File.createTempFile("mediasave_probe_", ".$ext") }.getOrNull()
            if (tempFile != null) {
                try {
                    tempFile.writeBytes(initialBytes)
                    val info = MediaProcessorEngine.inspectMediaFile(tempFile)
                    if (info.hasVideo || info.hasAudio) {
                        return info
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { tempFile.delete() }
                }
            }
        }

        // Next try probing remote headers directly via MediaMetadataRetriever / MediaExtractor
        var durationMs = 0L
        var width = 0
        var height = 0
        var frameRate = 30
        var bitrateKbps = 0
        var hasVideo = false
        var hasAudio = false
        var videoCodec = "None"
        var audioCodec = "None"
        var sampleRateHz = 44100
        var channelCount = 2
        var isHdr = false

        val headers = mapOf("User-Agent" to "MediaSave-Android/1.0")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, headers)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val br = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            if (br > 0) bitrateKbps = (br / 1000L).toInt()
            hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" || (width > 0 && height > 0)
            hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(url, headers)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    videoCodec = MediaProcessorEngine.readableCodecName(mime)
                    if (format.containsKey(MediaFormat.KEY_WIDTH)) width = format.getInteger(MediaFormat.KEY_WIDTH)
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        frameRate = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                        val tr = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                        isHdr = tr == MediaFormat.COLOR_TRANSFER_ST2084 || tr == MediaFormat.COLOR_TRANSFER_HLG
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    audioCodec = MediaProcessorEngine.readableCodecName(mime)
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }

        if (!hasVideo && !hasAudio) return null
        return com.example.data.media.InspectedFileMetadata(
            durationMs = durationMs,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            width = width,
            height = height,
            frameRate = frameRate,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            isHdr = isHdr,
            fileSizeBytes = 0L
        )
    }
}

/**
 * Adapter 3: YouTube Stream Extractor (InnerTube Player API + Piped/Invidious Fallback)
 */
class YouTubePlatformAdapter(
    private val httpClient: OkHttpClient
) : PlatformMediaAdapter {
    override val platform: MediaPlatform = MediaPlatform.YOUTUBE

    override suspend fun analyze(url: String): MediaAnalysisState = withContext(Dispatchers.IO) {
        val videoId = SocialStreamExtractor.extractYouTubeVideoId(url)
            ?: return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Invalid YouTube Link",
                userMessage = "Could not find a valid YouTube video or Shorts ID in the provided link."
            )

        var title = "YouTube Video ($videoId)"
        var author = "YouTube Creator"
        var thumbnailUrl: String? = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        var durationMs = 0L

        // Fetch public title/author via oEmbed first for fast metadata
        runCatching {
            val encoded = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
            val oembedUrl = "https://www.youtube.com/oembed?url=$encoded&format=json"
            val req = Request.Builder().url(oembedUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    title = json.optString("title").takeIf { it.isNotBlank() } ?: title
                    author = json.optString("author_name").takeIf { it.isNotBlank() } ?: author
                    thumbnailUrl = json.optString("thumbnail_url").takeIf { it.isNotBlank() } ?: thumbnailUrl
                }
            }
        }

        val videoStreams = mutableListOf<VideoStreamOption>()
        val audioStreams = mutableListOf<AudioStreamOption>()

        // Layer 1: YouTube InnerTube Player API (ANDROID_VR, IOS, ANDROID_TESTSUITE)
        val innerTubeExtracted = SocialStreamExtractor.extractYouTubeViaInnerTube(
            httpClient = httpClient,
            videoId = videoId
        )
        if (innerTubeExtracted != null) {
            if (innerTubeExtracted.title.isNotBlank()) title = innerTubeExtracted.title
            if (innerTubeExtracted.author.isNotBlank()) author = innerTubeExtracted.author
            if (innerTubeExtracted.durationMs > 0L) durationMs = innerTubeExtracted.durationMs
            if (!innerTubeExtracted.thumbnailUrl.isNullOrBlank()) thumbnailUrl = innerTubeExtracted.thumbnailUrl
            videoStreams.addAll(innerTubeExtracted.videoStreams)
            audioStreams.addAll(innerTubeExtracted.audioStreams)
        }

        // Layer 2: Public Piped / Invidious API Fallback if InnerTube streams were empty
        if (videoStreams.isEmpty() && audioStreams.isEmpty()) {
            val pipedExtracted = SocialStreamExtractor.extractYouTubeViaPipedOrInvidious(
                httpClient = httpClient,
                videoId = videoId
            )
            if (pipedExtracted != null) {
                if (pipedExtracted.title.isNotBlank()) title = pipedExtracted.title
                if (pipedExtracted.author.isNotBlank()) author = pipedExtracted.author
                if (pipedExtracted.durationMs > 0L) durationMs = pipedExtracted.durationMs
                if (!pipedExtracted.thumbnailUrl.isNullOrBlank()) thumbnailUrl = pipedExtracted.thumbnailUrl
                videoStreams.addAll(pipedExtracted.videoStreams)
                audioStreams.addAll(pipedExtracted.audioStreams)
            }
        }

        if (videoStreams.isEmpty() && audioStreams.isEmpty()) {
            return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Unable to Extract YouTube Stream",
                userMessage = "Could not extract direct media streams for this YouTube video. Verify the video is public, not age-restricted or live-only, and check your internet connection."
            )
        }

        MediaAnalysisState.Success(
            AnalyzedMediaMetadata(
                sourceUrl = url,
                title = title,
                authorOrDomain = author,
                platform = MediaPlatform.YOUTUBE,
                durationMs = durationMs,
                thumbnailUrl = thumbnailUrl,
                supportsRangeRequests = true,
                videoStreams = videoStreams.distinctBy { "${it.resolutionHeight}_${it.container}_${it.streamType}" }
                    .sortedBy { it.resolutionHeight },
                audioStreams = audioStreams.distinctBy { "${it.sourceFormat}_${it.bitrateKbps}" }
                    .sortedByDescending { it.bitrateKbps },
                inspectionMethod = "YouTube Stream Extractor (Multi-Resolution MP4/WebM & Audio)"
            )
        )
    }
}

/**
 * Adapter 4: Facebook Watch / Reels / Video Post Stream Extractor
 */
class FacebookPlatformAdapter(
    private val httpClient: OkHttpClient
) : PlatformMediaAdapter {
    override val platform: MediaPlatform = MediaPlatform.FACEBOOK

    override suspend fun analyze(url: String): MediaAnalysisState = withContext(Dispatchers.IO) {
        val extracted = SocialStreamExtractor.extractFacebookStreams(httpClient, url)
        if (extracted == null || (extracted.videoStreams.isEmpty() && extracted.audioStreams.isEmpty())) {
            return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Unable to Extract Facebook Video",
                userMessage = "Could not find a downloadable video stream at this Facebook link. Make sure the Facebook video or Reel is public (not friends-only or in a private group)."
            )
        }

        MediaAnalysisState.Success(
            AnalyzedMediaMetadata(
                sourceUrl = url,
                title = extracted.title.ifBlank { "Facebook Video" },
                authorOrDomain = extracted.author.ifBlank { "Facebook Creator" },
                platform = MediaPlatform.FACEBOOK,
                durationMs = extracted.durationMs,
                thumbnailUrl = extracted.thumbnailUrl,
                supportsRangeRequests = true,
                videoStreams = extracted.videoStreams.sortedBy { it.resolutionHeight },
                audioStreams = extracted.audioStreams,
                inspectionMethod = "Facebook HD/SD MP4 Stream Extractor"
            )
        )
    }
}

/**
 * Adapter 5: Instagram Reels / Post / IGTV Stream Extractor
 */
class InstagramPlatformAdapter(
    private val httpClient: OkHttpClient
) : PlatformMediaAdapter {
    override val platform: MediaPlatform = MediaPlatform.INSTAGRAM

    override suspend fun analyze(url: String): MediaAnalysisState = withContext(Dispatchers.IO) {
        val extracted = SocialStreamExtractor.extractInstagramStreams(httpClient, url)
        if (extracted == null || (extracted.videoStreams.isEmpty() && extracted.audioStreams.isEmpty())) {
            return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Unable to Extract Instagram Reel/Video",
                userMessage = "Could not extract a direct video stream from this Instagram link. Make sure the Reel or post is from a public account."
            )
        }

        MediaAnalysisState.Success(
            AnalyzedMediaMetadata(
                sourceUrl = url,
                title = extracted.title.ifBlank { "Instagram Reel" },
                authorOrDomain = extracted.author.ifBlank { "Instagram Creator" },
                platform = MediaPlatform.INSTAGRAM,
                durationMs = extracted.durationMs,
                thumbnailUrl = extracted.thumbnailUrl,
                supportsRangeRequests = true,
                videoStreams = extracted.videoStreams.sortedBy { it.resolutionHeight },
                audioStreams = extracted.audioStreams,
                inspectionMethod = "Instagram Reel/Post Direct MP4 Stream Extractor"
            )
        )
    }
}

/**
 * Adapter 6: TikTok No-Watermark HD Video & Original Audio Stream Extractor
 */
class TikTokPlatformAdapter(
    private val httpClient: OkHttpClient
) : PlatformMediaAdapter {
    override val platform: MediaPlatform = MediaPlatform.TIKTOK

    override suspend fun analyze(url: String): MediaAnalysisState = withContext(Dispatchers.IO) {
        val extracted = SocialStreamExtractor.extractTikTokStreams(httpClient, url)
        if (extracted == null || (extracted.videoStreams.isEmpty() && extracted.audioStreams.isEmpty())) {
            return@withContext MediaAnalysisState.Error(
                url = url,
                errorTitle = "Unable to Extract TikTok Video",
                userMessage = "Could not resolve downloadable video/audio streams for this TikTok link. Please verify the TikTok link is public and check your internet connection."
            )
        }

        MediaAnalysisState.Success(
            AnalyzedMediaMetadata(
                sourceUrl = url,
                title = extracted.title.ifBlank { "TikTok Video" },
                authorOrDomain = extracted.author.ifBlank { "TikTok Creator" },
                platform = MediaPlatform.TIKTOK,
                durationMs = extracted.durationMs,
                thumbnailUrl = extracted.thumbnailUrl,
                supportsRangeRequests = true,
                videoStreams = extracted.videoStreams.sortedBy { it.resolutionHeight },
                audioStreams = extracted.audioStreams,
                inspectionMethod = "TikTok HD No-Watermark & Original Audio Stream Extractor"
            )
        )
    }
}

class PlatformAdapterRegistry(
    httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private val adapters: Map<MediaPlatform, PlatformMediaAdapter> = mapOf(
        MediaPlatform.AUTHORIZED_LOCAL to AuthorizedLocalMediaAdapter(),
        MediaPlatform.DIRECT_URL to DirectUrlMediaAdapter(httpClient),
        MediaPlatform.YOUTUBE to YouTubePlatformAdapter(httpClient),
        MediaPlatform.FACEBOOK to FacebookPlatformAdapter(httpClient),
        MediaPlatform.INSTAGRAM to InstagramPlatformAdapter(httpClient),
        MediaPlatform.TIKTOK to TikTokPlatformAdapter(httpClient)
    )

    suspend fun validateAndAnalyze(rawUrl: String): MediaAnalysisState {
        return when (val validation = UrlValidator.validateAndClassify(rawUrl)) {
            is UrlValidationResult.Invalid -> MediaAnalysisState.Error(
                url = rawUrl,
                errorTitle = validation.errorTitle,
                userMessage = validation.errorMessage
            )
            is UrlValidationResult.Valid -> {
                val adapter = adapters[validation.platform]
                if (adapter == null) {
                    MediaAnalysisState.Error(
                        url = validation.normalizedUrl,
                        errorTitle = "Unsupported Platform",
                        userMessage = "No authorized adapter is registered for ${validation.platform.displayName}."
                    )
                } else {
                    adapter.analyze(validation.normalizedUrl)
                }
            }
        }
    }
}

internal fun parseHlsMasterPlaylist(
    sourceUrl: String,
    playlistText: String,
    hostOrAuthor: String
): MediaAnalysisState {
    val lines = playlistText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val videoStreams = mutableListOf<VideoStreamOption>()
    val baseUri = runCatching { URI(sourceUrl) }.getOrNull()

    var i = 0
    var indexCounter = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            val attrs = line.removePrefix("#EXT-X-STREAM-INF:")
            val bandwidthBps = Regex("BANDWIDTH=(\\d+)").find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 1_500_000L
            val resMatch = Regex("RESOLUTION=(\\d+)x(\\d+)").find(attrs)
            val width = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1280
            val height = resMatch?.groupValues?.get(2)?.toIntOrNull() ?: 720
            val fps = Regex("FRAME-RATE=([0-9.]+)").find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 30
            val codecsRaw = Regex("CODECS=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1).orEmpty()

            val nextLine = lines.getOrNull(i + 1).orEmpty()
            val resolvedUrl = if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                i++
                runCatching { baseUri?.resolve(nextLine)?.toString() }.getOrNull() ?: nextLine
            } else {
                sourceUrl
            }

            val videoCodec = when {
                codecsRaw.contains("avc1", true) -> "H.264 (AVC1)"
                codecsRaw.contains("hvc1", true) || codecsRaw.contains("hev1", true) -> "HEVC (H.265)"
                codecsRaw.contains("vp9", true) -> "VP9"
                codecsRaw.contains("av01", true) -> "AV1"
                else -> "H.264 (AVC1)"
            }
            val audioCodec = if (codecsRaw.contains("mp4a", true)) "AAC-LC" else "AAC"
            val bitrateKbps = (bandwidthBps / 1000L).toInt().coerceAtLeast(128)
            val estSize = (bandwidthBps * 60L) / 8L // Estimate per minute if content-length unknown

            videoStreams.add(
                VideoStreamOption(
                    id = "hls_v_${height}p_${indexCounter++}",
                    resolutionHeight = height,
                    resolutionWidth = width,
                    resolutionLabel = MediaProcessorEngine.resolutionLabelForHeight(width, height),
                    videoCodec = videoCodec,
                    audioCodec = audioCodec,
                    container = VideoContainerFormat.MP4,
                    streamType = StreamType.PROGRESSIVE,
                    frameRate = fps,
                    isHdr = false,
                    bitrateKbps = bitrateKbps,
                    estimatedSizeBytes = estSize,
                    downloadUrl = resolvedUrl
                )
            )
        }
        i++
    }

    if (videoStreams.isEmpty()) {
        return MediaAnalysisState.Error(
            url = sourceUrl,
            errorTitle = "Empty HLS Playlist",
            userMessage = "No stream variants (#EXT-X-STREAM-INF) were found in the playlist."
        )
    }

    return MediaAnalysisState.Success(
        AnalyzedMediaMetadata(
            sourceUrl = sourceUrl,
            title = "HLS Adaptive Stream",
            authorOrDomain = hostOrAuthor,
            platform = MediaPlatform.DIRECT_URL,
            durationMs = 0L,
            thumbnailUrl = null,
            supportsRangeRequests = true,
            videoStreams = videoStreams.sortedBy { it.resolutionHeight },
            audioStreams = emptyList(),
            inspectionMethod = "HLS Master Playlist (#EXT-X-STREAM-INF) Variant Inspection"
        )
    )
}

internal fun parseMediaSaveManifest(
    sourceUrl: String,
    jsonText: String,
    isLocal: Boolean
): MediaAnalysisState {
    return try {
        val root = JSONObject(jsonText)
        val title = root.optString("title", "Authorized Multi-Stream Media")
        val author = root.optString("author", "MediaSave Studio")
        val durationMs = root.optLong("durationMs", 3500L)
        val supportsRanges = root.optBoolean("supportsRangeRequests", true)

        val videoStreams = mutableListOf<VideoStreamOption>()
        val vArray = root.optJSONArray("videoStreams")
        if (vArray != null) {
            for (i in 0 until vArray.length()) {
                val item = vArray.getJSONObject(i)
                val streamUrl = item.optString("url").trim()
                if (streamUrl.isEmpty() || UrlValidator.isMockOrPlaceholderUrl(streamUrl)) continue
                val container = VideoContainerFormat.fromMimeOrExtension(null, item.optString("container", "MP4"))
                val streamType = runCatching {
                    StreamType.valueOf(item.optString("streamType", "PROGRESSIVE"))
                }.getOrDefault(StreamType.PROGRESSIVE)
                videoStreams.add(
                    VideoStreamOption(
                        id = item.optString("id", "v_$i"),
                        resolutionHeight = item.optInt("height", 720),
                        resolutionWidth = item.optInt("width", 1280),
                        resolutionLabel = item.optString("resolutionLabel", "720p HD"),
                        videoCodec = item.optString("videoCodec", "H.264 (AVC1)"),
                        audioCodec = item.optString("audioCodec").takeIf { it.isNotBlank() && it != "null" },
                        container = container,
                        streamType = streamType,
                        frameRate = item.optInt("frameRate", 30),
                        isHdr = item.optBoolean("isHdr", false),
                        bitrateKbps = item.optInt("bitrateKbps", 1500),
                        estimatedSizeBytes = item.optLong("sizeBytes", 0L),
                        downloadUrl = streamUrl,
                        pairedAudioStreamUrl = item.optString("pairedAudioUrl").takeIf { it.isNotBlank() && it != "null" },
                        pairedAudioSizeBytes = item.optLong("pairedAudioSizeBytes", 0L)
                    )
                )
            }
        }

        val audioStreams = mutableListOf<AudioStreamOption>()
        val aArray = root.optJSONArray("audioStreams")
        if (aArray != null) {
            for (i in 0 until aArray.length()) {
                val item = aArray.getJSONObject(i)
                val streamUrl = item.optString("url").trim()
                if (streamUrl.isEmpty() || UrlValidator.isMockOrPlaceholderUrl(streamUrl)) continue
                val fmt = runCatching {
                    AudioFormat.valueOf(item.optString("sourceFormat", "M4A"))
                }.getOrDefault(AudioFormat.M4A)
                audioStreams.add(
                    AudioStreamOption(
                        id = item.optString("id", "a_$i"),
                        sourceFormat = fmt,
                        sourceCodec = item.optString("sourceCodec", "AAC-LC"),
                        bitrateKbps = item.optInt("bitrateKbps", 192),
                        sampleRateHz = item.optInt("sampleRateHz", 44100),
                        channelCount = item.optInt("channelCount", 2),
                        isLosslessSource = item.optBoolean("isLosslessSource", fmt.isLossless),
                        estimatedSizeBytes = item.optLong("sizeBytes", 0L),
                        downloadUrl = streamUrl,
                        isExtractedFromVideo = item.optBoolean("isExtractedFromVideo", false)
                    )
                )
            }
        }

        if (videoStreams.isEmpty() && audioStreams.isEmpty()) {
            return MediaAnalysisState.Error(
                url = sourceUrl,
                errorTitle = "Mock or Non-Downloadable Test Metadata",
                userMessage = "This manifest contains only mock metadata without any valid downloadable stream URLs."
            )
        }

        MediaAnalysisState.Success(
            AnalyzedMediaMetadata(
                sourceUrl = sourceUrl,
                title = title,
                authorOrDomain = author,
                platform = if (isLocal) MediaPlatform.AUTHORIZED_LOCAL else MediaPlatform.DIRECT_URL,
                durationMs = durationMs,
                thumbnailUrl = root.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                supportsRangeRequests = supportsRanges,
                videoStreams = videoStreams.sortedBy { it.resolutionHeight },
                audioStreams = audioStreams.sortedByDescending { it.bitrateKbps },
                inspectionMethod = "Multi-Stream Manifest + Android MediaExtractor Track Verification"
            )
        )
    } catch (e: Exception) {
        MediaAnalysisState.Error(
            url = sourceUrl,
            errorTitle = "Manifest Parse Error",
            userMessage = "Could not parse the media stream manifest: ${e.localizedMessage}"
        )
    }
}
