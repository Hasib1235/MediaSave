package com.example.data.platform

import com.example.data.media.MediaProcessorEngine
import com.example.domain.model.AudioFormat
import com.example.domain.model.AudioStreamOption
import com.example.domain.model.StreamType
import com.example.domain.model.VideoContainerFormat
import com.example.domain.model.VideoStreamOption
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class ExtractedSocialStreams(
    val title: String,
    val author: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val videoStreams: List<VideoStreamOption>,
    val audioStreams: List<AudioStreamOption>
)

object SocialStreamExtractor {

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // =========================================================================
    // 1. TIKTOK STREAM EXTRACTOR
    // =========================================================================

    fun extractTikTokStreams(httpClient: OkHttpClient, url: String): ExtractedSocialStreams? {
        // Layer 1: TikWM API (supports vt.tiktok.com, vm.tiktok.com, www.tiktok.com, HD no-watermark + MP3)
        extractTikTokViaTikWm(httpClient, url)?.let { if (it.videoStreams.isNotEmpty() || it.audioStreams.isNotEmpty()) return it }

        // Layer 2: TiklyDown API fallback
        extractTikTokViaTiklyDown(httpClient, url)?.let { if (it.videoStreams.isNotEmpty() || it.audioStreams.isNotEmpty()) return it }

        // Layer 3: Direct TikTok Webpage Embedded State (__UNIVERSAL_DATA_FOR_REHYDRATION__ / SIGI_STATE)
        return extractTikTokFromWebPage(httpClient, url)
    }

    private fun extractTikTokViaTikWm(httpClient: OkHttpClient, url: String): ExtractedSocialStreams? {
        return try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string().orEmpty()
                val root = JSONObject(bodyStr)
                if (root.optInt("code", -1) != 0) return null
                val data = root.optJSONObject("data") ?: return null

                val title = data.optString("title").takeIf { it.isNotBlank() }
                    ?: "TikTok Video ${data.optString("id")}".trim()
                val authorObj = data.optJSONObject("author")
                val author = authorObj?.optString("nickname")?.takeIf { it.isNotBlank() }
                    ?: authorObj?.optString("unique_id")?.takeIf { it.isNotBlank() }
                    ?: "TikTok Creator"
                val durationSec = data.optLong("duration", 15L).coerceAtLeast(1L)
                val durationMs = durationSec * 1000L
                val cover = resolveTikwmUrl(
                    data.optString("origin_cover").ifBlank { data.optString("cover") }
                )

                val hdUrl = resolveTikwmUrl(data.optString("hdplay"))
                val playUrl = resolveTikwmUrl(data.optString("play"))
                val wmUrl = resolveTikwmUrl(data.optString("wmplay"))
                val musicUrl = resolveTikwmUrl(data.optString("music"))

                val hdSize = data.optLong("hd_size", 0L)
                val playSize = data.optLong("size", 0L)
                val wmSize = data.optLong("wm_size", 0L)

                val videoStreams = mutableListOf<VideoStreamOption>()
                val audioStreams = mutableListOf<AudioStreamOption>()

                if (!hdUrl.isNullOrBlank()) {
                    val estSize = if (hdSize > 0) hdSize else (durationSec * 350_000L)
                    videoStreams.add(
                        VideoStreamOption(
                            id = "tiktok_1080p_hd",
                            resolutionHeight = 1080,
                            resolutionWidth = 1080,
                            resolutionLabel = "1080p FHD (No Watermark)",
                            videoCodec = "H.264 (AVC1)",
                            audioCodec = "AAC-LC",
                            container = VideoContainerFormat.MP4,
                            streamType = StreamType.PROGRESSIVE,
                            frameRate = 30,
                            isHdr = false,
                            bitrateKbps = ((estSize * 8) / (durationSec * 1000)).toInt().coerceIn(800, 12000),
                            estimatedSizeBytes = estSize,
                            downloadUrl = hdUrl
                        )
                    )
                }

                if (!playUrl.isNullOrBlank() && playUrl != hdUrl) {
                    val estSize = if (playSize > 0) playSize else (durationSec * 180_000L)
                    videoStreams.add(
                        VideoStreamOption(
                            id = "tiktok_720p_nowm",
                            resolutionHeight = 720,
                            resolutionWidth = 720,
                            resolutionLabel = "720p HD (No Watermark)",
                            videoCodec = "H.264 (AVC1)",
                            audioCodec = "AAC-LC",
                            container = VideoContainerFormat.MP4,
                            streamType = StreamType.PROGRESSIVE,
                            frameRate = 30,
                            isHdr = false,
                            bitrateKbps = ((estSize * 8) / (durationSec * 1000)).toInt().coerceIn(500, 8000),
                            estimatedSizeBytes = estSize,
                            downloadUrl = playUrl
                        )
                    )
                }

                if (!wmUrl.isNullOrBlank() && wmUrl != playUrl) {
                    val estSize = if (wmSize > 0) wmSize else (durationSec * 140_000L)
                    videoStreams.add(
                        VideoStreamOption(
                            id = "tiktok_540p_wm",
                            resolutionHeight = 540,
                            resolutionWidth = 540,
                            resolutionLabel = "540p SD (Watermarked)",
                            videoCodec = "H.264 (AVC1)",
                            audioCodec = "AAC-LC",
                            container = VideoContainerFormat.MP4,
                            streamType = StreamType.PROGRESSIVE,
                            frameRate = 30,
                            isHdr = false,
                            bitrateKbps = ((estSize * 8) / (durationSec * 1000)).toInt().coerceIn(400, 6000),
                            estimatedSizeBytes = estSize,
                            downloadUrl = wmUrl
                        )
                    )
                }

                if (!musicUrl.isNullOrBlank()) {
                    val estAudioBytes = (durationSec * 24_000L).coerceAtLeast(32_768L)
                    audioStreams.add(
                        AudioStreamOption(
                            id = "tiktok_audio_original",
                            sourceFormat = AudioFormat.MP3,
                            sourceCodec = "MP3 / AAC Original Sound",
                            bitrateKbps = 192,
                            sampleRateHz = 44100,
                            channelCount = 2,
                            isLosslessSource = false,
                            estimatedSizeBytes = estAudioBytes,
                            downloadUrl = musicUrl,
                            isExtractedFromVideo = false
                        )
                    )
                }

                val fallbackVideoForAudio = playUrl ?: hdUrl ?: wmUrl
                if (!fallbackVideoForAudio.isNullOrBlank()) {
                    val estAudioBytes = (durationSec * 24_000L).coerceAtLeast(32_768L)
                    audioStreams.add(
                        AudioStreamOption(
                            id = "tiktok_audio_demux",
                            sourceFormat = AudioFormat.M4A,
                            sourceCodec = "AAC-LC (Demuxed from Video)",
                            bitrateKbps = 192,
                            sampleRateHz = 44100,
                            channelCount = 2,
                            isLosslessSource = false,
                            estimatedSizeBytes = estAudioBytes,
                            downloadUrl = fallbackVideoForAudio,
                            isExtractedFromVideo = true
                        )
                    )
                }

                ExtractedSocialStreams(
                    title = title,
                    author = author,
                    durationMs = durationMs,
                    thumbnailUrl = cover,
                    videoStreams = videoStreams,
                    audioStreams = audioStreams
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTikwmUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null") return null
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> "https://www.tikwm.com$trimmed"
            else -> "https://www.tikwm.com/$trimmed"
        }
    }

    private fun extractTikTokViaTiklyDown(httpClient: OkHttpClient, url: String): ExtractedSocialStreams? {
        return try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val req = Request.Builder()
                .url("https://api.tiklydown.eu.org/api/download?url=$encoded")
                .header("User-Agent", MOBILE_USER_AGENT)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                val title = json.optString("title", "TikTok Video")
                val author = json.optJSONObject("author")?.optString("name") ?: "TikTok Creator"
                val videoObj = json.optJSONObject("video") ?: return null
                val noWm = videoObj.optString("noWatermark").takeIf { it.startsWith("http") }
                val wm = videoObj.optString("watermark").takeIf { it.startsWith("http") }
                val musicUrl = json.optJSONObject("music")?.optString("play_url")?.takeIf { it.startsWith("http") }
                val cover = videoObj.optString("cover").takeIf { it.startsWith("http") }
                val durationSec = videoObj.optLong("duration", 15L).coerceAtLeast(1L)

                val videos = mutableListOf<VideoStreamOption>()
                val audios = mutableListOf<AudioStreamOption>()

                if (noWm != null) {
                    videos.add(
                        VideoStreamOption(
                            id = "tikly_720p_nowm",
                            resolutionHeight = 720,
                            resolutionWidth = 720,
                            resolutionLabel = "720p HD (No Watermark)",
                            videoCodec = "H.264 (AVC1)",
                            audioCodec = "AAC-LC",
                            container = VideoContainerFormat.MP4,
                            streamType = StreamType.PROGRESSIVE,
                            frameRate = 30,
                            isHdr = false,
                            bitrateKbps = 1500,
                            estimatedSizeBytes = durationSec * 180_000L,
                            downloadUrl = noWm
                        )
                    )
                }
                if (wm != null && wm != noWm) {
                    videos.add(
                        VideoStreamOption(
                            id = "tikly_540p_wm",
                            resolutionHeight = 540,
                            resolutionWidth = 540,
                            resolutionLabel = "540p SD (Watermarked)",
                            videoCodec = "H.264 (AVC1)",
                            audioCodec = "AAC-LC",
                            container = VideoContainerFormat.MP4,
                            streamType = StreamType.PROGRESSIVE,
                            frameRate = 30,
                            isHdr = false,
                            bitrateKbps = 1100,
                            estimatedSizeBytes = durationSec * 140_000L,
                            downloadUrl = wm
                        )
                    )
                }
                if (musicUrl != null) {
                    audios.add(
                        AudioStreamOption(
                            id = "tikly_audio",
                            sourceFormat = AudioFormat.MP3,
                            sourceCodec = "MP3 Original Audio",
                            bitrateKbps = 192,
                            sampleRateHz = 44100,
                            channelCount = 2,
                            isLosslessSource = false,
                            estimatedSizeBytes = durationSec * 24_000L,
                            downloadUrl = musicUrl,
                            isExtractedFromVideo = false
                        )
                    )
                }
                ExtractedSocialStreams(title, author, durationSec * 1000L, cover, videos, audios)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTikTokFromWebPage(httpClient: OkHttpClient, url: String): ExtractedSocialStreams? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string().orEmpty()
                val playAddr = Regex("\"playAddr\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                    ?.let(::unescapeJsonUrl)
                val downloadAddr = Regex("\"downloadAddr\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                    ?.let(::unescapeJsonUrl)
                val desc = Regex("\"desc\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                    ?.let(::unescapeJsonText) ?: "TikTok Video"
                val author = Regex("\"nickname\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                    ?.let(::unescapeJsonText) ?: "TikTok Creator"

                val videos = mutableListOf<VideoStreamOption>()
                val audios = mutableListOf<AudioStreamOption>()
                val primaryUrl = playAddr ?: downloadAddr ?: return null
                videos.add(
                    VideoStreamOption(
                        id = "tiktok_web_720p",
                        resolutionHeight = 720,
                        resolutionWidth = 720,
                        resolutionLabel = "720p HD",
                        videoCodec = "H.264 (AVC1)",
                        audioCodec = "AAC-LC",
                        container = VideoContainerFormat.MP4,
                        streamType = StreamType.PROGRESSIVE,
                        frameRate = 30,
                        isHdr = false,
                        bitrateKbps = 1500,
                        estimatedSizeBytes = 0L,
                        downloadUrl = primaryUrl
                    )
                )
                audios.add(
                    AudioStreamOption(
                        id = "tiktok_web_audio",
                        sourceFormat = AudioFormat.M4A,
                        sourceCodec = "AAC-LC (Demuxed from Video)",
                        bitrateKbps = 192,
                        sampleRateHz = 44100,
                        channelCount = 2,
                        isLosslessSource = false,
                        estimatedSizeBytes = 0L,
                        downloadUrl = primaryUrl,
                        isExtractedFromVideo = true
                    )
                )
                ExtractedSocialStreams(desc, author, 0L, null, videos, audios)
            }
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // 2. YOUTUBE STREAM EXTRACTOR (INNERTUBE + PIPED / INVIDIOUS)
    // =========================================================================

    fun extractYouTubeVideoId(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        val query = uri.rawQuery.orEmpty()

        // 1. youtu.be/<id>
        if (host.contains("youtu.be")) {
            val id = path.trim('/').substringBefore('/')
            if (id.length == 11) return id
        }

        // 2. ?v=<id>
        val vParam = query.split('&')
            .firstOrNull { it.startsWith("v=") }
            ?.substringAfter("v=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        if (!vParam.isNullOrBlank() && vParam.length == 11) {
            return vParam
        }

        // 3. /shorts/<id>, /embed/<id>, /live/<id>, /v/<id>
        val segments = path.split('/').filter { it.isNotBlank() }
        val markerIdx = segments.indexOfFirst {
            it.equals("shorts", true) || it.equals("embed", true) || it.equals("live", true) || it.equals("v", true)
        }
        if (markerIdx != -1 && markerIdx + 1 < segments.size) {
            val candidate = segments[markerIdx + 1]
            if (candidate.length == 11) return candidate
        }

        // 4. Fallback 11-char regex
        return Regex("(?:v=|/shorts/|/embed/|youtu\\.be/)([A-Za-z0-9_-]{11})")
            .find(trimmed)?.groupValues?.get(1)
    }

    private data class InnerTubeClientProfile(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val osName: String,
        val osVersion: String,
        val androidSdkVersion: Int? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null
    )

    private val INNERTUBE_CLIENTS = listOf(
        InnerTubeClientProfile(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 14; en_US; Quest 3; Build/UP1A.231005.007.A1; Cronet/132.0.6808.3)",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = 34,
            deviceMake = "Oculus",
            deviceModel = "Quest 3"
        ),
        InnerTubeClientProfile(
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            osName = "iPhone",
            osVersion = "18.1.0.22B83",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2"
        ),
        InnerTubeClientProfile(
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 14; en_US) gzip",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = 34
        )
    )

    fun extractYouTubeViaInnerTube(httpClient: OkHttpClient, videoId: String): ExtractedSocialStreams? {
        for (profile in INNERTUBE_CLIENTS) {
            val result = tryInnerTubeProfile(httpClient, videoId, profile)
            if (result != null && (result.videoStreams.isNotEmpty() || result.audioStreams.isNotEmpty())) {
                return result
            }
        }
        return null
    }

    private fun tryInnerTubeProfile(
        httpClient: OkHttpClient,
        videoId: String,
        profile: InnerTubeClientProfile
    ): ExtractedSocialStreams? {
        return try {
            val clientObj = JSONObject().apply {
                put("clientName", profile.clientName)
                put("clientVersion", profile.clientVersion)
                put("hl", "en")
                put("gl", "US")
                put("osName", profile.osName)
                put("osVersion", profile.osVersion)
                put("utcOffsetMinutes", 0)
                if (profile.androidSdkVersion != null) {
                    put("androidSdkVersion", profile.androidSdkVersion)
                }
                if (profile.deviceMake != null) {
                    put("deviceMake", profile.deviceMake)
                }
                if (profile.deviceModel != null) {
                    put("deviceModel", profile.deviceModel)
                }
            }
            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().put("client", clientObj))
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", profile.userAgent)
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                val playability = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                if (playability != "OK" && !root.has("streamingData")) return null

                val videoDetails = root.optJSONObject("videoDetails")
                val title = videoDetails?.optString("title").orEmpty()
                val author = videoDetails?.optString("author").orEmpty()
                val durationSec = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
                val durationMs = durationSec * 1000L
                val thumbArr = videoDetails?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumbUrl = if (thumbArr != null && thumbArr.length() > 0) {
                    thumbArr.optJSONObject(thumbArr.length() - 1)?.optString("url")
                } else null

                val streamingData = root.optJSONObject("streamingData") ?: return null
                val formats = streamingData.optJSONArray("formats") ?: JSONArray()
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()

                val videoStreams = mutableListOf<VideoStreamOption>()
                val audioStreams = mutableListOf<AudioStreamOption>()

                // First collect audio streams so we can pair the best M4A stream with adaptive MP4 video streams
                var bestM4aUrl: String? = null
                var bestM4aSize: Long = 0L

                for (i in 0 until adaptiveFormats.length()) {
                    val item = adaptiveFormats.optJSONObject(i) ?: continue
                    val streamUrl = item.optString("url").takeIf { it.startsWith("http") } ?: continue
                    val mimeRaw = item.optString("mimeType").lowercase()
                    if (!mimeRaw.startsWith("audio/")) continue

                    val bitrateBps = item.optLong("bitrate", 128_000L)
                    val bitrateKbps = (bitrateBps / 1000L).toInt().coerceAtLeast(48)
                    val contentLen = item.optString("contentLength").toLongOrNull()
                        ?: if (durationSec > 0) (bitrateBps * durationSec) / 8L else 0L
                    val sampleRate = item.optString("audioSampleRate").toIntOrNull() ?: 44100
                    val channels = item.optInt("audioChannels", 2).coerceAtLeast(1)
                    val isMp4Audio = mimeRaw.contains("mp4")

                    val audioFmt = if (isMp4Audio) AudioFormat.M4A else AudioFormat.OPUS
                    val codecName = if (isMp4Audio) "AAC-LC (M4A)" else "Opus (WebM)"

                    if (isMp4Audio && (bestM4aUrl == null || bitrateKbps >= 128)) {
                        bestM4aUrl = streamUrl
                        bestM4aSize = contentLen
                    }

                    audioStreams.add(
                        AudioStreamOption(
                            id = "yt_a_${item.optInt("itag", i)}",
                            sourceFormat = audioFmt,
                            sourceCodec = codecName,
                            bitrateKbps = bitrateKbps,
                            sampleRateHz = sampleRate,
                            channelCount = channels,
                            isLosslessSource = false,
                            estimatedSizeBytes = contentLen,
                            downloadUrl = streamUrl,
                            isExtractedFromVideo = false
                        )
                    )
                }

                // Parse progressive formats (video + audio together)
                for (i in 0 until formats.length()) {
                    val item = formats.optJSONObject(i) ?: continue
                    val streamUrl = item.optString("url").takeIf { it.startsWith("http") } ?: continue
                    val mimeRaw = item.optString("mimeType").lowercase()
                    if (!mimeRaw.startsWith("video/")) continue

                    val width = item.optInt("width", 640)
                    val height = item.optInt("height", 360)
                    val fps = item.optInt("fps", 30)
                    val bitrateBps = item.optLong("bitrate", 800_000L)
                    val bitrateKbps = (bitrateBps / 1000L).toInt().coerceAtLeast(128)
                    val contentLen = item.optString("contentLength").toLongOrNull()
                        ?: if (durationSec > 0) (bitrateBps * durationSec) / 8L else 0L
                    val container = if (mimeRaw.contains("webm")) VideoContainerFormat.WEBM else VideoContainerFormat.MP4

                    videoStreams.add(
                        VideoStreamOption(
                            id = "yt_prog_${height}p_${item.optInt("itag", i)}",
                            resolutionHeight = height,
                            resolutionWidth = width,
                            resolutionLabel = MediaProcessorEngine.resolutionLabelForHeight(width, height),
                            videoCodec = if (container == VideoContainerFormat.WEBM) "VP9" else "H.264 (AVC1)",
                            audioCodec = if (container == VideoContainerFormat.WEBM) "Opus" else "AAC-LC",
                            container = container,
                            streamType = StreamType.PROGRESSIVE,
                            frameRate = fps,
                            isHdr = false,
                            bitrateKbps = bitrateKbps,
                            estimatedSizeBytes = contentLen,
                            downloadUrl = streamUrl
                        )
                    )

                    if (audioStreams.isEmpty() && container == VideoContainerFormat.MP4) {
                        audioStreams.add(
                            AudioStreamOption(
                                id = "yt_prog_audio_${item.optInt("itag", i)}",
                                sourceFormat = AudioFormat.M4A,
                                sourceCodec = "AAC-LC (Demuxed from MP4)",
                                bitrateKbps = 160,
                                sampleRateHz = 44100,
                                channelCount = 2,
                                isLosslessSource = false,
                                estimatedSizeBytes = (contentLen / 5L).coerceAtLeast(32_768L),
                                downloadUrl = streamUrl,
                                isExtractedFromVideo = true
                            )
                        )
                    }
                }

                // Parse adaptive high-resolution video streams (144p -> 4K)
                for (i in 0 until adaptiveFormats.length()) {
                    val item = adaptiveFormats.optJSONObject(i) ?: continue
                    val streamUrl = item.optString("url").takeIf { it.startsWith("http") } ?: continue
                    val mimeRaw = item.optString("mimeType").lowercase()
                    if (!mimeRaw.startsWith("video/")) continue

                    val width = item.optInt("width", 1280)
                    val height = item.optInt("height", 720)
                    // Skip duplicate height if a progressive stream at the exact same resolution already exists
                    if (videoStreams.any { it.resolutionHeight == height && it.streamType == StreamType.PROGRESSIVE }) {
                        continue
                    }

                    val fps = item.optInt("fps", 30)
                    val bitrateBps = item.optLong("bitrate", 1_500_000L)
                    val bitrateKbps = (bitrateBps / 1000L).toInt().coerceAtLeast(128)
                    val contentLen = item.optString("contentLength").toLongOrNull()
                        ?: if (durationSec > 0) (bitrateBps * durationSec) / 8L else 0L
                    val isWebm = mimeRaw.contains("webm")
                    val container = if (isWebm) VideoContainerFormat.WEBM else VideoContainerFormat.MP4
                    val vCodec = when {
                        mimeRaw.contains("av01") -> "AV1"
                        mimeRaw.contains("vp9") || isWebm -> "VP9"
                        else -> "H.264 (AVC1)"
                    }

                    // Prefer H.264 MP4 for each resolution so hardware players support it universally
                    if (videoStreams.any { it.resolutionHeight == height && it.container == VideoContainerFormat.MP4 }) {
                        continue
                    }

                    videoStreams.add(
                        VideoStreamOption(
                            id = "yt_adap_${height}p_${item.optInt("itag", i)}",
                            resolutionHeight = height,
                            resolutionWidth = width,
                            resolutionLabel = MediaProcessorEngine.resolutionLabelForHeight(width, height),
                            videoCodec = vCodec,
                            audioCodec = if (bestM4aUrl != null && !isWebm) "AAC-LC" else null,
                            container = container,
                            streamType = if (bestM4aUrl != null && !isWebm) StreamType.VIDEO_ONLY else StreamType.PROGRESSIVE,
                            frameRate = fps,
                            isHdr = item.optString("qualityLabel").contains("HDR", ignoreCase = true),
                            bitrateKbps = bitrateKbps,
                            estimatedSizeBytes = contentLen,
                            downloadUrl = streamUrl,
                            pairedAudioStreamUrl = if (!isWebm) bestM4aUrl else null,
                            pairedAudioSizeBytes = if (!isWebm) bestM4aSize else 0L
                        )
                    )
                }

                ExtractedSocialStreams(
                    title = title,
                    author = author,
                    durationMs = durationMs,
                    thumbnailUrl = thumbUrl,
                    videoStreams = videoStreams,
                    audioStreams = audioStreams
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun extractYouTubeViaPipedOrInvidious(httpClient: OkHttpClient, videoId: String): ExtractedSocialStreams? {
        val pipedEndpoints = listOf(
            "https://pipedapi.kavin.rocks/streams/$videoId",
            "https://api.piped.private.coffee/streams/$videoId",
            "https://pipedapi.adminforge.de/streams/$videoId"
        )
        for (endpoint in pipedEndpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val title = root.optString("title", "YouTube Video")
                    val author = root.optString("uploader", "YouTube Creator")
                    val durationSec = root.optLong("duration", 0L)
                    val thumb = root.optString("thumbnailUrl").takeIf { it.isNotBlank() }

                    val videoStreams = mutableListOf<VideoStreamOption>()
                    val audioStreams = mutableListOf<AudioStreamOption>()

                    val aArr = root.optJSONArray("audioStreams") ?: JSONArray()
                    var bestM4aUrl: String? = null
                    for (i in 0 until aArr.length()) {
                        val aObj = aArr.optJSONObject(i) ?: continue
                        val sUrl = aObj.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val mime = aObj.optString("mimeType").lowercase()
                        val kbps = (aObj.optInt("bitrate", 128_000) / 1000).coerceIn(48, 320)
                        val isM4a = mime.contains("mp4") || aObj.optString("format").contains("M4A", true)
                        if (isM4a && bestM4aUrl == null) bestM4aUrl = sUrl
                        audioStreams.add(
                            AudioStreamOption(
                                id = "piped_a_$i",
                                sourceFormat = if (isM4a) AudioFormat.M4A else AudioFormat.OPUS,
                                sourceCodec = if (isM4a) "AAC-LC" else "Opus",
                                bitrateKbps = kbps,
                                sampleRateHz = 44100,
                                channelCount = 2,
                                isLosslessSource = false,
                                estimatedSizeBytes = if (durationSec > 0) (durationSec * kbps * 1000L) / 8L else 0L,
                                downloadUrl = sUrl,
                                isExtractedFromVideo = false
                            )
                        )
                    }

                    val vArr = root.optJSONArray("videoStreams") ?: JSONArray()
                    for (i in 0 until vArr.length()) {
                        val vObj = vArr.optJSONObject(i) ?: continue
                        val sUrl = vObj.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val videoOnly = vObj.optBoolean("videoOnly", false)
                        val height = vObj.optInt("height", 720)
                        val width = vObj.optInt("width", 1280)
                        val fps = vObj.optInt("fps", 30)
                        val kbps = (vObj.optInt("bitrate", 1_500_000) / 1000).coerceAtLeast(128)
                        val isWebm = vObj.optString("format").contains("WEBM", true)
                        if (videoOnly && isWebm) continue
                        if (videoStreams.any { it.resolutionHeight == height && !it.streamType.name.contains("VIDEO_ONLY") }) continue

                        videoStreams.add(
                            VideoStreamOption(
                                id = "piped_v_${height}p_$i",
                                resolutionHeight = height,
                                resolutionWidth = width,
                                resolutionLabel = MediaProcessorEngine.resolutionLabelForHeight(width, height),
                                videoCodec = if (isWebm) "VP9" else "H.264 (AVC1)",
                                audioCodec = "AAC-LC",
                                container = if (isWebm) VideoContainerFormat.WEBM else VideoContainerFormat.MP4,
                                streamType = if (videoOnly && bestM4aUrl != null) StreamType.VIDEO_ONLY else StreamType.PROGRESSIVE,
                                frameRate = fps,
                                isHdr = false,
                                bitrateKbps = kbps,
                                estimatedSizeBytes = if (durationSec > 0) (durationSec * kbps * 1000L) / 8L else 0L,
                                downloadUrl = sUrl,
                                pairedAudioStreamUrl = if (videoOnly) bestM4aUrl else null
                            )
                        )
                    }

                    if (videoStreams.isNotEmpty() || audioStreams.isNotEmpty()) {
                        return ExtractedSocialStreams(title, author, durationSec * 1000L, thumb, videoStreams, audioStreams)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    // =========================================================================
    // 3. FACEBOOK STREAM EXTRACTOR (WATCH / REELS / SHARE LINKS)
    // =========================================================================

    fun extractFacebookStreams(httpClient: OkHttpClient, url: String): ExtractedSocialStreams? {
        var resolvedUrl = url
        var html = ""

        // Step 1: Fetch main URL with desktop browser headers (follows fb.watch and /share/v/ redirects)
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Mode", "navigate")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                resolvedUrl = resp.request.url.toString()
                html = resp.body?.string().orEmpty()
            }
        }

        var hdUrl = extractFirstMatchingUrl(
            html,
            listOf(
                Regex("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"hd_src_no_ratelimit\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"hd_src\"\\s*:\\s*\"([^\"]+)\"")
            )
        )

        var sdUrl = extractFirstMatchingUrl(
            html,
            listOf(
                Regex("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"playable_url\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"sd_src_no_ratelimit\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"sd_src\"\\s*:\\s*\"([^\"]+)\""),
                Regex("<meta[^>]+property=[\"']og:video(?::secure_url|:url)?[\"'][^>]+content=[\"']([^\"']+)[\"']"),
                Regex("content=[\"']([^\"']+\\.mp4[^\"']*)[\"'][^>]+property=[\"']og:video")
            )
        )

        // Step 2: If desktop page didn't expose direct URLs, try mbasic.facebook.com redirect link
        if (hdUrl == null && sdUrl == null) {
            runCatching {
                val mbasicUrl = resolvedUrl
                    .replace("://www.facebook.com", "://mbasic.facebook.com")
                    .replace("://m.facebook.com", "://mbasic.facebook.com")
                    .replace("://web.facebook.com", "://mbasic.facebook.com")
                val req = Request.Builder()
                    .url(mbasicUrl)
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val mbasicHtml = resp.body?.string().orEmpty()
                    val redirectSrc = Regex("/video_redirect/\\?src=([^\"'&<>]+)")
                        .find(mbasicHtml)?.groupValues?.get(1)
                        ?.let { URLDecoder.decode(it, "UTF-8") }
                    if (!redirectSrc.isNullOrBlank() && redirectSrc.startsWith("http")) {
                        sdUrl = redirectSrc
                    } else {
                        sdUrl = extractFirstMatchingUrl(
                            mbasicHtml,
                            listOf(
                                Regex("<meta[^>]+property=[\"']og:video(?::secure_url|:url)?[\"'][^>]+content=[\"']([^\"']+)[\"']"),
                                Regex("\"(https?:\\\\/\\\\/[^\"]+\\.mp4[^\"]*)\"")
                            )
                        )
                    }
                }
            }
        }

        if (hdUrl == null && sdUrl == null) return null

        val title = Regex("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']")
            .find(html)?.groupValues?.get(1)?.let(::unescapeHtmlEntities)
            ?: Regex("<title>([^<]+)</title>").find(html)?.groupValues?.get(1)?.let(::unescapeHtmlEntities)
            ?: "Facebook Video"
        val thumb = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']")
            .find(html)?.groupValues?.get(1)?.let(::unescapeHtmlEntities)

        val videoStreams = mutableListOf<VideoStreamOption>()
        val audioStreams = mutableListOf<AudioStreamOption>()

        if (!hdUrl.isNullOrBlank()) {
            videoStreams.add(
                VideoStreamOption(
                    id = "fb_1080p_hd",
                    resolutionHeight = 1080,
                    resolutionWidth = 1920,
                    resolutionLabel = "1080p Full HD",
                    videoCodec = "H.264 (AVC1)",
                    audioCodec = "AAC-LC",
                    container = VideoContainerFormat.MP4,
                    streamType = StreamType.PROGRESSIVE,
                    frameRate = 30,
                    isHdr = false,
                    bitrateKbps = 2500,
                    estimatedSizeBytes = 0L,
                    downloadUrl = hdUrl
                )
            )
        }

        if (!sdUrl.isNullOrBlank() && sdUrl != hdUrl) {
            videoStreams.add(
                VideoStreamOption(
                    id = "fb_480p_sd",
                    resolutionHeight = 480,
                    resolutionWidth = 854,
                    resolutionLabel = "480p SD",
                    videoCodec = "H.264 (AVC1)",
                    audioCodec = "AAC-LC",
                    container = VideoContainerFormat.MP4,
                    streamType = StreamType.PROGRESSIVE,
                    frameRate = 30,
                    isHdr = false,
                    bitrateKbps = 950,
                    estimatedSizeBytes = 0L,
                    downloadUrl = sdUrl!!
                )
            )
        }

        val audioSourceUrl = sdUrl ?: hdUrl!!
        audioStreams.add(
            AudioStreamOption(
                id = "fb_audio_m4a",
                sourceFormat = AudioFormat.M4A,
                sourceCodec = "AAC-LC (Demuxed from Facebook Video)",
                bitrateKbps = 192,
                sampleRateHz = 44100,
                channelCount = 2,
                isLosslessSource = false,
                estimatedSizeBytes = 0L,
                downloadUrl = audioSourceUrl,
                isExtractedFromVideo = true
            )
        )

        return ExtractedSocialStreams(
            title = title,
            author = "Facebook Video",
            durationMs = 0L,
            thumbnailUrl = thumb,
            videoStreams = videoStreams,
            audioStreams = audioStreams
        )
    }

    // =========================================================================
    // 4. INSTAGRAM STREAM EXTRACTOR (REELS / POSTS / TV)
    // =========================================================================

    fun extractInstagramStreams(httpClient: OkHttpClient, url: String): ExtractedSocialStreams? {
        var resolvedUrl = url
        // Resolve share redirects (e.g. instagram.com/share/reel/...) first if needed
        if (url.contains("/share/", ignoreCase = true) || url.contains("instagr.am", ignoreCase = true)) {
            runCatching {
                val req = Request.Builder().url(url).header("User-Agent", MOBILE_USER_AGENT).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    resolvedUrl = resp.request.url.toString()
                }
            }
        }

        val shortcode = Regex("/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)").find(resolvedUrl)?.groupValues?.get(1)

        var videoUrl: String? = null
        var title = "Instagram Reel ${shortcode.orEmpty()}".trim()
        var author = "Instagram Creator"
        var thumb: String? = null

        // Layer 1: Instagram Embed Captioned Endpoint
        if (!shortcode.isNullOrBlank()) {
            runCatching {
                val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
                val req = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val html = resp.body?.string().orEmpty()
                        videoUrl = extractFirstMatchingUrl(
                            html,
                            listOf(
                                Regex("\\\\?\"video_url\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]+(?:\\\\.[^\"\\\\]+)*)\\\\?\""),
                                Regex("<video[^>]+src=[\"']([^\"']+)[\"']"),
                                Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"")
                            )
                        )
                        val username = Regex("\"username\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                        if (!username.isNullOrBlank()) author = "@$username"
                        val caption = Regex("class=\"Caption\"[^>]*>.*?<br\\s*/?>\\s*([^<]+)", RegexOption.DOT_MATCHES_ALL)
                            .find(html)?.groupValues?.get(1)?.trim()
                        if (!caption.isNullOrBlank()) title = unescapeHtmlEntities(caption).take(80)
                    }
                }
            }
        }

        // Layer 2: InstaFix / ddinstagram Open-Graph Direct Stream Resolver
        if (videoUrl == null && !shortcode.isNullOrBlank()) {
            runCatching {
                val ddUrl = "https://www.ddinstagram.com/p/$shortcode"
                val req = Request.Builder()
                    .url(ddUrl)
                    .header("User-Agent", "TelegramBot (like TwitterBot)")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val html = resp.body?.string().orEmpty()
                    val ogVideo = extractFirstMatchingUrl(
                        html,
                        listOf(
                            Regex("<meta[^>]+property=[\"']og:video(?::secure_url|:url)?[\"'][^>]+content=[\"']([^\"']+)[\"']"),
                            Regex("content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:video")
                        )
                    )
                    if (!ogVideo.isNullOrBlank()) {
                        videoUrl = if (ogVideo.startsWith("/")) "https://www.ddinstagram.com$ogVideo" else ogVideo
                    }
                    val ogDesc = Regex("<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']")
                        .find(html)?.groupValues?.get(1)
                    if (!ogDesc.isNullOrBlank()) title = unescapeHtmlEntities(ogDesc).take(80)
                }
            }
        }

        // Layer 3: Direct Instagram Page Inspection
        if (videoUrl == null) {
            runCatching {
                val req = Request.Builder()
                    .url(resolvedUrl)
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val html = resp.body?.string().orEmpty()
                    videoUrl = extractFirstMatchingUrl(
                        html,
                        listOf(
                            Regex("\"video_url\"\\s*:\\s*\"([^\"]+)\""),
                            Regex("<meta[^>]+property=[\"']og:video(?::secure_url|:url)?[\"'][^>]+content=[\"']([^\"']+)[\"']")
                        )
                    )
                    thumb = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']")
                        .find(html)?.groupValues?.get(1)?.let(::unescapeHtmlEntities)
                }
            }
        }

        val resolvedStreamUrl = videoUrl?.takeIf { it.startsWith("http") } ?: return null

        val videoStreams = listOf(
            VideoStreamOption(
                id = "ig_1080p_hd",
                resolutionHeight = 1080,
                resolutionWidth = 1080,
                resolutionLabel = "1080p Full HD",
                videoCodec = "H.264 (AVC1)",
                audioCodec = "AAC-LC",
                container = VideoContainerFormat.MP4,
                streamType = StreamType.PROGRESSIVE,
                frameRate = 30,
                isHdr = false,
                bitrateKbps = 2200,
                estimatedSizeBytes = 0L,
                downloadUrl = resolvedStreamUrl
            )
        )

        val audioStreams = listOf(
            AudioStreamOption(
                id = "ig_audio_m4a",
                sourceFormat = AudioFormat.M4A,
                sourceCodec = "AAC-LC (Demuxed from Instagram Reel)",
                bitrateKbps = 192,
                sampleRateHz = 44100,
                channelCount = 2,
                isLosslessSource = false,
                estimatedSizeBytes = 0L,
                downloadUrl = resolvedStreamUrl,
                isExtractedFromVideo = true
            )
        )

        return ExtractedSocialStreams(
            title = title,
            author = author,
            durationMs = 0L,
            thumbnailUrl = thumb,
            videoStreams = videoStreams,
            audioStreams = audioStreams
        )
    }

    // =========================================================================
    // STRING UNESCAPING HELPERS
    // =========================================================================

    private fun extractFirstMatchingUrl(text: String, patterns: List<Regex>): String? {
        if (text.isEmpty()) return null
        for (regex in patterns) {
            val match = regex.find(text)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) {
                val cleaned = unescapeJsonUrl(match)
                if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
                    return cleaned
                }
            }
        }
        return null
    }

    internal fun unescapeJsonUrl(raw: String): String {
        var s = raw.trim()
            .replace("\\/", "/")
            .replace("\\\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("\\u003F", "?", ignoreCase = true)
            .replace("\\u0025", "%", ignoreCase = true)
            .replace("&amp;", "&")
        // Decode any remaining \uXXXX sequences
        s = Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
        return s.trim('"', '\'', '\\')
    }

    private fun unescapeJsonText(raw: String): String {
        return unescapeHtmlEntities(unescapeJsonUrl(raw))
    }

    private fun unescapeHtmlEntities(raw: String): String {
        return raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }
}
