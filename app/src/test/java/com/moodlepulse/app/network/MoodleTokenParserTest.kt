package com.moodlepulse.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class MoodleTokenParserTest {

    @Test
    fun normalizeBaseUrl_addsHttpsAndStripsLoginPaths() {
        assertEquals(
            "https://moodle.ntu.edu.tw",
            MoodleTokenParser.normalizeBaseUrl("moodle.ntu.edu.tw/login/index.php")
        )
        assertEquals(
            "https://elearn.ncku.edu.tw/moodle",
            MoodleTokenParser.normalizeBaseUrl("http://elearn.ncku.edu.tw/moodle/my/")
        )
    }

    @Test
    fun parseCallbackUrl_extractsTokenAndPrivateTokenFromBase64() {
        val rawPayload = "sig123:::abcdef0123456789abcdef0123456789:::priv999"
        val encoded = Base64.getEncoder().encodeToString(rawPayload.toByteArray(StandardCharsets.UTF_8))
        val callback = "moodlepulse://token=$encoded"

        assertTrue(MoodleTokenParser.isTokenCallbackUrl(callback))
        val parsed = MoodleTokenParser.parseCallbackUrl(callback)
        assertNotNull(parsed)
        assertEquals("abcdef0123456789abcdef0123456789", parsed?.token)
        assertEquals("priv999", parsed?.privateToken)
        assertEquals("sig123", parsed?.signature)
    }

    @Test
    fun parseCallbackUrl_acceptsMoodlemobileSchemeAndHexToken() {
        val rawPayload = "sig:::11223344556677889900aabbccddeeff"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawPayload.toByteArray(StandardCharsets.UTF_8))
        val callback = "moodlemobile://token=$encoded&extra=1"

        assertTrue(MoodleTokenParser.isTokenCallbackUrl(callback))
        assertEquals("11223344556677889900aabbccddeeff", MoodleTokenParser.parseCallbackUrl(callback)?.token)

        val directHex = MoodleTokenParser.parseCallbackUrl("11223344556677889900aabbccddeeff")
        assertEquals("11223344556677889900aabbccddeeff", directHex?.token)
    }

    @Test
    fun parseCallbackUrl_rejectsInvalidPayloads() {
        assertFalse(MoodleTokenParser.isTokenCallbackUrl("https://example.com/home"))
        assertNull(MoodleTokenParser.parseCallbackUrl("moodlepulse://token=not-valid-base64!!!"))
    }
}
