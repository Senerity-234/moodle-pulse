package com.moodlepulse.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseParserTest {

    @Test
    fun normalizeRepoSlug_acceptsSlugAndFullGithubUrls() {
        assertEquals("Senerity-234/moodle-pulse", GitHubReleaseParser.normalizeRepoSlug("Senerity-234/moodle-pulse"))
        assertEquals(
            "Senerity-234/moodle-pulse",
            GitHubReleaseParser.normalizeRepoSlug("https://github.com/Senerity-234/moodle-pulse")
        )
        assertEquals(
            "Senerity-234/moodle-pulse",
            GitHubReleaseParser.normalizeRepoSlug("https://github.com/Senerity-234/moodle-pulse.git")
        )
        assertEquals(
            "Senerity-234/moodle-pulse",
            GitHubReleaseParser.normalizeRepoSlug("github.com/Senerity-234/moodle-pulse/releases")
        )
        assertNull(GitHubReleaseParser.normalizeRepoSlug("invalid-repo"))
        assertNull(GitHubReleaseParser.normalizeRepoSlug(""))
    }

    @Test
    fun isNewerVersion_comparesSemanticVersionsCorrectly() {
        assertTrue(GitHubReleaseParser.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(GitHubReleaseParser.isNewerVersion("v1.0.10", "1.0.2"))
        assertTrue(GitHubReleaseParser.isNewerVersion("1.1.0", "1.0.9"))
        assertTrue(GitHubReleaseParser.isNewerVersion("v1.0.1", "1.0.1-beta"))

        assertFalse(GitHubReleaseParser.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(GitHubReleaseParser.isNewerVersion("1.0.0", "1.0.1"))
        assertFalse(GitHubReleaseParser.isNewerVersion("v1.0.1-beta", "1.0.1"))
    }

    @Test
    fun parseLatestReleaseJson_prefersSignedReleaseApkOverDebugApk() {
        val sampleJson = """
            {
              "tag_name": "v1.2.0",
              "name": "MoodlePulse v1.2.0",
              "draft": false,
              "html_url": "https://github.com/Senerity-234/moodle-pulse/releases/tag/v1.2.0",
              "published_at": "2026-11-19T10:00:00Z",
              "body": "- 新增 GitHub 自我更新\n- 修正作業排序",
              "assets": [
                {
                  "name": "MoodlePulse-v1.2.0-debug.apk",
                  "size": 123456,
                  "url": "https://api.github.com/repos/Senerity-234/moodle-pulse/releases/assets/1",
                  "browser_download_url": "https://github.com/Senerity-234/moodle-pulse/releases/download/v1.2.0/MoodlePulse-v1.2.0-debug.apk"
                },
                {
                  "name": "MoodlePulse-v1.2.0-release.apk",
                  "size": 98765,
                  "url": "https://api.github.com/repos/Senerity-234/moodle-pulse/releases/assets/2",
                  "browser_download_url": "https://github.com/Senerity-234/moodle-pulse/releases/download/v1.2.0/MoodlePulse-v1.2.0-release.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = GitHubReleaseParser.parseLatestReleaseJson(sampleJson)
        assertNotNull(parsed)
        assertEquals("v1.2.0", parsed?.tagName)
        assertEquals("1.2.0", parsed?.versionName)
        assertEquals("MoodlePulse-v1.2.0-release.apk", parsed?.apkFileName)
        assertEquals(98765L, parsed?.apkSizeBytes)
        assertEquals(
            "https://github.com/Senerity-234/moodle-pulse/releases/download/v1.2.0/MoodlePulse-v1.2.0-release.apk",
            parsed?.apkDownloadUrl
        )
    }

    @Test
    fun parseLatestReleaseJson_ignoresDraftReleases() {
        val draftJson = """
            {
              "tag_name": "v2.0.0",
              "draft": true,
              "assets": []
            }
        """.trimIndent()
        assertNull(GitHubReleaseParser.parseLatestReleaseJson(draftJson))
    }
}
