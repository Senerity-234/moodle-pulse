package com.moodlepulse.app.network

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ParsedMoodleToken(
    val token: String,
    val privateToken: String? = null,
    val signature: String? = null
)

object MoodleTokenParser {

    fun normalizeBaseUrl(rawInput: String): String {
        val trimmed = rawInput.trim()
        require(trimmed.isNotEmpty()) { "請輸入學校 Moodle 網址" }

        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val uri = URI(withScheme)
        val host = uri.host ?: throw IllegalArgumentException("網址格式不正確：$rawInput")
        val scheme = if (uri.scheme.equals("http", ignoreCase = true)) "https" else uri.scheme.lowercase()
        val portPart = if (uri.port != -1 && uri.port != 443 && uri.port != 80) ":${uri.port}" else ""
        val cleanPath = uri.path?.trimEnd('/') ?: ""

        // Strip common trailing Moodle paths if user pasted a course or login URL
        val basePath = cleanPath
            .removeSuffix("/login/index.php")
            .removeSuffix("/my")
            .removeSuffix("/index.php")
            .trimEnd('/')

        return "$scheme://$host$portPart$basePath"
    }

    fun isTokenCallbackUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.startsWith("moodlepulse://") || lower.startsWith("moodlemobile://")) &&
            lower.contains("token=")
    }

    fun parseCallbackUrl(callbackUrl: String): ParsedMoodleToken? {
        val trimmed = callbackUrl.trim()
        val tokenMarkerIndex = trimmed.indexOf("token=", ignoreCase = true)
        if (tokenMarkerIndex == -1) return null

        var encodedPayload = trimmed.substring(tokenMarkerIndex + "token=".length)
        val ampIndex = encodedPayload.indexOf('&')
        if (ampIndex != -1) {
            encodedPayload = encodedPayload.substring(0, ampIndex)
        }
        val hashIndex = encodedPayload.indexOf('#')
        if (hashIndex != -1) {
            encodedPayload = encodedPayload.substring(0, hashIndex)
        }

        // Handle URL-encoded or URL-safe Base64 payloads returned by different Moodle versions
        val decodedUriPart = runCatching {
            URLDecoder.decode(encodedPayload, StandardCharsets.UTF_8.name())
        }.getOrDefault(encodedPayload).trim()

        // If the user pasted a raw 32-char hex token directly, support it gracefully
        if (decodedUriPart.matches(Regex("^[a-fA-F0-9]{24,64}$"))) {
            return ParsedMoodleToken(token = decodedUriPart)
        }

        val decodedString = decodeBase64Flexible(decodedUriPart) ?: return null
        val parts = decodedString.split(":::")
        if (parts.size < 2) return null

        val signature = parts[0].trim().ifEmpty { null }
        val token = parts[1].trim()
        if (token.isEmpty()) return null

        val privateToken = parts.getOrNull(2)?.trim()?.ifEmpty { null }
        return ParsedMoodleToken(
            token = token,
            privateToken = privateToken,
            signature = signature
        )
    }

    private fun decodeBase64Flexible(input: String): String? {
        val normalized = input
            .replace('-', '+')
            .replace('_', '/')
            .let { raw ->
                val remainder = raw.length % 4
                if (remainder == 0) raw else raw + "=".repeat(4 - remainder)
            }
        return runCatching {
            val bytes = Base64.getDecoder().decode(normalized)
            String(bytes, StandardCharsets.UTF_8)
        }.getOrNull()
    }
}
