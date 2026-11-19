package com.moodlepulse.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val apkAssetApiUrl: String?,
    val apkFileName: String?,
    val apkSizeBytes: Long,
    val publishedAt: String
)

object GitHubReleaseParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val slugRegex = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")

    /**
     * 將使用者輸入的 GitHub Repo 字串正規化為 "owner/repo" 格式。
     * 支援 "owner/repo"、"https://github.com/owner/repo"、"https://github.com/owner/repo.git" 等格式。
     */
    fun normalizeRepoSlug(input: String): String? {
        var cleaned = input.trim()
        if (cleaned.isEmpty()) return null

        cleaned = cleaned
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removePrefix("github.com/")
            .trim('/')

        val parts = cleaned.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null

        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        val candidate = "$owner/$repo"
        return candidate.takeIf { slugRegex.matches(it) }
    }

    fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").trim()
    }

    /**
     * 比較遠端 GitHub Release 版本是否比目前安裝版本更新。
     */
    fun isNewerVersion(remoteTagOrVersion: String, currentVersionName: String): Boolean {
        val remote = parseSemVer(remoteTagOrVersion) ?: return false
        val current = parseSemVer(currentVersionName) ?: return false

        val maxLen = maxOf(remote.numbers.size, current.numbers.size)
        for (i in 0 until maxLen) {
            val r = remote.numbers.getOrElse(i) { 0 }
            val c = current.numbers.getOrElse(i) { 0 }
            if (r != c) return r > c
        }

        // 主版本號相同時，正式版 (preRelease == null) 視為高於預覽版 (例如 1.0.1 > 1.0.1-beta)
        return when {
            remote.preRelease == null && current.preRelease != null -> true
            remote.preRelease != null && current.preRelease == null -> false
            remote.preRelease != null && current.preRelease != null ->
                remote.preRelease.compareTo(current.preRelease, ignoreCase = true) > 0
            else -> false
        }
    }

    fun parseLatestReleaseJson(jsonBody: String): GitHubReleaseInfo? {
        val obj = runCatching { json.parseToJsonElement(jsonBody).jsonObject }.getOrNull()
            ?: return null
        return parseReleaseObject(obj)
    }

    fun parseReleasesListJson(jsonBody: String): GitHubReleaseInfo? {
        val array = runCatching { json.parseToJsonElement(jsonBody).jsonArray }.getOrNull()
            ?: return null
        return array.firstOrNull()?.let { element ->
            runCatching { parseReleaseObject(element.jsonObject) }.getOrNull()
        }
    }

    private fun parseReleaseObject(obj: JsonObject): GitHubReleaseInfo? {
        if (obj.bool("draft") == true) return null

        val tagName = obj.str("tag_name")?.trim().orEmpty()
        if (tagName.isEmpty()) return null

        val versionName = normalizeVersion(tagName)
        val title = obj.str("name")?.trim().orEmpty().ifEmpty { tagName }
        val releaseNotes = obj.str("body")?.trim().orEmpty()
        val htmlUrl = obj.str("html_url")?.trim().orEmpty()
        val publishedAt = obj.str("published_at")?.trim().orEmpty()

        val apkAssets = obj["assets"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            ?.filter { asset ->
                val name = asset.str("name").orEmpty()
                name.endsWith(".apk", ignoreCase = true)
            }
            .orEmpty()

        // 若同時有 release 與 debug APK，優先挑選 release APK
        val chosenAsset = apkAssets.firstOrNull {
            val name = it.str("name").orEmpty()
            name.contains("release", ignoreCase = true) && !name.contains("unsigned", ignoreCase = true)
        } ?: apkAssets.firstOrNull {
            !it.str("name").orEmpty().contains("unsigned", ignoreCase = true)
        } ?: apkAssets.firstOrNull()

        return GitHubReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            title = title,
            releaseNotes = releaseNotes,
            htmlUrl = htmlUrl,
            apkDownloadUrl = chosenAsset?.str("browser_download_url"),
            apkAssetApiUrl = chosenAsset?.str("url"),
            apkFileName = chosenAsset?.str("name"),
            apkSizeBytes = chosenAsset?.long("size") ?: 0L,
            publishedAt = publishedAt
        )
    }

    private data class ParsedSemVer(
        val numbers: List<Int>,
        val preRelease: String?
    )

    private fun parseSemVer(raw: String): ParsedSemVer? {
        val cleaned = normalizeVersion(raw).substringBefore('+')
        if (cleaned.isEmpty()) return null

        val dashIndex = cleaned.indexOf('-')
        val corePart = if (dashIndex >= 0) cleaned.substring(0, dashIndex) else cleaned
        val preRelease = if (dashIndex >= 0) cleaned.substring(dashIndex + 1).ifBlank { null } else null

        val numbers = corePart.split('.').mapNotNull { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull()
        }
        if (numbers.isEmpty()) return null
        return ParsedSemVer(numbers = numbers, preRelease = preRelease)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}
