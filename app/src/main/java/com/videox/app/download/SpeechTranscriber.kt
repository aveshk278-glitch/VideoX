package com.videox.app.download

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import org.vosk.Model
import org.vosk.Recognizer

/**
 * On-device speech recognition (v2.10) with Vosk, for videos that have
 * no subtitles: "Extract text" falls back to transcribing the video's
 * speech, so text can be extracted from ANY video with spoken audio.
 *
 * Everything runs on the phone — no audio ever leaves the device. The
 * voice model (~40 MB download, English or Hindi) is fetched once from
 * alphacephei.com, unpacked into the app's private files dir, and reused
 * for every later transcription. The ZIP is deleted after unpacking.
 *
 * The Vosk API used here was verified against the real
 * vosk-android 0.3.75 AAR with javap: Model(String), Recognizer(Model,
 * float), acceptWaveForm(byte[], int), getResult(), getFinalResult().
 */
object SpeechTranscriber {

    private const val MODEL_BASE_URL = "https://alphacephei.com/vosk/models/"
    private const val ENGLISH_MODEL = "vosk-model-small-en-us-0.15"
    private const val HINDI_MODEL = "vosk-model-small-hi-0.22"

    /** Sample rate Vosk models expect: 16 kHz, mono, 16-bit PCM. */
    const val SAMPLE_RATE = 16000f

    /** Model name for a language code: "hi" -> Hindi, anything else -> English. */
    fun modelNameFor(language: String): String =
        if (language.trim().lowercase() == "hi") HINDI_MODEL else ENGLISH_MODEL

    /**
     * Return the unpacked model directory for [language], downloading
     * and unpacking it on first use. [onDownloadProgress] receives
     * 0-100 while the model ZIP is downloading (only on first use).
     * Throws IOException when the download or unpacking fails — the
     * caller reports it honestly and nothing is left half-installed
     * (a failed unpack removes the partial directory).
     */
    fun ensureModel(
        context: Context,
        language: String,
        onDownloadProgress: (Int) -> Unit
    ): File {
        val name = modelNameFor(language)
        val modelsDir = File(context.filesDir, "vosk-models")
        val modelDir = File(modelsDir, name)
        if (isValidModelDir(modelDir)) return modelDir
        modelsDir.mkdirs()
        val zipFile = File(modelsDir, "$name.zip")
        try {
            downloadFile("$MODEL_BASE_URL$name.zip", zipFile, onDownloadProgress)
            unzip(zipFile, modelsDir)
        } finally {
            try {
                zipFile.delete()
            } catch (_: Exception) {
                // A leftover ZIP is harmless; the next run re-downloads.
            }
        }
        if (!isValidModelDir(modelDir)) {
            try {
                modelDir.deleteRecursively()
            } catch (_: Exception) {
            }
            throw IOException("The downloaded voice model could not be unpacked. Check your connection and try again.")
        }
        return modelDir
    }

    /** A usable Vosk model directory has the acoustic model and config. */
    private fun isValidModelDir(dir: File): Boolean {
        return dir.isDirectory &&
            File(dir, "am").isDirectory &&
            File(dir, "conf").isDirectory
    }

    private fun downloadFile(url: String, target: File, onProgress: (Int) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException("Voice model download failed (HTTP ${connection.responseCode}). Check your connection and try again.")
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
            throw IOException("Voice model download was incomplete. Check your connection and try again.")
        }
    }

    /** Unpack a model ZIP, refusing entries that escape [targetDir] (Zip Slip). */
    private fun unzip(zipFile: File, targetDir: File) {
        val targetCanonical = targetDir.canonicalPath
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(targetCanonical + File.separator)) {
                    throw IOException("Unsafe entry in the voice model file.")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    /**
     * Transcribe a 16 kHz mono 16-bit PCM WAV file. The audio is
     * streamed in small chunks (the whole file is never held in
     * memory) and [onProgress] receives 0-100 as it is processed.
     * Returns the recognised text, one line per spoken segment, or an
     * empty string when no speech was recognised.
     */
    fun transcribe(wavFile: File, modelDir: File, onProgress: (Int) -> Unit): String {
        val totalBytes = wavFile.length()
        FileInputStream(wavFile).use { input ->
            val header = ByteArray(4096)
            var headerLen = 0
            while (headerLen < header.size) {
                val read = input.read(header, headerLen, header.size - headerLen)
                if (read < 0) break
                headerLen += read
            }
            val offset = TranscriptionText.pcmDataOffset(header.copyOf(headerLen))
                ?: throw IOException("The extracted audio is not a WAV file the transcriber can read.")
            input.channel.position(offset.toLong())
            val pcmTotal = (totalBytes - offset).coerceAtLeast(1L)
            Model(modelDir.absolutePath).use { model ->
                Recognizer(model, SAMPLE_RATE).use { recognizer ->
                    val segments = mutableListOf<String>()
                    val buffer = ByteArray(4000)
                    var fed = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (recognizer.acceptWaveForm(buffer, read)) {
                            TranscriptionText.textFromVoskJson(recognizer.getResult())
                                ?.takeIf { it.isNotBlank() }
                                ?.let { segments += it.trim() }
                        }
                        fed += read
                        val percent = ((fed * 100) / pcmTotal).toInt().coerceIn(0, 100)
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(percent)
                        }
                    }
                    TranscriptionText.textFromVoskJson(recognizer.getFinalResult())
                        ?.takeIf { it.isNotBlank() }
                        ?.let { segments += it.trim() }
                    return segments.joinToString("\n").trim()
                }
            }
        }
    }
}
