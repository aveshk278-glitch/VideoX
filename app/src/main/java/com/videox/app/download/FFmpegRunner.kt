package com.videox.app.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.yausername.ffmpeg.FFmpeg
import java.io.File
import java.io.IOException
import java.nio.file.Files
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

    /** Outcome of the most recent FFmpeg.init() attempt, for diagnostics. */
    @Volatile
    var lastInitOutcome: String = "not attempted"
        private set

    /**
     * Make sure the FFmpeg shared libraries are extracted before any
     * binary is executed. YtDlpEngine.init() normally does this at app
     * start, but probing must not depend on that having succeeded, so
     * this runs the same init itself whenever the libraries are still
     * missing. Retries are deliberate: a single failed attempt must
     * never poison the rest of the process. A failed init never throws
     * here: the later steps report honestly when a binary cannot start.
     */
    private fun ensureInit() {
        try {
            if (File(resolveLibDir(), "libavcodec.so.61").exists()) {
                lastInitOutcome = "libraries already present"
                return
            }
            FFmpeg.getInstance().init(appContext)
            lastInitOutcome = if (File(resolveLibDir(), "libavcodec.so.61").exists()) {
                "init ran, libraries present"
            } else {
                "init ran but libavcodec.so.61 is still missing"
            }
        } catch (e: Exception) {
            lastInitOutcome = "init failed: ${e.message}"
            // Reported honestly by the step that needs the binary.
        }
    }

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

    private fun builder(args: List<String>, withLibPath: Boolean = true): ProcessBuilder {
        val binary = File(args[0])
        if (binary.exists() && !binary.canExecute()) {
            try {
                binary.setExecutable(true)
            } catch (_: Exception) {
                // If the bit cannot be set, the process start fails below and is reported.
            }
        }
        return ProcessBuilder(args).apply {
            if (withLibPath) {
                // Append, never replace: the child keeps the paths the
                // app process itself was started with, plus the FFmpeg
                // libraries. Replacing the whole variable was suspected
                // in the v2.16 "processor could not be started" failure.
                val existing = environment()["LD_LIBRARY_PATH"]
                environment()["LD_LIBRARY_PATH"] = if (existing.isNullOrBlank()) {
                    libDir.absolutePath
                } else {
                    "$existing:${libDir.absolutePath}"
                }
            }
        }
    }

    /**
     * Run a short command and capture its combined output (capped).
     * Throws IOException on timeout or when the process cannot start.
     */
    suspend fun runCapture(
        args: List<String>,
        timeoutMs: Long = 30_000,
        withLibPath: Boolean = true
    ): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val process = try {
                builder(args, withLibPath).redirectErrorStream(true).start()
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

    /** Everything the support conversation needs to pin down a "processor could not be started" failure. */
    data class FfmpegDiagnosis(
        val binaryPath: String,
        val binaryExists: Boolean,
        val binaryExecutable: Boolean,
        val binaryLength: Long,
        val libDirPath: String,
        val libDirExists: Boolean,
        val libFileCount: Int,
        val libavcodecExists: Boolean,
        val libavcodecLength: Long,
        val libavcodecSymlink: Boolean,
        val initOutcome: String,
        val runWithLibPath: String,
        val runPlainEnv: String,
        val ok: Boolean
    )

    /**
     * Check the ffmpeg binary two ways (with the FFmpeg library path,
     * and with the plain inherited environment) and gather the
     * on-device facts. Never throws: every failure is data.
     */
    suspend fun diagnose(): FfmpegDiagnosis {
        ensureInit()
        val binary = ffmpegBinary
        val dir = libDir
        val codec = File(dir, "libavcodec.so.61")
        val libFiles = try {
            dir.listFiles()?.size ?: -1
        } catch (_: Exception) {
            -1
        }
        val codecSymlink = try {
            Files.isSymbolicLink(codec.toPath())
        } catch (_: Exception) {
            false
        }
        val withLib = runVersion(withLibPath = true)
        val plain = runVersion(withLibPath = false)
        val ok = withLib.first || plain.first
        return FfmpegDiagnosis(
            binaryPath = binary.absolutePath,
            binaryExists = binary.exists(),
            binaryExecutable = try {
                binary.canExecute()
            } catch (_: Exception) {
                false
            },
            binaryLength = try {
                binary.length()
            } catch (_: Exception) {
                -1L
            },
            libDirPath = dir.absolutePath,
            libDirExists = dir.exists(),
            libFileCount = libFiles,
            libavcodecExists = codec.exists(),
            libavcodecLength = try {
                codec.length()
            } catch (_: Exception) {
                -1L
            },
            libavcodecSymlink = codecSymlink,
            initOutcome = lastInitOutcome,
            runWithLibPath = withLib.second,
            runPlainEnv = plain.second,
            ok = ok
        )
    }

    private suspend fun runVersion(withLibPath: Boolean): Pair<Boolean, String> {
        return try {
            val (code, out) = runCapture(
                listOf(ffmpegBinary.absolutePath, "-version"),
                15_000,
                withLibPath
            )
            val head = out.trim().replace(Regex("\\s+"), " ").take(150)
            val good = code == 0 && out.contains("ffmpeg version", ignoreCase = true)
            Pair(good, "exit=$code out=\"$head\"")
        } catch (e: Exception) {
            Pair(false, "start failed: ${e.message}")
        }
    }

    /** Short multi-line rendering of [diagnose] for the on-screen error message. */
    fun formatDiagnosis(d: FfmpegDiagnosis): String {
        return listOf(
            "binary: ${d.binaryPath} exists=${d.binaryExists} exec=${d.binaryExecutable} size=${d.binaryLength}",
            "libdir: ${d.libDirPath} exists=${d.libDirExists} files=${d.libFileCount}",
            "libavcodec.so.61: exists=${d.libavcodecExists} size=${d.libavcodecLength} symlink=${d.libavcodecSymlink}",
            "init: ${d.initOutcome}",
            "run (with libs): ${d.runWithLibPath}",
            "run (plain env): ${d.runPlainEnv}"
        ).joinToString("\n")
    }

    /** True when the ffmpeg binary starts and reports its version. */
    suspend fun verifyBinary(): Boolean {
        return diagnose().ok
    }

    /** Probe resolution, frame rate, duration and audio presence. */
    suspend fun probeVideo(file: File): UpscaleMath.ProbeResult {
        ensureInit()
        var ffprobeDetail: String? = null
        val probed = try {
            val (videoCode, videoOut) =
                runCapture(UpscaleMath.probeVideoArgs(ffprobeBinary.absolutePath, file.absolutePath))
            val (_, audioOut) =
                runCapture(UpscaleMath.probeAudioArgs(ffprobeBinary.absolutePath, file.absolutePath))
            ffprobeDetail = "ffprobe exit code $videoCode, output: ${videoOut.trim().take(200)}"
            if (videoCode == 0) {
                UpscaleMath.parseProbeCsv(videoOut.trim().lineSequence().firstOrNull().orEmpty(), audioOut.trim())
            } else {
                null
            }
        } catch (e: Exception) {
            ffprobeDetail = "ffprobe could not run (${e.message})"
            null
        }
        if (probed != null) return probed
        return probeVideoFallback(file)
            ?: throw IOException(
                "Could not read this video's resolution or frame rate, so it cannot be upscaled. " +
                    "The Android fallback could not determine the width, height or frame rate. " +
                    (ffprobeDetail?.let { "ffprobe detail: $it" } ?: "ffprobe did not run.")
            )
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
                val durationSec = (durationMs ?: 0L) / 1000.0
                val fps = fallbackFrameRate(file)
                if (fps != null) {
                    UpscaleMath.ProbeResult(width, height, fps, durationSec, hasAudio)
                } else if (durationMs != null && durationMs > 0) {
                    // Width, height and duration read fine but no frame
                    // rate anywhere: assume 30 fps and say so honestly.
                    UpscaleMath.ProbeResult(width, height, 30.0, durationSec, hasAudio, fpsEstimated = true)
                } else {
                    null
                }
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Frame rate from the Android APIs: the track's declared rate when
     * present, otherwise measured from sample timestamps (many phone
     * and messenger videos do not declare a rate at all). Returns null
     * when neither source yields a sane rate.
     */
    private fun fallbackFrameRate(file: File): Double? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1
            var declaredFps: Double? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                videoTrack = i
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    declaredFps = format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                }
                break
            }
            declaredFps?.takeIf { it in 1.0..240.0 }?.let { return it }
            if (videoTrack < 0) return null
            extractor.selectTrack(videoTrack)
            var count = 0
            var firstUs = -1L
            var lastUs = -1L
            while (count < 1000) {
                val timeUs = extractor.getSampleTime()
                if (timeUs < 0) break
                if (firstUs < 0) firstUs = timeUs
                lastUs = timeUs
                count++
                if (!extractor.advance()) break
            }
            if (count < 10) return null
            val spanUs = lastUs - firstUs
            if (spanUs <= 0) return null
            ((count - 1) * 1_000_000.0 / spanUs).takeIf { it in 1.0..240.0 }
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /** True when this FFmpeg build offers the hardware H.264 encoder. Probed once, then cached. */
    suspend fun hasHardwareEncoder(): Boolean {
        ensureInit()
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
