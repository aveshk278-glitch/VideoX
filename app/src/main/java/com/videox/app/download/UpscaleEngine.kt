package com.videox.app.download

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer

/**
 * On-device AI upscaling with ONNX Runtime (v2.15). Runs the
 * Real-ESRGAN general-x4v3 model on tiles of each video frame.
 *
 * The ONNX Runtime API used here was verified against the real
 * onnxruntime-android 1.29.0 AAR with javap: OrtEnvironment,
 * createSession(String, SessionOptions), SessionOptions
 * (addNnapi/addXnnpack/addWebGPU, setOptimizationLevel,
 * setIntraOpNumThreads), OnnxTensor.createTensor(FloatBuffer,
 * shape), OrtSession.run, Result.get(0)/close.
 *
 * Preprocessing follows the standard Real-ESRGAN pipeline: RGB,
 * CHW layout, values in [0,1]. Output is clamped to [0,1] and
 * scaled back to 0-255.
 */
object UpscaleEngine {

    enum class Provider(val key: String, val displayName: String) {
        CPU("cpu", "CPU"),
        XNNPACK("xnnpack", "XNNPACK"),
        NNAPI("nnapi", "NNAPI"),
        WEBGPU("webgpu", "WebGPU (Vulkan)");

        companion object {
            /** Order used to break benchmark ties: earlier wins. */
            val PREFERENCE_ORDER = listOf("cpu", "xnnpack", "nnapi", "webgpu")

            fun fromKey(key: String): Provider? = entries.firstOrNull { it.key == key }

            fun displayName(key: String): String = fromKey(key)?.displayName ?: key
        }
    }

    /** One open model session. Close it when the job or benchmark ends. */
    class Session internal constructor(
        private val session: OrtSession,
        private val options: OrtSession.SessionOptions,
        val provider: Provider
    ) : AutoCloseable {
        override fun close() {
            try {
                session.close()
            } catch (_: Exception) {
            }
            try {
                options.close()
            } catch (_: Exception) {
            }
        }

        internal fun runTile(input: FloatBuffer, tileSize: Int): Array<Array<Array<FloatArray>>> {
            val tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                input,
                longArrayOf(1, 3, tileSize.toLong(), tileSize.toLong())
            )
            tensor.use {
                session.run(mapOf("input" to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    return (result.get(0) as OnnxTensor).value as Array<Array<Array<FloatArray>>>
                }
            }
        }
    }

    fun createSession(modelFile: File, provider: Provider, threads: Int): Session {
        val options = OrtSession.SessionOptions()
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(threads.coerceIn(1, 8))
            when (provider) {
                Provider.CPU -> Unit
                Provider.XNNPACK -> options.addXnnpack(emptyMap<String, String>())
                Provider.NNAPI -> options.addNnapi()
                Provider.WEBGPU -> options.addWebGPU(emptyMap<String, String>())
            }
            val session = OrtEnvironment.getEnvironment()
                .createSession(modelFile.absolutePath, options)
            return Session(session, options, provider)
        } catch (e: Exception) {
            try {
                options.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    /**
     * Upscale one JPEG frame to 4x, returned as JPEG bytes (quality
     * 95). The frame is edge-padded so every tile is exactly
     * [tileSize] pixels, tiled with overlap, stitched from the kept
     * regions, and cropped to exactly 4x the input size.
     * Throws OutOfMemoryError upward — the caller retries with a
     * smaller tile size. Bitmaps are recycled in finally blocks so
     * frame buffers are released immediately.
     */
    fun upscaleFrame(jpegBytes: ByteArray, session: Session, tileSize: Int, overlap: Int): ByteArray {
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
            ?: throw IOException("A video frame could not be decoded.")
        var padded: Bitmap? = null
        var output: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            val width = src.width
            val height = src.height
            val scale = UpscaleMath.MODEL_SCALE
            val paddedW = UpscaleMath.paddedDimension(width, tileSize, overlap)
            val paddedH = UpscaleMath.paddedDimension(height, tileSize, overlap)
            padded = padEdges(src, paddedW, paddedH)
            output = Bitmap.createBitmap(paddedW * scale, paddedH * scale, Bitmap.Config.ARGB_8888)
            val xOrigins = UpscaleMath.tileOrigins(paddedW, tileSize, overlap)
            val yOrigins = UpscaleMath.tileOrigins(paddedH, tileSize, overlap)
            val tilePixels = IntArray(tileSize * tileSize)
            val upWidth = tileSize * scale
            for (oy in yOrigins) {
                for (ox in xOrigins) {
                    padded.getPixels(tilePixels, 0, tileSize, ox, oy, tileSize, tileSize)
                    val upPixels = runTileToPixels(tilePixels, tileSize, session)
                    val keptX = UpscaleMath.keptRange(ox, paddedW, tileSize, overlap)
                    val keptY = UpscaleMath.keptRange(oy, paddedH, tileSize, overlap)
                    val keptW = (keptX.last - keptX.first + 1) * scale
                    val keptH = (keptY.last - keptY.first + 1) * scale
                    val kept = IntArray(keptW * keptH)
                    for (row in 0 until keptH) {
                        val srcRow = keptY.first * scale + row
                        System.arraycopy(
                            upPixels, srcRow * upWidth + keptX.first * scale,
                            kept, row * keptW, keptW
                        )
                    }
                    output.setPixels(kept, 0, keptW, keptX.first * scale, keptY.first * scale, keptW, keptH)
                }
            }
            cropped = Bitmap.createBitmap(output, 0, 0, width * scale, height * scale)
            val stream = ByteArrayOutputStream()
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                throw IOException("An upscaled frame could not be encoded.")
            }
            return stream.toByteArray()
        } finally {
            try {
                src.recycle()
            } catch (_: Exception) {
            }
            try {
                padded?.recycle()
            } catch (_: Exception) {
            }
            try {
                output?.recycle()
            } catch (_: Exception) {
            }
            try {
                cropped?.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** Edge-replicate [src] into a [paddedW] x [paddedH] bitmap. */
    private fun padEdges(src: Bitmap, paddedW: Int, paddedH: Int): Bitmap {
        val width = src.width
        val height = src.height
        val padded = Bitmap.createBitmap(paddedW, paddedH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawBitmap(src, 0f, 0f, null)
        if (paddedW > width) {
            canvas.drawBitmap(
                src,
                Rect(width - 1, 0, width, height),
                Rect(width, 0, paddedW, height),
                null
            )
        }
        if (paddedH > height) {
            canvas.drawBitmap(
                src,
                Rect(0, height - 1, width, height),
                Rect(0, height, width, paddedH),
                null
            )
        }
        if (paddedW > width && paddedH > height) {
            canvas.drawBitmap(
                src,
                Rect(width - 1, height - 1, width, height),
                Rect(width, height, paddedW, paddedH),
                null
            )
        }
        return padded
    }

    /** Run one tile through the model and return its upscaled ARGB pixels. */
    private fun runTileToPixels(tilePixels: IntArray, tileSize: Int, session: Session): IntArray {
        val count = tileSize * tileSize
        val buffer = FloatBuffer.allocate(3 * count)
        for (i in 0 until count) {
            val pixel = tilePixels[i]
            buffer.put(((pixel shr 16) and 0xFF) / 255f)
        }
        for (i in 0 until count) {
            val pixel = tilePixels[i]
            buffer.put(((pixel shr 8) and 0xFF) / 255f)
        }
        for (i in 0 until count) {
            val pixel = tilePixels[i]
            buffer.put((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        val out = session.runTile(buffer, tileSize)
        val scale = UpscaleMath.MODEL_SCALE
        val upSize = tileSize * scale
        val result = IntArray(upSize * upSize)
        val rPlane = out[0][0]
        val gPlane = out[0][1]
        val bPlane = out[0][2]
        for (y in 0 until upSize) {
            val rRow = rPlane[y]
            val gRow = gPlane[y]
            val bRow = bPlane[y]
            val rowOffset = y * upSize
            for (x in 0 until upSize) {
                val r = (rRow[x].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val g = (gRow[x].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val b = (bRow[x].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                result[rowOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return result
    }

    /**
     * Benchmark every provider on a synthetic 256x256 gradient tile:
     * [warmupRuns] warmup runs, then [timedRuns] timed runs, median
     * time kept. A provider that fails to start, throws, runs out of
     * memory, or returns flat/saturated output is reported as not
     * OK — never as a number. [onProviderDone] is called after each
     * provider so the UI can show live progress.
     */
    fun runBenchmark(
        modelFile: File,
        warmupRuns: Int = 1,
        timedRuns: Int = 3,
        threads: Int = 4,
        onProviderDone: (UpscaleMath.ProviderResult) -> Unit = {}
    ): List<UpscaleMath.ProviderResult> {
        val results = mutableListOf<UpscaleMath.ProviderResult>()
        val tileSize = 256
        val gradient = gradientTile(tileSize)
        for (provider in Provider.entries) {
            val result = try {
                createSession(modelFile, provider, threads).use { session ->
                    repeat(warmupRuns) { runTileToPixels(gradient, tileSize, session) }
                    val times = mutableListOf<Long>()
                    var lastPixels: IntArray? = null
                    repeat(timedRuns) {
                        val start = System.nanoTime()
                        lastPixels = runTileToPixels(gradient, tileSize, session)
                        times += (System.nanoTime() - start) / 1_000_000L
                    }
                    val pixels = lastPixels!!
                    val stats = outputStats(pixels)
                    if (!UpscaleMath.plausibleOutputStats(stats.first, stats.second)) {
                        UpscaleMath.ProviderResult(provider.key, null, false)
                    } else {
                        times.sort()
                        UpscaleMath.ProviderResult(provider.key, times[times.size / 2], true)
                    }
                }
            } catch (_: OutOfMemoryError) {
                UpscaleMath.ProviderResult(provider.key, null, false)
            } catch (_: Exception) {
                UpscaleMath.ProviderResult(provider.key, null, false)
            }
            results += result
            onProviderDone(result)
        }
        return results
    }

    /** A deterministic gradient tile (no assets needed for benchmarking). */
    private fun gradientTile(size: Int): IntArray {
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = (x * 255 / size + y * 255 / size) / 2
                val r = v
                val g = (v + 85) % 256
                val b = 255 - v
                pixels[y * size + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    /** Mean and standard deviation of the red channel, normalised to [0,1]. */
    private fun outputStats(pixels: IntArray): Pair<Double, Double> {
        var sum = 0.0
        for (pixel in pixels) sum += ((pixel shr 16) and 0xFF) / 255.0
        val mean = sum / pixels.size
        var variance = 0.0
        for (pixel in pixels) {
            val d = ((pixel shr 16) and 0xFF) / 255.0 - mean
            variance += d * d
        }
        return Pair(mean, kotlin.math.sqrt(variance / pixels.size))
    }
}
