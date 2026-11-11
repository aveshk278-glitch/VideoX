package com.videox.app.download

/**
 * Video-to-text extraction (v2.10): turn a subtitle file (SRT or WebVTT)
 * into clean plain text.
 *
 * Pure Kotlin with NO Android dependencies, so it is covered by plain
 * JVM unit tests (see SubtitleTextTest). The samples in the tests mirror
 * real subtitle files downloaded by yt-dlp 2026.10.26.
 *
 * Parsing rules (block-based: cues are separated by blank lines):
 * - A block whose first line is a file header (WEBVTT, Kind:, Language:)
 *   or a NOTE/STYLE/REGION block is skipped entirely.
 * - Inside a cue block, cue numbers (SRT) and cue identifiers (WebVTT)
 *   and timestamp lines (containing "-->") are dropped.
 * - The remaining lines are one cue's text: HTML-style tags such as
 *   <i> or <c.color> are stripped, and multi-line cues are joined with
 *   a single space.
 * - One output line per cue, in order. Nothing is deduplicated: if the
 *   video repeats a line, the text repeats it too.
 */
object SubtitleText {

    /** Convert SRT or WebVTT subtitle content to clean plain text. */
    fun toPlainText(content: String): String {
        val lines = content.lines()
        val blocks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) {
                    blocks += current
                    current = mutableListOf()
                }
            } else {
                current += line
            }
        }
        if (current.isNotEmpty()) blocks += current

        val outLines = mutableListOf<String>()
        for (block in blocks) {
            val first = block.first().trim()
            if (first.startsWith("WEBVTT") ||
                first.startsWith("NOTE") ||
                first.startsWith("STYLE") ||
                first.startsWith("REGION") ||
                first.startsWith("Kind:") ||
                first.startsWith("Language:")
            ) {
                continue
            }
            // A cue's text comes AFTER its timestamp line. Everything
            // before the timestamp (SRT cue number, WebVTT cue
            // identifier) is dropped. Blocks without a timestamp keep
            // their non-numeric lines.
            val tsIndex = block.indexOfFirst { it.contains("-->") }
            val textLines = if (tsIndex >= 0) block.drop(tsIndex + 1) else block
            val textParts = textLines
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { !isCueNumber(it) && !it.contains("-->") }
                .map { stripTags(it) }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (textParts.isNotEmpty()) {
                outLines += textParts.joinToString(" ")
            }
        }
        return outLines.joinToString("\n")
    }

    private fun isCueNumber(line: String): Boolean =
        line.all { it.isDigit() }

    private fun stripTags(text: String): String {
        val out = StringBuilder()
        var inTag = false
        for (ch in text) {
            when {
                ch == '<' -> inTag = true
                ch == '>' -> inTag = false
                !inTag -> out.append(ch)
            }
        }
        return out.toString()
    }
}
