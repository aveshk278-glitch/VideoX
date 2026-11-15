package com.videox.app.download

/**
 * Pure upscaling math (v2.15): profiles, target sizes, tile grids,
 * frame-rate parsing, ffprobe output parsing, FFmpeg command building
 * and benchmark selection. No Android imports, so every function here
 * is covered by JVM unit tests (UpscaleMathTest).
 *
 * The AI model upscales exactly 4x. A profile's target resolution is
 * reached by AI-upscaling 4x and then resizing to the target with
 * FFmpeg's Lanczos scaler — the enhancement is real AI work, the
 * final resize is an honest, disclosed step (see UpscalerScreen).
 */
object UpscaleMath {

    /** The Real-ESRGAN general-x4v3 model upscales exactly 4x. */
    const val MODEL_SCALE = 4

    /** Default tile size in input pixels. Halved on out-of-memory, down to [MIN_TILE_SIZE]. */
    const val DEFAULT_TILE_SIZE = 256

    /** Overlap between neighbouring tiles in input pixels. Must stay even (see keptRect). */
    const val DEFAULT_OVERLAP = 16

    const val MIN_TILE_SIZE = 64

    /** Battery Saver mode uses smaller tiles (less heat, less memory). */
    const val BATTERY_TILE_SIZE = 128

    enum class Profile(
        val label: String,
        val targetHeight: Int,
        val nominalInputHeight: Int,
        val experimental: Boolean
    ) {
        FAST("Fast — up to 1080p", 1080, 720, false),
        QUALITY("Quality — up to 1440p", 1440, 720, false),
        ULTRA("Ultra — up to 4K", 2160, 1080, true),
        MAXIMUM("Maximum — up to 4K", 2160, 480, true);

        companion object {
            fun fromIndex(index: Int): Profile = entries[index.coerceIn(0, entries.size - 1)]
        }
    }

    /** Largest even number at most [value] (H.264 needs even dimensions), at least 2. */
    fun evenDown(value: Int): Int = (value and 1.inv()).coerceAtLeast(2)

    /**
     * Output size for an input frame under [profile]: the profile's
     * target height, width scaled to preserve the aspect ratio and
     * rounded down to an even number.
     */
    fun targetSize(inputWidth: Int, inputHeight: Int, profile: Profile): Pair<Int, Int> {
        val targetH = profile.targetHeight
        val scaledW = ((inputWidth.toLong() * targetH) / inputHeight.coerceAtLeast(1)).toInt()
        return Pair(evenDown(scaledW), targetH)
    }

    /** A profile only makes sense when the input is below its target height. */
    fun profileUsable(inputHeight: Int, profile: Profile): Boolean =
        inputHeight in 1 until profile.targetHeight

    /**
     * Padded dimension for tiling: the image is edge-padded so whole
     * tiles of [tileSize] with [overlap] cover it exactly. Every tile
     * is then exactly [tileSize] pixels, which keeps the model input
     * shape constant even for edge tiles.
     */
    fun paddedDimension(size: Int, tileSize: Int, overlap: Int): Int {
        require(tileSize > overlap && overlap >= 0) { "tileSize must exceed overlap" }
        if (size <= tileSize) return tileSize
        val step = tileSize - overlap
        val extra = size - tileSize
        val steps = (extra + step - 1) / step
        return tileSize + steps * step
    }

    /** Tile origins along one axis for a padded dimension. */
    fun tileOrigins(paddedSize: Int, tileSize: Int, overlap: Int): List<Int> {
        val step = tileSize - overlap
        val origins = mutableListOf<Int>()
        var origin = 0
        while (origin + tileSize <= paddedSize) {
            origins += origin
            origin += step
        }
        return origins
    }

    /**
     * The input-pixel range of a tile that is kept when stitching, as
     * [start, end). Interior edges give up half the overlap to the
     * neighbouring tile; edges of the padded image keep everything.
     * Kept ranges of neighbouring tiles meet exactly (no gaps, no
     * double-written pixels) because the step equals
     * tileSize minus overlap. [overlap] must be even.
     */
    fun keptRange(origin: Int, paddedSize: Int, tileSize: Int, overlap: Int): IntRange {
        require(overlap % 2 == 0) { "overlap must be even" }
        val half = overlap / 2
        val start = if (origin == 0) origin else origin + half
        val end = if (origin + tileSize >= paddedSize) origin + tileSize else origin + tileSize - half
        return start until end
    }

    /**
     * Parse a frame rate: "30000/1001", "30/1", "29.97". Returns null
     * for "0/0", "N/A", empty or unparsable input.
     */
    fun parseFrameRate(text: String): Double? {
        val clean = text.trim()
        if (clean.isEmpty() || clean.equals("N/A", ignoreCase = true)) return null
        val slash = clean.indexOf('/')
        return try {
            if (slash >= 0) {
                val num = clean.substring(0, slash).toDouble()
                val den = clean.substring(slash + 1).toDouble()
                if (den == 0.0 || num <= 0.0) null else num / den
            } else {
                clean.toDouble().takeIf { it > 0.0 }
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    data class ProbeResult(
        val width: Int,
        val height: Int,
        val fps: Double,
        val durationSec: Double,
        val hasAudio: Boolean
    )

    /**
     * Parse ffprobe CSV output: [videoLine] is
     * "width,height,avg_frame_rate,duration" from the first video
     * stream; [audioLine] is non-blank when an audio stream exists.
     * Returns null when the video line is unusable.
     */
    fun parseProbeCsv(videoLine: String, audioLine: String): ProbeResult? {
        val parts = videoLine.trim().split(",")
        if (parts.size < 3) return null
        val width = parts[0].trim().toIntOrNull() ?: return null
        val height = parts[1].trim().toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val fps = parseFrameRate(parts[2]) ?: return null
        val duration = parts.getOrNull(3)?.trim()?.toDoubleOrNull() ?: 0.0
        return ProbeResult(width, height, fps, duration.coerceAtLeast(0.0), audioLine.isNotBlank())
    }

    /** Estimated frame count from probe data (used for progress only). */
    fun estimatedFrames(durationSec: Double, fps: Double): Int =
        (durationSec * fps).toInt().coerceAtLeast(1)

    /** FFmpeg decode arguments: video frames as a JPEG stream on stdout. */
    fun decodeArgs(ffmpegBinary: String, inputPath: String): List<String> = listOf(
        ffmpegBinary, "-hide_banner", "-loglevel", "error",
        "-i", inputPath,
        "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", "3",
        "-"
    )

    /** ffprobe arguments for the first video stream as one CSV line. */
    fun probeVideoArgs(ffprobeBinary: String, inputPath: String): List<String> = listOf(
        ffprobeBinary, "-v", "error",
        "-select_streams", "v:0",
        "-show_entries", "stream=width,height,avg_frame_rate,duration",
        "-of", "csv=p=0",
        inputPath
    )

    /** ffprobe arguments that print one line per audio stream (empty = no audio). */
    fun probeAudioArgs(ffprobeBinary: String, inputPath: String): List<String> = listOf(
        ffprobeBinary, "-v", "error",
        "-select_streams", "a",
        "-show_entries", "stream=codec_type",
        "-of", "csv=p=0",
        inputPath
    )

    /** FFmpeg arguments listing all encoders (for the MediaCodec probe). */
    fun encodersArgs(ffmpegBinary: String): List<String> =
        listOf(ffmpegBinary, "-hide_banner", "-encoders")

    /** True when [encodersOutput] lists an encoder named exactly [name]. */
    fun encoderListed(encodersOutput: String, name: String): Boolean {
        return encodersOutput.lineSequence().any { line ->
            val tokens = line.trim().split(Regex("\\s+"))
            tokens.size >= 2 && tokens[1] == name
        }
    }

    /** Video bitrate (kbps) for the hardware encoder, by output height. */
    fun videoBitrateKbps(targetHeight: Int): Int = when {
        targetHeight <= 1080 -> 12_000
        targetHeight <= 1440 -> 20_000
        else -> 40_000
    }

    /**
     * FFmpeg encode arguments: upscaled JPEG frames arrive on stdin
     * (image2pipe), audio is copied from the original file when it
     * has any. [needsScale] adds the Lanczos resize to the exact
     * target size; when the 4x output already equals the target, no
     * resize filter is used. [useHardwareEncoder] selects
     * h264_mediacodec with a fixed bitrate, otherwise libx264 CRF.
     */
    fun encodeArgs(
        ffmpegBinary: String,
        fps: Double,
        originalPath: String,
        useHardwareEncoder: Boolean,
        needsScale: Boolean,
        targetWidth: Int,
        targetHeight: Int,
        outputPath: String
    ): List<String> {
        val args = mutableListOf(
            ffmpegBinary, "-hide_banner", "-loglevel", "error", "-y",
            "-framerate", fps.toString(),
            "-f", "image2pipe", "-vcodec", "mjpeg", "-i", "-",
            "-i", originalPath,
            "-map", "0:v:0",
            "-map", "1:a?"
        )
        if (needsScale) {
            args += listOf("-vf", "scale=$targetWidth:$targetHeight:flags=lanczos")
        }
        if (useHardwareEncoder) {
            args += listOf("-c:v", "h264_mediacodec", "-b:v", "${videoBitrateKbps(targetHeight)}k")
        } else {
            args += listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20")
        }
        args += listOf(
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-shortest",
            "-movflags", "+faststart",
            outputPath
        )
        return args
    }

    /** One benchmark outcome for one execution provider. */
    data class ProviderResult(
        val provider: String,
        val msPerTile: Long?,
        val ok: Boolean
    )

    /**
     * Pick the fastest provider that ran successfully. Ties are
     * broken by [preferenceOrder] (earlier wins). Returns null when
     * no provider succeeded.
     */
    fun selectBest(
        results: List<ProviderResult>,
        preferenceOrder: List<String>
    ): String? {
        val usable = results.filter { it.ok && it.msPerTile != null }
        if (usable.isEmpty()) return null
        val fastest = usable.minOf { it.msPerTile!! }
        return usable
            .filter { it.msPerTile == fastest }
            .minByOrNull { preferenceOrder.indexOf(it.provider).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
            ?.provider
    }

    /**
     * Compact benchmark storage: "cpu:123;xnnpack:98;nnapi:FAIL;webgpu:FAIL".
     * A provider that failed stores FAIL instead of a time.
     */
    fun encodeBenchmark(results: List<ProviderResult>): String =
        results.joinToString(";") { "${it.provider}:${if (it.ok && it.msPerTile != null) it.msPerTile else "FAIL"}" }

    fun decodeBenchmark(stored: String): List<ProviderResult> {
        if (stored.isBlank()) return emptyList()
        return stored.split(";").mapNotNull { part ->
            val kv = part.split(":")
            if (kv.size != 2 || kv[0].isBlank()) return@mapNotNull null
            val ms = kv[1].toLongOrNull()
            if (ms != null && ms >= 0) ProviderResult(kv[0], ms, true)
            else if (kv[1] == "FAIL") ProviderResult(kv[0], null, false)
            else null
        }
    }

    /** One-line human summary of stored benchmark results, or null when none. */
    fun benchmarkSummary(stored: String, preferenceOrder: List<String>): String? {
        val results = decodeBenchmark(stored)
        if (results.isEmpty()) return null
        return benchmarkSummary(results, preferenceOrder)
    }

    /** One-line human summary of benchmark results, or null when none. */
    fun benchmarkSummary(results: List<ProviderResult>, preferenceOrder: List<String>): String? {
        if (results.isEmpty()) return null
        val winner = selectBest(results, preferenceOrder)
        val parts = results.joinToString(", ") { result ->
            if (result.ok && result.msPerTile != null) "${result.provider} ${result.msPerTile} ms" else "${result.provider} unavailable"
        }
        return if (winner != null) "Fastest: $winner — $parts" else parts
    }

    /**
     * Sanity check on a benchmark tile's output: a provider that
     * "succeeds" but returns black, white or flat output is treated
     * as unreliable. A gradient tile must produce mid-range values
     * with real variation.
     */
    fun plausibleOutputStats(mean: Double, stddev: Double): Boolean =
        mean in 0.05..0.95 && stddev > 0.01

    /** Rough output-size estimate for the free-space precheck (bytes). */
    fun estimatedOutputBytes(durationSec: Double, targetHeight: Int, hasAudio: Boolean): Long {
        val videoBits = videoBitrateKbps(targetHeight).toLong() * 1000L * durationSec.toLong().coerceAtLeast(1L)
        val audioBits = if (hasAudio) 192_000L * durationSec.toLong().coerceAtLeast(1L) else 0L
        return ((videoBits + audioBits) / 8L * 11L) / 10L
    }
}
