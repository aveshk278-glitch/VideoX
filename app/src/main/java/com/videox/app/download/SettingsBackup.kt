package com.videox.app.download

/**
 * Settings backup and restore (v2.9, from the Seal Roadmap board).
 *
 * Pure Kotlin with NO Android dependencies, so it is covered by plain
 * JVM unit tests (see SettingsBackupTest). The backup file is a flat
 * JSON object whose values are all strings, e.g.
 * {"wifi_only":"false","max_concurrent":"2","proxy_url":""}.
 *
 * A tiny hand-written parser is used on purpose: org.json lives in the
 * Android SDK and cannot run in local unit tests, and adding a JSON
 * library for nine settings would be overkill. The parser accepts only
 * the exact shape [encode] produces and returns null for anything
 * malformed, so a damaged backup file can never corrupt settings —
 * restore simply refuses it with an honest message.
 */
object SettingsBackup {

    /** Encode a flat settings map as a JSON object string. */
    fun encode(values: Map<String, String>): String {
        val body = values.entries.joinToString(",") { (key, value) ->
            "\"${escape(key)}\":\"${escape(value)}\""
        }
        return "{$body}"
    }

    /**
     * Decode a backup file. Returns the settings map, or null when the
     * text is not a flat JSON object of string keys and string values
     * (truncated file, wrong shape, trailing garbage, unquoted values).
     */
    fun decode(json: String): Map<String, String>? {
        val text = json.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val inner = text.substring(1, text.length - 1).trim()
        if (inner.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < inner.length) {
            index = skipWhitespace(inner, index)
            val key = readString(inner, index) ?: return null
            index = key.second
            index = skipWhitespace(inner, index)
            if (index >= inner.length || inner[index] != ':') return null
            index++
            index = skipWhitespace(inner, index)
            val value = readString(inner, index) ?: return null
            index = value.second
            result[key.first] = value.first
            index = skipWhitespace(inner, index)
            if (index >= inner.length) break
            if (inner[index] != ',') return null
            index++
            index = skipWhitespace(inner, index)
            if (index >= inner.length) return null // trailing comma
        }
        return result
    }

    private fun escape(raw: String): String {
        val out = StringBuilder()
        for (ch in raw) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    /**
     * Read a double-quoted string starting at [from]. Returns the
     * unescaped string plus the index just past the closing quote,
     * or null when there is no well-formed string there.
     */
    private fun readString(text: String, from: Int): Pair<String, Int>? {
        if (from >= text.length || text[from] != '"') return null
        val out = StringBuilder()
        var index = from + 1
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '"' -> return out.toString() to (index + 1)
                ch == '\\' -> {
                    if (index + 1 >= text.length) return null
                    when (text[index + 1]) {
                        '\\' -> out.append('\\')
                        '"' -> out.append('"')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
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
}
