package com.videox.app.download

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The AI upscaler model (v2.15): Real-ESRGAN general-x4v3, exported
 * to ONNX. The weights are NOT bundled with the app — they download
 * once on first use (4.7 MB) and are verified against the published
 * SHA-256 before use. A failed or mismatched download leaves nothing
 * behind and is reported honestly. Download code mirrors the Vosk
 * model download in SpeechTranscriber.
 *
 * Licence: Real-ESRGAN is BSD-3-Clause (Copyright 2021 Xintao Wang);
 * the app carries that attribution in Settings and
 * THIRD_PARTY_NOTICES.md. The user approved proceeding on this basis.
 */
object UpscaleModel {

    const val MODEL_URL = "https://assets.xlab.my/models/realesr-general-x4v3.onnx"
    const val MODEL_SHA256 = "04c4cfea5759f94e5b5ab98b5d1ef176b904bbcd670a3b661e99e623374fc370"
    const val MODEL_FILE_NAME = "realesr-general-x4v3.onnx"

    /** The model file if it is already downloaded and verified, else null. */
    fun existingModelFile(context: Context): File? {
        val file = File(File(context.filesDir, "upscale-models"), MODEL_FILE_NAME)
        if (!file.isFile) return null
        return try {
            if (Sha256.fileHex(file).equals(MODEL_SHA256, ignoreCase = true)) file else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Return the verified model file, downloading it on first use.
     * [onDownloadProgress] receives 0-100 while downloading.
     * Throws IOException with a plain message on any failure; a
     * partial or mismatched file is deleted, never kept.
     */
    suspend fun ensureModel(
        context: Context,
        onDownloadProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        existingModelFile(context)?.let { return@withContext it }
        val modelsDir = File(context.filesDir, "upscale-models").apply { mkdirs() }
        val target = File(modelsDir, MODEL_FILE_NAME)
        val temp = File(modelsDir, "$MODEL_FILE_NAME.tmp")
        try {
            downloadFile(MODEL_URL, temp, onDownloadProgress)
            val hash = Sha256.fileHex(temp)
            if (!hash.equals(MODEL_SHA256, ignoreCase = true)) {
                throw IOException(
                    "The AI model download did not match its expected checksum, so it was discarded. " +
                        "Check your connection and try again."
                )
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target
        } catch (e: IOException) {
            try {
                temp.delete()
            } catch (_: Exception) {
            }
            throw e
        } catch (e: Exception) {
            try {
                temp.delete()
            } catch (_: Exception) {
            }
            throw IOException("The AI model could not be downloaded: ${e.message}", e)
        }
    }

    private fun downloadFile(url: String, target: File, onProgress: (Int) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException("AI model download failed (HTTP ${connection.responseCode}). Check your connection and try again.")
        }
        val total = connection.contentLengthLong
        try {
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (total > 0 && target.length() != total) {
            throw IOException("AI model download was incomplete. Check your connection and try again.")
        }
    }
}
