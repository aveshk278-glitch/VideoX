package com.videox.app.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscaleMathTest {

    @Test
    fun evenDown_roundsDownToEven() {
        assertEquals(1920, UpscaleMath.evenDown(1921))
        assertEquals(1920, UpscaleMath.evenDown(1920))
        assertEquals(2, UpscaleMath.evenDown(3))
        assertEquals(2, UpscaleMath.evenDown(1))
        assertEquals(2, UpscaleMath.evenDown(0))
    }

    @Test
    fun targetSize_preservesAspectAndStaysEven() {
        val (w, h) = UpscaleMath.targetSize(1280, 720, UpscaleMath.Profile.FAST)
        assertEquals(1080, h)
        assertEquals(1920, w)
        val (w2, h2) = UpscaleMath.targetSize(854, 480, UpscaleMath.Profile.QUALITY)
        assertEquals(1440, h2)
        assertEquals(0, w2 % 2)
        assertEquals(2562, w2)
        val (w3, h3) = UpscaleMath.targetSize(1080, 1080, UpscaleMath.Profile.ULTRA)
        assertEquals(2160, h3)
        assertEquals(2160, w3)
    }

    @Test
    fun profileUsable_rejectsInputsAtOrAboveTarget() {
        assertTrue(UpscaleMath.profileUsable(720, UpscaleMath.Profile.FAST))
        assertFalse(UpscaleMath.profileUsable(1080, UpscaleMath.Profile.FAST))
        assertFalse(UpscaleMath.profileUsable(2160, UpscaleMath.Profile.ULTRA))
        assertFalse(UpscaleMath.profileUsable(0, UpscaleMath.Profile.FAST))
        assertTrue(UpscaleMath.profileUsable(480, UpscaleMath.Profile.MAXIMUM))
    }

    @Test
    fun paddedDimension_coversImageWithWholeTiles() {
        assertEquals(256, UpscaleMath.paddedDimension(100, 256, 16))
        assertEquals(256, UpscaleMath.paddedDimension(256, 256, 16))
        assertEquals(496, UpscaleMath.paddedDimension(257, 256, 16))
        assertEquals(1456, UpscaleMath.paddedDimension(1280, 256, 16))
        assertEquals(736, UpscaleMath.paddedDimension(720, 256, 16))
        assertEquals(1936, UpscaleMath.paddedDimension(1920, 256, 16))
    }

    @Test
    fun tileOrigins_coverPaddedSizeExactly() {
        val origins = UpscaleMath.tileOrigins(1456, 256, 16)
        assertEquals(listOf(0, 240, 480, 720, 960, 1200), origins)
        assertEquals(listOf(0), UpscaleMath.tileOrigins(256, 256, 16))
    }

    @Test
    fun keptRanges_tilePaddedImageWithoutGapsOrOverlaps() {
        val sizes = listOf(100, 256, 257, 720, 1080, 1280, 1920, 3840)
        for (size in sizes) {
            val padded = UpscaleMath.paddedDimension(size, 256, 16)
            val kept = UpscaleMath.tileOrigins(padded, 256, 16)
                .map { UpscaleMath.keptRange(it, padded, 256, 16) }
            var cursor = 0
            for (range in kept) {
                assertEquals("gap/overlap at size $size", cursor, range.first)
                cursor = range.last + 1
            }
            assertEquals("kept ranges must end at padded size $size", padded, cursor)
        }
    }

    @Test
    fun keptRanges_withSmallTiles() {
        val padded = UpscaleMath.paddedDimension(100, 64, 16)
        val kept = UpscaleMath.tileOrigins(padded, 64, 16)
            .map { UpscaleMath.keptRange(it, padded, 64, 16) }
        var cursor = 0
        for (range in kept) {
            assertEquals(cursor, range.first)
            cursor = range.last + 1
        }
        assertEquals(padded, cursor)
    }

    @Test
    fun parseFrameRate_handlesFractionsAndPlainValues() {
        assertEquals(29.97, UpscaleMath.parseFrameRate("30000/1001")!!, 0.01)
        assertEquals(30.0, UpscaleMath.parseFrameRate("30/1")!!, 0.001)
        assertEquals(23.976, UpscaleMath.parseFrameRate("24000/1001")!!, 0.01)
        assertEquals(29.97, UpscaleMath.parseFrameRate("29.97")!!, 0.001)
        assertNull(UpscaleMath.parseFrameRate("0/0"))
        assertNull(UpscaleMath.parseFrameRate("N/A"))
        assertNull(UpscaleMath.parseFrameRate(""))
        assertNull(UpscaleMath.parseFrameRate("abc"))
        assertNull(UpscaleMath.parseFrameRate("0/1"))
    }

    @Test
    fun parseProbeCsv_readsVideoLineAndAudioPresence() {
        val probe = UpscaleMath.parseProbeCsv("1920,1080,30000/1001,12.480000", "aac")
        assertNotNull(probe)
        assertEquals(1920, probe!!.width)
        assertEquals(1080, probe.height)
        assertEquals(29.97, probe.fps, 0.01)
        assertEquals(12.48, probe.durationSec, 0.001)
        assertTrue(probe.hasAudio)

        val noAudio = UpscaleMath.parseProbeCsv("854,480,30/1,N/A", "")
        assertNotNull(noAudio)
        assertFalse(noAudio!!.hasAudio)
        assertEquals(0.0, noAudio.durationSec, 0.001)

        assertNull(UpscaleMath.parseProbeCsv("bad", ""))
        assertNull(UpscaleMath.parseProbeCsv("1920,1080,0/0,10", ""))
        assertNull(UpscaleMath.parseProbeCsv("0,1080,30/1,10", ""))
    }

    @Test
    fun estimatedFrames_isAtLeastOne() {
        assertEquals(374, UpscaleMath.estimatedFrames(12.48, 30.0))
        assertEquals(1, UpscaleMath.estimatedFrames(0.0, 30.0))
    }

    @Test
    fun decodeArgs_useImage2PipeMjpeg() {
        val args = UpscaleMath.decodeArgs("/bin/ffmpeg", "/in.mp4")
        assertEquals("/bin/ffmpeg", args[0])
        assertTrue(args.contains("-f") && args.contains("image2pipe"))
        assertTrue(args.contains("mjpeg"))
        assertEquals("-", args.last())
    }

    @Test
    fun encodeArgs_mapVideoAndOptionalAudio() {
        val args = UpscaleMath.encodeArgs(
            "/bin/ffmpeg", 30.0, "/orig.mp4",
            useHardwareEncoder = false, needsScale = true,
            targetWidth = 1920, targetHeight = 1080, outputPath = "/out.mp4"
        )
        assertTrue(args.contains("0:v:0"))
        assertTrue(args.contains("1:a?"))
        assertTrue(args.contains("libx264"))
        assertTrue(args.contains("scale=1920:1080:flags=lanczos"))
        assertTrue(args.contains("copy"))
        assertEquals("/out.mp4", args.last())

        val hw = UpscaleMath.encodeArgs(
            "/bin/ffmpeg", 30.0, "/orig.mp4",
            useHardwareEncoder = true, needsScale = false,
            targetWidth = 3840, targetHeight = 2160, outputPath = "/out.mp4"
        )
        assertTrue(hw.contains("h264_mediacodec"))
        assertTrue(hw.contains("40000k"))
        assertFalse(hw.any { it.startsWith("scale=") })
    }

    @Test
    fun encoderListed_matchesExactName() {
        val output = "Encoders:\n V..... libx264  libx264 H.264\n V..... h264_mediacodec  Android MediaCodec\n"
        assertTrue(UpscaleMath.encoderListed(output, "h264_mediacodec"))
        assertTrue(UpscaleMath.encoderListed(output, "libx264"))
        assertFalse(UpscaleMath.encoderListed(output, "h264"))
        assertFalse(UpscaleMath.encoderListed(output, "libx265"))
    }

    @Test
    fun videoBitrateKbps_scalesWithHeight() {
        assertEquals(12_000, UpscaleMath.videoBitrateKbps(1080))
        assertEquals(20_000, UpscaleMath.videoBitrateKbps(1440))
        assertEquals(40_000, UpscaleMath.videoBitrateKbps(2160))
    }

    @Test
    fun selectBest_picksFastestSuccessfulWithTiePreference() {
        val order = listOf("cpu", "xnnpack", "nnapi", "webgpu")
        val results = listOf(
            UpscaleMath.ProviderResult("cpu", 120, true),
            UpscaleMath.ProviderResult("xnnpack", 98, true),
            UpscaleMath.ProviderResult("nnapi", null, false),
            UpscaleMath.ProviderResult("webgpu", 150, true)
        )
        assertEquals("xnnpack", UpscaleMath.selectBest(results, order))
        val tie = listOf(
            UpscaleMath.ProviderResult("cpu", 100, true),
            UpscaleMath.ProviderResult("xnnpack", 100, true)
        )
        assertEquals("cpu", UpscaleMath.selectBest(tie, order))
        assertNull(
            UpscaleMath.selectBest(
                listOf(UpscaleMath.ProviderResult("cpu", null, false)),
                order
            )
        )
        assertNull(UpscaleMath.selectBest(emptyList(), order))
    }

    @Test
    fun benchmarkCodec_roundTripsAndSummarises() {
        val results = listOf(
            UpscaleMath.ProviderResult("cpu", 120, true),
            UpscaleMath.ProviderResult("nnapi", null, false)
        )
        val encoded = UpscaleMath.encodeBenchmark(results)
        assertEquals("cpu:120;nnapi:FAIL", encoded)
        assertEquals(results, UpscaleMath.decodeBenchmark(encoded))
        assertTrue(UpscaleMath.decodeBenchmark("").isEmpty())
        assertTrue(UpscaleMath.decodeBenchmark("garbage").isEmpty())
        val summary = UpscaleMath.benchmarkSummary(encoded, listOf("cpu", "nnapi"))
        assertNotNull(summary)
        assertTrue(summary!!.contains("Fastest: cpu"))
        assertTrue(summary.contains("nnapi unavailable"))
        assertNull(UpscaleMath.benchmarkSummary("", listOf("cpu")))
    }

    @Test
    fun plausibleOutputStats_rejectsFlatOrSaturatedOutput() {
        assertTrue(UpscaleMath.plausibleOutputStats(0.5, 0.2))
        assertFalse(UpscaleMath.plausibleOutputStats(0.0, 0.0))
        assertFalse(UpscaleMath.plausibleOutputStats(1.0, 0.0))
        assertFalse(UpscaleMath.plausibleOutputStats(0.5, 0.0))
        assertFalse(UpscaleMath.plausibleOutputStats(0.01, 0.2))
    }

    @Test
    fun estimatedOutputBytes_growsWithDurationAndHeight() {
        val small = UpscaleMath.estimatedOutputBytes(10.0, 1080, false)
        val big = UpscaleMath.estimatedOutputBytes(10.0, 2160, false)
        assertTrue(big > small)
        assertTrue(UpscaleMath.estimatedOutputBytes(10.0, 1080, true) > small)
        assertTrue(small > 0)
    }

    @Test
    fun sha256_matchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".toByteArray())
        )
        val tmp = File.createTempFile("sha-test", ".bin")
        try {
            tmp.writeBytes("abc".toByteArray())
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Sha256.fileHex(tmp)
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun historyCodec_roundTripsWithEscaping() {
        val items = listOf(
            UpscaleHistoryItem("my \"quoted\" video.mp4", "Fast — up to 1080p", "out.mp4", 1920, 1080, 123456789L),
            UpscaleHistoryItem("back\\slash.mp4", "Quality — up to 1440p", "out2.mp4", 2560, 1440, 123456790L)
        )
        val decoded = UpscaleHistory.decode(UpscaleHistory.encode(items))
        assertEquals(items, decoded)
        assertTrue(UpscaleHistory.decode("").isEmpty())
        assertTrue(UpscaleHistory.decode("not json").isEmpty())
        assertTrue(UpscaleHistory.decode("[]").isEmpty())
        val many = (1..25).map {
            UpscaleHistoryItem("v$it.mp4", "Fast", "o$it.mp4", 1920, 1080, it.toLong())
        }
        assertEquals(UpscaleHistory.MAX_ITEMS, UpscaleHistory.decode(UpscaleHistory.encode(many)).size)
    }

    @Test
    fun jpegSplitter_framesAcrossChunks() {
        val frame1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
        val frame2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x33, 0xFF.toByte(), 0x00, 0x44, 0xFF.toByte(), 0xD9.toByte())
        val stream = byteArrayOf(0x99.toByte(), 0x88.toByte()) + frame1 + frame2
        val splitter = JpegStreamSplitter()
        val found = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < stream.size) {
            val chunk = minOf(3, stream.size - offset)
            found += splitter.feed(stream, offset, chunk)
            offset += chunk
        }
        assertNull(splitter.finish())
        assertEquals(2, found.size)
        assertTrue(frame1.contentEquals(found[0]))
        assertTrue(frame2.contentEquals(found[1]))
    }

    @Test
    fun jpegSplitter_reportsPartialFrame() {
        val splitter = JpegStreamSplitter()
        val frames = splitter.feed(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22))
        assertTrue(frames.isEmpty())
        assertNotNull(splitter.finish())
    }
}
