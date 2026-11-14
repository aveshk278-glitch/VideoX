package com.videox.app.download

/**
 * Language catalog for original-language text extraction (v2.14).
 *
 * Pure Kotlin with NO Android dependencies, so it is covered by plain
 * JVM unit tests (see ExtractionLanguageTest).
 *
 * Each entry knows:
 * - its display name (shown in Settings and on Home),
 * - the offline Vosk voice model for speech transcription, or null
 *   when no model exists for that language (verified against the
 *   official Vosk model list, 2026-11-14: small models exist only
 *   for en-us, en-in, hi, gu and te — Urdu, Bengali, Tamil, Marathi,
 *   Punjabi, Kannada and Malayalam have none, and transcription in
 *   those languages must say so honestly instead of silently using
 *   the English model, which was the v2.10-v2.13 bug),
 * - whether on-device translation (ML Kit) supports it (verified
 *   against the official TranslateLanguage constant list: Punjabi
 *   and Malayalam have no ML Kit model, so they are flagged false).
 *
 * Special codes: "auto" (detect the video's own language),
 * "original" (the video's own language, same resolution as auto),
 * "all" (every subtitle track). They are not real languages and have
 * no voice or translation model.
 */
object ExtractionLanguage {

    const val AUTO = "auto"
    const val ORIGINAL = "original"
    const val ALL = "all"

    data class Entry(
        val code: String,
        val displayName: String,
        val voskModel: String?,
        val voskModelSizeMb: Int?,
        val voskWeak: Boolean,
        val mlKitSupported: Boolean
    )

    val CATALOG: List<Entry> = listOf(
        Entry(AUTO, "Auto Detect", null, null, false, false),
        Entry(ORIGINAL, "Original Language", null, null, false, false),
        Entry("hi", "Hindi", "vosk-model-small-hi-0.22", 42, false, true),
        Entry("en", "English", "vosk-model-small-en-us-0.15", 40, false, true),
        Entry("en-in", "English (India)", "vosk-model-small-en-in-0.4", 36, false, true),
        Entry("ur", "Urdu", null, null, false, true),
        Entry("bn", "Bengali", null, null, false, true),
        Entry("ta", "Tamil", null, null, false, true),
        Entry("te", "Telugu", "vosk-model-small-te-0.42", 58, true, true),
        Entry("mr", "Marathi", null, null, false, true),
        Entry("gu", "Gujarati", "vosk-model-small-gu-0.42", 100, false, true),
        Entry("pa", "Punjabi", null, null, false, false),
        Entry("kn", "Kannada", null, null, false, true),
        Entry("ml", "Malayalam", null, null, false, false),
        Entry(ALL, "All languages", null, null, false, false)
    )

    /** Languages the optional translation target dropdown offers. */
    val translationTargets: List<Entry>
        get() = CATALOG.filter { it.mlKitSupported }

    /**
     * The catalog entry for a code. Unknown codes get a synthetic
     * entry named after the code itself, with no models — so callers
     * never deal with null and unknown languages are never guessed.
     */
    fun entryFor(code: String): Entry {
        val normalized = code.trim().lowercase()
        return CATALOG.firstOrNull { it.code == normalized }
            ?: Entry(normalized, normalized, null, null, false, false)
    }

    /** True when [code] is a catalog code (including auto/original/all). */
    fun isKnownCode(code: String): Boolean {
        val normalized = code.trim().lowercase()
        return CATALOG.any { it.code == normalized }
    }

    /** True when [code] can be a translation target (a real ML Kit language). */
    fun isTranslationTarget(code: String): Boolean =
        entryFor(code).mlKitSupported

    /**
     * Display name for a code. Unknown codes (e.g. a language yt-dlp
     * reports that is not in the catalog) are shown as the code
     * itself, never guessed.
     */
    fun displayName(code: String): String = entryFor(code).displayName

    /**
     * Offline Vosk voice-model directory name for a language code, or
     * null when no model exists. Exact match first ("en-in" gets the
     * Indian English model), then the base language ("en-US" gets the
     * US English model). Never substitutes another language's model.
     */
    fun resolveVoskModel(code: String): String? {
        val normalized = code.trim().lowercase()
        entryFor(normalized).voskModel?.let { return it }
        val base = normalized.substringBefore('-').substringBefore('_')
        if (base != normalized) {
            entryFor(base).voskModel?.let { return it }
        }
        return null
    }

    /**
     * Language code to request from yt-dlp's subtitle tracks.
     * Subtitle tracks use plain codes, so "en-in" requests "en";
     * everything else passes through unchanged.
     */
    fun subtitleCodeFor(code: String): String {
        val normalized = code.trim().lowercase()
        return if (normalized == "en-in") "en" else normalized
    }

    /** Comma-separated display names of the languages with a voice model. */
    fun voskSupportedNames(): String =
        listOf("en", "en-in", "hi", "gu", "te").joinToString(", ") { displayName(it) }
}
