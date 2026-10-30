package com.example

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.download.DownloadEngine
import com.example.data.download.EnqueueResult
import com.example.data.local.AppDatabase
import com.example.data.media.LocalSampleMediaGenerator
import com.example.data.platform.PlatformAdapterRegistry
import com.example.domain.model.AnalyzedMediaMetadata
import com.example.domain.model.AudioFormat
import com.example.domain.model.DownloadStatus
import com.example.domain.model.MediaAnalysisState
import com.example.domain.model.MediaPlatform
import com.example.domain.model.StreamType
import com.example.domain.model.VideoContainerFormat
import com.example.domain.model.VideoStreamOption
import com.example.domain.validation.UrlValidator
import com.example.ui.MediaSaveViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name resource is MediaSave`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MediaSave", appName)
    }

    @Test
    fun `clipboard paste handles empty clipboard valid url and invalid clipboard text`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val viewModel = MediaSaveViewModel(app)

        // 1. Empty clipboard should NOT insert a sample URI and must show a clear message
        clipboard.clearPrimaryClip()
        viewModel.clearUrlInput()
        viewModel.pasteFromClipboard(null)

        assertEquals("", viewModel.urlInput.value)
        val emptyState = viewModel.analysisState.value
        assertTrue(emptyState is MediaAnalysisState.Error)
        assertEquals("Clipboard is Empty", (emptyState as MediaAnalysisState.Error).errorTitle)
        assertTrue(viewModel.snackbarMessage.value.orEmpty().contains("Clipboard is empty"))

        // 2. Valid URL on Android clipboard is read and inserted into urlInput
        val validUrl = "https://www.w3schools.com/html/mov_bbb.mp4"
        clipboard.setPrimaryClip(ClipData.newPlainText("media_url", validUrl))
        viewModel.pasteFromClipboard(null)

        assertEquals(validUrl, viewModel.urlInput.value)
        assertEquals(MediaAnalysisState.Idle, viewModel.analysisState.value)

        // 3. Invalid text on Android clipboard is inserted into urlInput and flagged with a validation error
        clipboard.setPrimaryClip(ClipData.newPlainText("bad_text", "not_a_valid_url_at_all"))
        viewModel.pasteFromClipboard(null)

        assertEquals("not_a_valid_url_at_all", viewModel.urlInput.value)
        assertTrue(viewModel.analysisState.value is MediaAnalysisState.Error)
    }

    @Test
    fun `platform adapter registry enforces compliance for restricted platforms and parses authorized local manifests`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val samples = LocalSampleMediaGenerator.ensureAuthorizedSamplesReady(context)
        assertTrue(samples.isNotEmpty())

        val registry = PlatformAdapterRegistry()

        // 1. Authorized multi-resolution local manifest should succeed with multiple sorted resolutions
        val multiVideoSample = samples.first { it.id == "sample_multi_video" }
        val analysis = registry.validateAndAnalyze(multiVideoSample.uri)
        assertTrue(analysis is MediaAnalysisState.Success)
        val success = analysis as MediaAnalysisState.Success
        assertTrue(success.metadata.videoStreams.size >= 3)
        // Resolutions must be ordered from lowest to highest
        val heights = success.metadata.videoStreams.map { it.resolutionHeight }
        assertEquals(heights.sorted(), heights)

        // 2. Direct single-file URI should be inspected for real container/tracks
        val wavSample = samples.first { it.id == "sample_master_wav" }
        val wavAnalysis = registry.validateAndAnalyze(wavSample.uri)
        assertTrue(wavAnalysis is MediaAnalysisState.Success)
        val wavMeta = (wavAnalysis as MediaAnalysisState.Success).metadata
        assertTrue(wavMeta.audioStreams.isNotEmpty())
        assertEquals(AudioFormat.WAV, wavMeta.audioStreams.first().sourceFormat)

        // 3. Social URLs (YouTube, Facebook, Instagram, TikTok) extract downloadable video & audio streams
        val socialTestClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val reqUrl = req.url.toString()
                val bodyJson = when {
                    reqUrl.contains("youtubei/v1/player") -> """
                        {
                          "playabilityStatus": {"status": "OK"},
                          "videoDetails": {"title": "Test YouTube Clip", "author": "YT Channel", "lengthSeconds": "30"},
                          "streamingData": {
                            "formats": [
                              {"itag": 22, "url": "https://cdn.example.org/yt_720p.mp4", "mimeType": "video/mp4", "width": 1280, "height": 720, "bitrate": 1500000, "fps": 30}
                            ],
                            "adaptiveFormats": [
                              {"itag": 140, "url": "https://cdn.example.org/yt_audio.m4a", "mimeType": "audio/mp4", "bitrate": 128000, "audioSampleRate": "44100", "audioChannels": 2}
                            ]
                          }
                        }
                    """.trimIndent()
                    reqUrl.contains("tikwm.com/api") -> """
                        {
                          "code": 0,
                          "data": {
                            "id": "734567890",
                            "title": "TikTok Dance Clip",
                            "duration": 15,
                            "hdplay": "https://cdn.example.org/tiktok_1080p.mp4",
                            "play": "https://cdn.example.org/tiktok_720p.mp4",
                            "music": "https://cdn.example.org/tiktok_audio.mp3",
                            "author": {"nickname": "Creator"}
                          }
                        }
                    """.trimIndent()
                    reqUrl.contains("facebook.com") -> """
                        <html><head><title>FB Watch Clip</title></head><body>
                        {"browser_native_hd_url":"https:\/\/cdn.example.org\/fb_1080p.mp4","browser_native_sd_url":"https:\/\/cdn.example.org\/fb_480p.mp4"}
                        </body></html>
                    """.trimIndent()
                    reqUrl.contains("instagram.com") -> """
                        <html><body>{"video_url":"https:\/\/cdn.example.org\/ig_reel_1080p.mp4","username":"igcreator"}</body></html>
                    """.trimIndent()
                    else -> "{}"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", if (reqUrl.contains("facebook.com") || reqUrl.contains("instagram.com")) "text/html" else "application/json")
                    .body(bodyJson.toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val socialRegistry = PlatformAdapterRegistry(socialTestClient)

        val ytState = socialRegistry.validateAndAnalyze("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(ytState is MediaAnalysisState.Success)
        assertEquals(MediaPlatform.YOUTUBE, (ytState as MediaAnalysisState.Success).metadata.platform)
        assertTrue(ytState.metadata.videoStreams.isNotEmpty())

        val fbState = socialRegistry.validateAndAnalyze("https://www.facebook.com/watch/?v=123456789")
        assertTrue(fbState is MediaAnalysisState.Success)
        assertEquals(MediaPlatform.FACEBOOK, (fbState as MediaAnalysisState.Success).metadata.platform)
        assertTrue(fbState.metadata.videoStreams.isNotEmpty())

        val igState = socialRegistry.validateAndAnalyze("https://www.instagram.com/reel/C8xYz123AbC/")
        assertTrue(igState is MediaAnalysisState.Success)
        assertEquals(MediaPlatform.INSTAGRAM, (igState as MediaAnalysisState.Success).metadata.platform)
        assertTrue(igState.metadata.videoStreams.isNotEmpty())

        val ttState = socialRegistry.validateAndAnalyze("https://vt.tiktok.com/ZSgs61xSd/")
        assertTrue(ttState is MediaAnalysisState.Success)
        assertEquals(MediaPlatform.TIKTOK, (ttState as MediaAnalysisState.Success).metadata.platform)
        assertTrue(ttState.metadata.videoStreams.isNotEmpty())
        assertTrue(ttState.metadata.audioStreams.isNotEmpty())
    }

    @Test
    fun `download engine executes real video download and audio format conversion with progress and checksum`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.downloadTaskDao()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = DownloadEngine(context, dao, scope)
        val registry = PlatformAdapterRegistry()

        val samples = LocalSampleMediaGenerator.ensureAuthorizedSamplesReady(context)
        val videoSample = samples.first { it.id == "sample_multi_video" }
        val analysis = registry.validateAndAnalyze(videoSample.uri) as MediaAnalysisState.Success
        val stream720p = analysis.metadata.videoStreams.first { it.resolutionHeight == 720 }

        val enqueueRes = engine.enqueueVideoDownload(
            metadata = analysis.metadata,
            stream = stream720p,
            allowDuplicate = true
        )
        assertTrue(enqueueRes is EnqueueResult.Enqueued)
        val taskId = (enqueueRes as EnqueueResult.Enqueued).task.id

        // Wait for real file transfer to complete
        var completedTask = dao.getTaskById(taskId)
        var attempts = 0
        while (completedTask?.statusEnum != DownloadStatus.COMPLETED && attempts < 60) {
            delay(100)
            completedTask = dao.getTaskById(taskId)
            attempts++
        }

        assertNotNull(completedTask)
        assertEquals(DownloadStatus.COMPLETED, completedTask!!.statusEnum)
        assertEquals(100, completedTask.progressPercent)
        assertNotNull(completedTask.checksumSha256)

        val downloadedFile = File(completedTask.outputFilePath)
        assertTrue(downloadedFile.exists())
        assertTrue(downloadedFile.length() > 0L)

        // Test Audio Download & AIFF conversion from WAV master
        val wavSample = samples.first { it.id == "sample_master_wav" }
        val wavAnalysis = registry.validateAndAnalyze(wavSample.uri) as MediaAnalysisState.Success
        val wavSourceStream = wavAnalysis.metadata.audioStreams.first()
        val aiffChoice = UrlValidator.buildSupportedAudioOutputChoices(wavSourceStream)
            .first { it.targetFormat == AudioFormat.AIFF }

        val audioEnqueue = engine.enqueueAudioDownload(
            metadata = wavAnalysis.metadata,
            sourceStream = wavSourceStream,
            outputChoice = aiffChoice,
            allowDuplicate = true
        )
        assertTrue(audioEnqueue is EnqueueResult.Enqueued)
        val audioTaskId = (audioEnqueue as EnqueueResult.Enqueued).task.id

        var completedAudio = dao.getTaskById(audioTaskId)
        attempts = 0
        while (completedAudio?.statusEnum != DownloadStatus.COMPLETED && attempts < 60) {
            delay(100)
            completedAudio = dao.getTaskById(audioTaskId)
            attempts++
        }

        assertNotNull(completedAudio)
        assertEquals(DownloadStatus.COMPLETED, completedAudio!!.statusEnum)
        assertEquals(AudioFormat.AIFF.label, completedAudio.containerFormat)
        assertEquals("AIFF", completedAudio.targetAudioFormat)
        val aiffFile = File(completedAudio.outputFilePath)
        assertTrue(aiffFile.exists())
        assertTrue(aiffFile.length() > 54L)

        // Test deleting task and file
        engine.deleteTaskAndFile(taskId)
        engine.deleteTaskAndFile(audioTaskId)
        db.close()
    }

    @Test
    fun `download engine handles real http streams and rejects mock urls 404 expired 410 and html webpages`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.downloadTaskDao()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val validMp4Payload = ByteArray(4096) { idx ->
            when (idx) {
                0, 1, 2 -> 0
                3 -> 24
                4 -> 'f'.code.toByte()
                5 -> 't'.code.toByte()
                6 -> 'y'.code.toByte()
                7 -> 'p'.code.toByte()
                24, 25 -> 0
                26 -> 0x0F.toByte()
                27 -> 0xE8.toByte() // 4096 - 24 = 4072 bytes
                28 -> 'm'.code.toByte()
                29 -> 'd'.code.toByte()
                30 -> 'a'.code.toByte()
                31 -> 't'.code.toByte()
                else -> (idx % 251).toByte()
            }
        }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when {
                    url.contains("valid_video.mp4") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "video/mp4")
                            .header("Content-Length", validMp4Payload.size.toString())
                            .body(validMp4Payload.toResponseBody("video/mp4".toMediaType()))
                            .build()
                    }
                    url.contains("missing_video.mp4") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("Not Found".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    url.contains("expired_video.mp4") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(410)
                            .message("Gone")
                            .body("Gone".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    url.contains("webpage_disguised.mp4") -> {
                        val html = "<!DOCTYPE html><html><body>Login required</body></html>"
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "text/html; charset=utf-8")
                            .body(html.toResponseBody("text/html".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(500)
                            .message("Server Error")
                            .body("Error".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                }
            }
            .build()

        val engine = DownloadEngine(context, dao, scope, testHttpClient)
        val registry = PlatformAdapterRegistry(testHttpClient)

        // 1. Mock metadata URL must be rejected immediately and never enqueued
        val mockMeta = AnalyzedMediaMetadata(
            sourceUrl = "mock://video/sample.mp4",
            title = "Mock Video",
            authorOrDomain = "Mock",
            platform = MediaPlatform.DIRECT_URL,
            durationMs = 1000L,
            thumbnailUrl = null,
            supportsRangeRequests = false,
            videoStreams = emptyList(),
            audioStreams = emptyList(),
            inspectionMethod = "Test"
        )
        val mockStream = VideoStreamOption(
            id = "mock_1",
            resolutionHeight = 720,
            resolutionWidth = 1280,
            resolutionLabel = "720p HD",
            videoCodec = "H.264",
            audioCodec = "AAC",
            container = VideoContainerFormat.MP4,
            streamType = StreamType.PROGRESSIVE,
            frameRate = 30,
            isHdr = false,
            bitrateKbps = 1000,
            estimatedSizeBytes = 1024L,
            downloadUrl = "mock://video/sample.mp4"
        )
        val mockEnqueue = engine.enqueueVideoDownload(mockMeta, mockStream, allowDuplicate = true)
        assertTrue(mockEnqueue is EnqueueResult.Rejected)

        // 2. Webpage disguised with .mp4 extension must be rejected during analysis
        val htmlAnalysis = registry.validateAndAnalyze("https://cdn.example.org/webpage_disguised.mp4")
        assertTrue(htmlAnalysis is MediaAnalysisState.Error)
        assertEquals(
            "Webpage Instead of Direct Media Stream",
            (htmlAnalysis as MediaAnalysisState.Error).errorTitle
        )

        // 3. Valid HTTP 200 MP4 stream downloads, verifies size/checksum, and completes
        val validUrl = "https://cdn.example.org/valid_video.mp4"
        val validStream = mockStream.copy(
            id = "http_valid",
            estimatedSizeBytes = validMp4Payload.size.toLong(),
            downloadUrl = validUrl
        )
        val validEnqueue = engine.enqueueVideoDownload(
            mockMeta.copy(sourceUrl = validUrl, title = "HTTP Valid Clip"),
            validStream,
            allowDuplicate = true
        )
        assertTrue(validEnqueue is EnqueueResult.Enqueued)
        val validTaskId = (validEnqueue as EnqueueResult.Enqueued).task.id

        var validTask = dao.getTaskById(validTaskId)
        var attempts = 0
        while (validTask?.statusEnum?.isActive == true && attempts < 40) {
            delay(50)
            validTask = dao.getTaskById(validTaskId)
            attempts++
        }
        assertNotNull(validTask)
        assertEquals(DownloadStatus.COMPLETED, validTask!!.statusEnum)
        assertEquals(validMp4Payload.size.toLong(), File(validTask.outputFilePath).length())

        // 4. HTTP 404 Not Found transitions task to FAILED with meaningful 404 message
        val missingUrl = "https://cdn.example.org/missing_video.mp4"
        val missingEnqueue = engine.enqueueVideoDownload(
            mockMeta.copy(sourceUrl = missingUrl, title = "Missing Clip"),
            validStream.copy(id = "http_404", downloadUrl = missingUrl),
            allowDuplicate = true
        )
        val missingTaskId = (missingEnqueue as EnqueueResult.Enqueued).task.id
        var missingTask = dao.getTaskById(missingTaskId)
        attempts = 0
        while (missingTask?.statusEnum?.isActive == true && attempts < 40) {
            delay(50)
            missingTask = dao.getTaskById(missingTaskId)
            attempts++
        }
        assertNotNull(missingTask)
        assertEquals(DownloadStatus.FAILED, missingTask!!.statusEnum)
        assertTrue(missingTask.errorMessage.orEmpty().contains("404"))
        assertNull(missingTask.completedAt)

        // 5. HTTP 410 Expired Link transitions task to FAILED with expired link message
        val expiredUrl = "https://cdn.example.org/expired_video.mp4"
        val expiredEnqueue = engine.enqueueVideoDownload(
            mockMeta.copy(sourceUrl = expiredUrl, title = "Expired Clip"),
            validStream.copy(id = "http_410", downloadUrl = expiredUrl),
            allowDuplicate = true
        )
        val expiredTaskId = (expiredEnqueue as EnqueueResult.Enqueued).task.id
        var expiredTask = dao.getTaskById(expiredTaskId)
        attempts = 0
        while (expiredTask?.statusEnum?.isActive == true && attempts < 40) {
            delay(50)
            expiredTask = dao.getTaskById(expiredTaskId)
            attempts++
        }
        assertNotNull(expiredTask)
        assertEquals(DownloadStatus.FAILED, expiredTask!!.statusEnum)
        assertTrue(expiredTask.errorMessage.orEmpty().contains("expired", ignoreCase = true))

        db.close()
    }

    @Test
    fun `social media icons open external platform urls without routing to test media or modifying url input`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        val viewModel = MediaSaveViewModel(app)
        viewModel.clearUrlInput()

        val expectedPlatforms = listOf(
            "YouTube" to "https://www.youtube.com/",
            "Facebook" to "https://www.facebook.com/",
            "Instagram" to "https://www.instagram.com/",
            "TikTok" to "https://www.tiktok.com/"
        )

        for ((name, expectedUrl) in expectedPlatforms) {
            val result = viewModel.openSocialPlatform(name)
            assertTrue(
                result is com.example.domain.validation.SocialPlatformLaunchResult.LaunchedBrowser ||
                    result is com.example.domain.validation.SocialPlatformLaunchResult.LaunchedOfficialApp
            )
            // Must NOT inject sample URLs into urlInput or trigger analysis/test-media screen
            assertEquals("", viewModel.urlInput.value)
            assertEquals(MediaAnalysisState.Idle, viewModel.analysisState.value)

            val startedIntent = shadowApp.nextStartedActivity
            assertNotNull("Expected ACTION_VIEW intent for $name", startedIntent)
            assertEquals(android.content.Intent.ACTION_VIEW, startedIntent.action)
            assertEquals(expectedUrl, startedIntent.dataString)
        }
    }

    @Test
    fun `download engine classifies all error types specifically and recovers interrupted tasks on startup`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.downloadTaskDao()

        // Insert a task stuck in DOWNLOADING state from a previous killed session
        dao.upsertTask(
            com.example.domain.model.DownloadTaskEntity(
                id = "stuck_1",
                sourceUrl = "https://cdn.example.org/video.mp4",
                title = "Interrupted Video",
                author = "CDN",
                platformName = "Direct Media URL",
                mode = "VIDEO",
                outputFilePath = "/tmp/interrupted.mp4",
                fileName = "interrupted.mp4",
                mimeType = "video/mp4",
                containerFormat = "MP4",
                codecInfo = "H.264",
                totalBytes = 10_000_000L,
                downloadedBytes = 4_000_000L,
                status = DownloadStatus.DOWNLOADING.name,
                statusDetail = "Downloading..."
            )
        )

        val recoveredCount = dao.recoverInterruptedTasksOnStartup()
        assertEquals(1, recoveredCount)
        val recoveredTask = dao.getTaskById("stuck_1")
        assertNotNull(recoveredTask)
        assertEquals(DownloadStatus.PAUSED, recoveredTask!!.statusEnum)
        assertTrue(recoveredTask.statusDetail.contains("Interrupted by app restart"))

        val engine = DownloadEngine(context, dao)
        val exceptionsToTest = listOf(
            java.net.UnknownHostException("media.example.org"),
            java.net.SocketTimeoutException("Read timed out"),
            java.net.ConnectException("Connection refused"),
            javax.net.ssl.SSLException("Handshake failed"),
            SecurityException("Permission denied"),
            java.io.FileNotFoundException("/storage/missing.mp4"),
            java.io.EOFException("Premature EOF"),
            java.io.IOException("write failed: ENOSPC (No space left on device)"),
            IllegalArgumentException("Invalid URL"),
            IllegalStateException("Conversion failure")
        )

        for (ex in exceptionsToTest) {
            val formatted = engine.formatDownloadExceptionMessage(ex)
            assertTrue(formatted.isNotBlank())
            org.junit.Assert.assertFalse(
                "Error message should be specific and never fall back to generic 'Unexpected network or media storage error.'",
                formatted.contains("Unexpected network or media storage error")
            )
        }

        db.close()
    }

    @Test
    fun `manifest permissions and storage cleanup adhere to least privilege and MediaStore requirements`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pkgInfo = app.packageManager.getPackageInfo(
            app.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val requestedPermissions = pkgInfo.requestedPermissions?.toSet().orEmpty()

        // Required permissions must be declared
        assertTrue(requestedPermissions.contains(android.Manifest.permission.INTERNET))
        assertTrue(requestedPermissions.contains(android.Manifest.permission.ACCESS_NETWORK_STATE))
        assertTrue(requestedPermissions.contains(android.Manifest.permission.POST_NOTIFICATIONS))

        // Unnecessary broad/legacy storage permissions must NOT be declared
        org.junit.Assert.assertFalse(requestedPermissions.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        org.junit.Assert.assertFalse(requestedPermissions.contains(android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
        org.junit.Assert.assertFalse(requestedPermissions.contains(android.Manifest.permission.READ_MEDIA_VIDEO))
        org.junit.Assert.assertFalse(requestedPermissions.contains(android.Manifest.permission.READ_MEDIA_AUDIO))

        // Verify custom SAF download folder setting and reset
        val viewModel = MediaSaveViewModel(app)
        val safUri = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMyCustomMedia")
        viewModel.setCustomDownloadLocation(safUri)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Custom download location set to /storage/emulated/0/Download/MyCustomMedia", viewModel.snackbarMessage.value)

        viewModel.resetCustomDownloadLocation()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(viewModel.snackbarMessage.value.orEmpty().contains("MediaStore"))
    }

    @Test
    fun `http stream download executes on background IO thread and never throws NetworkOnMainThreadException`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.downloadTaskDao()

        val strictMainThreadGuardClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    throw android.os.NetworkOnMainThreadException()
                }
                val mp4Bytes = ByteArray(4096) { (it % 251).toByte() }
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Length", mp4Bytes.size.toString())
                    .body(mp4Bytes.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        // Even when given a Main-thread-bound scope, DownloadEngine must dispatch network I/O to Dispatchers.IO
        val mainBoundScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
        )
        val engine = DownloadEngine(
            context = context,
            dao = dao,
            externalScope = mainBoundScope,
            httpClient = strictMainThreadGuardClient
        )

        val streamOption = com.example.domain.model.VideoStreamOption(
            id = "yt_720p",
            resolutionLabel = "720p HD",
            resolutionWidth = 1280,
            resolutionHeight = 720,
            frameRate = 30,
            isHdr = false,
            container = com.example.domain.model.VideoContainerFormat.MP4,
            videoCodec = "H.264",
            audioCodec = "AAC",
            streamType = com.example.domain.model.StreamType.PROGRESSIVE,
            bitrateKbps = 1500,
            estimatedSizeBytes = 4096L,
            downloadUrl = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=18&c=ANDROID_VR"
        )
        val metadata = com.example.domain.model.AnalyzedMediaMetadata(
            sourceUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            title = "Test YouTube Video",
            authorOrDomain = "YouTube Creator",
            platform = com.example.domain.model.MediaPlatform.YOUTUBE,
            durationMs = 15_000L,
            thumbnailUrl = null,
            supportsRangeRequests = true,
            videoStreams = listOf(streamOption),
            audioStreams = emptyList(),
            inspectionMethod = "YouTube Stream Extractor"
        )

        val enqueueResult = engine.enqueueVideoDownload(metadata, streamOption)
        assertTrue(enqueueResult is EnqueueResult.Enqueued)
        val taskId = (enqueueResult as EnqueueResult.Enqueued).task.id

        var finalTask = dao.getTaskById(taskId)
        val deadline = System.currentTimeMillis() + 6000L
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            finalTask = dao.getTaskById(taskId)
            if (finalTask?.statusEnum == DownloadStatus.COMPLETED || finalTask?.statusEnum == DownloadStatus.FAILED) {
                break
            }
            kotlinx.coroutines.delay(50)
        }

        assertNotNull(finalTask)
        assertEquals(
            "Expected COMPLETED but failed with: ${finalTask?.errorMessage}",
            DownloadStatus.COMPLETED,
            finalTask?.statusEnum
        )
        db.close()
    }

    @Test
    fun `android share intent integration extracts social urls and opens download options directly`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()

        // 1. Verify UrlValidator.extractSharedUrlFromIntent extracts clean URLs from YouTube, TikTok, Instagram & Facebook share captions
        val tiktokShareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "Check out Creator's video! #TikTok https://vt.tiktok.com/ZSgs61xSd/!")
        }
        assertEquals("https://vt.tiktok.com/ZSgs61xSd/", UrlValidator.extractSharedUrlFromIntent(tiktokShareIntent))

        val youtubeShareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "Watch this awesome clip on YouTube: https://youtu.be/dQw4w9WgXcQ?si=xyz123.")
        }
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=xyz123", UrlValidator.extractSharedUrlFromIntent(youtubeShareIntent))

        val instagramShareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "https://www.instagram.com/reel/C8xYz123AbC/?igsh=abc")
        }
        assertEquals("https://www.instagram.com/reel/C8xYz123AbC/?igsh=abc", UrlValidator.extractSharedUrlFromIntent(instagramShareIntent))

        val facebookViewIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://fb.watch/rT9xYz123/")
        )
        assertEquals("https://fb.watch/rT9xYz123/", UrlValidator.extractSharedUrlFromIntent(facebookViewIntent))

        // 2. Verify MediaSaveViewModel.handleIncomingShareIntent sets isShareIntentFlow = true, bypasses Home screen,
        //    analyzes the shared media immediately, opens StreamSelectionBottomSheet, and starts the download
        val samples = LocalSampleMediaGenerator.ensureAuthorizedSamplesReady(app)
        val multiVideoSample = samples.first { it.id == "sample_multi_video" }

        val viewModel = MediaSaveViewModel(app)
        val mediaShareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "Shared MediaSave Stream: ${multiVideoSample.uri}")
        }

        val handled = viewModel.handleIncomingShareIntent(mediaShareIntent)
        assertTrue(handled)
        assertTrue(viewModel.isShareIntentFlow.value)
        assertEquals(multiVideoSample.uri, viewModel.urlInput.value)

        // Wait for background IO analysis to complete
        val deadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (viewModel.analysisState.value is MediaAnalysisState.Success) {
                break
            }
            delay(50)
        }

        assertTrue(viewModel.analysisState.value is MediaAnalysisState.Success)
        assertTrue("Stream selection sheet must open directly on Share Intent", viewModel.showStreamSelectionSheet.value)
        assertNotNull(viewModel.selectedVideoStream.value)

        // Start download directly from the Share Intent options sheet
        var navigatedToQueue = false
        viewModel.startSelectedDownload(
            forceAllowDuplicate = true,
            onNavigateToQueue = { navigatedToQueue = true }
        )

        val dlDeadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < dlDeadline && !navigatedToQueue) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(50)
        }

        assertTrue("Starting download from Share Intent must navigate to active download screen", navigatedToQueue)
        assertTrue(viewModel.showActiveDownloadingScreen.value)
    }

    @Test
    fun `http 403 forbidden stream automatically recovers via fallback client headers or fresh stream resolution`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.downloadTaskDao()
        val validMp4Payload = ByteArray(8192) { (it % 251).toByte() }

        // Simulate a googlevideo.com stream that returns HTTP 403 on MWEB/browser User-Agent,
        // and recovers automatically when DownloadEngine retries with ANDROID_VR / fresh stream resolution
        val recoveryHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val ua = req.header("User-Agent").orEmpty()
                when {
                    url.contains("googlevideo.com/videoplayback") && ua.contains("youtube.vr.oculus") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .header("Content-Type", "video/mp4")
                            .header("Content-Length", validMp4Payload.size.toString())
                            .body(validMp4Payload.toResponseBody("video/mp4".toMediaType()))
                            .build()
                    }
                    url.contains("googlevideo.com/videoplayback") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(403)
                            .message("Forbidden")
                            .body("Forbidden".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("Not Found".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                }
            }
            .build()

        val engine = DownloadEngine(
            context = context,
            dao = dao,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            httpClient = recoveryHttpClient
        )

        // Enqueue a stream initially tagged with c=MWEB (which returns 403 on initial mobile browser UA)
        val mwebStream = VideoStreamOption(
            id = "yt_prog_360p_18",
            resolutionLabel = "360p SD",
            resolutionWidth = 640,
            resolutionHeight = 360,
            frameRate = 30,
            isHdr = false,
            container = VideoContainerFormat.MP4,
            videoCodec = "H.264",
            audioCodec = "AAC",
            streamType = StreamType.PROGRESSIVE,
            bitrateKbps = 800,
            estimatedSizeBytes = validMp4Payload.size.toLong(),
            downloadUrl = "https://rr2---sn-test.googlevideo.com/videoplayback?itag=18&c=MWEB#ytid=dQw4w9WgXcQ"
        )
        val meta = AnalyzedMediaMetadata(
            sourceUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            title = "403 Recovery Clip",
            authorOrDomain = "YouTube Creator",
            platform = MediaPlatform.YOUTUBE,
            durationMs = 10_000L,
            thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            supportsRangeRequests = true,
            videoStreams = listOf(mwebStream),
            audioStreams = emptyList(),
            inspectionMethod = "Test"
        )

        val enqueued = engine.enqueueVideoDownload(meta, mwebStream, allowDuplicate = true)
        assertTrue(enqueued is EnqueueResult.Enqueued)
        val taskId = (enqueued as EnqueueResult.Enqueued).task.id

        var finalTask = dao.getTaskById(taskId)
        var attempts = 0
        while (finalTask?.statusEnum?.isActive == true && attempts < 50) {
            delay(50)
            finalTask = dao.getTaskById(taskId)
            attempts++
        }

        assertNotNull(finalTask)
        assertEquals(
            "Expected HTTP 403 stream to recover and complete, but got: ${finalTask?.errorMessage}",
            DownloadStatus.COMPLETED,
            finalTask?.statusEnum
        )
        assertEquals(validMp4Payload.size.toLong(), File(finalTask!!.outputFilePath).length())

        // Verify that when an expired TikTok CDN URL returns HTTP 403, DownloadEngine resolves a fresh stream
        // via SocialStreamExtractor / TikWM API and downloads 100% of the full video payload (never a 210 KB fallback)
        val fullVideoPayload = ByteArray(512 * 1024) { ((it * 31 + 7) and 0xFF).toByte() }
        val freshResolutionClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when {
                    url.contains("tikwm.com/api/") -> {
                        val json = """
                            {
                              "code": 0,
                              "data": {
                                "id": "734567890",
                                "title": "Recovered Full Size Video",
                                "duration": 25,
                                "hdplay": "https://v16m.tiktokcdn.com/fresh_hd_stream.mp4",
                                "hd_size": ${fullVideoPayload.size},
                                "play": "https://v16m.tiktokcdn.com/fresh_sd_stream.mp4",
                                "size": ${fullVideoPayload.size},
                                "author": { "nickname": "TikTok Creator" }
                              }
                            }
                        """.trimIndent()
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "application/json")
                            .body(json.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    url.contains("fresh_hd_stream.mp4") || url.contains("fresh_sd_stream.mp4") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "video/mp4")
                            .header("Content-Length", fullVideoPayload.size.toString())
                            .body(fullVideoPayload.toResponseBody("video/mp4".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(403)
                            .message("Forbidden")
                            .body("Forbidden".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                }
            }
            .build()

        // Seed a legacy FAILED task with the old 403 error message to verify startup auto-recovery with full bytes
        val legacyFailedFile = File(context.filesDir, "downloads/legacy_403_recovered.mp4")
        dao.upsertTask(
            com.example.domain.model.DownloadTaskEntity(
                id = "legacy_403_task",
                sourceUrl = "https://v16m.tiktokcdn.com/expired_sig_video.mp4#ttid=734567890",
                title = "Legacy 403 TikTok Video",
                author = "TikTok Creator",
                platformName = "TikTok",
                mode = "VIDEO",
                height = 720,
                width = 720,
                outputFilePath = legacyFailedFile.absolutePath,
                fileName = legacyFailedFile.name,
                mimeType = "video/mp4",
                containerFormat = "MP4",
                codecInfo = "H.264 + AAC",
                totalBytes = fullVideoPayload.size.toLong(),
                downloadedBytes = 0L,
                status = DownloadStatus.FAILED.name,
                statusDetail = "Download failed",
                errorMessage = "Access denied or expired media link (HTTP 403). Please re-analyze the source URL."
            )
        )

        val freshRecoveryEngine = DownloadEngine(
            context = context,
            dao = dao,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            httpClient = freshResolutionClient
        )

        var recoveredLegacy = dao.getTaskById("legacy_403_task")
        attempts = 0
        while (recoveredLegacy?.statusEnum != DownloadStatus.COMPLETED && attempts < 60) {
            delay(50)
            recoveredLegacy = dao.getTaskById("legacy_403_task")
            attempts++
        }
        assertNotNull(recoveredLegacy)
        assertEquals(
            "Expected legacy 403 failed task to auto-recover with fresh stream and complete, but got: ${recoveredLegacy?.errorMessage}",
            DownloadStatus.COMPLETED,
            recoveredLegacy?.statusEnum
        )
        assertNull(recoveredLegacy?.errorMessage)
        assertTrue(File(recoveredLegacy!!.outputFilePath).exists())
        assertEquals(fullVideoPayload.size.toLong(), File(recoveredLegacy.outputFilePath).length())

        freshRecoveryEngine.deleteTaskAndFile("legacy_403_task")
        db.close()
    }

    @Test
    fun `large 20MB multi-chunk googlevideo and adaptive dual-stream downloads complete past 16MB boundary with exact byte integrity`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.downloadTaskDao()

        // Create a 20 MB (20,971,520 bytes) payload that exceeds both the 2 MB and 16 MB (16,777,216) boundaries
        val largeSize = 20 * 1024 * 1024
        val largeVideoBytes = ByteArray(largeSize) { idx -> ((idx * 17 + 43) and 0xFF).toByte() }
        val rangeRequestsSeen = java.util.concurrent.CopyOnWriteArrayList<String>()

        val rangeServerClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val rangeHeader = req.header("Range")
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    rangeRequestsSeen.add(rangeHeader)
                    val spec = rangeHeader.removePrefix("bytes=")
                    val start = spec.substringBefore('-').toIntOrNull() ?: 0
                    val endRaw = spec.substringAfter('-', "").toIntOrNull()
                    if (start >= largeSize) {
                        return@addInterceptor Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(416)
                            .message("Range Not Satisfiable")
                            .header("Content-Range", "bytes */$largeSize")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    val end = minOf(endRaw ?: (largeSize - 1), largeSize - 1)
                    val slice = largeVideoBytes.copyOfRange(start, end + 1)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Type", "video/mp4")
                        .header("Content-Range", "bytes $start-$end/$largeSize")
                        .header("Content-Length", slice.size.toString())
                        .body(slice.toResponseBody("video/mp4".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "video/mp4")
                        .header("Content-Length", largeSize.toString())
                        .body(largeVideoBytes.toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            }
            .build()

        val engine = DownloadEngine(
            context = context,
            dao = dao,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            httpClient = rangeServerClient
        )

        val stream1080p = VideoStreamOption(
            id = "yt_1080p_large",
            resolutionLabel = "1080p FHD",
            resolutionWidth = 1920,
            resolutionHeight = 1080,
            frameRate = 60,
            isHdr = false,
            container = VideoContainerFormat.MP4,
            videoCodec = "H.264 (AVC1)",
            audioCodec = "AAC-LC",
            streamType = StreamType.PROGRESSIVE,
            bitrateKbps = 4500,
            estimatedSizeBytes = largeSize.toLong(),
            downloadUrl = "https://rr2---sn-test.googlevideo.com/videoplayback?itag=22&clen=$largeSize&c=ANDROID_VR"
        )
        val meta = AnalyzedMediaMetadata(
            sourceUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            title = "Large 20MB 1080p Video",
            authorOrDomain = "YouTube Creator",
            platform = MediaPlatform.YOUTUBE,
            durationMs = 35_000L,
            thumbnailUrl = null,
            supportsRangeRequests = true,
            videoStreams = listOf(stream1080p),
            audioStreams = emptyList(),
            inspectionMethod = "Test"
        )

        val enqueued = engine.enqueueVideoDownload(meta, stream1080p, allowDuplicate = true)
        assertTrue(enqueued is EnqueueResult.Enqueued)
        val taskId = (enqueued as EnqueueResult.Enqueued).task.id

        var task = dao.getTaskById(taskId)
        val deadline = System.currentTimeMillis() + 12_000L
        while (System.currentTimeMillis() < deadline && task?.statusEnum?.isActive == true) {
            delay(50)
            task = dao.getTaskById(taskId)
        }

        assertNotNull(task)
        assertEquals(
            "Expected 20 MB download to complete past 16777216 bytes, but got: ${task?.errorMessage}",
            DownloadStatus.COMPLETED,
            task?.statusEnum
        )
        val outFile = File(task!!.outputFilePath)
        assertTrue(outFile.exists())
        assertEquals(largeSize.toLong(), outFile.length())
        assertEquals(100, task.progressPercent)
        assertTrue("Expected multi-chunk Range headers past 16MB", rangeRequestsSeen.size >= 5)

        engine.deleteTaskAndFile(taskId)
        db.close()
    }

    @Test
    fun `slow downloads and mid-stream network interruptions automatically recover via HTTP Range resume to 100 percent`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.downloadTaskDao()

        val totalSize = 512 * 1024 // 512 KB
        val fullPayload = ByteArray(totalSize) { idx -> ((idx * 29 + 13) and 0xFF).toByte() }
        var attemptCounter = 0

        // Simulate a flaky server that drops the TCP stream at 150 KB on attempt 1,
        // drops again at 350 KB on attempt 2, and completes to 512 KB on attempt 3 via HTTP Range 206
        val flakyNetworkClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attemptCounter++
                val req = chain.request()
                val rangeHeader = req.header("Range").orEmpty()
                val startOffset = if (rangeHeader.startsWith("bytes=")) {
                    rangeHeader.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
                } else 0

                val remaining = totalSize - startOffset
                val cutoffBytes = when (attemptCounter) {
                    1 -> 150 * 1024 // Drop after 150 KB
                    2 -> 200 * 1024 // Drop after another 200 KB (at 350 KB total)
                    else -> remaining // Deliver the rest
                }

                val droppingSource = object : okio.Source {
                    private var pos = 0
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        if (pos >= cutoffBytes) {
                            if (cutoffBytes < remaining) {
                                throw java.io.EOFException("Simulated mid-stream network drop at byte ${startOffset + pos}")
                            }
                            return -1L
                        }
                        val toRead = minOf(byteCount.toInt(), cutoffBytes - pos, 16 * 1024)
                        sink.write(fullPayload, startOffset + pos, toRead)
                        pos += toRead
                        return toRead.toLong()
                    }
                    override fun timeout(): okio.Timeout = okio.Timeout.NONE
                    override fun close() {}
                }

                val body = object : okhttp3.ResponseBody() {
                    override fun contentType() = "video/mp4".toMediaType()
                    override fun contentLength() = remaining.toLong()
                    override fun source() = droppingSource.buffer()
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (startOffset > 0) 206 else 200)
                    .message(if (startOffset > 0) "Partial Content" else "OK")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Length", remaining.toString())
                    .apply {
                        if (startOffset > 0) {
                            header("Content-Range", "bytes $startOffset-${totalSize - 1}/$totalSize")
                        }
                    }
                    .body(body)
                    .build()
            }
            .build()

        val engine = DownloadEngine(
            context = context,
            dao = dao,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            httpClient = flakyNetworkClient
        )

        val stream = VideoStreamOption(
            id = "flaky_720p",
            resolutionLabel = "720p HD",
            resolutionWidth = 1280,
            resolutionHeight = 720,
            frameRate = 30,
            isHdr = false,
            container = VideoContainerFormat.MP4,
            videoCodec = "H.264",
            audioCodec = "AAC",
            streamType = StreamType.PROGRESSIVE,
            bitrateKbps = 1500,
            estimatedSizeBytes = totalSize.toLong(),
            downloadUrl = "https://cdn.example.org/videos/flaky_stream.mp4"
        )
        val meta = AnalyzedMediaMetadata(
            sourceUrl = "https://cdn.example.org/videos/flaky_stream.mp4",
            title = "Flaky Network Video",
            authorOrDomain = "cdn.example.org",
            platform = MediaPlatform.DIRECT_URL,
            durationMs = 12_000L,
            thumbnailUrl = null,
            supportsRangeRequests = true,
            videoStreams = listOf(stream),
            audioStreams = emptyList(),
            inspectionMethod = "Test"
        )

        val enqueued = engine.enqueueVideoDownload(meta, stream, allowDuplicate = true)
        assertTrue(enqueued is EnqueueResult.Enqueued)
        val taskId = (enqueued as EnqueueResult.Enqueued).task.id

        var finalTask = dao.getTaskById(taskId)
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline && finalTask?.statusEnum?.isActive == true) {
            delay(50)
            finalTask = dao.getTaskById(taskId)
        }

        assertNotNull(finalTask)
        assertEquals(
            "Expected interrupted stream to auto-resume via Range and complete, but got: ${finalTask?.errorMessage}",
            DownloadStatus.COMPLETED,
            finalTask?.statusEnum
        )
        assertTrue("Expected at least 3 attempts across network drops", attemptCounter >= 3)
        val savedBytes = File(finalTask!!.outputFilePath).readBytes()
        assertEquals(totalSize, savedBytes.size)
        org.junit.Assert.assertArrayEquals("Resumed file bytes must match original payload byte-for-byte", fullPayload, savedBytes)

        engine.deleteTaskAndFile(taskId)
        db.close()
    }

    @Test
    fun `expired URLs and HTTP 410 or 403 automatically switch to fallback stream or fail cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.downloadTaskDao()

        // Verify SocialStreamExtractor.isStreamUrlExpired detects expired timestamps
        assertTrue(
            com.example.data.platform.SocialStreamExtractor.isStreamUrlExpired(
                "https://rr1---sn-test.googlevideo.com/videoplayback?expire=1600000000&itag=18",
                nowEpochSeconds = 1700000000L
            )
        )
        org.junit.Assert.assertFalse(
            com.example.data.platform.SocialStreamExtractor.isStreamUrlExpired(
                "https://rr1---sn-test.googlevideo.com/videoplayback?expire=1900000000&itag=18",
                nowEpochSeconds = 1700000000L
            )
        )

        val validBytes = ByteArray(16 * 1024) { ((it * 19 + 5) and 0xFF).toByte() }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.contains("fresh_backup_720p.mp4")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "video/mp4")
                        .header("Content-Length", validBytes.size.toString())
                        .body(validBytes.toResponseBody("video/mp4".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(410)
                        .message("Gone")
                        .body("Gone".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
            .build()

        val engine = DownloadEngine(
            context = context,
            dao = dao,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            httpClient = client
        )

        val expiredStream = VideoStreamOption(
            id = "expired_720p",
            resolutionLabel = "720p HD",
            resolutionWidth = 1280,
            resolutionHeight = 720,
            frameRate = 30,
            isHdr = false,
            container = VideoContainerFormat.MP4,
            videoCodec = "H.264",
            audioCodec = "AAC",
            streamType = StreamType.PROGRESSIVE,
            bitrateKbps = 1500,
            estimatedSizeBytes = validBytes.size.toLong(),
            downloadUrl = "https://cdn.example.org/expired_720p.mp4?expire=1500000000"
        )
        val backupStream = expiredStream.copy(
            id = "backup_720p",
            downloadUrl = "https://cdn.example.org/fresh_backup_720p.mp4?expire=4000000000"
        )
        val meta = AnalyzedMediaMetadata(
            sourceUrl = "https://cdn.example.org/watch/123",
            title = "Expired Link Recovery",
            authorOrDomain = "cdn.example.org",
            platform = MediaPlatform.DIRECT_URL,
            durationMs = 8_000L,
            thumbnailUrl = null,
            supportsRangeRequests = true,
            videoStreams = listOf(expiredStream, backupStream),
            audioStreams = emptyList(),
            inspectionMethod = "Test"
        )

        val enqueued = engine.enqueueVideoDownload(meta, expiredStream, allowDuplicate = true)
        assertTrue(enqueued is EnqueueResult.Enqueued)
        val taskId = (enqueued as EnqueueResult.Enqueued).task.id

        var finalTask = dao.getTaskById(taskId)
        val deadline = System.currentTimeMillis() + 6000L
        while (System.currentTimeMillis() < deadline && finalTask?.statusEnum?.isActive == true) {
            delay(50)
            finalTask = dao.getTaskById(taskId)
        }

        assertNotNull(finalTask)
        assertEquals(DownloadStatus.COMPLETED, finalTask?.statusEnum)
        assertEquals(validBytes.size.toLong(), File(finalTask!!.outputFilePath).length())

        engine.deleteTaskAndFile(taskId)
        db.close()
    }

    @Test
    fun `html json xml plain-text error responses and truncated partial MP4 files are rejected and never saved as completed videos`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.downloadTaskDao()

        val invalidScenarios = listOf(
            // 1. JSON error returned with 200 OK and application/json
            Triple("https://cdn.example.org/json_err.mp4", "application/json", """{"error":"Invalid token","code":403}""".toByteArray()),
            // 2. JSON error disguised with video/mp4 Content-Type
            Triple("https://cdn.example.org/disguised_json.mp4", "video/mp4", """{"status":"error","message":"Quota exceeded"}""".toByteArray()),
            // 3. HTML login wall disguised with application/octet-stream
            Triple("https://cdn.example.org/disguised_html.mp4", "application/octet-stream", "<!DOCTYPE html><html><body>Login required</body></html>".toByteArray()),
            // 4. Plain-text error disguised with video/mp4
            Triple("https://cdn.example.org/disguised_text.mp4", "video/mp4", "403 Forbidden: Access denied by CDN".toByteArray()),
            // 5. Truncated MP4 container where mdat box header declares 500,000 bytes but file only has 200 bytes
            Triple(
                "https://cdn.example.org/truncated_box.mp4",
                "video/mp4",
                java.nio.ByteBuffer.allocate(64).apply {
                    putInt(16)
                    put("ftyp".toByteArray())
                    put("isom".toByteArray())
                    putInt(0)
                    putInt(500_000) // Declared mdat size = 500 KB, but stream ends after 64 bytes!
                    put("mdat".toByteArray())
                }.array()
            )
        )

        val rejectingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val match = invalidScenarios.first { it.first == url }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", match.second)
                    .header("Content-Length", match.third.size.toString())
                    .body(match.third.toResponseBody(match.second.toMediaType()))
                    .build()
            }
            .build()

        val engine = DownloadEngine(
            context = context,
            dao = dao,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            httpClient = rejectingClient
        )

        for ((idx, scenario) in invalidScenarios.withIndex()) {
            val stream = VideoStreamOption(
                id = "invalid_$idx",
                resolutionLabel = "720p HD",
                resolutionWidth = 1280,
                resolutionHeight = 720,
                frameRate = 30,
                isHdr = false,
                container = VideoContainerFormat.MP4,
                videoCodec = "H.264",
                audioCodec = "AAC",
                streamType = StreamType.PROGRESSIVE,
                bitrateKbps = 1000,
                estimatedSizeBytes = scenario.third.size.toLong(),
                downloadUrl = scenario.first
            )
            val meta = AnalyzedMediaMetadata(
                sourceUrl = scenario.first,
                title = "Invalid Scenario $idx",
                authorOrDomain = "cdn.example.org",
                platform = MediaPlatform.DIRECT_URL,
                durationMs = 5_000L,
                thumbnailUrl = null,
                supportsRangeRequests = false,
                videoStreams = listOf(stream),
                audioStreams = emptyList(),
                inspectionMethod = "Test"
            )

            val res = engine.enqueueVideoDownload(meta, stream, allowDuplicate = true)
            assertTrue(res is EnqueueResult.Enqueued)
            val taskId = (res as EnqueueResult.Enqueued).task.id

            var task = dao.getTaskById(taskId)
            val deadline = System.currentTimeMillis() + 4000L
            while (System.currentTimeMillis() < deadline && task?.statusEnum?.isActive == true) {
                delay(40)
                task = dao.getTaskById(taskId)
            }

            assertNotNull(task)
            assertEquals(
                "Scenario $idx (${scenario.first}) must be rejected as FAILED, never COMPLETED",
                DownloadStatus.FAILED,
                task?.statusEnum
            )
            org.junit.Assert.assertFalse(
                "Corrupt/non-media output file must not remain on disk for scenario $idx",
                File(task!!.outputFilePath).exists()
            )
        }

        db.close()
    }
}

