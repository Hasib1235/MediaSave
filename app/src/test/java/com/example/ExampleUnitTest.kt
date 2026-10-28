package com.example

import com.example.data.media.MediaProcessorEngine
import com.example.domain.model.AudioFormat
import com.example.domain.model.AudioStreamOption
import com.example.domain.model.DownloadStatus
import com.example.domain.model.DownloadTaskEntity
import com.example.domain.model.MediaPlatform
import com.example.domain.validation.ClipboardParseResult
import com.example.domain.validation.UrlValidationResult
import com.example.domain.validation.UrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExampleUnitTest {

    @Test
    fun `url validator rejects empty and unsupported schemes`() {
        val emptyResult = UrlValidator.validateAndClassify("   ")
        assertTrue(emptyResult is UrlValidationResult.Invalid)

        val ftpResult = UrlValidator.validateAndClassify("ftp://files.example.com/video.mp4")
        assertTrue(ftpResult is UrlValidationResult.Invalid)

        val traversalResult = UrlValidator.validateAndClassify("file:///data/user/0/../../etc/passwd")
        assertTrue(traversalResult is UrlValidationResult.Invalid)
    }

    @Test
    fun `url validator rejects mock and non-downloadable placeholder urls`() {
        assertTrue(UrlValidator.isMockOrPlaceholderUrl("mock://video/1080p.mp4"))
        assertTrue(UrlValidator.isMockOrPlaceholderUrl("https://example.com/mock/sample.mp4"))
        assertTrue(UrlValidator.isMockOrPlaceholderUrl("https://test.invalid/stream.mp4"))
        assertFalse(UrlValidator.isMockOrPlaceholderUrl("https://www.w3schools.com/html/mov_bbb.mp4"))

        val mockValidation = UrlValidator.validateAndClassify("mock://video/1080p.mp4")
        assertTrue(mockValidation is UrlValidationResult.Invalid)
        assertEquals(
            "Mock or Non-Downloadable Test Metadata",
            (mockValidation as UrlValidationResult.Invalid).errorTitle
        )
    }

    @Test
    fun `clipboard parser handles empty clipboard valid urls share text and invalid text`() {
        assertEquals(ClipboardParseResult.Empty, UrlValidator.parseClipboardContent(null))
        assertEquals(ClipboardParseResult.Empty, UrlValidator.parseClipboardContent("   \n  "))

        val validClip = UrlValidator.parseClipboardContent("  https://cdn.example.org/videos/nature_1080p.mp4  ")
        assertTrue(validClip is ClipboardParseResult.ValidUrl)
        assertEquals(
            "https://cdn.example.org/videos/nature_1080p.mp4",
            (validClip as ClipboardParseResult.ValidUrl).pastedText
        )

        val shareClip = UrlValidator.parseClipboardContent("Watch this clip: https://www.w3schools.com/html/mov_bbb.mp4 now!")
        assertTrue(shareClip is ClipboardParseResult.ValidUrl)
        assertEquals(
            "https://www.w3schools.com/html/mov_bbb.mp4",
            (shareClip as ClipboardParseResult.ValidUrl).pastedText
        )

        val invalidClip = UrlValidator.parseClipboardContent("just some random notes without a link")
        assertTrue(invalidClip is ClipboardParseResult.InvalidText)
    }

    @Test
    fun `url validator classifies youtube facebook instagram tiktok and direct urls`() {
        val yt = UrlValidator.validateAndClassify("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(yt is UrlValidationResult.Valid)
        assertEquals(MediaPlatform.YOUTUBE, (yt as UrlValidationResult.Valid).platform)

        val fb = UrlValidator.validateAndClassify("https://fb.watch/abc123xyz/")
        assertTrue(fb is UrlValidationResult.Valid)
        assertEquals(MediaPlatform.FACEBOOK, (fb as UrlValidationResult.Valid).platform)

        val ig = UrlValidator.validateAndClassify("https://www.instagram.com/reel/C1234567/")
        assertTrue(ig is UrlValidationResult.Valid)
        assertEquals(MediaPlatform.INSTAGRAM, (ig as UrlValidationResult.Valid).platform)

        val tt = UrlValidator.validateAndClassify("https://www.tiktok.com/@creator/video/987654321")
        assertTrue(tt is UrlValidationResult.Valid)
        assertEquals(MediaPlatform.TIKTOK, (tt as UrlValidationResult.Valid).platform)

        val direct = UrlValidator.validateAndClassify("https://cdn.example.org/media/clip_1080p.mp4")
        assertTrue(direct is UrlValidationResult.Valid)
        assertEquals(MediaPlatform.DIRECT_URL, (direct as UrlValidationResult.Valid).platform)
    }

    @Test
    fun `sanitizeFileName removes illegal characters and path traversal`() {
        val unsafe = "../My:Video*Title?With/Slashes|And<Tags>"
        val safe = UrlValidator.sanitizeFileName(unsafe, ".MP4")
        assertFalse(safe.contains(".."))
        assertFalse(safe.contains("/"))
        assertFalse(safe.contains(":"))
        assertTrue(safe.endsWith(".mp4"))
    }

    @Test
    fun `format selection distinguishes direct copy and flags lossy to lossless conversions`() {
        val lossySource = AudioStreamOption(
            id = "a_lossy",
            sourceFormat = AudioFormat.M4A,
            sourceCodec = "AAC-LC",
            bitrateKbps = 192,
            sampleRateHz = 44100,
            channelCount = 2,
            isLosslessSource = false,
            estimatedSizeBytes = 120_000L,
            downloadUrl = "file:///tmp/sample.m4a",
            isExtractedFromVideo = true
        )

        val choices = UrlValidator.buildSupportedAudioOutputChoices(lossySource)
        assertTrue(choices.isNotEmpty())

        val directChoice = choices.first { it.targetFormat == AudioFormat.M4A }
        assertTrue(directChoice.isDirectStreamCopy)
        assertFalse(directChoice.isLossyToLosslessWrap)
        assertNull(directChoice.warningMessage)

        val wavChoice = choices.first { it.targetFormat == AudioFormat.WAV }
        assertFalse(wavChoice.isDirectStreamCopy)
        assertTrue(wavChoice.isLossyToLosslessWrap)
        assertNotNull(wavChoice.warningMessage)
        assertTrue(wavChoice.warningMessage!!.contains("does NOT restore lost frequencies"))
    }

    @Test
    fun `lossless source to lossless wav or aiff does not trigger lossy warning`() {
        val losslessSource = AudioStreamOption(
            id = "a_lossless",
            sourceFormat = AudioFormat.WAV,
            sourceCodec = "PCM 16-bit",
            bitrateKbps = 1411,
            sampleRateHz = 44100,
            channelCount = 2,
            isLosslessSource = true,
            estimatedSizeBytes = 352_844L,
            downloadUrl = "file:///tmp/master.wav",
            isExtractedFromVideo = false
        )

        val choices = UrlValidator.buildSupportedAudioOutputChoices(losslessSource)
        val aiffChoice = choices.first { it.targetFormat == AudioFormat.AIFF }
        assertFalse(aiffChoice.isDirectStreamCopy)
        assertFalse(aiffChoice.isLossyToLosslessWrap)
        assertNull(aiffChoice.warningMessage)
    }

    @Test
    fun `download state management calculates progress percentage accurately`() {
        val task = DownloadTaskEntity(
            id = "task_1",
            sourceUrl = "https://example.com/file.mp4",
            title = "Sample Video",
            author = "Studio",
            platformName = "Direct Media URL",
            mode = "VIDEO",
            outputFilePath = "/tmp/file.mp4",
            fileName = "file.mp4",
            mimeType = "video/mp4",
            containerFormat = "MP4",
            codecInfo = "H.264 + AAC",
            totalBytes = 1_000_000L,
            downloadedBytes = 450_000L,
            status = DownloadStatus.DOWNLOADING.name
        )

        assertEquals(DownloadStatus.DOWNLOADING, task.statusEnum)
        assertEquals(45, task.progressPercent)
        assertTrue(task.statusEnum.isActive)

        val pausedTask = task.copy(status = DownloadStatus.PAUSED.name)
        assertEquals(DownloadStatus.PAUSED, pausedTask.statusEnum)
        assertFalse(pausedTask.statusEnum.isActive)
    }

    @Test
    fun `wav and aiff audio conversion writes valid headers and sample payloads`() {
        val pcm = MediaProcessorEngine.generateStudioChordPcm(
            durationSeconds = 0.5,
            sampleRateHz = 44100,
            channels = 2
        )
        assertTrue(pcm.isNotEmpty())

        val tempWav = File.createTempFile("mediasave_test_", ".wav")
        val tempAiff = File.createTempFile("mediasave_test_", ".aiff")
        try {
            MediaProcessorEngine.writeWavFile(pcm, tempWav, 44100, 2)
            assertEquals(pcm.size + 44L, tempWav.length())

            MediaProcessorEngine.writeAiffFile(pcm, tempAiff, 44100, 2)
            assertEquals(pcm.size + 54L, tempAiff.length())
        } finally {
            tempWav.delete()
            tempAiff.delete()
        }
    }

    @Test
    fun `social platform shortcuts map to exact official urls and sanitize diagnostic logs`() {
        val yt = UrlValidator.resolveSocialPlatformShortcut("YouTube")
        assertNotNull(yt)
        assertEquals("https://www.youtube.com/", yt!!.officialWebUrl)

        val fb = UrlValidator.resolveSocialPlatformShortcut("Facebook")
        assertNotNull(fb)
        assertEquals("https://www.facebook.com/", fb!!.officialWebUrl)

        val ig = UrlValidator.resolveSocialPlatformShortcut("Instagram")
        assertNotNull(ig)
        assertEquals("https://www.instagram.com/", ig!!.officialWebUrl)

        val tt = UrlValidator.resolveSocialPlatformShortcut("TikTok")
        assertNotNull(tt)
        assertEquals("https://www.tiktok.com/", tt!!.officialWebUrl)

        // Verify diagnostic log sanitizer strips query tokens and private paths
        val sanitized = UrlValidator.sanitizeUrlForDiagnosticLog(
            "https://cdn.example.org/private/folder/clip_4k.mp4?token=secret123&sig=abc"
        )
        assertFalse(sanitized.contains("secret123"))
        assertFalse(sanitized.contains("private/folder"))
        assertEquals("https://cdn.example.org/.../*.mp4", sanitized)
    }
}
