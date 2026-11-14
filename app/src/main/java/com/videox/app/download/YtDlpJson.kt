package com.videox.app.download

/**
 * Minimal parser for yt-dlp `--dump-single-json` output (v2.14).
 *
 * Pure Kotlin with NO Android dependencies and NO org.json (which
 * lives in the Android SDK and cannot run in local unit tests), so
 * it is covered by plain JVM unit tests (see YtDlpJsonTest). The
 * same hand-rolled scanning pattern as SettingsBackup and
 * TranscriptionText: only the fields extraction needs are read, and
 * malformed input yields empty results instead of ever crashing.
 *
 * Only top-level fields are read. Nested objects (formats, chapters,
 * thumbnails) may contain keys with the same names — a format's
 * "language" field must never be mistaken for the video's language,
 * so the scanner tracks brace depth and string state.
 *
 * Fields read:
 * - "language": the video's own language code (may be absent/null).
 * - "subtitles": object whose keys are the manual subtitle languages.
 * - "automatic_captions": object whose keys are the auto-caption languages.
 */
object YtDlpJson {

    /** What Auto Detect learned about a video. Empty sets mean "none found". */
    data class Probe(
        val videoLanguage: String?,
        val manualSubLangs: Set<String>,
        val autoSubLangs: Set<String>
    )

    /** Parse dump-json output. Never throws: bad input gives an empty probe. */
    fun parse(json: String?): Probe {
        if (json.isNullOrBlank()) return Probe(null, emptySet(), emptySet())
        return try {
            val scanner = Scanner(json)
            val language = scanner.findTopLevelString("language")
            val manual = scanner.findTopLevelObjectKeys("subtitles")
            val auto = scanner.findTopLevelObjectKeys("automatic_captions")
            Probe(language, manual, auto)
        } catch (_: Exception) {
            Probe(null, emptySet(), emptySet())
        }
    }

    /**
     * Pick the subtitle track to request.
     *
     * Auto Detect / Original Language resolution, in order:
     * 1. the video's own language, when it has manual subtitles,
     * 2. any manual subtitle track (first alphabetically, for a
     *    deterministic choice),
     * 3. the video's own language in auto-generated captions,
     * 4. any auto-generated caption track,
     * 5. null when the video has no subtitle tracks at all.
     *
     * An explicit language code matches that track (manual first,
     * then auto-captions), exact match first and then by base
     * language ("en" matches an "en-US" track), or null when the
     * video has no track in that language. "all" returns null: the
     * caller requests every track and needs no single choice.
     *
     * The returned value is the track key exactly as yt-dlp reports
     * it, so the subtitle request uses a code yt-dlp recognises.
     */
    fun chooseSubtitleLanguage(probe: Probe, requestedCode: String): String? {
        val wanted = requestedCode.trim().lowercase()
        if (wanted == ExtractionLanguage.ALL) return null
        if (wanted == ExtractionLanguage.AUTO || wanted == ExtractionLanguage.ORIGINAL) {
            val videoLang = probe.videoLanguage?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (videoLang != null) {
                findTrack(probe.manualSubLangs, videoLang)?.let { return it }
            }
            probe.manualSubLangs.sorted().firstOrNull()?.let { return it }
            if (videoLang != null) {
                findTrack(probe.autoSubLangs, videoLang)?.let { return it }
            }
            probe.autoSubLangs.sorted().firstOrNull()?.let { return it }
            return null
        }
        findTrack(probe.manualSubLangs, wanted)?.let { return it }
        findTrack(probe.autoSubLangs, wanted)?.let { return it }
        return null
    }

    /** A track matching [wanted]: exact code first, then base language. */
    private fun findTrack(tracks: Set<String>, wanted: String): String? {
        tracks.firstOrNull { it.trim().lowercase() == wanted }?.let { return it }
        val wantedBase = baseLang(wanted)
        return tracks.sorted().firstOrNull { baseLang(it.trim().lowercase()) == wantedBase }
    }

    /** "en-US" -> "en", "pt_BR" -> "pt", "hi" -> "hi". */
    private fun baseLang(code: String): String =
        code.substringBefore('-').substringBefore('_')

    /**
     * Single-purpose JSON scanner. It only understands enough JSON to
     * find top-level keys, read a string value, collect an object's
     * immediate keys, and skip values it does not need — with full
     * string/escape handling so braces inside strings never confuse
     * the depth tracking. Every method is bounds-checked and returns
     * null/false on malformed input instead of throwing.
     */
    private class Scanner(private val text: String) {

        /** Top-level string value for [key], or null (absent, null, or not a string). */
        fun findTopLevelString(key: String): String? {
            val valueStart = findTopLevelValueStart(key) ?: return null
            var index = valueStart
            index = skipWhitespace(index)
            if (index >= text.length) return null
            if (text[index] != '"') return null // null, number, object: no usable string
            return readString(index)?.first
        }

        /** Immediate keys of the top-level object [key], or empty when absent/not an object. */
        fun findTopLevelObjectKeys(key: String): Set<String> {
            val valueStart = findTopLevelValueStart(key) ?: return emptySet()
            var index = skipWhitespace(valueStart)
            if (index >= text.length || text[index] != '{') return emptySet()
            index++
            val keys = mutableSetOf<String>()
            while (true) {
                index = skipWhitespace(index)
                if (index >= text.length) return keys
                when (text[index]) {
                    '}' -> return keys
                    ',' -> index++
                    '"' -> {
                        val read = readString(index) ?: return keys
                        keys += read.first
                        index = skipWhitespace(read.second)
                        if (index >= text.length || text[index] != ':') return keys
                        index++
                        index = skipValue(index) ?: return keys
                    }
                    else -> return keys
                }
            }
        }

        /**
         * Index where the top-level value for [key] starts, or null.
         * Only depth-1 members of the outermost object are considered.
         */
        private fun findTopLevelValueStart(key: String): Int? {
            var index = skipWhitespace(0)
            if (index >= text.length || text[index] != '{') return null
            index++
            while (true) {
                index = skipWhitespace(index)
                if (index >= text.length) return null
                when (text[index]) {
                    '}' -> return null
                    ',' -> index++
                    '"' -> {
                        val read = readString(index) ?: return null
                        index = skipWhitespace(read.second)
                        if (index >= text.length || text[index] != ':') return null
                        index++
                        index = skipWhitespace(index)
                        if (read.first == key) return index
                        index = skipValue(index) ?: return null
                    }
                    else -> return null
                }
            }
        }

        /** Read a JSON string starting at [from] (must be at the opening quote). */
        private fun readString(from: Int): Pair<String, Int>? {
            if (from >= text.length || text[from] != '"') return null
            val out = StringBuilder()
            var index = from + 1
            while (index < text.length) {
                val ch = text[index]
                when {
                    ch == '"' -> return out.toString() to (index + 1)
                    ch == '\\' -> {
                        if (index + 1 >= text.length) return null
                        when (val esc = text[index + 1]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (index + 5 >= text.length) return null
                                val hex = text.substring(index + 2, index + 6)
                                val code = hex.toIntOrNull(16) ?: return null
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> return null
                        }
                        index += 2
                    }
                    else -> {
                        out.append(ch)
                        index++
                    }
                }
            }
            return null // unterminated string
        }

        /** Skip one JSON value starting at [from]; returns the index just past it, or null. */
        private fun skipValue(from: Int): Int? {
            var index = skipWhitespace(from)
            if (index >= text.length) return null
            return when (text[index]) {
                '{', '[' -> skipBalanced(index)
                '"' -> readString(index)?.second
                else -> skipLiteral(index)
            }
        }

        /** Skip a balanced {...} or [...] starting at [from]. */
        private fun skipBalanced(from: Int): Int? {
            val open = text[from]
            val close = if (open == '{') '}' else ']'
            var index = from + 1
            var depth = 1
            while (index < text.length && depth > 0) {
                when (val ch = text[index]) {
                    '"' -> {
                        index = readString(index)?.second ?: return null
                    }
                    '{', '[' -> {
                        // A nested container of either kind adds depth;
                        // JSON only nests matching pairs, so one counter
                        // is enough as long as strings are skipped.
                        depth++
                        index++
                    }
                    '}', ']' -> {
                        depth--
                        index++
                        if (depth == 0) {
                            if (ch != close) return null
                            return index
                        }
                    }
                    else -> index++
                }
            }
            return null
        }

        /** Skip a literal (number, true, false, null) up to a delimiter. */
        private fun skipLiteral(from: Int): Int? {
            var index = from
            while (index < text.length) {
                val ch = text[index]
                if (ch == ',' || ch == '}' || ch == ']' || ch.isWhitespace()) break
                index++
            }
            return if (index > from) index else null
        }

        private fun skipWhitespace(from: Int): Int {
            var index = from
            while (index < text.length && text[index].isWhitespace()) index++
            return index
        }
    }
}
