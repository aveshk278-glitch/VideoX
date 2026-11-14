package com.videox.app.download

/**
 * Splitting extracted text into translation-sized chunks (v2.14).
 *
 * Pure Kotlin with NO Android dependencies, so it is covered by plain
 * JVM unit tests (see TranslatorChunkingTest).
 *
 * Extracted text is one line per subtitle cue (or per spoken segment),
 * so chunks split on line boundaries: no cue is ever cut in half
 * unless a single cue is longer than the cap, in which case that cue
 * alone is hard-split. Chunks joined with a newline reconstruct the
 * original text exactly, except for hard-split cues.
 */
object TranslationChunks {

    /** Maximum characters per translation request. */
    const val MAX_CHUNK_CHARS = 4000

    /**
     * Split [text] into chunks of at most [maxChars] characters.
     * Returns an empty list for blank input.
     */
    fun chunk(text: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        if (text.isBlank()) return emptyList()
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current.toString()
                current.clear()
            }
        }
        for (line in text.lines()) {
            var rest = line
            // A single cue longer than the cap is hard-split on its own.
            while (rest.length > maxChars) {
                flush()
                chunks += rest.substring(0, maxChars)
                rest = rest.substring(maxChars)
            }
            val addition = if (current.isEmpty()) rest else "\n$rest"
            if (current.length + addition.length > maxChars) {
                flush()
                current.append(rest)
            } else {
                current.append(addition)
            }
        }
        flush()
        return chunks
    }
}
