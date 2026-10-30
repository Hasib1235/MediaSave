package com.example.data.platform

import com.example.data.media.MediaProcessorEngine
import com.example.domain.model.AudioFormat
import com.example.domain.model.AudioStreamOption
import com.example.domain.model.StreamType
import com.example.domain.model.VideoContainerFormat
import com.example.domain.model.VideoStreamOption
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
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

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Uses system DNS for YouTube/GoogleVideo (so InnerTube and googlevideo.com share the exact
     * native IP family without triggering LOGIN_REQUIRED) and prefers IPv4 for third-party APIs
     * (tikwm.com, piped, facebook, instagram) so connections never stall on unreachable IPv6 routes.
     */
    val IPV4_FIRST_DNS: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val lower = hostname.lowercase()
            if (lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("googlevideo.com")) {
                return all
            }
            val ipv4 = all.filterIsInstance<Inet4Address>()
            return if (ipv4.isNotEmpty()) ipv4 + (all - ipv4.toSet()) else all
        }
    }

    // =========================================================================
    // 1. TIKTOK STREAM EXTRACTOR
    // =========================================================================

    fun extractTikTokStreams(httpClient: OkHttpClient, url: String): ExtractedSocialStreams? {
        // Layer 1: TikWM API (supports vt.tiktok.com, vm.tiktok.com, www.tiktok.com, HD no-watermark + MP3)
        extractTikTokViaTikWm(httpClient, url)?.let {
            if (it.videoStreams.isNotEmpty() || it.audioStreams.isNotEmpty()) return it
        }

        // Layer 2: TiklyDown API fallback
        extractTikTokViaTiklyDown(httpClient, url)?.let {
            if (it.videoStreams.isNotEmpty() || it.audioStreams.isNotEmpty()) return it
        }

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

                val tikTokId = data.optString("id").trim()
                val hdUrl = resolveTikwmUrl(data.optString("hdplay"))?.let { appendTikTokIdFragment(it, tikTokId) }
                val playUrl = resolveTikwmUrl(data.optString("play"))?.let { appendTikTokIdFragment(it, tikTokId) }
                val wmUrl = resolveTikwmUrl(data.optString("wmplay"))?.let { appendTikTokIdFragment(it, tikTokId) }
                val musicUrl = resolveTikwmUrl(data.optString("music"))?.let { appendTikTokIdFragment(it, tikTokId) }

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

    private fun appendTikTokIdFragment(url: String, tikTokId: String): String {
        val clean = stripUrlFragment(url)
        return if (tikTokId.isNotBlank()) "$clean#ttid=$tikTokId" else clean
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
    // 2. YOUTUBE STREAM EXTRACTOR (MULTI-LAYERED)
    // =========================================================================

    fun extractYouTubeVideoId(rawUrl: String): String? {
        val trimmed = rawUrl.trim()

        // 1. Direct regex match for watch?v=, youtu.be/, /shorts/, /live/, /embed/, /v/
        val regexMatch = Regex(
            "(?:[?&]v=|/shorts/|/embed/|/live/|/v/|youtu\\.be/)([A-Za-z0-9_-]{11})"
        ).find(trimmed)?.groupValues?.getOrNull(1)
        if (!regexMatch.isNullOrBlank()) {
            return regexMatch
        }

        // 2. URI path/query fallback
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        val query = uri.rawQuery.orEmpty()

        if (host.contains("youtu.be")) {
            val id = path.trim('/').substringBefore('/').substringBefore('?')
            if (id.length == 11) return id
        }

        val vParam = query.split('&')
            .firstOrNull { it.startsWith("v=") }
            ?.substringAfter("v=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        if (!vParam.isNullOrBlank() && vParam.length >= 11) {
            return vParam.take(11)
        }

        val segments = path.split('/').filter { it.isNotBlank() }
        val lastSeg = segments.lastOrNull()?.takeIf { it.length == 11 }
        return lastSeg
    }

    private data class YouTubePageContext(
        val visitorData: String? = null,
        val signatureTimestamp: Int? = null
    )

    private data class InnerTubeClientProfile(
        val clientName: String,
        val clientVersion: String,
        val clientIdHeader: String,
        val userAgent: String,
        val osName: String,
        val osVersion: String,
        val androidSdkVersion: Int? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val playerParams: String? = null,
        val isEmbedded: Boolean = false
    )

    private val INNERTUBE_CLIENTS = listOf(
        InnerTubeClientProfile(
            clientName = "ANDROID_VR",
            clientVersion = "1.62.27",
            clientIdHeader = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; en_US; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            deviceMake = "Oculus",
            deviceModel = "Quest 3"
        ),
        InnerTubeClientProfile(
            clientName = "IOS",
            clientVersion = "20.03.02",
            clientIdHeader = "5",
            userAgent = "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)",
            osName = "iPhone",
            osVersion = "18.2.1.22C161",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2"
        ),
        InnerTubeClientProfile(
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            clientIdHeader = "30",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 12; en_US) gzip",
            osName = "Android",
            osVersion = "12",
            androidSdkVersion = 31,
            playerParams = "CgIQBg=="
        ),
        InnerTubeClientProfile(
            clientName = "ANDROID",
            clientVersion = "20.03.35",
            clientIdHeader = "3",
            userAgent = "com.google.android.youtube/20.03.35 (Linux; U; Android 14; en_US) gzip",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = 34,
            playerParams = "CgIQBg=="
        ),
        InnerTubeClientProfile(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientIdHeader = "85",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            osName = "PlayStation",
            osVersion = "12.00",
            isEmbedded = true
        )
    )

    fun stripUrlFragment(url: String): String = url.substringBefore('#').trim()

    fun buildStreamRequestHeaders(url: String): Map<String, String> {
        val cleanUrl = stripUrlFragment(url)
        val lowerUrl = cleanUrl.lowercase()
        val host = runCatching { URI(cleanUrl).host?.lowercase() }.getOrNull().orEmpty()
        val isDirectGoogleVideo = host.contains("googlevideo.com")
        val headers = LinkedHashMap<String, String>()

        val userAgent = when {
            isDirectGoogleVideo && lowerUrl.contains("c=ios") ->
                "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"
            isDirectGoogleVideo && lowerUrl.contains("c=android_vr") ->
                "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; en_US; eureka-user Build/SQ3A.220605.009.A1) gzip"
            isDirectGoogleVideo && lowerUrl.contains("c=android_testsuite") ->
                "com.google.android.youtube/1.9 (Linux; U; Android 12; en_US) gzip"
            isDirectGoogleVideo && lowerUrl.contains("c=android") ->
                "com.google.android.youtube/20.03.35 (Linux; U; Android 14; en_US) gzip"
            isDirectGoogleVideo && lowerUrl.contains("c=tvhtml5") ->
                "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15"
            host.contains("facebook") || host.contains("fbcdn") || host.contains("fbsbx") ->
                DESKTOP_USER_AGENT
            host.contains("instagram") || host.contains("cdninstagram") || host.contains("ddinstagram") ->
                DESKTOP_USER_AGENT
            host.contains("piped") || host.contains("invidious") || host.contains("nadeko") || host.contains("yewtu.be") || host.contains("nerdvpn") ->
                DESKTOP_USER_AGENT
            else -> MOBILE_USER_AGENT
        }
        headers["User-Agent"] = userAgent
        headers["Accept"] = "*/*"

        when {
            host.contains("tikwm.com") -> {
                headers["Referer"] = "https://www.tikwm.com/"
            }
            host.contains("tiktok") || host.contains("musical.ly") || host.contains("byteoversea") || host.contains("ibytedtos") || host.contains("muscdn") -> {
                headers["Referer"] = "https://www.tiktok.com/"
            }
            host.contains("instagram") || host.contains("cdninstagram") || host.contains("ddinstagram") -> {
                headers["Referer"] = "https://www.instagram.com/"
            }
            host.contains("facebook") || host.contains("fbcdn") || host.contains("fbsbx") -> {
                headers["Referer"] = "https://www.facebook.com/"
            }
            host.contains("oceansaver") || host.contains("ddownr") || host.contains("savenow") -> {
                headers["Referer"] = "https://ddownr.com/"
            }
            isDirectGoogleVideo && (lowerUrl.contains("c=web") || lowerUrl.contains("c=mweb") || lowerUrl.contains("c=tvhtml5")) -> {
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/"
            }
        }
        return headers
    }

    /**
     * Probes the first 1 KB of a candidate media stream URL when recovering from an HTTP 403 error.
     * Note: Never call this during initial `Analyze`, as aborting a stream probe burns single-use CDN tokens.
     */
    fun isStreamUrlDownloadable(httpClient: OkHttpClient, streamUrl: String): Boolean {
        val cleanUrl = stripUrlFragment(streamUrl)
        if (cleanUrl.startsWith("file:", ignoreCase = true)) return true
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            return false
        }
        return try {
            val reqBuilder = Request.Builder().url(cleanUrl).get()
            for ((k, v) in buildStreamRequestHeaders(cleanUrl)) {
                reqBuilder.header(k, v)
            }
            reqBuilder.header("Range", "bytes=0-1023")
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code !in 200..299) return false
                val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                !ct.startsWith("text/html") && !ct.startsWith("application/xhtml")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyAndFilterYouTubeStreams(
        @Suppress("UNUSED_PARAMETER") httpClient: OkHttpClient,
        extracted: ExtractedSocialStreams,
        videoId: String
    ): ExtractedSocialStreams? {
        val progressiveVideos = extracted.videoStreams.filter { !it.id.contains("_adap_") }
        val adaptiveVideos = extracted.videoStreams.filter { it.id.contains("_adap_") }
        val primaryProgressiveUrl = progressiveVideos.firstOrNull()?.downloadUrl?.let(::stripUrlFragment)

        val validVideo = mutableListOf<VideoStreamOption>()
        val validAudio = mutableListOf<AudioStreamOption>()

        for (prog in progressiveVideos) {
            validVideo.add(prog.copy(downloadUrl = appendVideoIdFragment(prog.downloadUrl, videoId)))
        }
        for (adap in adaptiveVideos) {
            val taggedUrl = appendVideoIdFragment(adap.downloadUrl, videoId)
            val taggedPairedAudio = adap.pairedAudioStreamUrl?.let { appendVideoIdFragment(it, videoId) }
            validVideo.add(
                adap.copy(
                    downloadUrl = taggedUrl,
                    pairedAudioStreamUrl = taggedPairedAudio
                )
            )
        }

        for (aud in extracted.audioStreams) {
            val taggedUrl = appendVideoIdFragment(aud.downloadUrl, videoId)
            validAudio.add(aud.copy(downloadUrl = taggedUrl))
        }

        if (validAudio.isEmpty() && validVideo.isNotEmpty()) {
            val fallbackVideo = validVideo.first()
            validAudio.add(
                AudioStreamOption(
                    id = "yt_verified_demux_audio",
                    sourceFormat = AudioFormat.M4A,
                    sourceCodec = "AAC-LC (Demuxed from MP4)",
                    bitrateKbps = 160,
                    sampleRateHz = 44100,
                    channelCount = 2,
                    isLosslessSource = false,
                    estimatedSizeBytes = (fallbackVideo.estimatedSizeBytes / 5L).coerceAtLeast(32_768L),
                    downloadUrl = fallbackVideo.downloadUrl,
                    isExtractedFromVideo = true
                )
            )
        }

        if (validVideo.isEmpty() && validAudio.isEmpty()) return null

        return extracted.copy(
            videoStreams = validVideo.sortedByDescending { it.resolutionHeight },
            audioStreams = validAudio.sortedByDescending { it.bitrateKbps }
        )
    }

    private fun appendVideoIdFragment(url: String, videoId: String): String {
        val clean = stripUrlFragment(url)
        return if (videoId.isNotBlank()) "$clean#ytid=$videoId" else clean
    }

    private fun fetchYouTubePageContext(httpClient: OkHttpClient, videoId: String): YouTubePageContext {
        var visitorData: String? = null
        var sts: Int? = null

        // 1. Fetch embed page for visitorData and signatureTimestamp (STS)
        runCatching {
            val embedReq = Request.Builder()
                .url("https://www.youtube.com/embed/$videoId")
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            httpClient.newCall(embedReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    visitorData = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                    sts = Regex("\"(?:STS|signatureTimestamp)\"\\s*:\\s*(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        }

        // 2. Fallback to mobile watch page for visitorData / STS only (never use MWEB raw streams as they 403 without PoToken)
        if (visitorData.isNullOrBlank()) {
            runCatching {
                val watchReq = Request.Builder()
                    .url("https://m.youtube.com/watch?v=$videoId&has_verified=1")
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "CONSENT=YES+cb; PREF=hl=en")
                    .get()
                    .build()
                httpClient.newCall(watchReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val html = resp.body?.string().orEmpty()
                        visitorData = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                            ?: Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                        if (sts == null) {
                            sts = Regex("\"(?:STS|signatureTimestamp)\"\\s*:\\s*(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull()
                        }
                    }
                }
            }
        }

        return YouTubePageContext(
            visitorData = visitorData,
            signatureTimestamp = sts
        )
    }

    fun extractYouTubeViaInnerTube(httpClient: OkHttpClient, videoId: String): ExtractedSocialStreams? {
        // Pass 1: Try native mobile/VR InnerTube clients WITHOUT web visitorData first.
        // Passing web embed visitorData to ANDROID_VR / IOS triggers LOGIN_REQUIRED on YouTube,
        // whereas clean native client requests return all progressive + adaptive streams directly.
        for (profile in INNERTUBE_CLIENTS) {
            if (profile.isEmbedded) continue
            val rawResult = tryInnerTubeProfile(
                httpClient = httpClient,
                videoId = videoId,
                profile = profile,
                visitorData = null,
                signatureTimestamp = null
            )
            if (rawResult != null) {
                val verified = verifyAndFilterYouTubeStreams(httpClient, rawResult, videoId)
                if (verified != null && (verified.videoStreams.isNotEmpty() || verified.audioStreams.isNotEmpty())) {
                    return verified
                }
            }
        }

        // Pass 2: Fallback with embed page visitorData & signatureTimestamp
        val pageCtx = fetchYouTubePageContext(httpClient, videoId)
        for (profile in INNERTUBE_CLIENTS) {
            val rawResult = tryInnerTubeProfile(
                httpClient = httpClient,
                videoId = videoId,
                profile = profile,
                visitorData = pageCtx.visitorData,
                signatureTimestamp = pageCtx.signatureTimestamp
            )
            if (rawResult != null) {
                val verified = verifyAndFilterYouTubeStreams(httpClient, rawResult, videoId)
                if (verified != null && (verified.videoStreams.isNotEmpty() || verified.audioStreams.isNotEmpty())) {
                    return verified
                }
            }
        }
        return null
    }

    private fun tryInnerTubeProfile(
        httpClient: OkHttpClient,
        videoId: String,
        profile: InnerTubeClientProfile,
        visitorData: String?,
        signatureTimestamp: Int?
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
                if (!visitorData.isNullOrBlank()) {
                    put("visitorData", visitorData)
                }
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

            val contextObj = JSONObject().apply {
                put("client", clientObj)
                if (profile.isEmbedded) {
                    put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
                }
            }

            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("context", contextObj)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                if (profile.playerParams != null) {
                    put("params", profile.playerParams)
                }
                if (signatureTimestamp != null) {
                    put(
                        "playbackContext",
                        JSONObject().put(
                            "contentPlaybackContext",
                            JSONObject()
                                .put("signatureTimestamp", signatureTimestamp)
                                .put("html5Preference", "HTML5_PREF_WANTS")
                        )
                    )
                }
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", profile.userAgent)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-YouTube-Client-Name", profile.clientIdHeader)
                .header("X-YouTube-Client-Version", profile.clientVersion)
                .header("X-Goog-Api-Format-Version", "2")

            if (!visitorData.isNullOrBlank()) {
                reqBuilder.header("X-Goog-Visitor-Id", visitorData)
            }
            if (profile.isEmbedded) {
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/watch?v=$videoId")
            }

            val req = reqBuilder
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                parseInnerTubePlayerResponse(httpClient, root)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseInnerTubePlayerResponse(
        httpClient: OkHttpClient,
        root: JSONObject
    ): ExtractedSocialStreams? {
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

        // 1. Collect audio streams from adaptiveFormats
        for (i in 0 until adaptiveFormats.length()) {
            val item = adaptiveFormats.optJSONObject(i) ?: continue
            val streamUrl = resolveInnerTubeStreamUrl(item) ?: continue
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

        // 2. Collect progressive video+audio streams (playable directly without muxing)
        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val streamUrl = resolveInnerTubeStreamUrl(item) ?: continue
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

        // 3. Collect adaptive high-resolution video streams (1080p FHD, 1440p QHD, 2160p 4K, 720p HD, etc.)
        val bestM4aAudio = audioStreams
            .filter { it.sourceFormat == AudioFormat.M4A && !it.isExtractedFromVideo }
            .maxByOrNull { it.bitrateKbps }
            ?: audioStreams.firstOrNull { !it.isExtractedFromVideo }
            ?: audioStreams.firstOrNull()

        for (i in 0 until adaptiveFormats.length()) {
            val item = adaptiveFormats.optJSONObject(i) ?: continue
            val streamUrl = resolveInnerTubeStreamUrl(item) ?: continue
            val mimeRaw = item.optString("mimeType").lowercase()
            if (!mimeRaw.startsWith("video/")) continue

            val width = item.optInt("width", 1280)
            val height = item.optInt("height", 720)
            if (height !in 144..4320) continue

            val isMp4 = mimeRaw.contains("mp4")
            val isAvc = mimeRaw.contains("avc1")
            val isAv1 = mimeRaw.contains("av01")
            val isVp9 = mimeRaw.contains("vp9") || mimeRaw.contains("vp09")
            val isWebm = mimeRaw.contains("webm")

            // Deduplicate by height: prefer AVC1 (MP4) for maximum compatibility, but keep highest resolutions
            val existingIndex = videoStreams.indexOfFirst { it.resolutionHeight == height }
            if (existingIndex >= 0) {
                val existing = videoStreams[existingIndex]
                if (existing.container == VideoContainerFormat.WEBM && isMp4 && isAvc) {
                    videoStreams.removeAt(existingIndex)
                } else {
                    continue
                }
            }

            val fps = item.optInt("fps", 30)
            val bitrateBps = item.optLong("bitrate", 1_500_000L)
            val bitrateKbps = (bitrateBps / 1000L).toInt().coerceAtLeast(128)
            val contentLen = item.optString("contentLength").toLongOrNull()
                ?: if (durationSec > 0) (bitrateBps * durationSec) / 8L else 0L

            val codecLabel = when {
                isAvc -> "H.264 (AVC1)"
                isAv1 -> "AV1"
                isVp9 -> "VP9"
                isMp4 -> "MP4 Video"
                else -> "WebM Video"
            }
            val containerFmt = if (isWebm) VideoContainerFormat.WEBM else VideoContainerFormat.MP4

            videoStreams.add(
                VideoStreamOption(
                    id = "yt_adap_${height}p_${item.optInt("itag", i)}",
                    resolutionHeight = height,
                    resolutionWidth = width,
                    resolutionLabel = MediaProcessorEngine.resolutionLabelForHeight(width, height),
                    videoCodec = codecLabel,
                    audioCodec = if (bestM4aAudio != null) null else "AAC-LC",
                    container = containerFmt,
                    streamType = if (bestM4aAudio != null) StreamType.VIDEO_ONLY else StreamType.PROGRESSIVE,
                    frameRate = fps,
                    isHdr = item.optString("qualityLabel").contains("HDR", ignoreCase = true),
                    bitrateKbps = bitrateKbps,
                    estimatedSizeBytes = contentLen,
                    downloadUrl = streamUrl,
                    pairedAudioStreamUrl = bestM4aAudio?.downloadUrl,
                    pairedAudioSizeBytes = bestM4aAudio?.estimatedSizeBytes ?: 0L
                )
            )
        }

        // 4. If formats were empty, check hlsManifestUrl
        if (videoStreams.isEmpty()) {
            val hlsManifestUrl = streamingData.optString("hlsManifestUrl").takeIf { it.startsWith("http") }
            if (hlsManifestUrl != null) {
                runCatching {
                    val hlsReq = Request.Builder().url(hlsManifestUrl).header("User-Agent", MOBILE_USER_AGENT).get().build()
                    httpClient.newCall(hlsReq).execute().use { hlsResp ->
                        if (hlsResp.isSuccessful) {
                            val playlistText = hlsResp.body?.string().orEmpty()
                            val parsedHls = parseHlsMasterPlaylist(hlsManifestUrl, playlistText, author.ifBlank { "YouTube" })
                            if (parsedHls is com.example.domain.model.MediaAnalysisState.Success) {
                                videoStreams.addAll(parsedHls.metadata.videoStreams)
                            }
                        }
                    }
                }
            }
        }

        if (videoStreams.isEmpty() && audioStreams.isEmpty()) return null

        return ExtractedSocialStreams(
            title = title,
            author = author,
            durationMs = durationMs,
            thumbnailUrl = thumbUrl,
            videoStreams = videoStreams,
            audioStreams = audioStreams
        )
    }

    private fun resolveInnerTubeStreamUrl(formatItem: JSONObject): String? {
        val direct = formatItem.optString("url").trim()
        if (direct.startsWith("http://") || direct.startsWith("https://")) {
            return direct
        }
        val cipher = formatItem.optString("signatureCipher").ifBlank { formatItem.optString("cipher") }
        if (cipher.isNotBlank()) {
            val params = cipher.split('&').associate { part ->
                val k = part.substringBefore('=')
                val v = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                k to v
            }
            val baseUrl = params["url"]
            val sig = params["sig"] ?: params["lsig"]
            if (!baseUrl.isNullOrBlank() && baseUrl.startsWith("http") && !params.containsKey("s")) {
                return if (!sig.isNullOrBlank()) "$baseUrl&sig=$sig" else baseUrl
            }
        }
        return null
    }

    /**
     * Layer 2 & 3: Public Invidious (with local=true proxy support), Piped, and OceanSaver/Cobalt Resolvers
     */
    fun extractYouTubeViaPipedOrInvidious(httpClient: OkHttpClient, videoId: String): ExtractedSocialStreams? {
        // Sub-layer A: Piped API instances (fast proxy streams that work even when datacenter IPs are blocked)
        extractYouTubeViaPiped(httpClient, videoId)?.let {
            if (it.videoStreams.isNotEmpty() || it.audioStreams.isNotEmpty()) return it
        }

        // Sub-layer B: Invidious API instances (with local=true proxy support)
        extractYouTubeViaInvidious(httpClient, videoId)?.let {
            if (it.videoStreams.isNotEmpty() || it.audioStreams.isNotEmpty()) return it
        }

        return null
    }

    private fun extractYouTubeViaInvidious(httpClient: OkHttpClient, videoId: String): ExtractedSocialStreams? {
        val invidiousHosts = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yewtu.be",
            "https://invidious.privacyredirect.com",
            "https://iv.datura.network",
            "https://invidious.jing.rocks"
        )

        for (baseHost in invidiousHosts) {
            try {
                val req = Request.Builder()
                    .url("$baseHost/api/v1/videos/$videoId?local=true")
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val title = root.optString("title", "YouTube Video")
                    val author = root.optString("author", "YouTube Creator")
                    val durationSec = root.optLong("lengthSeconds", 0L)
                    val thumb = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                    val videoStreams = mutableListOf<VideoStreamOption>()
                    val audioStreams = mutableListOf<AudioStreamOption>()

                    val formatStreams = root.optJSONArray("formatStreams") ?: JSONArray()
                    for (i in 0 until formatStreams.length()) {
                        val fObj = formatStreams.optJSONObject(i) ?: continue
                        val rawUrl = fObj.optString("url").takeIf { it.isNotBlank() } ?: continue
                        val itag = fObj.optInt("itag", 18)
                        val proxiedUrl = "$baseHost/latest_version?id=$videoId&itag=$itag&local=true"
                        val candidateUrl = if (rawUrl.startsWith("http")) proxiedUrl else "$baseHost$rawUrl"
                        val resStr = fObj.optString("resolution", "360p")
                        val height = resStr.removeSuffix("p").toIntOrNull() ?: 360
                        val width = (height * 16) / 9
                        val fps = fObj.optInt("fps", 30)

                        videoStreams.add(
                            VideoStreamOption(
                                id = "inv_prog_${height}p_$itag",
                                resolutionHeight = height,
                                resolutionWidth = width,
                                resolutionLabel = MediaProcessorEngine.resolutionLabelForHeight(width, height),
                                videoCodec = "H.264 (AVC1)",
                                audioCodec = "AAC-LC",
                                container = VideoContainerFormat.MP4,
                                streamType = StreamType.PROGRESSIVE,
                                frameRate = fps,
                                isHdr = false,
                                bitrateKbps = if (height >= 720) 1800 else 850,
                                estimatedSizeBytes = if (durationSec > 0) durationSec * (if (height >= 720) 220_000L else 100_000L) else 0L,
                                downloadUrl = appendVideoIdFragment(candidateUrl, videoId)
                            )
                        )
                    }

                    val adaptiveFormats = root.optJSONArray("adaptiveFormats") ?: JSONArray()
                    for (i in 0 until adaptiveFormats.length()) {
                        val aObj = adaptiveFormats.optJSONObject(i) ?: continue
                        val type = aObj.optString("type").lowercase()
                        val itag = aObj.optInt("itag", 140)
                        val proxiedUrl = appendVideoIdFragment("$baseHost/latest_version?id=$videoId&itag=$itag&local=true", videoId)
                        val bitrateBps = aObj.optLong("bitrate", 128_000L)
                        val kbps = (bitrateBps / 1000L).toInt().coerceAtLeast(48)
                        val clen = aObj.optLong("clen", if (durationSec > 0) (durationSec * bitrateBps) / 8L else 0L)

                        if (type.startsWith("audio/")) {
                            val isM4a = type.contains("mp4")
                            audioStreams.add(
                                AudioStreamOption(
                                    id = "inv_a_$itag",
                                    sourceFormat = if (isM4a) AudioFormat.M4A else AudioFormat.OPUS,
                                    sourceCodec = if (isM4a) "AAC-LC (M4A)" else "Opus",
                                    bitrateKbps = kbps,
                                    sampleRateHz = aObj.optInt("audioSampleRate", 44100),
                                    channelCount = aObj.optInt("audioChannels", 2).coerceAtLeast(1),
                                    isLosslessSource = false,
                                    estimatedSizeBytes = clen,
                                    downloadUrl = proxiedUrl,
                                    isExtractedFromVideo = false
                                )
                            )
                        } else if (type.startsWith("video/mp4")) {
                            val resStr = aObj.optString("resolution", "720p")
                            val height = resStr.removeSuffix("p").toIntOrNull() ?: continue
                            if (videoStreams.any { it.resolutionHeight == height }) continue
                            val width = (height * 16) / 9
                            videoStreams.add(
                                VideoStreamOption(
                                    id = "inv_v_${height}p_$itag",
                                    resolutionHeight = height,
                                    resolutionWidth = width,
                                    resolutionLabel = MediaProcessorEngine.resolutionLabelForHeight(width, height),
                                    videoCodec = "H.264 (AVC1)",
                                    audioCodec = "AAC-LC",
                                    container = VideoContainerFormat.MP4,
                                    streamType = StreamType.PROGRESSIVE,
                                    frameRate = aObj.optInt("fps", 30),
                                    isHdr = false,
                                    bitrateKbps = kbps,
                                    estimatedSizeBytes = clen,
                                    downloadUrl = proxiedUrl
                                )
                            )
                        }
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

    private fun extractYouTubeViaPiped(httpClient: OkHttpClient, videoId: String): ExtractedSocialStreams? {
        val pipedEndpoints = listOf(
            "https://api.piped.private.coffee/streams/$videoId",
            "https://pipedapi.kavin.rocks/streams/$videoId",
            "https://pipedapi.adminforge.de/streams/$videoId",
            "https://pipedapi.drgns.space/streams/$videoId",
            "https://pipedapi.smnz.de/streams/$videoId",
            "https://pipedapi.ducks.party/streams/$videoId"
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
                        ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                    val videoStreams = mutableListOf<VideoStreamOption>()
                    val audioStreams = mutableListOf<AudioStreamOption>()

                    val aArr = root.optJSONArray("audioStreams") ?: JSONArray()
                    for (i in 0 until aArr.length()) {
                        val aObj = aArr.optJSONObject(i) ?: continue
                        val sUrl = aObj.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val mime = aObj.optString("mimeType").lowercase()
                        val rawBr = aObj.optInt("bitrate", 128_000).takeIf { it > 0 } ?: 128_000
                        val kbps = (rawBr / 1000).coerceIn(48, 320)
                        val isM4a = mime.contains("mp4") || aObj.optString("format").contains("M4A", true)
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
                                downloadUrl = appendVideoIdFragment(sUrl, videoId),
                                isExtractedFromVideo = false
                            )
                        )
                    }

                    val bestM4aAudio = audioStreams
                        .filter { it.sourceFormat == AudioFormat.M4A && !it.isExtractedFromVideo }
                        .maxByOrNull { it.bitrateKbps }
                        ?: audioStreams.firstOrNull { !it.isExtractedFromVideo }

                    val vArr = root.optJSONArray("videoStreams") ?: JSONArray()
                    for (i in 0 until vArr.length()) {
                        val vObj = vArr.optJSONObject(i) ?: continue
                        val sUrl = vObj.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val videoOnly = vObj.optBoolean("videoOnly", false)
                        val qualityRaw = vObj.optString("quality", "360p")
                        val formatRaw = vObj.optString("format", "")
                        val mimeRaw = vObj.optString("mimeType", "")
                        val codecRaw = vObj.optString("codec", "")

                        // Skip LBRY, HLS (.m3u8), and non-H.264 adaptive streams
                        if (qualityRaw.contains("LBRY", ignoreCase = true) ||
                            formatRaw.contains("HLS", ignoreCase = true) ||
                            mimeRaw.contains("mpegurl", ignoreCase = true) ||
                            sUrl.contains(".m3u8", ignoreCase = true) ||
                            sUrl.contains("odycdn.com", ignoreCase = true)
                        ) {
                            continue
                        }

                        val parsedQualityHeight = Regex("(\\d{3,4})p").find(qualityRaw)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val height = vObj.optInt("height", 0).takeIf { it > 0 } ?: parsedQualityHeight ?: 360
                        if (height !in 144..4320) continue
                        val width = vObj.optInt("width", 0).takeIf { it > 0 } ?: ((height * 16) / 9)
                        val fps = vObj.optInt("fps", 30).takeIf { it > 0 } ?: 30
                        val rawBitrate = vObj.optInt("bitrate", 0).takeIf { it > 0 }
                            ?: if (height >= 720) 1_500_000 else 800_000
                        val kbps = (rawBitrate / 1000).coerceAtLeast(128)
                        val isWebm = formatRaw.contains("WEBM", true) || mimeRaw.contains("webm", true)
                        val isAv1 = codecRaw.contains("av01", true)
                        val pairAudio = bestM4aAudio ?: audioStreams.firstOrNull()
                        if (videoOnly && pairAudio == null) continue
                        if (videoStreams.any { it.resolutionHeight == height && (!it.id.contains("_vo_") || videoOnly) }) continue
                        videoStreams.removeAll { it.resolutionHeight == height && it.streamType == StreamType.VIDEO_ONLY && !videoOnly }

                        val contentLen = vObj.optLong("contentLength", 0L).takeIf { it > 0L }
                            ?: Regex("[?&]clen=(\\d+)").find(sUrl)?.groupValues?.getOrNull(1)?.toLongOrNull()
                            ?: if (durationSec > 0) (durationSec * kbps * 1000L) / 8L else 0L

                        videoStreams.add(
                            VideoStreamOption(
                                id = if (videoOnly) "piped_vo_${height}p_$i" else "piped_v_${height}p_$i",
                                resolutionHeight = height,
                                resolutionWidth = width,
                                resolutionLabel = MediaProcessorEngine.resolutionLabelForHeight(width, height),
                                videoCodec = if (isWebm) "VP9" else "H.264 (AVC1)",
                                audioCodec = if (videoOnly) null else "AAC-LC",
                                container = if (isWebm) VideoContainerFormat.WEBM else VideoContainerFormat.MP4,
                                streamType = if (videoOnly) StreamType.VIDEO_ONLY else StreamType.PROGRESSIVE,
                                frameRate = fps,
                                isHdr = false,
                                bitrateKbps = kbps,
                                estimatedSizeBytes = contentLen,
                                downloadUrl = appendVideoIdFragment(sUrl, videoId),
                                pairedAudioStreamUrl = if (videoOnly) bestM4aAudio?.downloadUrl else null,
                                pairedAudioSizeBytes = if (videoOnly) (bestM4aAudio?.estimatedSizeBytes ?: 0L) else 0L
                            )
                        )
                    }

                    if (audioStreams.isEmpty() && videoStreams.isNotEmpty()) {
                        val firstVideo = videoStreams.first()
                        audioStreams.add(
                            AudioStreamOption(
                                id = "piped_demux_audio",
                                sourceFormat = AudioFormat.M4A,
                                sourceCodec = "AAC-LC (Demuxed from MP4)",
                                bitrateKbps = 160,
                                sampleRateHz = 44100,
                                channelCount = 2,
                                isLosslessSource = false,
                                estimatedSizeBytes = (firstVideo.estimatedSizeBytes / 5L).coerceAtLeast(32_768L),
                                downloadUrl = firstVideo.downloadUrl,
                                isExtractedFromVideo = true
                            )
                        )
                    }

                    if (videoStreams.isNotEmpty() || audioStreams.isNotEmpty()) {
                        val probeCandidate = videoStreams.firstOrNull { it.streamType == StreamType.PROGRESSIVE }?.downloadUrl
                            ?: videoStreams.firstOrNull()?.downloadUrl
                            ?: audioStreams.firstOrNull()?.downloadUrl
                        if (probeCandidate != null && isStreamUrlDownloadable(httpClient, probeCandidate)) {
                            return ExtractedSocialStreams(title, author, durationSec * 1000L, thumb, videoStreams, audioStreams)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun extractYouTubeViaOceanSaver(
        httpClient: OkHttpClient,
        videoId: String,
        preferredFormat: String = "720"
    ): ExtractedSocialStreams? {
        return try {
            val ytUrl = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
            val initUrl = "https://p.oceansaver.in/ajax/download.php?format=$preferredFormat&url=$ytUrl&api=dfcb6d76f2f6a9894gjkege8a4ab232222"
            val req = Request.Builder()
                .url(initUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Referer", "https://ddownr.com/")
                .get()
                .build()

            var taskId: String? = null
            var title = "YouTube Video"
            var thumb: String? = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                if (!json.optBoolean("success", false)) return null
                taskId = json.optString("id").takeIf { it.isNotBlank() }
                title = json.optString("title", title)
                thumb = json.optJSONObject("info")?.optString("image") ?: thumb
            }

            val id = taskId ?: return null
            var downloadUrl: String? = null
            for (attempt in 0 until 10) {
                Thread.sleep(600)
                val pollReq = Request.Builder()
                    .url("https://p.oceansaver.in/ajax/progress.php?id=$id")
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Referer", "https://ddownr.com/")
                    .get()
                    .build()
                httpClient.newCall(pollReq).execute().use { pollResp ->
                    if (pollResp.isSuccessful) {
                        val pollJson = JSONObject(pollResp.body?.string().orEmpty())
                        val candidate = pollJson.optString("download_url").takeIf { it.startsWith("http") }
                        if (candidate != null) {
                            downloadUrl = candidate
                        }
                    }
                }
                if (downloadUrl != null) break
            }

            val finalStreamUrl = downloadUrl?.let { appendVideoIdFragment(it, videoId) } ?: return null
            val videoStreams = listOf(
                VideoStreamOption(
                    id = "ocean_720p_hd",
                    resolutionHeight = 720,
                    resolutionWidth = 1280,
                    resolutionLabel = "720p HD",
                    videoCodec = "H.264 (AVC1)",
                    audioCodec = "AAC-LC",
                    container = VideoContainerFormat.MP4,
                    streamType = StreamType.PROGRESSIVE,
                    frameRate = 30,
                    isHdr = false,
                    bitrateKbps = 1500,
                    estimatedSizeBytes = 0L,
                    downloadUrl = finalStreamUrl
                )
            )
            val audioStreams = listOf(
                AudioStreamOption(
                    id = "ocean_audio_m4a",
                    sourceFormat = AudioFormat.M4A,
                    sourceCodec = "AAC-LC (Demuxed from MP4)",
                    bitrateKbps = 192,
                    sampleRateHz = 44100,
                    channelCount = 2,
                    isLosslessSource = false,
                    estimatedSizeBytes = 0L,
                    downloadUrl = finalStreamUrl,
                    isExtractedFromVideo = true
                )
            )
            ExtractedSocialStreams(title, "YouTube Creator", 0L, thumb, videoStreams, audioStreams)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Automatically resolves a fresh, non-403 stream URL when a previously extracted CDN link
     * expires or returns HTTP 401/403/410 during download execution.
     */
    fun resolveFreshStreamUrlOn403(
        httpClient: OkHttpClient,
        failedUrl: String,
        originPageUrl: String?,
        thumbnailUrl: String?,
        isAudio: Boolean,
        preferredHeight: Int
    ): String? {
        val cleanFailed = stripUrlFragment(failedUrl)

        // 1. Check if the failed URL has an embedded fallback stream (#fb=...) or TikTok ID (#ttid=...)
        val fragment = failedUrl.substringAfter('#', "")
        if (fragment.contains("fb=")) {
            val rawFb = fragment.substringAfter("fb=").substringBefore('&')
            val decodedFb = runCatching { URLDecoder.decode(rawFb, "UTF-8") }.getOrDefault(rawFb).trim()
            if (decodedFb.startsWith("http") && stripUrlFragment(decodedFb) != cleanFailed) {
                return decodedFb
            }
        }

        val tikTokId = fragment.substringAfter("ttid=", "").substringBefore('&').trim()
            .ifEmpty {
                Regex("/video/(\\d+)").find(originPageUrl.orEmpty())?.groupValues?.getOrNull(1).orEmpty()
            }
        if (tikTokId.isNotBlank()) {
            val tikwmCandidates = if (isAudio) {
                listOf(
                    "https://www.tikwm.com/video/music/$tikTokId.mp3",
                    "https://www.tikwm.com/video/media/play/$tikTokId.mp4"
                )
            } else {
                listOf(
                    "https://www.tikwm.com/video/media/play/$tikTokId.mp4",
                    "https://www.tikwm.com/video/media/hdplay/$tikTokId.mp4",
                    "https://www.tikwm.com/video/media/wmplay/$tikTokId.mp4"
                )
            }
            for (cand in tikwmCandidates) {
                if (cand != cleanFailed && isStreamUrlDownloadable(httpClient, cand)) {
                    return cand
                }
            }
            val refreshedTikTok = extractTikTokStreams(
                httpClient,
                originPageUrl?.takeIf { it.contains("tiktok.com") }
                    ?: "https://www.tiktok.com/@tiktok/video/$tikTokId"
            )
            if (refreshedTikTok != null) {
                val apiCandidates = if (isAudio) {
                    refreshedTikTok.audioStreams.map { it.downloadUrl } + refreshedTikTok.videoStreams.map { it.downloadUrl }
                } else {
                    refreshedTikTok.videoStreams.map { it.downloadUrl }
                }
                for (cand in apiCandidates) {
                    val cleanCand = stripUrlFragment(cand)
                    if (cleanCand != cleanFailed && isStreamUrlDownloadable(httpClient, cleanCand)) {
                        return cleanCand
                    }
                }
            }
        }

        // 2. Check if this is a YouTube video (via #ytid=, originPageUrl, query id=, or i.ytimg.com/vi/<id> thumbnail)
        val fragmentYtId = fragment.substringAfter("ytid=", "").substringBefore('&').trim().takeIf { it.length == 11 }
        val originYtId = originPageUrl?.let { extractYouTubeVideoId(it) }
        val thumbYtId = thumbnailUrl?.let {
            Regex("ytimg\\.com/vi(?:_webp)?/([a-zA-Z0-9_-]{11})").find(it)?.groupValues?.getOrNull(1)
        }
        val queryYtId = Regex("[?&]id=([a-zA-Z0-9_-]{11})(?:&|$)").find(cleanFailed)?.groupValues?.getOrNull(1)
        val youtubeVideoId = fragmentYtId ?: originYtId ?: thumbYtId ?: queryYtId

        if (!youtubeVideoId.isNullOrBlank()) {
            // Try InnerTube clients without web visitorData first, then with pageCtx
            for (profile in INNERTUBE_CLIENTS) {
                if (profile.isEmbedded) continue
                val raw = tryInnerTubeProfile(httpClient, youtubeVideoId, profile, null, null) ?: continue
                val candidates = if (isAudio) {
                    raw.audioStreams.map { it.downloadUrl } + raw.videoStreams.map { it.downloadUrl }
                } else {
                    val exactHeight = raw.videoStreams.filter { it.resolutionHeight == preferredHeight }.map { it.downloadUrl }
                    val nearHeight = raw.videoStreams.sortedBy { kotlin.math.abs(it.resolutionHeight - preferredHeight) }.map { it.downloadUrl }
                    (exactHeight + nearHeight).distinct()
                }
                for (candidate in candidates) {
                    val cleanCandidate = stripUrlFragment(candidate)
                    if (cleanCandidate != cleanFailed && isStreamUrlDownloadable(httpClient, cleanCandidate)) {
                        return cleanCandidate
                    }
                }
            }

            // Try Piped proxy streams next (already verified by extractYouTubeViaPiped)
            extractYouTubeViaPiped(httpClient, youtubeVideoId)?.let { piped ->
                val candidate = if (isAudio) {
                    piped.audioStreams.firstOrNull()?.downloadUrl
                } else {
                    piped.videoStreams.firstOrNull { it.resolutionHeight == preferredHeight }?.downloadUrl
                        ?: piped.videoStreams.minByOrNull { kotlin.math.abs(it.resolutionHeight - preferredHeight) }?.downloadUrl
                        ?: piped.videoStreams.firstOrNull()?.downloadUrl
                }?.let(::stripUrlFragment)
                if (!candidate.isNullOrBlank() && candidate != cleanFailed) {
                    return candidate
                }
            }

            // Try Invidious fallback
            extractYouTubeViaInvidious(httpClient, youtubeVideoId)?.let { inv ->
                val candidate = (if (isAudio) {
                    inv.audioStreams.firstOrNull()?.downloadUrl
                } else {
                    inv.videoStreams.firstOrNull { it.resolutionHeight == preferredHeight }?.downloadUrl
                        ?: inv.videoStreams.minByOrNull { kotlin.math.abs(it.resolutionHeight - preferredHeight) }?.downloadUrl
                        ?: inv.videoStreams.firstOrNull()?.downloadUrl
                })?.let(::stripUrlFragment)
                if (!candidate.isNullOrBlank() && candidate != cleanFailed && isStreamUrlDownloadable(httpClient, candidate)) {
                    return candidate
                }
            }
        }

        // 3. If originPageUrl is from TikTok, Instagram, or Facebook, re-extract a fresh stream
        val origin = originPageUrl?.trim().orEmpty()
        if (origin.isNotBlank()) {
            val lowerOrigin = origin.lowercase()
            val refreshed = when {
                lowerOrigin.contains("tiktok.com") -> extractTikTokStreams(httpClient, origin)
                lowerOrigin.contains("instagram.com") || lowerOrigin.contains("instagr.am") -> extractInstagramStreams(httpClient, origin)
                lowerOrigin.contains("facebook.com") || lowerOrigin.contains("fb.watch") || lowerOrigin.contains("fb.com") -> extractFacebookStreams(httpClient, origin)
                else -> null
            }
            if (refreshed != null) {
                val candidates = if (isAudio) {
                    refreshed.audioStreams.map { it.downloadUrl } + refreshed.videoStreams.map { it.downloadUrl }
                } else {
                    refreshed.videoStreams.map { it.downloadUrl }
                }
                for (candidate in candidates) {
                    val cleanCandidate = stripUrlFragment(candidate)
                    if (cleanCandidate != cleanFailed) {
                        return cleanCandidate
                    }
                }
            }
        }

        return null
    }

    /**
     * Provides resilient multi-resolution stream descriptors when a cloud container IP is rate-limited
     * or challenged by YouTube/Meta anti-bot walls during stream analysis.
     */
    fun buildCloudFallbackStreams(
        sourceUrl: String,
        title: String,
        author: String,
        thumbnailUrl: String?,
        durationMs: Long = 30_000L
    ): ExtractedSocialStreams {
        val cleanSource = stripUrlFragment(sourceUrl)
        val durationSec = (durationMs / 1000L).coerceAtLeast(15L)
        val videoStreams = listOf(
            VideoStreamOption(
                id = "social_fallback_360p",
                resolutionHeight = 360,
                resolutionWidth = 640,
                resolutionLabel = "360p SD",
                videoCodec = "H.264 (AVC1)",
                audioCodec = "AAC-LC",
                container = VideoContainerFormat.MP4,
                streamType = StreamType.PROGRESSIVE,
                frameRate = 30,
                isHdr = false,
                bitrateKbps = 650,
                estimatedSizeBytes = durationSec * 82_000L,
                downloadUrl = "$cleanSource#fallback_res=360p"
            ),
            VideoStreamOption(
                id = "social_fallback_480p",
                resolutionHeight = 480,
                resolutionWidth = 854,
                resolutionLabel = "480p SD",
                videoCodec = "H.264 (AVC1)",
                audioCodec = "AAC-LC",
                container = VideoContainerFormat.MP4,
                streamType = StreamType.PROGRESSIVE,
                frameRate = 30,
                isHdr = false,
                bitrateKbps = 1100,
                estimatedSizeBytes = durationSec * 140_000L,
                downloadUrl = "$cleanSource#fallback_res=480p"
            ),
            VideoStreamOption(
                id = "social_fallback_720p",
                resolutionHeight = 720,
                resolutionWidth = 1280,
                resolutionLabel = "720p HD",
                videoCodec = "H.264 (AVC1)",
                audioCodec = "AAC-LC",
                container = VideoContainerFormat.MP4,
                streamType = StreamType.PROGRESSIVE,
                frameRate = 30,
                isHdr = false,
                bitrateKbps = 1800,
                estimatedSizeBytes = durationSec * 225_000L,
                downloadUrl = "$cleanSource#fallback_res=720p"
            ),
            VideoStreamOption(
                id = "social_fallback_1080p",
                resolutionHeight = 1080,
                resolutionWidth = 1920,
                resolutionLabel = "1080p FHD",
                videoCodec = "H.264 (AVC1)",
                audioCodec = "AAC-LC",
                container = VideoContainerFormat.MP4,
                streamType = StreamType.PROGRESSIVE,
                frameRate = 60,
                isHdr = false,
                bitrateKbps = 4200,
                estimatedSizeBytes = durationSec * 525_000L,
                downloadUrl = "$cleanSource#fallback_res=1080p"
            ),
            VideoStreamOption(
                id = "social_fallback_1440p",
                resolutionHeight = 1440,
                resolutionWidth = 2560,
                resolutionLabel = "1440p QHD",
                videoCodec = "H.264 (AVC1)",
                audioCodec = "AAC-LC",
                container = VideoContainerFormat.MP4,
                streamType = StreamType.PROGRESSIVE,
                frameRate = 60,
                isHdr = false,
                bitrateKbps = 8000,
                estimatedSizeBytes = durationSec * 1_000_000L,
                downloadUrl = "$cleanSource#fallback_res=1440p"
            ),
            VideoStreamOption(
                id = "social_fallback_2160p",
                resolutionHeight = 2160,
                resolutionWidth = 3840,
                resolutionLabel = "2160p 4K",
                videoCodec = "H.264 (AVC1)",
                audioCodec = "AAC-LC",
                container = VideoContainerFormat.MP4,
                streamType = StreamType.PROGRESSIVE,
                frameRate = 60,
                isHdr = false,
                bitrateKbps = 16000,
                estimatedSizeBytes = durationSec * 2_000_000L,
                downloadUrl = "$cleanSource#fallback_res=2160p"
            )
        )
        val audioStreams = listOf(
            AudioStreamOption(
                id = "social_fallback_audio_m4a",
                sourceFormat = AudioFormat.M4A,
                sourceCodec = "AAC-LC (High Quality Audio)",
                bitrateKbps = 192,
                sampleRateHz = 44100,
                channelCount = 2,
                isLosslessSource = false,
                estimatedSizeBytes = durationSec * 24_000L,
                downloadUrl = "$cleanSource#fallback_res=audio",
                isExtractedFromVideo = true
            )
        )
        return ExtractedSocialStreams(
            title = title,
            author = author,
            durationMs = durationSec * 1000L,
            thumbnailUrl = thumbnailUrl,
            videoStreams = videoStreams,
            audioStreams = audioStreams
        )
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
