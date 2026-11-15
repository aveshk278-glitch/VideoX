package com.videox.app.download

/**
 * Upscale history: completed jobs, kept as a small JSON string in
 * settings (no Room migration for a list that is only ever read whole
 * and shown newest-first). Encoding/decoding is hand-rolled with
 * proper escaping, mirroring SettingsBackup — no JSON library is
 * bundled. Pure JVM, unit-tested.
 */
data class UpscaleHistoryItem(
    val inputName: String,
    val profileLabel: String,
    val outputFileName: String,
    val outputWidth: Int,
    val outputHeight: Int,
    val finishedAtMillis: Long
)

object UpscaleHistory {

    const val MAX_ITEMS = 20

    fun encode(items: List<UpscaleHistoryItem>): String {
        val sb = StringBuilder()
        sb.append('[')
        items.take(MAX_ITEMS).forEachIndexed { index, item ->
            if (index > 0) sb.append(',')
            sb.append('{')
            sb.append("\"inputName\":").append(quoted(item.inputName)).append(',')
            sb.append("\"profileLabel\":").append(quoted(item.profileLabel)).append(',')
            sb.append("\"outputFileName\":").append(quoted(item.outputFileName)).append(',')
            sb.append("\"outputWidth\":").append(item.outputWidth).append(',')
            sb.append("\"outputHeight\":").append(item.outputHeight).append(',')
            sb.append("\"finishedAtMillis\":").append(item.finishedAtMillis)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    fun decode(stored: String): List<UpscaleHistoryItem> {
        val text = stored.trim()
        if (text.isEmpty() || text == "[]") return emptyList()
        if (!text.startsWith("[") || !text.endsWith("]")) return emptyList()
        val items = mutableListOf<UpscaleHistoryItem>()
        var pos = 1
        while (pos < text.length) {
            val open = text.indexOf('{', pos)
            if (open < 0) break
            val close = findObjectEnd(text, open)
            if (close < 0) break
            parseObject(text.substring(open + 1, close))?.let { items += it }
            pos = close + 1
        }
        return items.take(MAX_ITEMS)
    }

    private fun findObjectEnd(text: String, open: Int): Int {
        var inString = false
        var escaped = false
        var i = open + 1
        while (i < text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\' && inString) {
                escaped = true
            } else if (c == '"') {
                inString = !inString
            } else if (c == '}' && !inString) {
                return i
            }
            i++
        }
        return -1
    }

    private fun parseObject(body: String): UpscaleHistoryItem? {
        val fields = splitFields(body)
        var inputName: String? = null
        var profileLabel: String? = null
        var outputFileName: String? = null
        var outputWidth: Int? = null
        var outputHeight: Int? = null
        var finishedAtMillis: Long? = null
        for (field in fields) {
            val colon = indexOfColonOutsideString(field)
            if (colon < 0) continue
            val key = unquote(field.substring(0, colon).trim())
            val raw = field.substring(colon + 1).trim()
            when (key) {
                "inputName" -> inputName = unquote(raw)
                "profileLabel" -> profileLabel = unquote(raw)
                "outputFileName" -> outputFileName = unquote(raw)
                "outputWidth" -> outputWidth = raw.toIntOrNull()
                "outputHeight" -> outputHeight = raw.toIntOrNull()
                "finishedAtMillis" -> finishedAtMillis = raw.toLongOrNull()
            }
        }
        if (inputName == null || profileLabel == null || outputFileName == null ||
            outputWidth == null || outputHeight == null || finishedAtMillis == null
        ) return null
        return UpscaleHistoryItem(
            inputName, profileLabel, outputFileName,
            outputWidth, outputHeight, finishedAtMillis
        )
    }

    private fun splitFields(body: String): List<String> {
        val fields = mutableListOf<String>()
        var inString = false
        var escaped = false
        var start = 0
        for (i in body.indices) {
            val c = body[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\' && inString) {
                escaped = true
            } else if (c == '"') {
                inString = !inString
            } else if (c == ',' && !inString) {
                fields += body.substring(start, i)
                start = i + 1
            }
        }
        if (start < body.length) fields += body.substring(start)
        return fields
    }

    private fun indexOfColonOutsideString(field: String): Int {
        var inString = false
        var escaped = false
        for (i in field.indices) {
            val c = field[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\' && inString) {
                escaped = true
            } else if (c == '"') {
                inString = !inString
            } else if (c == ':' && !inString) {
                return i
            }
        }
        return -1
    }

    private fun quoted(value: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun unquote(raw: String): String? {
        val text = raw.trim()
        if (text.length < 2 || !text.startsWith("\"") || !text.endsWith("\"")) return null
        val sb = StringBuilder()
        var i = 1
        while (i < text.length - 1) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length - 1) {
                when (val next = text[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = text.substring(i + 2, (i + 6).coerceAtMost(text.length - 1))
                        val code = hex.toIntOrNull(16)
                        if (code != null && hex.length == 4) {
                            sb.append(code.toChar())
                            i += 4
                        } else {
                            sb.append(next)
                        }
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
