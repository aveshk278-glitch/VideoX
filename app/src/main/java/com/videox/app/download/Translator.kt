package com.videox.app.download

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optional on-device translation for extracted text (v2.14), using
 * the ML Kit Translate STANDALONE SDK (com.google.mlkit:translate).
 * It is free, needs no API key, no account and no cloud service:
 * translation runs on the phone after a one-time language-model
 * download of about 30 MB per language pair. Nothing here calls the
 * Cloud Translation API and nothing is billed.
 *
 * Translation is strictly opt-in: the caller only invokes this when
 * the user turned the translation toggle on, and the translated
 * text is always saved as a SEPARATE file — the original-language
 * file is never modified. Nothing here transliterates.
 *
 * The API used here was verified against the official ML Kit docs:
 * TranslatorOptions.Builder().setSourceLanguage().setTargetLanguage()
 * .build(), Translation.getClient(options), downloadModelIfNeeded(),
 * translate(String) returning Task<String>, and close(). The
 * supported-language map below uses the TranslateLanguage constants
 * exactly as published in the official reference — Punjabi and
 * Malayalam have no constants there, so they are unsupported and
 * report honestly instead of failing inside the SDK.
 *
 * Model downloads go through Google Play Services: on a phone
 * without it (or without a connection) the download fails, and the
 * caller gets an IOException with an honest explanation instead of
 * a crash or a silent skip.
 */
object Translator {

    /**
     * Normalise a VideoX language code to the ML Kit language code,
     * or null when ML Kit cannot translate that language. "en-in"
     * (Indian English) translates as plain English, and regional
     * codes ("pt-BR", "en-US") translate as their base language.
     */
    fun mlKitCode(code: String): String? {
        val normalized = code.trim().lowercase()
        exactMlKitCode(normalized)?.let { return it }
        val base = normalized.substringBefore('-').substringBefore('_')
        if (base != normalized) {
            exactMlKitCode(base)?.let { return it }
        }
        return null
    }

    private fun exactMlKitCode(code: String): String? = when (code) {
        "af" -> TranslateLanguage.AFRIKAANS
        "sq" -> TranslateLanguage.ALBANIAN
        "ar" -> TranslateLanguage.ARABIC
        "be" -> TranslateLanguage.BELARUSIAN
        "bn" -> TranslateLanguage.BENGALI
        "bg" -> TranslateLanguage.BULGARIAN
        "ca" -> TranslateLanguage.CATALAN
        "zh" -> TranslateLanguage.CHINESE
        "hr" -> TranslateLanguage.CROATIAN
        "cs" -> TranslateLanguage.CZECH
        "da" -> TranslateLanguage.DANISH
        "nl" -> TranslateLanguage.DUTCH
        "en", "en-in" -> TranslateLanguage.ENGLISH
        "eo" -> TranslateLanguage.ESPERANTO
        "et" -> TranslateLanguage.ESTONIAN
        "fi" -> TranslateLanguage.FINNISH
        "fr" -> TranslateLanguage.FRENCH
        "gl" -> TranslateLanguage.GALICIAN
        "ka" -> TranslateLanguage.GEORGIAN
        "de" -> TranslateLanguage.GERMAN
        "el" -> TranslateLanguage.GREEK
        "gu" -> TranslateLanguage.GUJARATI
        "ht" -> TranslateLanguage.HAITIAN_CREOLE
        "he" -> TranslateLanguage.HEBREW
        "hi" -> TranslateLanguage.HINDI
        "hu" -> TranslateLanguage.HUNGARIAN
        "is" -> TranslateLanguage.ICELANDIC
        "id" -> TranslateLanguage.INDONESIAN
        "ga" -> TranslateLanguage.IRISH
        "it" -> TranslateLanguage.ITALIAN
        "ja" -> TranslateLanguage.JAPANESE
        "kn" -> TranslateLanguage.KANNADA
        "ko" -> TranslateLanguage.KOREAN
        "lv" -> TranslateLanguage.LATVIAN
        "lt" -> TranslateLanguage.LITHUANIAN
        "mk" -> TranslateLanguage.MACEDONIAN
        "ms" -> TranslateLanguage.MALAY
        "mt" -> TranslateLanguage.MALTESE
        "mr" -> TranslateLanguage.MARATHI
        "no" -> TranslateLanguage.NORWEGIAN
        "fa" -> TranslateLanguage.PERSIAN
        "pl" -> TranslateLanguage.POLISH
        "pt" -> TranslateLanguage.PORTUGUESE
        "ro" -> TranslateLanguage.ROMANIAN
        "ru" -> TranslateLanguage.RUSSIAN
        "sk" -> TranslateLanguage.SLOVAK
        "sl" -> TranslateLanguage.SLOVENIAN
        "es" -> TranslateLanguage.SPANISH
        "sw" -> TranslateLanguage.SWAHILI
        "sv" -> TranslateLanguage.SWEDISH
        "tl" -> TranslateLanguage.TAGALOG
        "ta" -> TranslateLanguage.TAMIL
        "te" -> TranslateLanguage.TELUGU
        "th" -> TranslateLanguage.THAI
        "tr" -> TranslateLanguage.TURKISH
        "uk" -> TranslateLanguage.UKRAINIAN
        "ur" -> TranslateLanguage.URDU
        "vi" -> TranslateLanguage.VIETNAMESE
        "cy" -> TranslateLanguage.WELSH
        else -> null
    }

    /** True when ML Kit can translate from (or to) [code]. */
    fun isSupported(code: String): Boolean = mlKitCode(code) != null

    /**
     * Translate [text] from [sourceCode] to [targetCode], chunked on
     * cue boundaries. Downloads the language model first when it is
     * not already on the phone. [onProgress] receives 0-100 as chunks
     * finish. Returns the input unchanged when both languages are the
     * same. Throws IOException with a user-facing message when the
     * language is unsupported or the model cannot be downloaded.
     * The translator client is always closed before returning.
     */
    suspend fun translateText(
        sourceCode: String,
        targetCode: String,
        text: String,
        onProgress: (Int) -> Unit
    ): String {
        val source = mlKitCode(sourceCode)
            ?: throw IOException(
                "Translation from ${ExtractionLanguage.displayName(sourceCode)} is not available on this phone."
            )
        val target = mlKitCode(targetCode)
            ?: throw IOException(
                "Translation to ${ExtractionLanguage.displayName(targetCode)} is not available on this phone."
            )
        if (source == target) return text
        val chunks = TranslationChunks.chunk(text)
        if (chunks.isEmpty()) return ""
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val translator = Translation.getClient(options)
        try {
            try {
                translator.downloadModelIfNeeded().await()
            } catch (e: Exception) {
                throw IOException(
                    "The translation model (about 30 MB) could not be downloaded. " +
                        "Check your connection and try again — translation needs Google Play Services " +
                        "to fetch the language model.",
                    e
                )
            }
            val translated = mutableListOf<String>()
            for ((index, chunk) in chunks.withIndex()) {
                try {
                    translated += translator.translate(chunk).await()
                } catch (e: Exception) {
                    throw IOException("Translation failed: ${e.message ?: "unknown error"}", e)
                }
                onProgress(((index + 1) * 100 / chunks.size).coerceIn(0, 100))
            }
            return translated.joinToString("\n")
        } finally {
            try {
                translator.close()
            } catch (_: Exception) {
                // Closing must never mask the translation result.
            }
        }
    }

    /** Bridge a Play Services Task to a coroutine without extra dependencies. */
    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
}
