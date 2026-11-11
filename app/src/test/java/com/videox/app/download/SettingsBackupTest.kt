package com.videox.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the settings backup format (SettingsBackup).
 * These run on the JVM via `./gradlew testDebugUnitTest` — no device
 * and no Android SDK needed, because SettingsBackup is pure Kotlin.
 */
class SettingsBackupTest {

    @Test
    fun `encode then decode round-trips all settings`() {
        val values = mapOf(
            "wifi_only" to "false",
            "show_notifications" to "true",
            "dark_theme" to "true",
            "max_concurrent" to "2",
            "warn_metered" to "true",
            "proxy_url" to "http://user:pass@proxy.example.com:8080",
            "subtitles_enabled" to "false",
            "subtitle_langs" to "en",
            "embed_chapters" to "false"
        )
        assertEquals(values, SettingsBackup.decode(SettingsBackup.encode(values)))
    }

    @Test
    fun `encode escapes quotes and backslashes so decode restores them`() {
        val values = mapOf("proxy_url" to "http://ho\"st\\x:8080")
        assertEquals(values, SettingsBackup.decode(SettingsBackup.encode(values)))
    }

    @Test
    fun `decode accepts an empty object and whitespace`() {
        assertEquals(emptyMap<String, String>(), SettingsBackup.decode("{}"))
        assertEquals(
            mapOf("dark_theme" to "true"),
            SettingsBackup.decode("  {  \"dark_theme\" : \"true\" }  ")
        )
    }

    @Test
    fun `decode rejects malformed backups`() {
        assertNull(SettingsBackup.decode(""))
        assertNull(SettingsBackup.decode("{bad"))
        assertNull(SettingsBackup.decode("[]"))
        assertNull(SettingsBackup.decode("{\"a\":1}"))
        assertNull(SettingsBackup.decode("{\"a\":\"b\"} trailing"))
        assertNull(SettingsBackup.decode("{\"a\":\"b\",}"))
        assertNull(SettingsBackup.decode("{\"a\":\"unterminated}"))
        assertNull(SettingsBackup.decode("not json at all"))
    }

    @Test
    fun `encode produces a flat JSON object`() {
        val json = SettingsBackup.encode(mapOf("a" to "1", "b" to "2"))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"a\":\"1\""))
        assertTrue(json.contains("\"b\":\"2\""))
    }
}
