package com.moodlepulse.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class GitHubUpdateManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    suspend fun checkForUpdate(
        repoInput: String,
        currentVersionName: String,
        githubToken: String? = null
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val slug = GitHubReleaseParser.normalizeRepoSlug(repoInput)
            ?: return@withContext UpdateCheckResult.Error("GitHub Repo 格式不正確，請填寫 owner/repo（例如 Senerity-234/moodle-pulse）")

        val cleanToken = githubToken?.trim()?.takeIf { it.isNotEmpty() }

        runCatching {
            val latestRequest = buildGitHubApiRequest(
                url = "https://api.github.com/repos/$slug/releases/latest",
                token = cleanToken
            )
            httpClient.newCall(latestRequest).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string().orEmpty()
                        val release = GitHubReleaseParser.parseLatestReleaseJson(body)
                            ?: return@use UpdateCheckResult.Error("無法解析 GitHub Release 回應內容")
                        evaluateRelease(release, currentVersionName)
                    }
                    404 -> {
                        // 若尚未有正式 latest release（或只有 pre-release / 私人 Repo 未帶 Token），嘗試查詢 releases 清單
                        fallbackCheckFirstRelease(slug, cleanToken, currentVersionName)
                    }
                    401, 403 -> {
                        val hint = if (cleanToken == null) {
                            "GitHub API 存取受限或此 Repo 為私人儲存庫，請於下方設定 GitHub Token。"
                        } else {
                            "GitHub Token 無效或權限不足（HTTP ${response.code}）。"
                        }
                        UpdateCheckResult.Error(hint)
                    }
                    else -> UpdateCheckResult.Error("檢查更新失敗（HTTP ${response.code}）")
                }
            }
        }.getOrElse { err ->
            UpdateCheckResult.Error("無法連線至 GitHub：${err.message ?: "網路錯誤"}")
        }
    }

    private fun fallbackCheckFirstRelease(
        slug: String,
        token: String?,
        currentVersionName: String
    ): UpdateCheckResult {
        val listRequest = buildGitHubApiRequest(
            url = "https://api.github.com/repos/$slug/releases?per_page=1",
            token = token
        )
        return httpClient.newCall(listRequest).execute().use { response ->
            when (response.code) {
                200 -> {
                    val body = response.body?.string().orEmpty()
                    val release = GitHubReleaseParser.parseReleasesListJson(body)
                        ?: return@use UpdateCheckResult.Error("此 Repo 尚未建立任何 Release（請先建立 v1.x.x Tag Release）")
                    evaluateRelease(release, currentVersionName)
                }
                404 -> UpdateCheckResult.Error("找不到 $slug 或此為私人 Repo（若是私人 Repo 請填寫 GitHub Token）")
                401, 403 -> UpdateCheckResult.Error("GitHub Token 權限不足或 API 次數達上限")
                else -> UpdateCheckResult.Error("找不到可用的 Release（HTTP ${response.code}）")
            }
        }
    }

    private fun evaluateRelease(release: GitHubReleaseInfo, currentVersionName: String): UpdateCheckResult {
        return if (GitHubReleaseParser.isNewerVersion(release.versionName, currentVersionName)) {
            UpdateCheckResult.UpdateAvailable(release)
        } else {
            UpdateCheckResult.UpToDate(currentVersionName)
        }
    }

    suspend fun downloadApk(
        release: GitHubReleaseInfo,
        githubToken: String? = null,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val cleanToken = githubToken?.trim()?.takeIf { it.isNotEmpty() }
        val useApiAssetUrl = cleanToken != null && !release.apkAssetApiUrl.isNullOrBlank()
        val downloadUrl = if (useApiAssetUrl) {
            release.apkAssetApiUrl!!
        } else {
            release.apkDownloadUrl ?: throw IOException("此 Release 未附加 .apk 安裝檔")
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updatesDir.listFiles()?.forEach { oldFile ->
            if (oldFile.isFile) oldFile.delete()
        }

        val safeName = (release.apkFileName ?: "MoodlePulse-${release.versionName}.apk")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }

        val tempFile = File(updatesDir, "$safeName.tmp")
        val targetFile = File(updatesDir, safeName)

        val requestBuilder = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", USER_AGENT)

        if (useApiAssetUrl) {
            requestBuilder
                .header("Accept", "application/octet-stream")
                .header("Authorization", "Bearer $cleanToken")
        } else if (cleanToken != null) {
            requestBuilder.header("Authorization", "Bearer $cleanToken")
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下載 APK 失敗（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IOException("伺服器未回傳 APK 內容")
            val totalBytes = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes

            var lastReportedPercent = -1
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0L) {
                            val percent = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
        }

        if (!tempFile.exists() || tempFile.length() == 0L) {
            tempFile.delete()
            throw IOException("下載完成的 APK 檔案為空")
        }
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        onProgress(100)
        targetFile
    }

    fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun createInstallPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun createInstallApkIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun buildGitHubApiRequest(url: String, token: String?): Request {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .apply {
                if (!token.isNullOrBlank()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
    }

    companion object {
        private const val USER_AGENT = "MoodlePulse-Android"
    }
}
