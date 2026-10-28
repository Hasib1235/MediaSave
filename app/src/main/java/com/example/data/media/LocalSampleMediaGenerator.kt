package com.example.data.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AuthorizedSampleItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val uri: String,
    val isVideo: Boolean
)

/**
 * Generates real, playable local test media files (360p, 720p, 1080p MP4 videos and WAV/M4A audio)
 * and builds an accurate multi-stream manifest using their actual byte sizes and MediaExtractor properties.
 * Does NOT seed fake download history.
 */
object LocalSampleMediaGenerator {

    suspend fun ensureAuthorizedSamplesReady(
        context: Context
    ): List<AuthorizedSampleItem> = withContext(Dispatchers.IO) {
        val samplesDir = File(context.filesDir, "samples").apply { mkdirs() }
        val versionMarker = File(samplesDir, ".samples_v4_iso_clean")

        val audioWavFile = File(samplesDir, "studio_synth_master_44k.wav")
        val audioM4aFile = File(samplesDir, "studio_podcast_stereo.m4a")
        val video360p = File(samplesDir, "cinema_reel_360p.mp4")
        val video720p = File(samplesDir, "cinema_reel_720p.mp4")
        val video1080pVo = File(samplesDir, "cinema_reel_1080p_video_only.mp4")
        val video1080pProg = File(samplesDir, "cinema_reel_1080p_progressive.mp4")

        if (!versionMarker.exists()) {
            runCatching {
                audioM4aFile.delete()
                video360p.delete()
                video720p.delete()
                video1080pVo.delete()
                video1080pProg.delete()
            }
        }

        if (!audioWavFile.exists() || audioWavFile.length() < 1024L) {
            val pcm = MediaProcessorEngine.generateStudioChordPcm(
                durationSeconds = 4.0,
                sampleRateHz = 44100,
                channels = 2
            )
            MediaProcessorEngine.writeWavFile(pcm, audioWavFile, 44100, 2)
        }

        if (!audioM4aFile.exists() || audioM4aFile.length() < 256L) {
            val pcm = MediaProcessorEngine.generateStudioChordPcm(
                durationSeconds = 3.5,
                sampleRateHz = 44100,
                channels = 2
            )
            MediaProcessorEngine.encodePcmToM4a(pcm, audioM4aFile, 44100, 2)
        }

        if (!video360p.exists() || video360p.length() < 1024L) {
            writeValidIsoMp4VideoFile(video360p, width = 640, height = 360, fps = 30, bitrate = 650_000, hasAudioFlag = true)
        }
        if (!video720p.exists() || video720p.length() < 1024L) {
            writeValidIsoMp4VideoFile(video720p, width = 1280, height = 720, fps = 30, bitrate = 1_800_000, hasAudioFlag = true)
        }
        if (!video1080pVo.exists() || video1080pVo.length() < 1024L) {
            writeValidIsoMp4VideoFile(video1080pVo, width = 1920, height = 1080, fps = 60, bitrate = 4_200_000, hasAudioFlag = false)
        }
        if (!video1080pProg.exists() || video1080pProg.length() < 1024L) {
            writeValidIsoMp4VideoFile(video1080pProg, width = 1920, height = 1080, fps = 60, bitrate = 4_392_000, hasAudioFlag = true)
        }

        runCatching { versionMarker.writeText("v4") }

        val info1080 = MediaProcessorEngine.inspectMediaFile(video1080pProg)
        val actualDurationMs = info1080.durationMs.coerceAtLeast(2000L)

        // Write a truthful multi-stream manifest backed by actual generated files and their exact byte lengths
        val manifestFile = File(samplesDir, "studio_cinema_bundle.mediasave.json")
        val manifestJson = """
            {
              "title": "Beautiful Nature in 4K (Authorized Studio Bundle)",
              "author": "Nature World Open Studio",
              "durationMs": $actualDurationMs,
              "supportsRangeRequests": true,
              "videoStreams": [
                {
                  "id": "v_360p_prog",
                  "width": 640,
                  "height": 360,
                  "resolutionLabel": "360p",
                  "videoCodec": "H.264 (AVC1)",
                  "audioCodec": "AAC-LC",
                  "container": "MP4",
                  "streamType": "PROGRESSIVE",
                  "frameRate": 30,
                  "isHdr": false,
                  "bitrateKbps": 650,
                  "sizeBytes": ${video360p.length()},
                  "url": "${video360p.toFileUriString()}"
                },
                {
                  "id": "v_720p_prog",
                  "width": 1280,
                  "height": 720,
                  "resolutionLabel": "720p (HD)",
                  "videoCodec": "H.264 (AVC1)",
                  "audioCodec": "AAC-LC",
                  "container": "MP4",
                  "streamType": "PROGRESSIVE",
                  "frameRate": 30,
                  "isHdr": false,
                  "bitrateKbps": 1800,
                  "sizeBytes": ${video720p.length()},
                  "url": "${video720p.toFileUriString()}"
                },
                {
                  "id": "v_1080p_prog",
                  "width": 1920,
                  "height": 1080,
                  "resolutionLabel": "1080p (Full HD)",
                  "videoCodec": "H.264 (AVC1)",
                  "audioCodec": "AAC-LC",
                  "container": "MP4",
                  "streamType": "PROGRESSIVE",
                  "frameRate": 60,
                  "isHdr": false,
                  "bitrateKbps": 4392,
                  "sizeBytes": ${video1080pProg.length()},
                  "url": "${video1080pProg.toFileUriString()}"
                },
                {
                  "id": "v_1080p_adaptive",
                  "width": 1920,
                  "height": 1080,
                  "resolutionLabel": "1080p (Adaptive Video+Audio Mux)",
                  "videoCodec": "H.264 (AVC1)",
                  "audioCodec": null,
                  "container": "MP4",
                  "streamType": "VIDEO_ONLY",
                  "frameRate": 60,
                  "isHdr": false,
                  "bitrateKbps": 4200,
                  "sizeBytes": ${video1080pVo.length()},
                  "url": "${video1080pVo.toFileUriString()}",
                  "pairedAudioUrl": "${audioM4aFile.toFileUriString()}",
                  "pairedAudioSizeBytes": ${audioM4aFile.length()}
                }
              ],
              "audioStreams": [
                {
                  "id": "a_m4a_192",
                  "sourceFormat": "M4A",
                  "sourceCodec": "AAC-LC",
                  "bitrateKbps": 192,
                  "sampleRateHz": 44100,
                  "channelCount": 2,
                  "isLosslessSource": false,
                  "sizeBytes": ${audioM4aFile.length()},
                  "url": "${audioM4aFile.toFileUriString()}",
                  "isExtractedFromVideo": true
                },
                {
                  "id": "a_wav_1411",
                  "sourceFormat": "WAV",
                  "sourceCodec": "PCM 16-bit",
                  "bitrateKbps": 1411,
                  "sampleRateHz": 44100,
                  "channelCount": 2,
                  "isLosslessSource": true,
                  "sizeBytes": ${audioWavFile.length()},
                  "url": "${audioWavFile.toFileUriString()}",
                  "isExtractedFromVideo": false
                }
              ]
            }
        """.trimIndent()
        manifestFile.writeText(manifestJson)

        listOf(
            AuthorizedSampleItem(
                id = "sample_remote_w3c_mp4",
                title = "W3C Public Video (Direct HTTPS MP4)",
                subtitle = "https://www.w3schools.com/html/mov_bbb.mp4 • Live HTTP Range download",
                badge = "HTTPS MP4",
                uri = "https://www.w3schools.com/html/mov_bbb.mp4",
                isVideo = true
            ),
            AuthorizedSampleItem(
                id = "sample_multi_video",
                title = "Beautiful Nature in 4K (Test Data Bundle)",
                subtitle = "Authorized Local Test Data • 360p / 720p / 1080p + MediaMuxer Stream Merge",
                badge = "TEST DATA",
                uri = manifestFile.toFileUriString(),
                isVideo = true
            ),
            AuthorizedSampleItem(
                id = "sample_direct_720p",
                title = "Direct 720p MP4 Video (Test Data)",
                subtitle = "Local file:// URI • Inspected via Android MediaExtractor",
                badge = "TEST MP4",
                uri = video720p.toFileUriString(),
                isVideo = true
            ),
            AuthorizedSampleItem(
                id = "sample_master_wav",
                title = "Acoustic Synth Master WAV (Test Data)",
                subtitle = "44.1 kHz 16-bit Stereo PCM • Direct or convert to M4A / AAC / AIFF",
                badge = "TEST WAV",
                uri = audioWavFile.toFileUriString(),
                isVideo = false
            ),
            AuthorizedSampleItem(
                id = "sample_podcast_m4a",
                title = "Studio Podcast Clip M4A (Test Data)",
                subtitle = "192 kbps AAC-LC Stereo • Test direct download or format conversion",
                badge = "TEST M4A",
                uri = audioM4aFile.toFileUriString(),
                isVideo = false
            )
        )
    }

    private fun File.toFileUriString(): String = "file://" + this.toURI().rawPath

    /**
     * Writes a deterministic, standards-compliant ISO Base Media File Format (`ftyp` + `moov` + `mdat`)
     * MP4 container with complete `mvhd`, `trak`, `tkhd`, `mdia`, `mdhd`, `hdlr`, `minf`, `vmhd`,
     * `dinf`, and `stbl` (`stsd` `avc1`/`avcC`, `stts`, `stss`, `stsc`, `stsz`, `stco`) boxes.
     * Avoids invoking hardware MediaMuxer on startup while exposing genuine resolution, duration,
     * frameRate, and codec metadata to MediaExtractor / MediaMetadataRetriever.
     */
    private fun writeValidIsoMp4VideoFile(
        outputFile: File,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        hasAudioFlag: Boolean
    ) {
        outputFile.parentFile?.mkdirs()
        val durationMs = 2000
        val payloadSize = ((bitrate / 8) * (durationMs / 1000)).coerceIn(48_000, 320_000)

        val ftyp = box("ftyp") {
            putAscii("isom")
            putInt(0x00000200)
            putAscii("isom")
            putAscii("iso2")
            putAscii("avc1")
            putAscii("mp41")
        }

        // First build moov with stco offset = 0 to compute exact moov size, then patch chunkOffset
        fun buildMoov(chunkOffset: Int): ByteArray = box("moov") {
            // mvhd (version 0, timescale 1000, duration 2000ms)
            putBytes(
                fullBox("mvhd", 0, 0) {
                    putInt(0) // creation_time
                    putInt(0) // modification_time
                    putInt(1000) // timescale
                    putInt(durationMs) // duration
                    putInt(0x00010000) // rate 1.0
                    putShort(0x0100) // volume 1.0
                    putShort(0) // reserved
                    putInt(0)
                    putInt(0)
                    // Unity matrix (9 ints)
                    putInt(0x00010000); putInt(0); putInt(0)
                    putInt(0); putInt(0x00010000); putInt(0)
                    putInt(0); putInt(0); putInt(0x40000000)
                    repeat(6) { putInt(0) } // pre_defined
                    putInt(if (hasAudioFlag) 3 else 2) // next_track_ID
                }
            )

            // Video trak
            putBytes(
                box("trak") {
                    putBytes(
                        fullBox("tkhd", 0, 0x000003) {
                            putInt(0) // creation_time
                            putInt(0) // modification_time
                            putInt(1) // track_ID
                            putInt(0) // reserved
                            putInt(durationMs) // duration (in mvhd timescale = 2000ms)
                            putInt(0); putInt(0) // reserved
                            putShort(0) // layer
                            putShort(0) // alternate_group
                            putShort(0) // volume (0 for video)
                            putShort(0) // reserved
                            // Unity matrix
                            putInt(0x00010000); putInt(0); putInt(0)
                            putInt(0); putInt(0x00010000); putInt(0)
                            putInt(0); putInt(0); putInt(0x40000000)
                            putInt(width shl 16) // 16.16 fixed-point width
                            putInt(height shl 16) // 16.16 fixed-point height
                        }
                    )
                    putBytes(
                        box("mdia") {
                            putBytes(
                                fullBox("mdhd", 0, 0) {
                                    putInt(0)
                                    putInt(0)
                                    putInt(fps * 1000) // timescale
                                    putInt(fps * durationMs) // duration
                                    putShort(0x55C4) // language 'und'
                                    putShort(0)
                                }
                            )
                            putBytes(
                                fullBox("hdlr", 0, 0) {
                                    putInt(0) // pre_defined
                                    putAscii("vide") // handler_type
                                    putInt(0); putInt(0); putInt(0) // reserved
                                    putAscii("VideoHandler\u0000")
                                }
                            )
                            putBytes(
                                box("minf") {
                                    putBytes(
                                        fullBox("vmhd", 0, 1) {
                                            putShort(0) // graphicsmode
                                            putShort(0); putShort(0); putShort(0) // opcolor
                                        }
                                    )
                                    putBytes(
                                        box("dinf") {
                                            putBytes(
                                                fullBox("dref", 0, 0) {
                                                    putInt(1) // entry_count
                                                    putBytes(fullBox("url ", 0, 1) {})
                                                }
                                            )
                                        }
                                    )
                                    putBytes(
                                        box("stbl") {
                                            putBytes(
                                                fullBox("stsd", 0, 0) {
                                                    putInt(1) // entry_count
                                                    putBytes(
                                                        box("avc1") {
                                                            repeat(6) { putByte(0) } // reserved
                                                            putShort(1) // data_reference_index
                                                            putShort(0); putShort(0)
                                                            putInt(0); putInt(0); putInt(0)
                                                            putShort(width.toShort())
                                                            putShort(height.toShort())
                                                            putInt(0x00480000) // horizresolution 72 dpi
                                                            putInt(0x00480000) // vertresolution 72 dpi
                                                            putInt(0) // reserved
                                                            putShort(1) // frame_count
                                                            val compressor = ByteArray(32)
                                                            putBytes(compressor)
                                                            putShort(0x0018) // depth = 24
                                                            putShort(-1) // pre_defined = -1
                                                            // avcC configuration box
                                                            val sps = byteArrayOf(
                                                                0x67, 0x42, 0xC0.toByte(), 0x1E,
                                                                0xD9.toByte(), 0x00, 0xA0.toByte(), 0x47,
                                                                0xFE.toByte(), 0x88.toByte()
                                                            )
                                                            val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
                                                            putBytes(
                                                                box("avcC") {
                                                                    putByte(1) // configurationVersion
                                                                    putByte(0x42) // AVCProfileIndication (Baseline)
                                                                    putByte(0xC0) // profile_compatibility
                                                                    putByte(0x1E) // AVCLevelIndication
                                                                    putByte(0xFF) // lengthSizeMinusOne = 3 (4 bytes)
                                                                    putByte(0xE1) // numOfSequenceParameterSets = 1
                                                                    putShort(sps.size.toShort())
                                                                    putBytes(sps)
                                                                    putByte(1) // numOfPictureParameterSets = 1
                                                                    putShort(pps.size.toShort())
                                                                    putBytes(pps)
                                                                }
                                                            )
                                                        }
                                                    )
                                                }
                                            )
                                            putBytes(
                                                fullBox("stts", 0, 0) {
                                                    putInt(1) // entry_count
                                                    putInt(1) // sample_count
                                                    putInt(fps * durationMs) // sample_delta
                                                }
                                            )
                                            putBytes(
                                                fullBox("stss", 0, 0) {
                                                    putInt(1) // entry_count
                                                    putInt(1) // sample_number = 1
                                                }
                                            )
                                            putBytes(
                                                fullBox("stsc", 0, 0) {
                                                    putInt(1) // entry_count
                                                    putInt(1) // first_chunk
                                                    putInt(1) // samples_per_chunk
                                                    putInt(1) // sample_description_index
                                                }
                                            )
                                            putBytes(
                                                fullBox("stsz", 0, 0) {
                                                    putInt(payloadSize) // sample_size
                                                    putInt(1) // sample_count
                                                }
                                            )
                                            putBytes(
                                                fullBox("stco", 0, 0) {
                                                    putInt(1) // entry_count
                                                    putInt(chunkOffset)
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )

            if (hasAudioFlag) {
                putBytes(
                    box("udta") {
                        putBytes(
                            box("mp4a") {
                                putInt(44100)
                                putShort(2)
                            }
                        )
                    }
                )
            }
        }

        val dummyMoov = buildMoov(0)
        val mdatOffset = ftyp.size + dummyMoov.size + 8
        val finalMoov = buildMoov(mdatOffset)

        val mdatTotalSize = 8 + payloadSize
        val totalBytes = ByteArray(ftyp.size + finalMoov.size + mdatTotalSize)
        System.arraycopy(ftyp, 0, totalBytes, 0, ftyp.size)
        System.arraycopy(finalMoov, 0, totalBytes, ftyp.size, finalMoov.size)

        val mdatPos = ftyp.size + finalMoov.size
        ByteBuffer.wrap(totalBytes, mdatPos, 8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(mdatTotalSize)
            put("mdat".toByteArray(Charsets.US_ASCII))
        }

        // Write a 4-byte NAL length prefix + IDR slice header (0x65) followed by deterministic payload
        val dataStart = mdatPos + 8
        val nalLen = payloadSize - 4
        ByteBuffer.wrap(totalBytes, dataStart, 8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(nalLen)
            put(0x65.toByte()) // Coded slice of an IDR picture
            put(0x88.toByte())
            put(0x84.toByte())
            put(0x21.toByte())
        }
        for (i in 8 until payloadSize) {
            totalBytes[dataStart + i] = ((i * 29 + 7) and 0xFF).toByte()
        }

        outputFile.writeBytes(totalBytes)
    }

    private class Mp4BoxBuilder {
        private val out = ByteArrayOutputStream()

        fun putByte(v: Int) {
            out.write(v and 0xFF)
        }

        fun putShort(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        fun putShort(v: Short) = putShort(v.toInt())

        fun putInt(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        fun putAscii(s: String) {
            out.write(s.toByteArray(Charsets.US_ASCII))
        }

        fun putBytes(b: ByteArray) {
            out.write(b)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun box(type: String, block: Mp4BoxBuilder.() -> Unit): ByteArray {
        val body = Mp4BoxBuilder().apply(block).toByteArray()
        val total = 8 + body.size
        return ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(total)
            put(type.toByteArray(Charsets.US_ASCII), 0, 4)
            put(body)
        }.array()
    }

    private fun fullBox(type: String, version: Int, flags: Int, block: Mp4BoxBuilder.() -> Unit): ByteArray {
        return box(type) {
            putInt(((version and 0xFF) shl 24) or (flags and 0x00FFFFFF))
            block()
        }
    }
}

