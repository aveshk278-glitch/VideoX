package com.videox.app.download

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Thrown when the user cancels an upscale job (not an error). */
class UpscaleCancelled : Exception("Upscaling was cancelled.")

/** Pause/cancel flags shared between the UI and a running job. */
class UpscaleControl {
    @Volatile
    var paused: Boolean = false

    @Volatile
    var cancelled: Boolean = false

    /** Throw when cancelled; suspend while paused. Checked between frames and during waits. */
    suspend fun check() {
        if (cancelled) throw UpscaleCancelled()
        while (paused) {
            if (cancelled) throw UpscaleCancelled()
            delay(200)
        }
    }
}

data class UpscaleProgress(
    val phase: String,
    val framesDone: Int,
    val framesEstimated: Int,
    /** Measured frames/second once enough frames are done, else null ("estimating"). */
    val measuredFps: Double?,
    val detail: String? = null
)

data class UpscaleResult(
    val outputFile: File,
    val width: Int,
    val height: Int,
    val frames: Int,
    val elapsedMs: Long,
    val providerKey: String,
    val encoder: String,
    val inputName: String
)

/**
 * One upscale job, end to end (v2.15): probe, model, benchmark,
 * streaming decode -> AI upscale -> streaming encode, verification.
 *
 * Frames stream through the job one at a time — FFmpeg decodes to a
 * JPEG pipe, each frame is upscaled in memory, and the upscaled JPEG
 * bytes go straight into the encoder's stdin. No frame files are
 * ever written, so a long video cannot fill the disk with
 * intermediates. Thermal throttling pauses the job automatically,
 * low memory pauses it too, and out-of-memory retries with smaller
 * tiles before giving up honestly. The output file is only moved
 * into Downloads after its real resolution is verified.
 */
object UpscalePipeline {

    private const val THERMAL_CHECK_EVERY_FRAMES = 30
    private const val MAX_THERMAL_WAIT_MS = 10 * 60 * 1000L
    private const val MAX_MEMORY_WAIT_MS = 2 * 60 * 1000L

    suspend fun runJob(
        context: Context,
        inputFile: File,
        profile: UpscaleMath.Profile,
        batterySaver: Boolean,
        providerOverrideKey: String,
        storedBenchmark: String,
        onBenchmarkDone: (String) -> Unit,
        control: UpscaleControl,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val runner = FFmpegRunner(context)
        val cacheWork = File(context.cacheDir, "upscale-work").apply { mkdirs() }
        val tempOutput = File(cacheWork, "upscaled-temp.mp4")
        val decodeStderr = File(cacheWork, "decode-err.log")
        val encodeStderr = File(cacheWork, "encode-err.log")
        if (tempOutput.exists()) tempOutput.delete()

        try {
            onProgress(UpscaleProgress("Preparing", 0, 1, null))
            control.check()
            if (!runner.verifyBinary()) {
                throw IOException(
                    "The video processor could not be started on this phone, so upscaling is unavailable. " +
                        "Try restarting VideoX."
                )
            }
            val probe = runner.probeVideo(inputFile)
            if (!UpscaleMath.profileUsable(probe.height, profile)) {
                throw IOException(
                    "This video is already ${probe.height}p, which is at or above the ${profile.targetHeight}p " +
                        "target of ${profile.label}. Pick a higher target or a lower-resolution video."
                )
            }
            val (targetW, targetH) = UpscaleMath.targetSize(probe.width, probe.height, profile)
            val framesEstimated = UpscaleMath.estimatedFrames(probe.durationSec, probe.fps)

            val outputDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${DownloadManagerHelper.DOWNLOAD_FOLDER}/Upscaled"
            ).apply { mkdirs() }
            val needed = UpscaleMath.estimatedOutputBytes(probe.durationSec, targetH, probe.hasAudio)
            val free = try {
                StatFs(outputDir.absolutePath).availableBytes
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
            if (free < needed) {
                throw IOException(
                    "Not enough free space for the upscaled file (about ${needed / 1_000_000} MB needed). " +
                        "Free up space and try again."
                )
            }

            onProgress(UpscaleProgress("Preparing AI model", 0, framesEstimated, null, "Checking the AI model…"))
            control.check()
            val modelFile = UpscaleModel.ensureModel(context) { percent ->
                onProgress(
                    UpscaleProgress(
                        "Downloading AI model", 0, framesEstimated, null,
                        "Downloading the AI model (4.7 MB)… $percent%"
                    )
                )
            }

            val threads = if (batterySaver) 2 else Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val tileStart = if (batterySaver) UpscaleMath.BATTERY_TILE_SIZE else UpscaleMath.DEFAULT_TILE_SIZE
            val providerKey = resolveProvider(
                batterySaver, providerOverrideKey, storedBenchmark,
                modelFile, threads, onBenchmarkDone, onProgress, framesEstimated, control
            )
            val provider = UpscaleEngine.Provider.fromKey(providerKey)
                ?: throw IOException("Unknown AI provider \"$providerKey\".")

            val useHardwareEncoder = runner.hasHardwareEncoder()
            val encoderName = if (useHardwareEncoder) "h264_mediacodec" else "libx264"
            val upscaledW = probe.width * UpscaleMath.MODEL_SCALE
            val upscaledH = probe.height * UpscaleMath.MODEL_SCALE
            val needsScale = upscaledW != targetW || upscaledH != targetH
            val encodeArgs = UpscaleMath.encodeArgs(
                runner.ffmpegPath(), probe.fps, inputFile.absolutePath,
                useHardwareEncoder, needsScale, targetW, targetH, tempOutput.absolutePath
            )

            var framesDone = 0
            var tileSize = tileStart
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            UpscaleEngine.createSession(modelFile, provider, threads).use { session ->
                val decodeProcess = runner.startDecode(inputFile, decodeStderr)
                val encodeProcess = runner.startEncode(encodeArgs, encodeStderr)
                try {
                    val encoderOut = BufferedOutputStream(encodeProcess.outputStream)
                    val splitter = JpegStreamSplitter()
                    val readBuffer = ByteArray(8192)
                    val decodeInput = decodeProcess.inputStream
                    var decodeFailed = false
                    try {
                        while (true) {
                            val read = try {
                                decodeInput.read(readBuffer)
                            } catch (_: IOException) {
                                -1
                            }
                            if (read < 0) break
                            val frames = splitter.feed(readBuffer, 0, read)
                            for (frame in frames) {
                                control.check()
                                if (framesDone % THERMAL_CHECK_EVERY_FRAMES == 0) {
                                    waitForCoolDown(powerManager, control, onProgress, framesDone, framesEstimated)
                                    waitForMemory(activityManager, control, onProgress, framesDone, framesEstimated)
                                }
                                val upscaled = upscaleWithTileRetry(frame, session, tileSize) { newSize ->
                                    tileSize = newSize
                                }
                                try {
                                    encoderOut.write(upscaled)
                                } catch (e: IOException) {
                                    decodeFailed = true
                                    throw IOException(
                                        "The video encoder stopped unexpectedly. " +
                                            runner.stderrTail(encodeStderr).takeIf { it.isNotBlank() }
                                                ?.let { "Encoder said: $it" }.orEmpty()
                                    )
                                }
                                framesDone++
                                val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000.0
                                val measured = if (framesDone >= 10 && elapsedSec > 0) framesDone / elapsedSec else null
                                onProgress(
                                    UpscaleProgress(
                                        "Upscaling", framesDone, framesEstimated, measured,
                                        "Frame $framesDone of about $framesEstimated • ${provider.displayName} • $encoderName"
                                    )
                                )
                            }
                        }
                    } finally {
                        try {
                            decodeInput.close()
                        } catch (_: Exception) {
                        }
                        try {
                            encoderOut.flush()
                            encoderOut.close()
                        } catch (_: Exception) {
                        }
                    }
                    if (framesDone == 0) {
                        val detail = runner.stderrTail(decodeStderr).takeIf { it.isNotBlank() }
                            ?.let { " Decoder said: $it" }.orEmpty()
                        throw IOException("No video frames could be decoded from this file.$detail")
                    }
                    if (!decodeFailed) {
                        val decodeDone = decodeProcess.waitFor(30, TimeUnit.SECONDS)
                        if (!decodeDone) decodeProcess.destroyForcibly()
                    }
                    val encodeDone = encodeProcess.waitFor(120, TimeUnit.SECONDS)
                    if (!encodeDone) {
                        encodeProcess.destroyForcibly()
                        throw IOException("The video encoder timed out while finishing the file.")
                    }
                    if (encodeProcess.exitValue() != 0) {
                        val detail = runner.stderrTail(encodeStderr).takeIf { it.isNotBlank() }
                            ?.let { " Encoder said: $it" }.orEmpty()
                        throw IOException("The video encoder failed to write the upscaled file.$detail")
                    }
                } finally {
                    if (decodeProcess.isAlive) decodeProcess.destroyForcibly()
                    if (encodeProcess.isAlive) encodeProcess.destroyForcibly()
                }
            }

            control.check()
            onProgress(UpscaleProgress("Verifying", framesDone, framesEstimated, null, "Checking the finished file…"))
            if (!tempOutput.isFile || tempOutput.length() == 0L) {
                throw IOException("The encoder finished but no output file was produced.")
            }
            val verified = runner.probeVideo(tempOutput)
            if (verified.width != targetW || verified.height != targetH) {
                throw IOException(
                    "The finished file is ${verified.width}x${verified.height} instead of " +
                        "${targetW}x${targetH}, so it was discarded rather than presented as a success."
                )
            }
            val baseName = sanitizeBaseName(inputFile.nameWithoutExtension)
            val outputName = "${baseName}_${targetW}x${targetH}.mp4"
            val outputFile = uniqueFile(outputDir, outputName)
            if (!tempOutput.renameTo(outputFile)) {
                tempOutput.copyTo(outputFile, overwrite = true)
                tempOutput.delete()
            }
            UpscaleResult(
                outputFile, targetW, targetH, framesDone,
                System.currentTimeMillis() - startedAt,
                provider.key, encoderName, inputFile.name
            )
        } finally {
            try {
                tempOutput.delete()
            } catch (_: Exception) {
            }
            try {
                decodeStderr.delete()
            } catch (_: Exception) {
            }
            try {
                encodeStderr.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Decide which provider runs the job. Battery Saver always uses
     * CPU. An explicit override wins next. Otherwise the stored
     * benchmark's winner is used, or a quick benchmark runs when no
     * stored results exist (and its results are persisted).
     */
    private suspend fun resolveProvider(
        batterySaver: Boolean,
        providerOverrideKey: String,
        storedBenchmark: String,
        modelFile: File,
        threads: Int,
        onBenchmarkDone: (String) -> Unit,
        onProgress: (UpscaleProgress) -> Unit,
        framesEstimated: Int,
        control: UpscaleControl
    ): String {
        if (batterySaver) return UpscaleEngine.Provider.CPU.key
        if (providerOverrideKey != "auto") {
            if (UpscaleEngine.Provider.fromKey(providerOverrideKey) == null) {
                throw IOException("Unknown AI provider \"$providerOverrideKey\".")
            }
            return providerOverrideKey
        }
        val stored = UpscaleMath.decodeBenchmark(storedBenchmark)
        UpscaleMath.selectBest(stored, UpscaleEngine.Provider.PREFERENCE_ORDER)?.let { return it }
        onProgress(UpscaleProgress("Benchmarking", 0, framesEstimated, null, "Measuring the fastest AI provider on this phone…"))
        control.check()
        val results = UpscaleEngine.runBenchmark(modelFile, warmupRuns = 1, timedRuns = 2, threads = threads)
        val encoded = UpscaleMath.encodeBenchmark(results)
        onBenchmarkDone(encoded)
        return UpscaleMath.selectBest(results, UpscaleEngine.Provider.PREFERENCE_ORDER)
            ?: throw IOException(
                "No working AI provider was found on this phone, so upscaling cannot run. " +
                    "Try restarting VideoX."
            )
    }

    /**
     * Upscale one frame, halving the tile size and retrying when the
     * phone runs out of memory, down to the minimum tile size. The
     * reduced size is reported back so later frames start smaller.
     */
    private fun upscaleWithTileRetry(
        jpegBytes: ByteArray,
        session: UpscaleEngine.Session,
        startTileSize: Int,
        onTileSizeReduced: (Int) -> Unit
    ): ByteArray {
        var tileSize = startTileSize
        while (true) {
            try {
                return UpscaleEngine.upscaleFrame(jpegBytes, session, tileSize, UpscaleMath.DEFAULT_OVERLAP)
            } catch (oom: OutOfMemoryError) {
                val next = tileSize / 2
                if (next < UpscaleMath.MIN_TILE_SIZE) {
                    throw IOException(
                        "The phone ran out of memory while upscaling, even with the smallest tile size. " +
                            "Close other apps and try a shorter video."
                    )
                }
                tileSize = next
                onTileSizeReduced(tileSize)
            }
        }
    }

    /** Pause while the phone reports severe thermal throttling (or the user cancels). */
    private suspend fun waitForCoolDown(
        powerManager: PowerManager,
        control: UpscaleControl,
        onProgress: (UpscaleProgress) -> Unit,
        framesDone: Int,
        framesEstimated: Int
    ) {
        val waitedStart = System.currentTimeMillis()
        var warned = false
        while (true) {
            val status = try {
                powerManager.currentThermalStatus
            } catch (_: Exception) {
                PowerManager.THERMAL_STATUS_NONE
            }
            if (status < PowerManager.THERMAL_STATUS_SEVERE) return
            if (!warned) {
                warned = true
                onProgress(
                    UpscaleProgress(
                        "Paused — phone is warm", framesDone, framesEstimated, null,
                        "Waiting for the phone to cool down before continuing…"
                    )
                )
            }
            if (System.currentTimeMillis() - waitedStart > MAX_THERMAL_WAIT_MS) {
                throw IOException("The phone stayed too warm for too long, so upscaling stopped. Let it cool down and try again.")
            }
            control.check()
            delay(5_000)
        }
    }

    /** Pause while Android reports low memory (or the user cancels). */
    private suspend fun waitForMemory(
        activityManager: ActivityManager,
        control: UpscaleControl,
        onProgress: (UpscaleProgress) -> Unit,
        framesDone: Int,
        framesEstimated: Int
    ) {
        val waitedStart = System.currentTimeMillis()
        var warned = false
        while (true) {
            val info = ActivityManager.MemoryInfo()
            try {
                activityManager.getMemoryInfo(info)
            } catch (_: Exception) {
                return
            }
            if (!info.lowMemory) return
            if (!warned) {
                warned = true
                onProgress(
                    UpscaleProgress(
                        "Paused — low memory", framesDone, framesEstimated, null,
                        "Waiting for memory to free up before continuing…"
                    )
                )
            }
            if (System.currentTimeMillis() - waitedStart > MAX_MEMORY_WAIT_MS) {
                throw IOException("The phone stayed low on memory, so upscaling stopped. Close other apps and try again.")
            }
            control.check()
            delay(5_000)
        }
    }

    private fun sanitizeBaseName(name: String): String {
        val clean = name.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("").trim('.', '_', ' ')
        return clean.take(80).ifBlank { "video" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var index = 2
        while (candidate.exists()) {
            val base = name.substringBeforeLast(".")
            val ext = name.substringAfterLast(".", "")
            candidate = File(dir, "${base}_$index.$ext")
            index++
        }
        return candidate
    }
}
