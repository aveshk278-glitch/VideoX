package com.videox.app.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the bundled FFmpeg/ffprobe binaries (v2.15). The youtubedl-android
 * ffmpeg module ships the real ffmpeg executable as libffmpeg.so in the
 * native library dir, plus its shared libraries extracted by
 * FFmpeg.init() under noBackupFilesDir/youtubedl-android/packages/ffmpeg.
 * Both facts were verified against the real 0.18.1 AAR (the .so is an
 * ARM PIE executable with a "usage:" string; the zip holds the usr/lib tree).
 * Processes are started with LD_LIBRARY_PATH pointing at the extracted
 * libraries, and every failure is reported honestly — nothing here
 * pretends a step worked when it did not.
 */
class FFmpegRunner(context: Context) {

    private val appContext = context.applicationContext

    private val ffmpegBinary: File by lazy {
        File(appContext.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    }

    private val ffprobeBinary: File by lazy {
        File(appContext.applicationInfo.nativeLibraryDir, "libffprobe.so")
    }

    private val libDir: File by lazy { resolveLibDir() }

    @Volatile
    private var cachedHardwareEncoder: Boolean? = null

    private fun resolveLibDir(): File {
        val base = File(appContext.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        val candidates = listOf(
            File(base, "usr/lib"),
            File(base, "lib"),
            File(appContext.applicationInfo.nativeLibraryDir)
        )
        return candidates.firstOrNull { File(it, "libavcodec.so.61").exists() } ?: candidates[0]
    }

    private fun builder(args: List<String>): ProcessBuilder {
        val binary = File(args[0])
        if (binary.exists() && !binary.canExecute()) {
            try {
                binary.setExecutable(true)
            } catch (_: Exception) {
                // If the bit cannot be set, the process start fails below and is reported.
            }
        }
        return ProcessBuilder(args).apply {
            environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
        }
    }

    /**
     * Run a short command and capture its combined output (capped).
     * Throws IOException on timeout or when the process cannot start.
     */
    suspend fun runCapture(args: List<String>, timeoutMs: Long = 30_000): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val process = try {
                builder(args).redirectErrorStream(true).start()
            } catch (e: Exception) {
                throw IOException("Could not start the video processor: ${e.message}", e)
            }
            val output = StringBuilder()
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        if (output.length < 32_768) {
                            output.append(buffer, 0, read)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw IOException("The video processor timed out. Try again.")
            }
            Pair(process.exitValue(), output.toString())
        }

    /** True when the ffmpeg binary starts and reports its version. */
    suspend fun verifyBinary(): Boolean {
        return try {
            val (code, out) = runCapture(listOf(ffmpegBinary.absolutePath, "-version"), 15_000)
            code == 0 && out.contains("ffmpeg version", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /** Probe resolution, frame rate, duration and audio presence. */
    suspend fun probeVideo(file: File): UpscaleMath.ProbeResult {
        val probed = try {
            val (videoCode, videoOut) =
                runCapture(UpscaleMath.probeVideoArgs(ffprobeBinary.absolutePath, file.absolutePath))
            val (_, audioOut) =
                runCapture(UpscaleMath.probeAudioArgs(ffprobeBinary.absolutePath, file.absolutePath))
            if (videoCode == 0) {
                UpscaleMath.parseProbeCsv(videoOut.trim().lineSequence().firstOrNull().orEmpty(), audioOut.trim())
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        if (probed != null) return probed
        return probeVideoFallback(file)
            ?: throw IOException("Could not read this video's resolution or frame rate, so it cannot be upscaled.")
    }

    /** Android-API fallback when ffprobe cannot read the file. */
    private fun probeVideoFallback(file: File): UpscaleMath.ProbeResult? {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                if (width == null || height == null || width <= 0 || height <= 0) return null
                var fps: Double? = null
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            fps = format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                            break
                        }
                    }
                } finally {
                    extractor.release()
                }
                val rate = fps?.takeIf { it > 0.0 } ?: return null
                UpscaleMath.ProbeResult(
                    width, height, rate,
                    (durationMs ?: 0L) / 1000.0, hasAudio
                )
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** True when this FFmpeg build offers the hardware H.264 encoder. Probed once, then cached. */
    suspend fun hasHardwareEncoder(): Boolean {
        cachedHardwareEncoder?.let { return it }
        val found = try {
            val (code, out) = runCapture(UpscaleMath.encodersArgs(ffmpegBinary.absolutePath))
            code == 0 && UpscaleMath.encoderListed(out, "h264_mediacodec")
        } catch (_: Exception) {
            false
        }
        cachedHardwareEncoder = found
        return found
    }

    /**
     * Start the decode process: JPEG frames on stdout, diagnostics in
     * [stderrFile]. The caller owns both the process and the file.
     */
    fun startDecode(input: File, stderrFile: File): Process {
        return builder(UpscaleMath.decodeArgs(ffmpegBinary.absolutePath, input.absolutePath))
            .redirectError(stderrFile)
            .start()
    }

    /**
     * Start the encode process: upscaled JPEG frames are written to
     * its stdin, diagnostics go to [stderrFile]. The caller owns
     * both the process and the file. (Stdout is merged into the
     * diagnostics file — the encoder writes nothing to stdout, and
     * this avoids leaving an undrained pipe behind.)
     */
    fun startEncode(args: List<String>, stderrFile: File): Process {
        return builder(args)
            .redirectErrorStream(true)
            .redirectOutput(stderrFile)
            .start()
    }

    fun ffmpegPath(): String = ffmpegBinary.absolutePath

    /** Last lines of a diagnostics file, for honest error messages. */
    fun stderrTail(stderrFile: File, maxChars: Int = 600): String {
        return try {
            if (!stderrFile.exists()) return ""
            val text = stderrFile.readText().trim()
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        } catch (_: Exception) {
            ""
        }
    }
}
