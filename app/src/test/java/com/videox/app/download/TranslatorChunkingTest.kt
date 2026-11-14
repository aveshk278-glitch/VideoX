package com.videox.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TranslationChunks (v2.14): splitting extracted text
 * into translation-sized chunks on cue boundaries. Pure Kotlin —
 * runs on the JVM via `./gradlew testDebugUnitTest`.
 */
class TranslatorChunkingTest {

    @Test
    fun `short text stays in one chunk`() {
        assertEquals(listOf("Hello world"), TranslationChunks.chunk("Hello world"))
    }

    @Test
    fun `multi-line text chunks on line boundaries and reconstructs exactly`() {
        val text = (1..100).joinToString("\n") { "Cue number $it with some words." }
        val chunks = TranslationChunks.chunk(text, maxChars = 200)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(chunk.length <= 200)
        }
        assertEquals(text, chunks.joinToString("\n"))
    }

    @Test
    fun `no chunk exceeds the cap`() {
        val text = (1..500).joinToString("\n") { "Line $it — a subtitle cue of moderate length." }
        val chunks = TranslationChunks.chunk(text)
        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue(chunk.length <= TranslationChunks.MAX_CHUNK_CHARS)
        }
        assertEquals(text, chunks.joinToString("\n"))
    }

    @Test
    fun `a single over-long line is hard-split`() {
        val longLine = "x".repeat(9500)
        val chunks = TranslationChunks.chunk(longLine)
        assertEquals(3, chunks.size)
        assertEquals(4000, chunks[0].length)
        assertEquals(4000, chunks[1].length)
        assertEquals(1500, chunks[2].length)
        assertEquals(longLine, chunks.joinToString(""))
    }

    @Test
    fun `empty and blank text give no chunks`() {
        assertTrue(TranslationChunks.chunk("").isEmpty())
        assertTrue(TranslationChunks.chunk("   \n  ").isEmpty())
    }

    @Test
    fun `text exactly at the cap stays in one chunk`() {
        val text = "a".repeat(TranslationChunks.MAX_CHUNK_CHARS)
        assertEquals(listOf(text), TranslationChunks.chunk(text))
    }

    @Test
    fun `default cap is 4000 characters`() {
        assertEquals(4000, TranslationChunks.MAX_CHUNK_CHARS)
    }

    @Test
    fun `boundary case with small cap splits cleanly`() {
        // Lines of 3 chars + newline: "aaa\nbbb\nccc" with cap 7.
        val chunks = TranslationChunks.chunk("aaa\nbbb\nccc", maxChars = 7)
        assertEquals(listOf("aaa\nbbb", "ccc"), chunks)
    }
}
