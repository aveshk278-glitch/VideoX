package com.videox.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the extraction language catalog (v2.14). Pure
 * Kotlin — runs on the JVM via `./gradlew testDebugUnitTest`.
 * The Vosk model names/sizes mirror the official list at
 * alphacephei.com/vosk/models.html as verified on 2026-11-14.
 */
class ExtractionLanguageTest {

    @Test
    fun `catalog contains the specials plus every requested language`() {
        val codes = ExtractionLanguage.CATALOG.map { it.code }
        assertEquals(
            listOf(
                "auto", "original",
                "hi", "en", "en-in", "ur", "bn", "ta", "te", "mr", "gu",
                "pa", "kn", "ml",
                "all"
            ),
            codes
        )
    }

    @Test
    fun `catalog display names are human-readable`() {
        assertEquals("Auto Detect", ExtractionLanguage.displayName("auto"))
        assertEquals("Original Language", ExtractionLanguage.displayName("original"))
        assertEquals("Hindi", ExtractionLanguage.displayName("hi"))
        assertEquals("English (India)", ExtractionLanguage.displayName("en-in"))
        assertEquals("Urdu", ExtractionLanguage.displayName("ur"))
        assertEquals("All languages", ExtractionLanguage.displayName("all"))
    }

    @Test
    fun `displayName falls back to the code itself for unknown codes`() {
        assertEquals("xx", ExtractionLanguage.displayName("xx"))
        assertEquals("fr", ExtractionLanguage.displayName("fr"))
    }

    @Test
    fun `resolveVoskModel returns the verified model names`() {
        assertEquals("vosk-model-small-hi-0.22", ExtractionLanguage.resolveVoskModel("hi"))
        assertEquals("vosk-model-small-en-us-0.15", ExtractionLanguage.resolveVoskModel("en"))
        assertEquals("vosk-model-small-en-in-0.4", ExtractionLanguage.resolveVoskModel("en-in"))
        assertEquals("vosk-model-small-gu-0.42", ExtractionLanguage.resolveVoskModel("gu"))
        assertEquals("vosk-model-small-te-0.42", ExtractionLanguage.resolveVoskModel("te"))
    }

    @Test
    fun `resolveVoskModel is null for languages with no offline voice model`() {
        for (code in listOf("ur", "bn", "ta", "mr", "pa", "kn", "ml")) {
            assertNull("expected no model for $code", ExtractionLanguage.resolveVoskModel(code))
        }
        assertNull(ExtractionLanguage.resolveVoskModel("auto"))
        assertNull(ExtractionLanguage.resolveVoskModel("original"))
        assertNull(ExtractionLanguage.resolveVoskModel("all"))
    }

    @Test
    fun `resolveVoskModel never substitutes another language`() {
        // The v2.10-v2.13 bug: every language silently used the
        // English model. An unknown code must resolve to nothing.
        assertNull(ExtractionLanguage.resolveVoskModel("xx"))
        assertNull(ExtractionLanguage.resolveVoskModel(""))
        assertNull(ExtractionLanguage.resolveVoskModel("fr"))
    }

    @Test
    fun `resolveVoskModel matches base language and ignores case`() {
        assertEquals("vosk-model-small-en-us-0.15", ExtractionLanguage.resolveVoskModel("en-US"))
        assertEquals("vosk-model-small-en-us-0.15", ExtractionLanguage.resolveVoskModel("EN"))
        assertEquals("vosk-model-small-hi-0.22", ExtractionLanguage.resolveVoskModel("HI"))
        assertEquals("vosk-model-small-hi-0.22", ExtractionLanguage.resolveVoskModel("hi-IN"))
    }

    @Test
    fun `telugu model is flagged weak`() {
        val te = ExtractionLanguage.entryFor("te")
        assertTrue(te.voskWeak)
        assertEquals(58, te.voskModelSizeMb)
        assertFalse(ExtractionLanguage.entryFor("hi").voskWeak)
    }

    @Test
    fun `vosk model sizes match the official list`() {
        assertEquals(40, ExtractionLanguage.entryFor("en").voskModelSizeMb)
        assertEquals(36, ExtractionLanguage.entryFor("en-in").voskModelSizeMb)
        assertEquals(42, ExtractionLanguage.entryFor("hi").voskModelSizeMb)
        assertEquals(100, ExtractionLanguage.entryFor("gu").voskModelSizeMb)
    }

    @Test
    fun `translation targets exclude specials and unsupported languages`() {
        val targets = ExtractionLanguage.translationTargets.map { it.code }
        assertFalse(targets.contains("auto"))
        assertFalse(targets.contains("original"))
        assertFalse(targets.contains("all"))
        // Punjabi and Malayalam have no ML Kit Translate constants.
        assertFalse(targets.contains("pa"))
        assertFalse(targets.contains("ml"))
        assertTrue(targets.contains("hi"))
        assertTrue(targets.contains("en"))
        assertTrue(targets.contains("ur"))
        assertTrue(targets.contains("bn"))
        assertTrue(targets.contains("ta"))
        assertTrue(targets.contains("te"))
        assertTrue(targets.contains("mr"))
        assertTrue(targets.contains("gu"))
        assertTrue(targets.contains("kn"))
    }

    @Test
    fun `isTranslationTarget matches the target list`() {
        assertTrue(ExtractionLanguage.isTranslationTarget("en"))
        assertTrue(ExtractionLanguage.isTranslationTarget("hi"))
        assertFalse(ExtractionLanguage.isTranslationTarget("auto"))
        assertFalse(ExtractionLanguage.isTranslationTarget("all"))
        assertFalse(ExtractionLanguage.isTranslationTarget("pa"))
        assertFalse(ExtractionLanguage.isTranslationTarget("ml"))
        assertFalse(ExtractionLanguage.isTranslationTarget("xx"))
    }

    @Test
    fun `isKnownCode accepts catalog codes only`() {
        assertTrue(ExtractionLanguage.isKnownCode("auto"))
        assertTrue(ExtractionLanguage.isKnownCode("hi"))
        assertTrue(ExtractionLanguage.isKnownCode("all"))
        assertFalse(ExtractionLanguage.isKnownCode("xx"))
        assertFalse(ExtractionLanguage.isKnownCode(""))
    }

    @Test
    fun `subtitleCodeFor maps indian english to plain english for yt-dlp`() {
        assertEquals("en", ExtractionLanguage.subtitleCodeFor("en-in"))
        assertEquals("hi", ExtractionLanguage.subtitleCodeFor("hi"))
        assertEquals("ur", ExtractionLanguage.subtitleCodeFor("ur"))
    }

    @Test
    fun `voskSupportedNames lists exactly the five model languages`() {
        assertEquals(
            "English, English (India), Hindi, Gujarati, Telugu",
            ExtractionLanguage.voskSupportedNames()
        )
    }
}
